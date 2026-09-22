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
 * 交互盒拾取（2.1.17~2.1.19）：右键我们自己的碰撞盒时，用「宽容射线 + 精确命中优先 / 最具体盒优先」判定一次；
 * **判不到时按四类原因分别反馈**（actionbar + 日志），便于现场定位"点了没反应"。
 *
 * <p>未命中的四类原因：① 部件已被消耗/禁用；② 部件无 @动画/$命令（不可交互）；③ 该次摆放没有碰撞盒（附近没有交互盒）；
 * ④ 真的没瞄中（射线离盒面 N 格）。旧文案把②③④都写成"偏移"，会误导。
 */
@EventBusSubscriber(modid = PolarisObjuilder.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class ColliderClickAssist {
    /** 射线长度（格）：服务端按同一数值校验 */
    private static final double REACH = 4.5;
    /** 膨胀下限/上限（格） */
    private static final double MIN_INFLATE = 0.25;
    private static final double MAX_INFLATE = 0.6;
    /** 未命中时的"打扰半径"（格）：准星离某个交互盒比它更近才提示，避免右键箱子也弹提示 */
    private static final double MISS_HINT_OFFSET = 0.8;
    /** 探测"离准星多近"用的膨胀档位 */
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

    /** 未命中 → 按四类原因反馈（仅当准星附近确实有我们的交互盒时才提示/记日志，避免刷屏） */
    private static void reportMiss(Minecraft mc) {
        Entity cam = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
        Vec3 eye = cam.getEyePosition(1.0f);
        Vec3 end = eye.add(cam.getViewVector(1.0f).scale(REACH));
        List<ColliderEntity> all = mc.level.getEntitiesOfClass(ColliderEntity.class,
                new AABB(eye, end).inflate(MAX_INFLATE + 0.5), ce -> true);

        ColliderEntity nearest = null;
        double nearestOff = Double.MAX_VALUE;
        int nearCount = 0;
        StringBuilder dump = new StringBuilder();
        for (ColliderEntity ce : all) {
            AABB bx = ce.getBoundingBox();
            double off = rayOffset(eye, end, bx);
            if (off > MISS_HINT_OFFSET) continue;
            nearCount++;
            dump.append(String.format(" | '%s' 摆放#%d 实体#%d %s off=%.2f 盒=[%.3f,%.3f,%.3f→%.3f,%.3f,%.3f]",
                    ce.getPartName(), ce.getPlacementId(), ce.getId(),
                    ce.isPickable() ? "可交互" : (ce.isPickEnabled() ? "无动画/命令" : "已消耗"),
                    off, bx.minX, bx.minY, bx.minZ, bx.maxX, bx.maxY, bx.maxZ));
            if (off < nearestOff) {
                nearestOff = off;
                nearest = ce;
            }
        }
        if (nearest == null) return;   // 附近没有我们的盒 → 静默（右键箱子/门不受打扰）

        long now = Util.getMillis();
        if (now - lastMissMs < 1000L) return;
        lastMissMs = now;

        String part = nearest.getPartName();
        int pid = nearest.getPlacementId();
        String msg;
        if (!nearest.isPickEnabled()) {
            msg = String.format("§c[Objuilder] 触发失败：部件 '%s' 已被消耗/禁用（摆放 #%d）", part, pid);
        } else if (!nearest.isPickable()) {
            msg = String.format("§c[Objuilder] 触发失败：部件 '%s' 不可交互（无 @动画/$命令，摆放 #%d）", part, pid);
        } else {
            msg = String.format("§e[Objuilder] 未触发：准星离部件 '%s' 约 %.2f 格（摆放 #%d）", part, nearestOff, pid);
        }
        mc.player.displayClientMessage(Component.literal(msg), true);
        PolarisObjuilder.LOGGER.info(
                "[Objuilder][交互拾取] 未命中：eye=[{},{},{}] 视线终点=[{},{},{}] 附近交互盒 {} 个:{}",
                fmt(eye.x), fmt(eye.y), fmt(eye.z), fmt(end.x), fmt(end.y), fmt(end.z),
                nearCount, dump.length() == 0 ? " 无" : dump);
    }

    private static String fmt(double v) {
        return String.format("%.3f", v);
    }

    /** 射线到盒面的近似距离（用若干膨胀档位探测；超过最大档返回 999） */
    private static double rayOffset(Vec3 eye, Vec3 end, AABB box) {
        for (double k : MISS_PROBE) {
            if (box.inflate(k).clip(eye, end).isPresent()) return k;
        }
        return 999.0;
    }

    /** 宽容射线拾取：精确命中优先（取最具体盒），否则取最近膨胀命中；只考虑 pickable 的盒 */
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
