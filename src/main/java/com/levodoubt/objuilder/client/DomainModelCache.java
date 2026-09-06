package com.levodoubt.objuilder.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.levodoubt.objuilder.core.ObjMesh;
import com.levodoubt.objuilder.core.ShadowLodBuilder;
import com.levodoubt.objuilder.core.Voxelizer;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
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
    /** modelId → 阴影专用 LOD 三角形（大面细分后；懒构建，仅阴影 pass 用，主 pass 仍用 GEOM） */
    private static final Map<Integer, List<Voxelizer.Triangle>> SHADOW_LOD = new HashMap<>();
    /** modelId → 主渲染紧凑网格（展平连续数组，渲染高速路径；懒构建，动画 updateGeometry 时失效） */
    private static final Map<Integer, RenderMesh> RENDER_MESH = new HashMap<>();
    /** modelId → 阴影 pass 紧凑网格（从 SHADOW_LOD 懒构建；同上失效） */
    private static final Map<Integer, RenderMesh> SHADOW_RENDER_MESH = new HashMap<>();
    /** modelId → 主渲染 GPU 常驻缓冲（方案 A：静态模型 VRAM 直绘；动画 updateGeometry 时失效） */
    private static final Map<Integer, StaticModelBuffer> STATIC_BUFFER = new HashMap<>();
    /** modelId → 阴影 pass GPU 常驻缓冲（同上） */
    private static final Map<Integer, StaticModelBuffer> SHADOW_STATIC_BUFFER = new HashMap<>();
    /** modelId → 域局部 AABB（视锥剔除用） */
    private static final Map<Integer, AABB> BOUNDS = new HashMap<>();
    /** modelId → 子块列表（空间网格分桶，供渲染器逐桶视锥剔除；null = 未分桶，走全量渲染） */
    private static final Map<Integer, List<Bucket>> BUCKETS = new HashMap<>();
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

    /**
     * 子块（空间网格单元）：一个 AABB + 落入该格的所有三角形。
     * 渲染时按 AABB 做视锥剔除，视锥外的桶整桶跳过三角形提交。
     */
    public static final class Bucket {
        /** 桶的局部坐标 AABB（相对模型原点） */
        public final AABB bounds;
        /** 桶内三角形 */
        public final List<Voxelizer.Triangle> tris;

        Bucket(AABB bounds, List<Voxelizer.Triangle> tris) {
            this.bounds = bounds;
            this.tris = tris;
        }
    }

    /** 子块分桶的空间网格边长（格）。三角形按重心归桶，桶越小剔除越精确，桶越多剔除测试越多 */
    private static final float BUCKET_SIZE = 4.0f;
    /** 分桶体积阈值：模型 AABB 体积大于 4×4×4 格（64 格³）才分桶（小模型分桶无收益） */
    private static final double BUCKET_MIN_VOLUME = 64.0;

    /**
     * 空间网格分桶：模型整体 AABB 体积大于 4×4×4 格时才分桶；
     * 三角形按重心坐标归入 BUCKET_SIZE 网格单元。
     * 返回 null 表示不分桶（体积太小或全挤一格，无剔除意义）。
     */
    private static List<Bucket> bucketize(List<Voxelizer.Triangle> tris) {
        // 先算整体 AABB，判断体积是否达到分桶阈值
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
        double volume = (double) (maxX - minX) * (maxY - minY) * (maxZ - minZ);
        if (volume <= BUCKET_MIN_VOLUME) return null; // 体积不超过 4×4×4 格，不分桶

        Map<Long, List<Voxelizer.Triangle>> cells = new HashMap<>();
        for (Voxelizer.Triangle t : tris) {
            float cx = 0f, cy = 0f, cz = 0f;
            for (int i = 0; i < 3; i++) {
                ObjMesh.Vec3 p = t.p(i);
                cx += p.x(); cy += p.y(); cz += p.z();
            }
            cx /= 3f; cy /= 3f; cz /= 3f;
            int gx = (int) Math.floor(cx / BUCKET_SIZE);
            int gy = (int) Math.floor(cy / BUCKET_SIZE);
            int gz = (int) Math.floor(cz / BUCKET_SIZE);
            cells.computeIfAbsent(packCell(gx, gy, gz), k -> new ArrayList<>()).add(t);
        }
        if (cells.size() <= 1) return null; // 全挤一格，无剔除意义
        List<Bucket> buckets = new ArrayList<>(cells.size());
        for (List<Voxelizer.Triangle> cell : cells.values()) {
            float bMinX = Float.MAX_VALUE, bMinY = Float.MAX_VALUE, bMinZ = Float.MAX_VALUE;
            float bMaxX = -Float.MAX_VALUE, bMaxY = -Float.MAX_VALUE, bMaxZ = -Float.MAX_VALUE;
            for (Voxelizer.Triangle t : cell) {
                for (int i = 0; i < 3; i++) {
                    ObjMesh.Vec3 p = t.p(i);
                    bMinX = Math.min(bMinX, p.x()); bMaxX = Math.max(bMaxX, p.x());
                    bMinY = Math.min(bMinY, p.y()); bMaxY = Math.max(bMaxY, p.y());
                    bMinZ = Math.min(bMinZ, p.z()); bMaxZ = Math.max(bMaxZ, p.z());
                }
            }
            buckets.add(new Bucket(new AABB(bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ), cell));
        }
        return buckets;
    }

    /** 三维网格坐标打包成 long（每维 21 bit，范围 ±1048575，BUCKET_SIZE=4 → 覆盖 ±419 万格，足够） */
    private static long packCell(int gx, int gy, int gz) {
        long l = gx & 0x1FFFFF;
        l = (l << 21) | (gy & 0x1FFFFF);
        l = (l << 21) | (gz & 0x1FFFFF);
        return l;
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
        BUCKETS.put(modelId, bucketize(tris));
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
        BUCKETS.put(modelId, bucketize(tris));
        SHADOW_LOD.remove(modelId); // 动画几何变了，旧的阴影 LOD 失效，下次阴影 pass 懒重建
        RENDER_MESH.remove(modelId);        // 紧凑网格同样失效（动画模型每帧懒重建）
        SHADOW_RENDER_MESH.remove(modelId);
        releaseStaticBuffer(modelId);       // GPU 常驻缓冲失效（动画几何变化）
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
        BUCKETS.remove(modelId);
        SHADOW_LOD.remove(modelId);
        RENDER_MESH.remove(modelId);
        SHADOW_RENDER_MESH.remove(modelId);
        releaseStaticBuffer(modelId);
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

    /**
     * 取阴影专用 LOD（大面细分后的三角形，仅阴影 pass 用）。
     * 懒构建：首次调用时对 GEOM 做细分并缓存；无大面时直接返回原 GEOM 列表（零拷贝）。
     * 主 pass 请继续用 {@link #get(int)}（原始网格，避免高密网格拖慢主渲染）。
     */
    public static List<Voxelizer.Triangle> getShadowLod(int modelId) {
        List<Voxelizer.Triangle> lod = SHADOW_LOD.get(modelId);
        if (lod == null) {
            List<Voxelizer.Triangle> src = GEOM.get(modelId);
            lod = src == null ? List.of() : ShadowLodBuilder.subdivide(src, ShadowLodBuilder.DEFAULT_THRESHOLD);
            SHADOW_LOD.put(modelId, lod);
        }
        return lod;
    }

    /**
     * 取紧凑渲染网格（2.1.11 性能优化·CPU 提交批量化）。
     * - 主 pass：从 GEOM 懒构建并缓存；
     * - 阴影 pass：从 SHADOW_LOD（细分网格）懒构建并缓存。
     * 几何为空时返回 null（调用方按「几何缺失」处理）。
     */
    public static RenderMesh getRenderMesh(int modelId, boolean shadowPass) {
        if (shadowPass) {
            RenderMesh m = SHADOW_RENDER_MESH.get(modelId);
            if (m == null) {
                m = buildRenderMesh(modelId, getShadowLod(modelId));
                SHADOW_RENDER_MESH.put(modelId, m);
            }
            return m;
        }
        RenderMesh m = RENDER_MESH.get(modelId);
        if (m == null) {
            m = buildRenderMesh(modelId, GEOM.get(modelId));
            RENDER_MESH.put(modelId, m);
        }
        return m;
    }

    /**
     * 取 GPU 常驻缓冲（方案 A）。仅对<b>非动画中</b>的模型生效：
     * - 动画播放中的模型返回 null（走 CPU 路径，几何每帧变化不值得烘焙）；
     * - 懒构建 + 缓存；动画 updateGeometry 失效后重建（动画停止后下一帧重建为稳定几何）；
     * - 非自发光材质烘焙了实体 packedLight，光照变化时重建。
     */
    public static StaticModelBuffer getStaticBuffer(int modelId, boolean shadowPass, int packedLight) {
        if (GlbAnimationManager.isAnimating(modelId)) return null;
        if (shadowPass) {
            StaticModelBuffer b = SHADOW_STATIC_BUFFER.get(modelId);
            if (b == null || b.lightChanged(packedLight)) {
                releaseShadowStaticBuffer(modelId);
                b = StaticModelBuffer.build(modelId, getRenderMesh(modelId, true), true, packedLight);
                SHADOW_STATIC_BUFFER.put(modelId, b);
            }
            return b;
        }
        StaticModelBuffer b = STATIC_BUFFER.get(modelId);
        if (b == null || b.lightChanged(packedLight)) {
            releaseStaticBuffer(modelId);
            b = StaticModelBuffer.build(modelId, getRenderMesh(modelId, false), false, packedLight);
            STATIC_BUFFER.put(modelId, b);
        }
        return b;
    }

    /** 释放某模型的主/阴影 GPU 常驻缓冲 */
    private static void releaseStaticBuffer(int modelId) {
        StaticModelBuffer b = STATIC_BUFFER.remove(modelId);
        if (b != null) b.close();
    }

    private static void releaseShadowStaticBuffer(int modelId) {
        StaticModelBuffer b = SHADOW_STATIC_BUFFER.remove(modelId);
        if (b != null) b.close();
    }

    /**
     * 把三角形列表展平为紧凑渲染网格：按 materialId 分组（保持首见顺序，与旧渲染器 LinkedHashMap 一致）
     * → 连续写入位置/法线/UV 数组 → 预计算每 run 的打包颜色（ARGB）与光照。
     * 逐顶点逻辑与原 {@link DomainEntityRenderer} 渲染循环完全一致（含无贴图位置投影 UV、glb v 翻转、
     * 顶点法线回退面法线），保证视觉零差异。
     */
    private static RenderMesh buildRenderMesh(int modelId, List<Voxelizer.Triangle> tris) {
        if (tris == null || tris.isEmpty()) return null;

        // 1) 按材质分组（保持首见顺序）
        Map<Integer, List<Voxelizer.Triangle>> byMat = new LinkedHashMap<>();
        for (Voxelizer.Triangle t : tris) {
            byMat.computeIfAbsent(t.materialId(), k -> new ArrayList<>()).add(t);
        }

        TextureAtlasSprite fallback = fallbackSprite;
        float fU = fallback != null ? fallback.getU(0.5f) : 0f;
        float fV = fallback != null ? fallback.getV(0.5f) : 0f;

        int totalVerts = tris.size() * 3;
        float[] pos = new float[totalVerts * 3];
        float[] nrm = new float[totalVerts * 3];
        float[] uv = new float[totalVerts * 2];
        int runCount = byMat.size();
        int[] runMaterial = new int[runCount];
        int[] runStart = new int[runCount];
        int[] runLength = new int[runCount];
        int[] runColor = new int[runCount];
        int[] runLight = new int[runCount];

        int vi = 0; // 顶点下标
        int run = 0;
        for (Map.Entry<Integer, List<Voxelizer.Triangle>> e : byMat.entrySet()) {
            int matId = e.getKey();
            List<Voxelizer.Triangle> list = e.getValue();
            runMaterial[run] = matId;
            runStart[run] = vi;
            runLength[run] = list.size() * 3;

            // 材质属性（与原渲染器逐材质逻辑一致）
            boolean noTex = texture(modelId, matId) == null;
            float[] kd = noTex ? color(modelId, matId) : null;
            float[] em = emissive(modelId, matId);
            boolean emissive = em != null;
            float alpha = alpha(modelId, matId);
            float cr, cg, cb;
            if (emissive) {
                float kr = kd != null ? kd[0] : 1f;
                float kg = kd != null ? kd[1] : 1f;
                float kb = kd != null ? kd[2] : 1f;
                cr = Math.min(1f, kr + em[0]);
                cg = Math.min(1f, kg + em[1]);
                cb = Math.min(1f, kb + em[2]);
            } else {
                cr = kd != null ? kd[0] : 1f;
                cg = kd != null ? kd[1] : 1f;
                cb = kd != null ? kd[2] : 1f;
            }
            int r = (int) (cr * 255f), g = (int) (cg * 255f), b = (int) (cb * 255f), a = (int) (alpha * 255f);
            runColor[run] = (a << 24) | (r << 16) | (g << 8) | b;
            runLight[run] = emissive ? LightTexture.pack(15, 15) : -1;

            for (Voxelizer.Triangle t : list) {
                ObjMesh.Vec3 fn = t.n();
                for (int i = 0; i < 3; i++) {
                    ObjMesh.Vec3 p = t.p(i);
                    ObjMesh.Vec3 vn = t.vn(i) != null ? t.vn(i) : fn;
                    float u, v;
                    if (noTex) {
                        // 无贴图：按面法线主分量选投影轴（与原渲染器一致，保证光影下 tangent 梯度）
                        float ax = Math.abs(fn.x()), ay = Math.abs(fn.y()), az = Math.abs(fn.z());
                        if (ay >= ax && ay >= az) {
                            u = p.x();
                            v = p.z();
                        } else if (ax >= az) {
                            u = p.z();
                            v = p.y();
                        } else {
                            u = p.x();
                            v = p.y();
                        }
                    } else if (t.uv(i) != null) {
                        u = t.uv(i).u();
                        v = 1f - t.uv(i).v(); // 与渲染器一致：glb 已在展平翻转过一次，此处再翻 = 净不翻
                    } else {
                        u = fU;
                        v = fV;
                    }
                    int i3 = vi * 3;
                    pos[i3] = p.x();
                    pos[i3 + 1] = p.y();
                    pos[i3 + 2] = p.z();
                    nrm[i3] = vn.x();
                    nrm[i3 + 1] = vn.y();
                    nrm[i3 + 2] = vn.z();
                    int i2 = vi * 2;
                    uv[i2] = u;
                    uv[i2 + 1] = v;
                    vi++;
                }
            }
            run++;
        }
        return new RenderMesh(runCount, runMaterial, runStart, runLength, runColor, runLight,
                totalVerts, pos, nrm, uv);
    }

    /** 模型局部 AABB（视锥剔除用） */
    public static AABB getBounds(int modelId) {
        return BOUNDS.get(modelId);
    }

    /** 模型子块列表（null = 未分桶，走全量渲染） */
    public static List<Bucket> buckets(int modelId) {
        return BUCKETS.get(modelId);
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
