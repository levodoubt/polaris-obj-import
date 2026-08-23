package com.levodoubt.objuilder.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.levodoubt.objuilder.core.Voxelizer;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;

/**
 * 模板族几何缓存（客户端）：把切分器产出的格内三角形烘焙为 BakedQuad。
 * BakedModel.getQuads 每次动态查询此缓存 → 命令导入后即时生效，无需重载。
 */
public class PieceModelCache {
    private static final Map<Integer, List<BakedQuad>> QUADS = new HashMap<>();

    public static void bake(Map<Integer, List<Voxelizer.Triangle>> geometry, TextureAtlasSprite sprite) {
        QUADS.clear();
        for (Map.Entry<Integer, List<Voxelizer.Triangle>> e : geometry.entrySet()) {
            List<BakedQuad> quads = new ArrayList<>();
            int color = colorForId(e.getKey());
            for (Voxelizer.Triangle t : e.getValue()) {
                quads.add(buildQuad(t, sprite, color));
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

    /** 每族一个颜色（黄金角色环）→ 直观验证聚类效果：同族同色 */
    private static int colorForId(int id) {
        float hue = (id * 0.618033988749895f) % 1.0f;
        int rgb = java.awt.Color.HSBtoRGB(hue, 0.65f, 1.0f);
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return 0xFF000000 | (b << 16) | (g << 8) | r; // ABGR
    }

    /**
     * 三角形 → BakedQuad。
     * 顶点格式（DefaultVertexFormat.BLOCK，每顶点 8 个 int）：
     *  0-2 位置(3f) · 3 颜色(ABGR) · 4-5 UV(2f) · 6 光照(UV2) · 7 法线(3 个 signed byte)
     *
     * 关键点：
     *  - 顶点位置 clamp 到 [0,1]（跨格三角形局部坐标会越界，越界部分超出方块体积不渲染 → 空洞）
     *  - UV 必须经 sprite.getU()/getV() 映射到 atlas 内绝对坐标（直接写 0~1 会采样到整张 atlas）
     */
    private static BakedQuad buildQuad(Voxelizer.Triangle t, TextureAtlasSprite sprite, int color) {
        Direction dir = Direction.getNearest(t.n().x(), t.n().y(), t.n().z());
        int[] data = new int[32];
        for (int i = 0; i < 3; i++) {
            var p = t.p(i);
            float x = clamp01(p.x());
            float y = clamp01(p.y());
            float z = clamp01(p.z());
            int base = i * 8;
            data[base + 0] = Float.floatToRawIntBits(x);
            data[base + 1] = Float.floatToRawIntBits(y);
            data[base + 2] = Float.floatToRawIntBits(z);
            data[base + 3] = color;
            data[base + 4] = Float.floatToRawIntBits(sprite.getU(x)); // 平铺 UV（white 纯色）
            data[base + 5] = Float.floatToRawIntBits(sprite.getV(z));
            data[base + 6] = 0x00F000F0;                     // 满亮（block 240 + sky 240）
            data[base + 7] = packNormal(t.n().x(), t.n().y(), t.n().z());
        }
        System.arraycopy(data, 16, data, 24, 8); // 第 4 顶点 = 第 3 重复（三角形）
        return new BakedQuad(data, -1, dir, sprite, true);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static int packNormal(float x, float y, float z) {
        return (int) (x * 127f) & 255 | ((int) (y * 127f) & 255) << 8 | ((int) (z * 127f) & 255) << 16;
    }
}
