package com.levodoubt.objuilder.client;

import java.lang.reflect.Field;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;

/**
 * 子块视锥（客户端，供渲染器做超大模型的空间分桶剔除）。
 *
 * <p><b>不再自己构造 Frustum</b>——自己用 Camera 姿态 + 投影矩阵拼 view/projection 极易在
 * 乘法顺序、坐标系约定、相机相对位置、culling 专用投影上出错，导致"各桶随视角/位置消失"。
 * 这里直接复用 MC 官方每帧维护好的 {@code LevelRenderer.cullingFrustum}
 * （renderLevel 在 renderEntities 之前就用 `prepareCullFrustum(view, projection, camPos)`
 * 正确构造并持续使用），与 MC 内部仅实体级剔除同一套，绝对正确。
 *
 * <p>访问方式：反射读 private final 字段（Field 缓存一次），失败返回 null → 调用方回退全量渲染。
 */
public final class ViewCulling {
    /** LevelRenderer 的 cullingFrustum 字段（NeoForge mapped 名），反射缓存 */
    private static final Field CULLING_FRUSTUM;

    static {
        Field f = null;
        try {
            f = LevelRenderer.class.getDeclaredField("cullingFrustum");
            f.setAccessible(true);
        } catch (Throwable t) {
            f = null;
        }
        CULLING_FRUSTUM = f;
    }

    private ViewCulling() {
    }

    /** 当前帧 MC 官方视锥（世界坐标，精确匹配实体渲染的 culling）；不可用时返回 null（回退全量渲染） */
    public static Frustum get() {
        if (CULLING_FRUSTUM == null) return null;
        LevelRenderer lr = Minecraft.getInstance().levelRenderer;
        if (lr == null) return null;
        try {
            return (Frustum) CULLING_FRUSTUM.get(lr);
        } catch (Throwable t) {
            return null;
        }
    }
}