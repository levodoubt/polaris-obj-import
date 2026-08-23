package com.levodoubt.objuilder.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.levodoubt.objuilder.core.ObjMesh;
import com.levodoubt.objuilder.core.Voxelizer;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;

/**
 * 模板族几何缓存（客户端）：把切分器产出的格内三角形烘焙为 BakedQuad。
 * 贴图继承的等效方案：用 UV 采样模型贴图，把颜色烘焙到顶点色（配合 white 纹理 + MC 原生光照）。
 * BakedModel.getQuads 每次动态查询此缓存 → 命令导入后即时生效。
 */
public class PieceModelCache {
    private static final Map<Integer, List<BakedQuad>> QUADS = new HashMap<>();

    public static void bake(Map<Integer, List<Voxelizer.Triangle>> geometry,
                            TextureAtlasSprite sprite, NativeImage texture) {
        QUADS.clear();
        for (Map.Entry<Integer, List<Voxelizer.Triangle>> e : geometry.entrySet()) {
            List<BakedQuad> quads = new ArrayList<>();
            for (Voxelizer.Triangle t : e.getValue()) {
                quads.add(buildQuad(t, sprite, texture));
            }
            QUADS.put(e.getKey(), quads);
        }
    }

    public static List<BakedQuad> getQuads(int pieceId) {
        return QUADS.get(pieceId);
    }

    public static boolean isEmpty() {
        return QUADS.isEmpty();
    }

    /**
     * 三角形 → BakedQuad。
     * 顶点格式（DefaultVertexFormat.BLOCK，每顶点 8 个 int）：
     *  0-2 位置 · 3 颜色(ABGR) · 4-5 UV · 6 光照(UV2，渲染时由 MC AO 路径覆盖) · 7 法线
     * 顶点色 = UV 采样模型贴图（无贴图则 UV 可视化色，无 UV 则纯白）。
     */
    private static BakedQuad buildQuad(Voxelizer.Triangle t, TextureAtlasSprite sprite, NativeImage texture) {
        // 方向用面法线（cull 判定），光照法线用顶点法线（平滑着色）
        Direction dir = Direction.getNearest(t.n().x(), t.n().y(), t.n().z());
        int[] data = new int[32];
        for (int i = 0; i < 3; i++) {
            var p = t.p(i);
            float x = clamp01(p.x());
            float y = clamp01(p.y());
            float z = clamp01(p.z());
            ObjMesh.Vec3 vn = t.vn(i) != null ? t.vn(i) : t.n(); // 平滑法线，无则回退面法线
            int base = i * 8;
            data[base + 0] = Float.floatToRawIntBits(x);
            data[base + 1] = Float.floatToRawIntBits(y);
            data[base + 2] = Float.floatToRawIntBits(z);
            data[base + 3] = colorForVertex(t, i, texture);
            data[base + 4] = Float.floatToRawIntBits(sprite.getU(x));
            data[base + 5] = Float.floatToRawIntBits(sprite.getV(z));
            data[base + 6] = 0;
            data[base + 7] = packNormal(vn.x(), vn.y(), vn.z());
        }
        System.arraycopy(data, 16, data, 24, 8);
        return new BakedQuad(data, -1, dir, sprite, true);
    }

    /** 顶点色：UV 采样贴图 → 颜色；无贴图则 UV 可视化色；无 UV 则纯白 */
    private static int colorForVertex(Voxelizer.Triangle t, int i, NativeImage texture) {
        ObjMesh.Vec2 uv = t.uv(i);
        if (uv != null && texture != null) {
            // UV 平铺（取模到 [0,1]）+ v 翻转（OBJ v 底=0，图像 y 顶=0）
            float u = wrap01(uv.u());
            float v = wrap01(uv.v());
            int px = clamp((int) (u * texture.getWidth()), 0, texture.getWidth() - 1);
            int py = clamp((int) ((1f - v) * texture.getHeight()), 0, texture.getHeight() - 1);
            return texture.getPixelRGBA(px, py); // ABGR，与 BakedQuad 顶点色格式一致
        }
        if (uv != null) {
            // UV 可视化色：u→红，v→绿（验证 UV 展开是否正确）
            int r = (int) (wrap01(uv.u()) * 255) & 0xFF;
            int g = (int) (wrap01(uv.v()) * 255) & 0xFF;
            return 0xFF000000 | (0x80 << 16) | (g << 8) | r; // ABGR
        }
        return 0xFFFFFFFF; // 无 UV：纯白（配合 AO 光照显示形状）
    }

    private static float wrap01(float v) {
        return v - (float) Math.floor(v);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int packNormal(float x, float y, float z) {
        return (int) (x * 127f) & 255 | ((int) (y * 127f) & 255) << 8 | ((int) (z * 127f) & 255) << 16;
    }
}
