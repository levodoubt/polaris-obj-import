package com.levodoubt.objuilder.client;

import com.levodoubt.objuilder.PolarisObjuilder;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
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
}