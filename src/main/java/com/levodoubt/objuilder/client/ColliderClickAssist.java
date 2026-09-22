package com.levodoubt.objuilder.client;

import java.util.List;
import java.util.Optional;

import com.levodoubt.objuilder.PolarisObjuilder;
import com.levodoubt.objuilder.entity.ColliderEntity;
import com.levodoubt.objuilder.network.ColliderClickPayload;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 交互盒拾取（2.1.17）：右键我们自己的碰撞盒时，用「宽容射线 + 精确命中优先 / 最具体盒优先」自己判定一次。
 *
 * <p><b>为什么不用原版拾取</b>：原版判定盒 = {@code getBoundingBox().inflate(getPickRadius())}，
 * 且必须在 {@code entityInteractionRange()}（默认 3 格）内、服务端还会再查一次 3 格。
 * 而 0.3.1（e037bfc）为修"大盒膨胀吞掉 2cm 子弹盒"，把 {@code getPickRadius()} 从
 * {@code min(4, size*0.5+0.25)}（步枪盒 0.615 m）收窄到 {@code min(0.3, 0.1+size*0.05)}（0.136 m）
 * —— 可点区域缩掉约 0.5 m，导致"大致瞄着枪身"点不中（用户实测：老版本没问题、新版偶尔才成功）。
 *
 * <p><b>现在的规则</b>（既恢复手感、又不吞盒）：
 * <ol>
 *   <li>射线与 {@code inflate(min(0.6, 0.25 + size*0.35))} 求交，收集全部候选；</li>
 *   <li>若其中有"射线直接命中**未膨胀**盒"的候选 → 取**未膨胀体积最小**者（精确瞄准 = 最具体部件，
 *       子弹盒赢过枪盒/桌子）；</li>
 *   <li>否则取**最近**的膨胀命中（近失手也能点中，与原版语义一致）。</li>
 * </ol>
 *
 * <p>命中我们的盒 → 取消原版处理（避免双重触发）+ 发 {@link ColliderClickPayload} + 挥手；
 * 没命中 → 什么都不做，原版路径照旧（箱子/门/其它模组不受影响）。
 */
@EventBusSubscriber(modid = PolarisObjuilder.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class ColliderClickAssist {
    /** 射线长度（格）：服务端按同一数值校验 */
    private static final double REACH = 4.5;
    /** 膨胀下限/上限（格） */
    private static final double MIN_INFLATE = 0.25;
    private static final double MAX_INFLATE = 0.6;
    private static long lastLogMs = 0L;

    private ColliderClickAssist() {
    }

    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (mc.player.isSpectator()) return;
        ColliderEntity hit = pick(mc);
        if (hit == null) return;
        event.setCanceled(true);
        PacketDistributor.sendToServer(new ColliderClickPayload(hit.getId(),
                event.getHand() == InteractionHand.OFF_HAND ? 1 : 0));
        mc.player.swing(event.getHand());
        long now = Util.getMillis();
        if (now - lastLogMs > 1000L) {
            lastLogMs = now;
            PolarisObjuilder.LOGGER.info("[Objuilder][交互拾取] 命中部件 '{}'（摆放 #{}）",
                    hit.getPartName(), hit.getPlacementId());
        }
    }

    /** 宽容射线拾取：精确命中优先（取最具体盒），否则取最近膨胀命中 */
    private static ColliderEntity pick(Minecraft mc) {
        Entity cam = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
        Vec3 eye = cam.getEyePosition(1.0f);
        Vec3 end = eye.add(cam.getViewVector(1.0f).scale(REACH));
        AABB search = new AABB(eye, end).inflate(MAX_INFLATE + 0.5);
        List<ColliderEntity> cands = mc.level.getEntitiesOfClass(ColliderEntity.class, search,
                ce -> ce.isPickable());
        ColliderEntity exactBest = null;   // 直接命中未膨胀盒 → 体积最小者
        double exactVol = Double.MAX_VALUE;
        ColliderEntity nearBest = null;    // 仅命中膨胀盒 → 最近者
        double nearD2 = Double.MAX_VALUE;
        for (ColliderEntity ce : cands) {
            AABB raw = ce.getBoundingBox();
            double inflate = Math.max(MIN_INFLATE, Math.min(MAX_INFLATE, 0.25 + raw.getSize() * 0.35));
            AABB fat = raw.inflate(inflate);
            Optional<Vec3> hitFat = fat.clip(eye, end);
            if (hitFat.isEmpty()) continue;
            double d2 = eye.distanceToSqr(hitFat.get());
            if (d2 < nearD2) {
                nearD2 = d2;
                nearBest = ce;
            }
            if (raw.clip(eye, end).isPresent()) {
                double vol = raw.getXsize() * raw.getYsize() * raw.getZsize();
                if (vol < exactVol) {
                    exactVol = vol;
                    exactBest = ce;
                }
            }
        }
        return exactBest != null ? exactBest : nearBest;
    }
}
