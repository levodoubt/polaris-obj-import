package com.levodoubt.objuilder;

import com.levodoubt.objuilder.client.DomainEntityRenderer;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * MOD bus 客户端事件：注册共面域实体渲染器。
 * 注意：EntityRenderersEvent 在 MOD bus，需与 GAME bus 的 PolarisObjuilderClient 分开。
 */
@EventBusSubscriber(modid = PolarisObjuilder.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class PolarisObjuilderClientModBus {
    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(PolarisObjuilder.DOMAIN_ENTITY.get(), DomainEntityRenderer::new);
    }
}
