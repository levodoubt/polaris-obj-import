package com.levodoubt.objuilder;

import com.levodoubt.objuilder.client.GlbAnimationManager;
import com.levodoubt.objuilder.client.PlacementModelLoader;
import com.levodoubt.objuilder.entity.ColliderEntity;
import com.levodoubt.objuilder.network.CacheReleasePayload;
import com.levodoubt.objuilder.network.ColliderClickPayload;
import com.levodoubt.objuilder.network.GlbAnimPayload;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 网络包注册（MOD bus，双端）。
 *
 * 注册动画控制包（play 方向：服务端 → 客户端）。
 * 客户端收包后 enqueueWork 到主线程，转 {@link GlbAnimationManager#onPayload} 触发动画。
 * 专用服务器只会 send 该包、永不 receive，handler 中 clientbound 分支不会执行，
 * 引用的客户端类不会被加载（HotSpot 惰性类解析，安全）。
 */
@EventBusSubscriber(modid = PolarisObjuilder.MODID, bus = EventBusSubscriber.Bus.MOD)
public class PolarisObjuilderNetwork {
    @SubscribeEvent
    public static void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playBidirectional(GlbAnimPayload.TYPE, GlbAnimPayload.STREAM_CODEC,
                        PolarisObjuilderNetwork::handleAnimPayload)
                .playBidirectional(CacheReleasePayload.TYPE, CacheReleasePayload.STREAM_CODEC,
                        PolarisObjuilderNetwork::handleCacheRelease)
                .playBidirectional(ColliderClickPayload.TYPE, ColliderClickPayload.STREAM_CODEC,
                        PolarisObjuilderNetwork::handleColliderClick);
    }

    /** 缓存释放包处理：仅客户端接收并释放该摆放的几何/纹理缓存（专用服务器永不触发该分支） */
    private static void handleCacheRelease(CacheReleasePayload payload, IPayloadContext context) {
        if (!context.flow().isClientbound()) return;
        context.enqueueWork(() -> PlacementModelLoader.release(payload.placementId()))
                .exceptionally(e -> {
                    PolarisObjuilder.LOGGER.error("[Objuilder] 缓存释放包处理失败", e);
                    return null;
                });
    }

    /** 动画控制包处理：仅客户端接收并执行（专用服务器永不触发该分支） */
    private static void handleAnimPayload(GlbAnimPayload payload, IPayloadContext context) {
        if (!context.flow().isClientbound()) return;
        context.enqueueWork(() -> GlbAnimationManager.onPayload(payload))
                .exceptionally(e -> {
                    PolarisObjuilder.LOGGER.error("[Glb] 动画控制包处理失败", e);
                    return null;
                });
    }

    // ===================== 点击辅助（2.1.16，客户端 → 服务端） =====================

    /** 同一实体 3 tick 内不重复触发（同一次点击的双保险防抖） */
    private static final java.util.Map<Integer, Long> ASSIST_LAST = new java.util.HashMap<>();
    private static long assistWarnAtMs = 0L;

    /** 点击辅助包：仅服务端处理（客户端用宽容射线命中后发来，绕开原版 3 格实体交互距离） */
    private static void handleColliderClick(ColliderClickPayload payload, IPayloadContext context) {
        if (context.flow().isClientbound()) return;
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            net.minecraft.world.entity.Entity ent = player.level().getEntity(payload.entityId());
            if (!(ent instanceof ColliderEntity ce)) {
                reject(player, "实体不存在或未加载", null, payload.entityId());
                return;
            }
            if (player.isSpectator()) return;
            if (!ce.isPickEnabled()) {
                reject(player, "该部件已被消耗/禁用", ce, payload.entityId());
                return;
            }
            if (!ce.isPickable()) {
                reject(player, "该部件不可交互（无 @动画/$命令）", ce, payload.entityId());
                return;
            }
            double reach = 4.5;
            double d2 = player.getEyePosition().distanceToSqr(ce.getBoundingBox().getCenter());
            if (d2 > reach * reach) {
                reject(player, String.format("超出交互距离 %.2f > %.1f 格", Math.sqrt(d2), reach), ce, payload.entityId());
                return;
            }
            long now = player.level().getGameTime();
            Long last = ASSIST_LAST.get(payload.entityId());
            if (last != null && now - last < 3L) return;
            ASSIST_LAST.put(payload.entityId(), now);
            // 复用实体自身的交互逻辑（动画 → 消耗占位 → 命令 → 失败回滚），不复制任何判定
            ce.interact(player, payload.hand() == 1 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        }).exceptionally(e -> {
            PolarisObjuilder.LOGGER.error("[Objuilder] 点击辅助包处理失败", e);
            return null;
        });
    }

    /** 触发失败 → 游戏内 actionbar + 日志（节流 1 秒，避免刷屏）；ce 可能为 null（实体缺失时） */
    private static void reject(ServerPlayer player, String reason, ColliderEntity ce, int entityId) {
        String part = ce == null ? "?" : String.valueOf(ce.getPartName());
        int pid = ce == null ? -1 : ce.getPlacementId();
        long nowMs = net.minecraft.Util.getMillis();
        if (nowMs - assistWarnAtMs > 1000L) {
            assistWarnAtMs = nowMs;
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "§c[Objuilder] 触发失败：" + reason + "（部件 " + part + " · 摆放 #" + pid + "）"), true);
        }
        PolarisObjuilder.LOGGER.info("[Objuilder][点击辅助] 拒绝：{}（部件 '{}' 摆放 #{} 实体 #{} 玩家 {}）",
                reason, part, pid, entityId, player.getScoreboardName());
    }
}
