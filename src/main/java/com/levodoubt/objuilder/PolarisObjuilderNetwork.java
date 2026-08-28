package com.levodoubt.objuilder;

import com.levodoubt.objuilder.client.GlbAnimationManager;
import com.levodoubt.objuilder.client.PlacementModelLoader;
import com.levodoubt.objuilder.network.CacheReleasePayload;
import com.levodoubt.objuilder.network.GlbAnimPayload;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
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
                        PolarisObjuilderNetwork::handleCacheRelease);
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
}
