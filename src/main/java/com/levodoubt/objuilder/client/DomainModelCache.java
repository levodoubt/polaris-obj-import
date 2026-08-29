package com.levodoubt.objuilder.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.levodoubt.objuilder.core.ObjMesh;
import com.levodoubt.objuilder.core.Voxelizer;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
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
    /** modelId → (materialId → alpha 透明度；仅 <1（半透明）材质在内) */
    private static final Map<Integer, Map<Integer, Float>> ALPHAS = new HashMap<>();
    /** modelId → doubleSided 的 materialId 集合（glTF doubleSided=true → 渲染用双面 NO_CULL） */
    private static final Map<Integer, Set<Integer>> DOUBLE_SIDED = new HashMap<>();
    /** modelId → MASK 镂空的 materialId 集合（glTF alphaMode=MASK → alpha-test cutout 渲染，B1 收尾·事项 5） */
    private static final Map<Integer, Set<Integer>> MASKED = new HashMap<>();
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
     * 每帧更新模型几何（动画用）：只重建三角形与 AABB，不动纹理/颜色/发光映射
     * （这些由 bake/registerTextures 等一次性写入）。动画模型每 tick 调用此方法。
     */
    public static void updateGeometry(int modelId, List<Voxelizer.Domain> domains) {
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
        return registerPbrTextures(images, null, null);
    }

    /**
     * 批量注册多材质贴图为 PBR 动态纹理（无 Iris 时退化为普通动态纹理）。
     * 每材质索引 i：base = baseImages[i]（null=无贴图），normal/specular 为 PBR 辅助贴图。
     * 缺 normal/specular 时自动补中性贴图（法线 (0,0,1)、无金属无高光），
     * 确保光影下实体始终有 texture_n / texture_s 绑定，避免采样未绑定纹理产生噪点（见子工程 4 补遗）。
     * 返回 materialId → 纹理位置映射。
     */
    public static Map<Integer, ResourceLocation> registerPbrTextures(List<NativeImage> baseImages,
                                                                     List<NativeImage> normalImages,
                                                                     List<NativeImage> specularImages) {
        Map<Integer, ResourceLocation> map = new HashMap<>();
        int size = baseImages != null ? baseImages.size() : 0;
        for (int i = 0; i < size; i++) {
            NativeImage base = baseImages.get(i);
            NativeImage normal = normalImages != null && i < normalImages.size() ? normalImages.get(i) : null;
            NativeImage specular = specularImages != null && i < specularImages.size() ? specularImages.get(i) : null;
            // 无 base 贴图（纯色/自发光材质，如 GLB 无 baseColorTexture）：补白色 base，
            // 但保留其法线/specular → 光影下法线/粗糙度/金属照常生效（不能整材质跳过）
            if (base == null) base = whitePixel();
            if (normal == null) normal = neutralNormal();
            if (specular == null) specular = neutralSpecular();
            // register(String, DynamicTexture) 重载只收 DynamicTexture 且返回位置；ResourceLocation 重载接受 AbstractTexture 但返回 void
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath("polarisobjuilder",
                    "model_" + TEXTURE_SEQ.incrementAndGet());
            Minecraft.getInstance().getTextureManager().register(loc, new PbrCapableTexture(base, normal, specular));
            map.put(i, loc);
        }
        return map;
    }

    /** 无贴图材质（无 base 贴图，如无材质的 OBJ / 纯色 glb）用的默认白色 + 中性 PBR 纹理 */
    private static ResourceLocation DEFAULT_TEXTURE;

    public static ResourceLocation defaultTexture() {
        if (DEFAULT_TEXTURE == null) {
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath("polarisobjuilder", "model_default_pbr");
            Minecraft.getInstance().getTextureManager().register(loc,
                    new PbrCapableTexture(whitePixel(), neutralNormal(), neutralSpecular()));
            DEFAULT_TEXTURE = loc;
        }
        return DEFAULT_TEXTURE;
    }

    /** 1×1 白色像素（无 base 贴图材质的兜底 base，保留其 normal/specular 生效） */
    private static NativeImage whitePixel() {
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
        img.setPixelRGBA(0, 0, 0xFFFFFFFF); // 白色
        return img;
    }

    /** 中性法线贴图：切线空间法线 (0,0,1) → GL RGB (0.5,0.5,1.0)。NativeImage 为 ABGR 布局 */
    private static NativeImage neutralNormal() {
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
        // ABGR：A=255, B=255, G=128, R=128 → 0xFFFF8080
        img.setPixelRGBA(0, 0, 0xFF000000 | (255 << 16) | (128 << 8) | 128);
        return img;
    }

    /** 中性 specular（labPBR）：无金属（G=F0≈0.04）、无高光（R=smoothness=0）、无自发光（A=255） */
    private static NativeImage neutralSpecular() {
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
        // ABGR：R=0, G=10(f0≈0.04), B=0, A=255 → 0xFF000A00
        img.setPixelRGBA(0, 0, 0xFF000000 | (0 << 16) | (10 << 8) | 0);
        return img;
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

    /** 设置某模型的各材质透明度（索引 = materialId；仅 alpha<1 的半透明材质会存入） */
    public static void setMaterialAlpha(int modelId, List<Float> alphas) {
        Map<Integer, Float> m = new HashMap<>();
        if (alphas != null) {
            for (int i = 0; i < alphas.size(); i++) {
                Float a = alphas.get(i);
                if (a != null && a < 1f) m.put(i, a);
            }
        }
        ALPHAS.put(modelId, m);
    }

    /** 设置某模型的 doubleSided 材质索引集合（glTF doubleSided=true → 双面渲染 NO_CULL） */
    public static void setDoubleSided(int modelId, Set<Integer> doubleSidedMats) {
        if (doubleSidedMats == null || doubleSidedMats.isEmpty()) {
            DOUBLE_SIDED.remove(modelId);
        } else {
            DOUBLE_SIDED.put(modelId, doubleSidedMats);
        }
    }

    /** 设置某模型的 MASK 镂空材质索引集合（glTF alphaMode=MASK → alpha-test cutout 渲染） */
    public static void setMaterialMasked(int modelId, Set<Integer> maskedMats) {
        if (maskedMats == null || maskedMats.isEmpty()) {
            MASKED.remove(modelId);
        } else {
            MASKED.put(modelId, maskedMats);
        }
    }

    /** 某模型某材质是否 MASK 镂空（alpha-test cutout 渲染，B1 收尾·事项 5） */
    public static boolean isMasked(int modelId, int materialId) {
        Set<Integer> s = MASKED.get(modelId);
        return s != null && s.contains(materialId);
    }

    /**
     * 释放某模型的全部缓存（B1 收尾·事项 1：引用计数归零后调用）。
     * 释放几何 / AABB / 颜色 / 发光 / 透明度 / doubleSided 映射，以及该模型独占的动态纹理
     * （PbrCapableTexture 含 base/normal/specular，随 TextureManager.release 一并释放 GL 资源）。
     * 共享的默认白纹理（defaultTexture）与回退 sprite 不释放（其它模型可能还在用）。
     */
    public static void release(int modelId) {
        Map<Integer, ResourceLocation> texs = TEXTURES.remove(modelId);
        if (texs != null && !texs.isEmpty()) {
            for (ResourceLocation loc : texs.values()) {
                if (loc != null && !loc.equals(DEFAULT_TEXTURE)) {
                    Minecraft.getInstance().getTextureManager().release(loc);
                }
            }
        }
        GEOM.remove(modelId);
        BOUNDS.remove(modelId);
        COLORS.remove(modelId);
        EMISSIVE.remove(modelId);
        ALPHAS.remove(modelId);
        DOUBLE_SIDED.remove(modelId);
        MASKED.remove(modelId);
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

    /** 某模型某材质的透明度（默认 1 = 不透明；<1 = 半透明） */
    public static float alpha(int modelId, int materialId) {
        Map<Integer, Float> m = ALPHAS.get(modelId);
        return m == null ? 1f : m.getOrDefault(materialId, 1f);
    }

    /** 某模型某材质是否 doubleSided（glTF doubleSided=true → 双面渲染 NO_CULL） */
    public static boolean isDoubleSided(int modelId, int materialId) {
        Set<Integer> s = DOUBLE_SIDED.get(modelId);
        return s != null && s.contains(materialId);
    }

    /** 无贴图时的白色回退 sprite */
    public static TextureAtlasSprite fallbackSprite() {
        return fallbackSprite;
    }

    public static int size() {
        return GEOM.size();
    }
}
