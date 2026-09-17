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
 * <p><b>2.1.15 新增诊断接口</b>（问题 1"模型无投影/无阴影"取证）：除 isShadowPass() 外，
 * 追加读取 Iris 阴影侧运行时状态——阴影 pass 是否激活（ACTIVE）、光影包是否声明渲染实体
 * （shaders.properties 的 {@code shadowEntities}，由 ENTITY_SHADOWS 选项决定）、Iris 本帧
 * 真正画进 shadowtex 的实体数、阴影图分辨率/视距/实体距离倍率。这些值全部只读、缺失即返回
 * -1/"?"，不影响渲染。
 *
 * <p>实现为纯反射（不引 Iris 编译依赖）：类加载时探测 Iris 存在与否，Iris 缺失则成员为 null。
 */
public final class ShadowPassDetect {
    /** ShadowRenderingState.areShadowsCurrentlyBeingRendered()（public static，Iris 1.8.x） */
    private static final Method ARE_RENDERING;
    /** ShadowRenderer.FRUSTUM（public static，阴影视锥） */
    private static final Field SHADOW_FRUSTUM;

    // ===== 2.1.15 诊断（全部可空） =====
    /** ShadowRenderer.ACTIVE（public static boolean） */
    private static final Field F_ACTIVE;
    /** ShadowRenderer.RESOLUTION（public static int） */
    private static final Field F_RESOLUTION;
    /** ShadowRenderer.renderDistance（public static int） */
    private static final Field F_RENDER_DISTANCE;
    /** Iris.getPipelineManager()（public static） */
    private static final Method M_GET_PIPELINE_MANAGER;
    /** PipelineManager.getPipelineNullable()（public） */
    private static final Method M_GET_PIPELINE;
    /** IrisRenderingPipeline.shadowRenderer（private final ShadowRenderer） */
    private static final Field F_SHADOW_RENDERER;
    /** ShadowRenderer.shouldRenderEntities（private final boolean，来自光影包 shadowEntities 指令） */
    private static final Field F_SHOULD_RENDER_ENTITIES;
    /** ShadowRenderer.renderedShadowEntities（private int，上一帧画进 shadowtex 的实体数） */
    private static final Field F_RENDERED_ENTITIES;
    /** ShadowRenderer.entityShadowDistanceMultiplier（private final float） */
    private static final Field F_ENTITY_DIST_MUL;

    /** 诊断绑定失败原因（null = 全部成功）；仅用于日志，不影响既有行为 */
    private static final String DIAG_ERROR;

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

        Field active = null, resolution = null, renderDist = null;
        Method getPm = null, getPipeline = null;
        Field shadowRenderer = null, shouldRenderEntities = null, renderedEntities = null, entityDistMul = null;
        String err = null;
        try {
            Class<?> renderer = Class.forName("net.irisshaders.iris.shadows.ShadowRenderer");
            active = renderer.getField("ACTIVE");
            resolution = renderer.getField("RESOLUTION");
            renderDist = renderer.getField("renderDistance");
            shouldRenderEntities = renderer.getDeclaredField("shouldRenderEntities");
            shouldRenderEntities.setAccessible(true);
            renderedEntities = renderer.getDeclaredField("renderedShadowEntities");
            renderedEntities.setAccessible(true);
            entityDistMul = renderer.getDeclaredField("entityShadowDistanceMultiplier");
            entityDistMul.setAccessible(true);

            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            getPm = iris.getMethod("getPipelineManager");
            Class<?> pm = Class.forName("net.irisshaders.iris.pipeline.PipelineManager");
            getPipeline = pm.getMethod("getPipelineNullable");
            Class<?> pipeline = Class.forName("net.irisshaders.iris.pipeline.IrisRenderingPipeline");
            shadowRenderer = pipeline.getDeclaredField("shadowRenderer");
            shadowRenderer.setAccessible(true);
        } catch (Throwable t) {
            err = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        F_ACTIVE = active;
        F_RESOLUTION = resolution;
        F_RENDER_DISTANCE = renderDist;
        M_GET_PIPELINE_MANAGER = getPm;
        M_GET_PIPELINE = getPipeline;
        F_SHADOW_RENDERER = shadowRenderer;
        F_SHOULD_RENDER_ENTITIES = shouldRenderEntities;
        F_RENDERED_ENTITIES = renderedEntities;
        F_ENTITY_DIST_MUL = entityDistMul;
        DIAG_ERROR = err;
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

    // ===================== 2.1.15 诊断 =====================

    /** 诊断反射绑定状态（"OK" 或失败原因），供日志输出 */
    public static String bindStatus() {
        if (ARE_RENDERING == null) return "无 Iris（阴影 pass 检测不可用）";
        return DIAG_ERROR == null ? "OK" : "部分失败(" + DIAG_ERROR + ")";
    }

    /** 阴影 pass 是否激活（ShadowRenderer.ACTIVE）；不可用 -1 */
    public static int active() {
        return readIntStatic(F_ACTIVE);
    }

    /** 阴影图分辨率；不可用 -1 */
    public static int resolution() {
        return readIntStatic(F_RESOLUTION);
    }

    /** 阴影视距（格）；不可用 -1 */
    public static int renderDistance() {
        return readIntStatic(F_RENDER_DISTANCE);
    }

    /** 光影包 shadowEntities 指令：1=开，0=★关（此时实体永不进 shadowtex），-1=未知 */
    public static int shouldRenderEntities() {
        Object sr = shadowRendererInstance();
        if (sr == null || F_SHOULD_RENDER_ENTITIES == null) return -1;
        try {
            return ((boolean) F_SHOULD_RENDER_ENTITIES.get(sr)) ? 1 : 0;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Iris 本帧画进 shadowtex 的实体数（0 = 一个实体都没进阴影图）；不可用 -1 */
    public static int renderedShadowEntities() {
        Object sr = shadowRendererInstance();
        if (sr == null || F_RENDERED_ENTITIES == null) return -1;
        try {
            return (int) F_RENDERED_ENTITIES.get(sr);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 实体阴影距离倍率（shadow.entityDistanceMultiplier）；不可用 -1 */
    public static float entityShadowDistanceMultiplier() {
        Object sr = shadowRendererInstance();
        if (sr == null || F_ENTITY_DIST_MUL == null) return -1f;
        try {
            return (float) F_ENTITY_DIST_MUL.get(sr);
        } catch (Throwable t) {
            return -1f;
        }
    }

    /** 反射取当前 IrisRenderingPipeline 的 ShadowRenderer 实例（取不到返回 null） */
    private static Object shadowRendererInstance() {
        if (M_GET_PIPELINE_MANAGER == null || M_GET_PIPELINE == null || F_SHADOW_RENDERER == null) return null;
        try {
            Object pm = M_GET_PIPELINE_MANAGER.invoke(null);
            if (pm == null) return null;
            Object pipeline = M_GET_PIPELINE.invoke(pm);
            if (pipeline == null || !F_SHADOW_RENDERER.getDeclaringClass().isInstance(pipeline)) return null;
            return F_SHADOW_RENDERER.get(pipeline);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int readIntStatic(Field f) {
        if (f == null) return -1;
        try {
            return (int) f.get(null);
        } catch (Throwable t) {
            return -1;
        }
    }
}
