package com.levodoubt.objuilder.client;

import java.util.List;
import java.util.Optional;

import com.levodoubt.objuilder.PolarisObjuilder;
import com.levodoubt.objuilder.entity.ColliderEntity;
import com.levodoubt.objuilder.network.ColliderClickPayload;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
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
 * 交互盒拾取（2.1.17 / 2.1.18）：右键我们自己的碰撞盒时，用「宽容射线 + 精确命中优先 / 最具体盒优先」自己判定一次；
 * **判不到时也要给出原因**（actionbar + 日志），便于现场定位"点了没反应"。
 *
 * <p>规则见 v0.3.8 注释：膨胀 {@code min(0.6, 0.25+size*0.35)}；命中**未膨胀**盒者取体积最小，
 * 否则取最近膨胀命中。命中即发 {@link ColliderClickPayload}（服务端复用 {@code ColliderEntity.interact}）。
 */
@EventBusSubscriber(modid = PolarisObjuilder.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class ColliderClickAssist {
    /** 射线长度（格）：服务端按同一数值校验 */
    private static final double REACH = 4.5;
    /** 膨胀下限/上限（格） */
    private static final double MIN_INFLATE = 0.25;
    private static final double MAX_INFLATE = 0.6;
    /** 未命中时的"打扰半径"（格）：准星离某个可交互盒比它更近才提示，避免右键箱子也弹提示 */
    private static final double MISS_HINT_OFFSET = 0.8;
    /** 探测用的膨胀档位（找"离准星多近"） */
    private static final double[] MISS_PROBE = {0.05, 0.1, 0.2, 0.35, 0.55, 0.8};

    private static long lastLogMs = 0L;
    private static long lastMissMs = 0L;

    private ColliderClickAssist() {
    }

    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (mc.player.isSpectator()) return;
        ColliderEntity hit = pick(mc);
        if (hit == null) {
            reportMiss(mc);
            return;
        }
        event.setCanceled(true);
        PacketDistributor.sendToServer(new ColliderClickPayload(hit.getId(),
                event.getHand() == InteractionHand.OFF_HAND ? 1 : 0));
        mc.player.swing(event.getHand());
        long now = Util.getMillis();
        if (now - lastLogMs > 1000L) {
            lastLogMs = now;
            PolarisObjuilder.LOGGER.info("[Objuilder][交互拾取] 命中部件 '{}'（摆放 #{}，实体 #{}）",
                    hit.getPartName(), hit.getPlacementId(), hit.getId());
        }
    }

    /**
     * 没命中时的原因反馈：若准星附近（{@value #MISS_HINT_OFFSET} 格内）存在可交互盒，
     * 说明"玩家在瞄它但没点中" → actionbar + 日志给出偏离量与部件信息（节流 1 秒）。
     */
    private static void reportMiss(Minecraft mc) {
        Entity cam = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
        Vec3 eye = cam.getEyePosition(1.0f);
        Vec3 end = eye.add(cam.getViewVector(1.0f).scale(REACH));
        List<ColliderEntity> near = mc.level.getEntitiesOfClass(ColliderEntity.class,
                new AABB(eye, end).inflate(MAX_INFLATE + 0.5), ce -> ce.isPickable());
        ColliderEntity closest = null;
        double closestOff = Double.MAX_VALUE;
        for (ColliderEntity ce : near) {
            double off = rayOffset(eye, end, ce.getBoundingBox());
            if (off < closestOff) {
                closestOff = off;
                closest = ce;
            }
        }
        if (closest == null || closestOff > MISS_HINT_OFFSET) return;
        long now = Util.getMillis();
        if (now - lastMissMs < 1000L) return;
        lastMissMs = now;
        String part = closest.getPartName();
        int pid = closest.getPlacementId();
        mc.player.displayClientMessage(Component.literal(String.format(
                "§e[Objuilder] 未触发：准星偏离部件 '%s' 约 %.2f 格（摆放 #%d）", part, closestOff, pid)), true);
        PolarisObjuilder.LOGGER.info("[Objuilder][交互拾取] 未命中：最近可交互部件 '{}'（摆放 #{}，实体 #{}）偏离准星 {} 格",
                part, pid, closest.getId(), String.format("%.2f", closestOff));
    }

    /** 射线到盒面的近似距离（用若干膨胀档位探测；超过最大档返回 999） */
    private static double rayOffset(Vec3 eye, Vec3 end, AABB box) {
        for (double k : MISS_PROBE) {
            if (box.inflate(k).clip(eye, end).isPresent()) return k;
        }
        return 999.0;
    }

    /** 宽容射线拾取：精确命中优先（取最具体盒），否则取最近膨胀命中 */
    private static ColliderEntity pick(Minecraft mc) {
        Entity cam = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
        Vec3 eye = cam.getEyePosition(1.0f);
        Vec3 end = eye.add(cam.getViewVector(1.0f).scale(REACH));
        AABB search = new AABB(eye, end).inflate(MAX_INFLATE + 0.5);
        List<ColliderEntity> cands = mc.level.getEntitiesOfClass(ColliderEntity.class, search,
                ce -> ce.isPickable());
        ColliderEntity exactBest = null;
        double exactVol = Double.MAX_VALUE;
        ColliderEntity nearBest = null;
        double nearD2 = Double.MAX_VALUE;
        for (ColliderEntity ce : cands) {
            AABB raw = ce.getBoundingBox();
            double inflate = Math.max(MIN_INFLATE, Math.min(MAX_INFLATE, 0.25 + raw.getSize() * 0.35));
            Optional<Vec3> hitFat = raw.inflate(inflate).clip(eye, end);
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
