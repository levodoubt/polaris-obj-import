package com.levodoubt.objuilder.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.client.renderer.culling.Frustum;

/**
 * Iris 阴影 pass 检测（2.1.10 光影问题·问题 1 落地修正）。
 *
 * <p><b>背景 bug</b>：{@link DomainEntityRenderer} 的分桶剔除用的是
 * {@code LevelRenderer.cullingFrustum}（主相机视锥），而实体渲染器的 render() 在
 * Iris 的 shadow pass 里也会被调用——阴影 pass 用相机视锥剔除会把"相机视野外"的模型部分
 * 剔出 shadowtex（阳光从背后照时被挡的部分恰好不在视野内）→ 模型投影随人物位置/视角
 * 变化出现大块缺失/移动，即"不稳定阴影"。关 AO 无效（根因不是 SSAO）。
 *
 * <p><b>修复</b>：阴影 pass 内改用 Iris 阴影视锥（ShadowRenderer.FRUSTUM，public static）
 * 做分桶剔除；不可用时回退全量渲染（宁多画不缺影）。无 Iris 时恒 false/null，零影响。
 *
 * <p>实现为纯反射（不引 Iris 编译依赖）：类加载时探测 Iris 存在与否，Iris 缺失则成员为 null。
 */
public final class ShadowPassDetect {
    /** ShadowRenderingState.areShadowsCurrentlyBeingRendered()（public static，Iris 1.8.x） */
    private static final Method ARE_RENDERING;
    /** ShadowRenderer.FRUSTUM（public static，阴影视锥） */
    private static final Field SHADOW_FRUSTUM;

    static {
        Method m = null;
        Field f = null;
        try {
            Class<?> state = Class.forName("net.irisshaders.iris.shadows.ShadowRenderingState");
            m = state.getMethod("areShadowsCurrentlyBeingRendered");
            Class<?> renderer = Class.forName("net.irisshaders.iris.shadows.ShadowRenderer");
            f = renderer.getField("FRUSTUM");
        } catch (Throwable t) {
            m = null; // 无 Iris（或 API 变动）→ 检测不可用，调用方回退安全行为
            f = null;
        }
        ARE_RENDERING = m;
        SHADOW_FRUSTUM = f;
    }

    private ShadowPassDetect() {
    }

    /** 当前是否处于 Iris 阴影 pass（无 Iris 恒 false） */
    public static boolean isShadowPass() {
        if (ARE_RENDERING == null) return false;
        try {
            return (boolean) ARE_RENDERING.invoke(null);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Iris 阴影视锥（仅阴影 pass 内有效；无 Iris / 取不到时返回 null → 调用方应回退全量渲染） */
    public static Frustum shadowFrustum() {
        if (SHADOW_FRUSTUM == null) return null;
        try {
            return (Frustum) SHADOW_FRUSTUM.get(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
