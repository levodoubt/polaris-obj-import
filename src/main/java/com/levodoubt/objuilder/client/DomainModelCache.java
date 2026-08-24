package com.levodoubt.objuilder.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.levodoubt.objuilder.core.ObjMesh;
import com.levodoubt.objuilder.core.Voxelizer;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;

/**
 * 共面域几何缓存（客户端）：按【模型 id】分组缓存，支持地图内多个模型共存。
 * 每次导入生成唯一 modelId，几何 / 贴图 / 颜色 / 发光 均按 modelId 独立存储，
 * 避免后导入的模型覆盖先导入模型的缓存，导致前导入模型贴图错乱。
 */
public class DomainModelCache {
    /** modelId → 单域整体三角形 */
    private static final Map<Integer, List<Voxelizer.Triangle>> GEOM = new HashMap<>();
    /** modelId → 域局部 AABB（视锥剔除用） */
    private static final Map<Integer, AABB> BOUNDS = new HashMap<>();
    /** modelId → (materialId → 动态纹理位置；无贴图材质不在内) */
    private static final Map<Integer, Map<Integer, ResourceLocation>> TEXTURES = new HashMap<>();
    /** modelId → (materialId → [r,g,b] 漫反射；无贴图材质用) */
    private static final Map<Integer, Map<Integer, float[]>> COLORS = new HashMap<>();
    /** modelId → (materialId → [r,g,b] 自发光；仅 Ke>0 材质在内) */
    private static final Map<Integer, Map<Integer, float[]>> EMISSIVE = new HashMap<>();
    /** 无贴图时的白色回退 sprite */
    private static TextureAtlasSprite fallbackSprite;
    /** 模型 id 序号（每次导入自增，唯一） */
    private static final AtomicInteger MODEL_SEQ = new AtomicInteger();
    /** 纹理名序号（保证每次注册名唯一） */
    private static final AtomicInteger TEXTURE_SEQ = new AtomicInteger();

    /** 分配新的全局唯一模型 id */
    public static int nextModelId() {
        return MODEL_SEQ.incrementAndGet();
    }

    /** 缓存一个模型的几何 + 贴图映射 + 域局部 AABB */
    public static void bake(int modelId, List<Voxelizer.Domain> domains, TextureAtlasSprite spr,
                            Map<Integer, ResourceLocation> textures) {
        fallbackSprite = spr;
        TEXTURES.put(modelId, textures != null ? textures : Map.of());
        List<Voxelizer.Triangle> tris = new ArrayList<>();
        if (domains != null) {
            for (Voxelizer.Domain d : domains) {
                tris.addAll(d.tris());
            }
        }
        GEOM.put(modelId, tris);
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (Voxelizer.Triangle t : tris) {
            for (int i = 0; i < 3; i++) {
                ObjMesh.Vec3 p = t.p(i);
                minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x());
                minY = Math.min(minY, p.y()); maxY = Math.max(maxY, p.y());
                minZ = Math.min(minZ, p.z()); maxZ = Math.max(maxZ, p.z());
            }
        }
        BOUNDS.put(modelId, new AABB(minX, minY, minZ, maxX, maxY, maxZ));
    }

    /**
     * 批量注册多材质贴图为动态纹理。传入 null 表示无贴图。
     * 返回 materialId → 纹理位置映射。多模型共存，不释放旧纹理。
     */
    public static Map<Integer, ResourceLocation> registerTextures(List<NativeImage> images) {
        Map<Integer, ResourceLocation> map = new HashMap<>();
        if (images != null) {
            for (int i = 0; i < images.size(); i++) {
                NativeImage img = images.get(i);
                if (img == null) continue;
                ResourceLocation loc = Minecraft.getInstance().getTextureManager().register(
                        "polarisobjuilder_model_" + TEXTURE_SEQ.incrementAndGet(),
                        new DynamicTexture(img));
                map.put(i, loc);
            }
        }
        return map;
    }

    /** 设置某模型的各材质漫反射颜色（索引 = materialId） */
    public static void setMaterialColors(int modelId, List<float[]> colors) {
        Map<Integer, float[]> m = new HashMap<>();
        if (colors != null) {
            for (int i = 0; i < colors.size(); i++) {
                if (colors.get(i) != null) m.put(i, colors.get(i));
            }
        }
        COLORS.put(modelId, m);
    }

    /** 设置某模型的各材质自发光颜色（索引 = materialId；仅 Ke>0 的材质会存入） */
    public static void setMaterialEmissive(int modelId, List<float[]> emissive) {
        Map<Integer, float[]> m = new HashMap<>();
        if (emissive != null) {
            for (int i = 0; i < emissive.size(); i++) {
                float[] ke = emissive.get(i);
                if (ke != null && (ke[0] > 0 || ke[1] > 0 || ke[2] > 0)) {
                    m.put(i, ke);
                }
            }
        }
        EMISSIVE.put(modelId, m);
    }

    /** 取模型几何（单域整体三角形） */
    public static List<Voxelizer.Triangle> get(int modelId) {
        return GEOM.get(modelId);
    }

    /** 模型局部 AABB（视锥剔除用） */
    public static AABB getBounds(int modelId) {
        return BOUNDS.get(modelId);
    }

    /** 某模型某材质的贴图位置（null = 该材质无贴图） */
    public static ResourceLocation texture(int modelId, int materialId) {
        Map<Integer, ResourceLocation> m = TEXTURES.get(modelId);
        return m == null ? null : m.get(materialId);
    }

    /** 某模型某材质的漫反射颜色（null = 默认白） */
    public static float[] color(int modelId, int materialId) {
        Map<Integer, float[]> m = COLORS.get(modelId);
        return m == null ? null : m.get(materialId);
    }

    /** 某模型某材质的自发光颜色（null = 无自发光） */
    public static float[] emissive(int modelId, int materialId) {
        Map<Integer, float[]> m = EMISSIVE.get(modelId);
        return m == null ? null : m.get(materialId);
    }

    /** 无贴图时的白色回退 sprite */
    public static TextureAtlasSprite fallbackSprite() {
        return fallbackSprite;
    }

    public static int size() {
        return GEOM.size();
    }
}
