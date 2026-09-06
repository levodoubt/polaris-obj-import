package com.levodoubt.objuilder.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 阴影专用 LOD 构建器（2.1.10 光影问题探究·问题 3 结论落地）。
 *
 * 背景：Photon / Eclipse 等光影包的阴影畸变（shadow distortion）在 vertex shader 对顶点
 * 做非线性投影、三角形内部线性插值；采样侧却按片元精确重算畸变。大三角形二者不一致 →
 * 阴影随玩家移动"游动/位移"+ 同心弧带。实测：把三角形边长压到 ≤4 格后该现象完全消失，
 * 但主渲染 pass 渲染高密网格会掉帧。
 *
 * 因此：只在【阴影 pass】用细分网格（shadow LOD），主 pass 仍用原始网格。
 * 本类对边长 > 阈值的三角形做 1-to-4 中点细分（三边取中点 → 4 子三角形），迭代到无长边。
 *
 * 顶点法线不插值（阴影 pass 的 shadow.vsh 不用法线；渲染器会自动回退面法线），
 * UV 必须插值（阴影 pass 的 shadow.fsh 依赖 baseColorTexture 的 alpha 通道做 MASK 镂空 discard）。
 */
public final class ShadowLodBuilder {
    /** 最大边长阈值（格）：实测 4 格稳定、8 格有残留，故默认 4。 */
    public static final float DEFAULT_THRESHOLD = 4.0f;
    /** 递归深度上限（安全阀；4 格阈值下最坏 log2(512/4)=7 层，12 层足够） */
    private static final int MAX_DEPTH = 12;

    private ShadowLodBuilder() {
    }

    /**
     * 把 src 中边长 > threshold 的三角形细分到边长 ≤ threshold。
     * 若无任何三角形需要细分，直接返回原列表（零拷贝、零额外内存）。
     */
    public static List<Voxelizer.Triangle> subdivide(List<Voxelizer.Triangle> src, float threshold) {
        if (src == null || src.isEmpty()) {
            return src;
        }
        float threshSq = threshold * threshold;
        // 先扫一遍是否有需要细分的三角形，避免无细分时做整表拷贝
        boolean anyLarge = false;
        for (Voxelizer.Triangle t : src) {
            if (maxEdgeSq(t) > threshSq) {
                anyLarge = true;
                break;
            }
        }
        if (!anyLarge) {
            return src;
        }
        List<Voxelizer.Triangle> out = new ArrayList<>(src.size() * 2);
        for (Voxelizer.Triangle t : src) {
            subdivide(t, threshold, 0, out);
        }
        return out;
    }

    /** 递归细分单个三角形。返回该三角形是否被切分（供顶层判断）。 */
    private static boolean subdivide(Voxelizer.Triangle t, float threshold, int depth,
                                     List<Voxelizer.Triangle> out) {
        ObjMesh.Vec3 p0 = t.p(0), p1 = t.p(1), p2 = t.p(2);
        if (maxEdgeSq(t) <= threshold * threshold || depth >= MAX_DEPTH) {
            out.add(t);
            return false;
        }

        ObjMesh.Vec3 m01 = p0.lerp(p1, 0.5f);
        ObjMesh.Vec3 m12 = p1.lerp(p2, 0.5f);
        ObjMesh.Vec3 m20 = p2.lerp(p0, 0.5f);

        ObjMesh.Vec2 uv0 = t.uv(0), uv1 = t.uv(1), uv2 = t.uv(2);
        ObjMesh.Vec2 uv01 = uv0 != null && uv1 != null ? uv0.lerp(uv1, 0.5f) : null;
        ObjMesh.Vec2 uv12 = uv1 != null && uv2 != null ? uv1.lerp(uv2, 0.5f) : null;
        ObjMesh.Vec2 uv20 = uv2 != null && uv0 != null ? uv2.lerp(uv0, 0.5f) : null;

        ObjMesh.Vec3 fn = t.n();
        int mat = t.materialId();

        // 4 个子三角形，保持 CCW 绕向；顶点法线置 null（渲染器回退面法线，阴影 pass 不用）
        subdivide(new Voxelizer.Triangle(p0, m01, m20, fn, null, null, null, uv0, uv01, uv20, mat),
                threshold, depth + 1, out);
        subdivide(new Voxelizer.Triangle(p1, m12, m01, fn, null, null, null, uv1, uv12, uv01, mat),
                threshold, depth + 1, out);
        subdivide(new Voxelizer.Triangle(p2, m20, m12, fn, null, null, null, uv2, uv20, uv12, mat),
                threshold, depth + 1, out);
        subdivide(new Voxelizer.Triangle(m01, m12, m20, fn, null, null, null, uv01, uv12, uv20, mat),
                threshold, depth + 1, out);
        return true;
    }

    private static float maxEdgeSq(Voxelizer.Triangle t) {
        float d01 = distSq(t.p(0), t.p(1));
        float d12 = distSq(t.p(1), t.p(2));
        float d20 = distSq(t.p(2), t.p(0));
        return Math.max(Math.max(d01, d12), d20);
    }

    private static float distSq(ObjMesh.Vec3 a, ObjMesh.Vec3 b) {
        float dx = a.x() - b.x();
        float dy = a.y() - b.y();
        float dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }
}
