package com.levodoubt.objuilder.client;

import com.levodoubt.objuilder.PolarisObjuilder;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 客户端 GAME bus 事件：每帧渲染前驱动 glb 循环动画。
 * 动画时间（秒）= (level.getGameTime() + partialTick) / 20。
 * 用 RenderLevelStageEvent（每帧触发）而非 ClientTickEvent（每 tick 20Hz），
 * 否则动画几何 20Hz 离散更新与 60Hz 渲染不同步 → 跳变/残影/卡顿。
 *
 * <p>超大模型子块视锥剔除的视锥来源见 {@link ViewCulling}——直接复用 MC 官方 cullingFrustum，
 * 无需在此构造。
 */
@EventBusSubscriber(modid = PolarisObjuilder.MODID, value = Dist.CLIENT,
        bus = EventBusSubscriber.Bus.GAME)
public class PolarisObjuilderClientEvents {
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        // AFTER_SOLID_BLOCKS：固体方块渲染后、实体渲染前，每帧更新动画几何 → 本帧实体读到最新姿态
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return; // 加载/暂停场景无世界

        if (!GlbAnimationManager.hasAnimations()) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float t = (mc.level.getGameTime() + partial) / 20.0f;
        GlbAnimationManager.tick(t);
    }

    /**
     * 客户端断开连接（退回标题界面/切换存档）时清空全部模型缓存：
     * ① 静态 Map（PlacementModelLoader/DomainModelCache）跨世界存活，不清空会导致新世界的
     *    摆放 pid 命中旧世界映射 → 直接复用旧模型几何（渲染错误模型且不触发重载）；
     * ② Iris 在重进世界/维度切换时销毁重建 pipeline（全部 shader 程序与其 framebuffer），
     *    旧缓存若持有旧 ShaderInstance 会变野引用（"Tried to use a destroyed GlResource" 崩溃）——
     *    shader 已改为每帧现取，此处再逐 release 释放几何/纹理/GPU 缓冲兜底。
     */
    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        PlacementModelLoader.clearAll();
        DomainModelCache.clearAll();
        GlbAnimationManager.clear();
    }
}