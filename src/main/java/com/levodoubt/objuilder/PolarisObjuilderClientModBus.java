package com.levodoubt.objuilder;

import com.levodoubt.objuilder.client.ColliderEntityRenderer;
import com.levodoubt.objuilder.client.DomainEntityRenderer;
import com.levodoubt.objuilder.client.PbrTextureSupport;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * MOD bus 客户端事件：注册共面域实体渲染器 + PBR 光影适配初始化。
 * 注意：EntityRenderersEvent 在 MOD bus，需与 GAME bus 的 PolarisObjuilderClient 分开。
 */
@EventBusSubscriber(modid = PolarisObjuilder.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class PolarisObjuilderClientModBus {
    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(PolarisObjuilder.DOMAIN_ENTITY.get(), DomainEntityRenderer::new);
        event.registerEntityRenderer(PolarisObjuilder.COLLIDER_ENTITY.get(), ColliderEntityRenderer::new);
    }

    /** 客户端启动：检测 Iris 并注册 PBR loader（无 Iris 时静默跳过，不引 Iris 类加载） */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        PbrTextureSupport.init();
    }
}
