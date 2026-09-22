package com.levodoubt.objuilder.client;

import java.util.List;

import com.levodoubt.objuilder.PolarisObjuilder;
import com.levodoubt.objuilder.entity.ColliderEntity;
import com.levodoubt.objuilder.network.ColliderClickPayload;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 点击辅助（2.1.16）：右键时若**原版准星没命中交互盒**，用更宽容的射线再找一次。
 *
 * <p><b>为什么需要</b>：原版实体拾取 = 客户端 `GameRenderer.pick` 用
 * `getBoundingBox().inflate(getPickRadius())` 且只在 `entityInteractionRange()`（默认 3 格）内生效；
 * 服务端还会再查一次 3 格。模型里的交互盒往往薄而长（如步枪盒 1.83×0.28×0.085 m），
 * 玩家站得稍远或瞄得稍偏就"点了没反应"。
 *
 * <p><b>行为</b>：本类只在"原版没命中我们的盒"时介入 —— 命中后取消原版处理（避免同一次点击触发两次）、
 * 发 {@link ColliderClickPayload} 给服务端、挥手。原版已命中的情况完全交给原版，行为不变。
 */
@EventBusSubscriber(modid = PolarisObjuilder.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class ColliderClickAssist {
    /** 辅助射线长度（格）：比原版 3 格放宽；服务端按同一数值校验 */
    private static final double REACH = 4.5;
    /** 最小额外膨胀（格）：标记盒普遍很薄，给一点宽容度 */
    private static final double MIN_INFLATE = 0.15;
    /** 诊断日志节流 */
    private static long lastLogMs = 0L;

    private ColliderClickAssist() {
    }

    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (mc.player.isSpectator()) return;
        // 原版已命中我们的交互盒 → 交给原版（避免重复触发）
        if (mc.hitResult instanceof EntityHitResult ehr && ehr.getEntity() instanceof ColliderEntity) return;
        ColliderEntity hit = pick(mc);
        if (hit == null) return;
        event.setCanceled(true);
        PacketDistributor.sendToServer(new ColliderClickPayload(hit.getId(),
                event.getHand() == InteractionHand.OFF_HAND ? 1 : 0));
        mc.player.swing(event.getHand());
        long now = Util.getMillis();
        if (now - lastLogMs > 1000L) {
            lastLogMs = now;
            PolarisObjuilder.LOGGER.info("[Objuilder][点击辅助] 原版未命中 → 辅助射线命中部件 '{}'（摆放 #{}）",
                    hit.getPartName(), hit.getPlacementId());
        }
    }

    /** 沿视线做一次"交互盒优先"的宽容射线检测；返回最近的候选（没有则 null） */
    private static ColliderEntity pick(Minecraft mc) {
        Entity cam = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
        Vec3 eye = cam.getEyePosition(1.0f);
        Vec3 end = eye.add(cam.getViewVector(1.0f).scale(REACH));
        AABB search = new AABB(eye, end).inflate(1.0);
        List<ColliderEntity> cands = mc.level.getEntitiesOfClass(ColliderEntity.class, search,
                ce -> ce.isPickable());
        ColliderEntity best = null;
        double bestD2 = Double.MAX_VALUE;
        for (ColliderEntity ce : cands) {
            AABB box = ce.getBoundingBox().inflate(Math.max(MIN_INFLATE, ce.getPickRadius()));
            java.util.Optional<Vec3> hit = box.clip(eye, end);
            if (hit.isEmpty()) continue;
            double d2 = eye.distanceToSqr(hit.get());
            if (d2 < bestD2) {
                bestD2 = d2;
                best = ce;
            }
        }
        return best;
    }
}
