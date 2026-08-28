package com.levodoubt.objuilder.client;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.levodoubt.objuilder.PolarisObjuilder;
import com.levodoubt.objuilder.command.ObjImportCommand;
import com.levodoubt.objuilder.core.GlbModel;
import com.levodoubt.objuilder.core.GlbParser;
import com.levodoubt.objuilder.core.ObjMesh;
import com.levodoubt.objuilder.core.ObjParser;
import com.levodoubt.objuilder.core.Voxelizer;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

/**
 * 全功能摆放 · 客户端几何懒加载（子工程 5 + B1 收尾·事项 4）。
 *
 * 服务端只存模型引用字符串（如 models/door.glb），不解析模型；
 * 客户端收到带 modelRef 的 DomainEntity 后，按引用本地懒加载几何。
 *
 * B1 收尾·事项 4 升级：每个摆放（placementId）分配独立 modelId（破共享），
 * - 同一引用的多个摆放各自持有独立几何缓存 + 独立 GlbAnimationController →
 *   多同名门可独立开关（右键/剧情精确触发一个，其它不受影响）
 * - 释放按摆放（placementId）：服务端 objremove/objclear 删一个广播一个 CacheReleasePayload
 * - 加载期间释放用 RELEASED 标记，异步加载完成后在 finish() 丢弃
 */
public class PlacementModelLoader {
    /** 摆放 id → DomainModelCache 的 modelId（每个摆放独立；B1 收尾·事项 4 破共享） */
    private static final Map<Integer, Integer> PID_TO_MODEL_ID = new HashMap<>();
    /** 正在异步加载的摆放 key（"pid:<id>"，防并发重复加载） */
    private static final Set<String> LOADING = new HashSet<>();
    /** 加载失败（文件缺失/解析异常）的摆放 key（避免每帧重试） */
    private static final Set<String> FAILED = new HashSet<>();
    /** 已释放但仍在异步加载中的摆放 key（加载完成后丢弃刚烘焙的缓存，不注册映射） */
    private static final Set<String> RELEASED = new HashSet<>();

    private PlacementModelLoader() {
    }

    private static String key(int placementId) {
        return "pid:" + placementId;
    }

    /** 摆放 id → 已烘焙的 modelId（未加载返回 null） */
    public static Integer modelIdOf(String ref, int placementId) {
        if (placementId < 0) return null; // 旧流程/无摆放 id：走 domainId，不走本加载器
        return PID_TO_MODEL_ID.get(placementId);
    }

    /** 摆放 id → modelId（动画精确触发用，B1 收尾·事项 4） */
    public static Integer modelIdByPid(int placementId) {
        return PID_TO_MODEL_ID.get(placementId);
    }

    /** 摆放是否已加载完成 */
    public static boolean isLoaded(int placementId) {
        return PID_TO_MODEL_ID.containsKey(placementId);
    }

    /** 摆放是否已失效（加载失败） */
    public static boolean isFailed(int placementId) {
        return FAILED.contains(key(placementId));
    }

    /**
     * 释放某摆放的全部缓存（B1 收尾·事项 1/4，网络包回调：服务端已删除该摆放）。
     * - 已加载 → 移除映射 + 释放 DomainModelCache 几何/纹理 + 注销 GlbAnimationManager 动画
     * - 加载中 → 标记 RELEASED，异步加载完成后在 finish() 丢弃刚烘焙的缓存
     * - 已失败 → 移除失败标记（允许后续重新 objplace 时重新加载）
     * 幂等：对未加载且未加载中的摆放无操作。
     */
    public static void release(int placementId) {
        if (placementId < 0) return;
        String k = key(placementId);
        FAILED.remove(k);
        Integer modelId = PID_TO_MODEL_ID.remove(placementId);
        if (modelId != null) {
            GlbAnimationManager.remove(modelId);
            DomainModelCache.release(modelId);
            PolarisObjuilder.LOGGER.info(
                    "[Objuilder] 已释放摆放缓存: pid={} → modelId {}（剩余缓存 {}）",
                    placementId, modelId, DomainModelCache.size());
        } else if (LOADING.contains(k)) {
            RELEASED.add(k);
            PolarisObjuilder.LOGGER.info("[Objuilder] 摆放 pid={} 加载中，标记释放（完成后丢弃）", placementId);
        }
    }

    /**
     * 请求懒加载：未加载、未在加载中、未失败 → 启动异步加载。
     * 渲染器每帧调用，幂等（已加载/加载中/失败均直接返回）。
     */
    public static void requestLoad(String ref, int placementId) {
        if (ref == null || ref.isEmpty() || placementId < 0) return;
        String k = key(placementId);
        if (PID_TO_MODEL_ID.containsKey(placementId) || LOADING.contains(k) || FAILED.contains(k)) return;

        File file = resolveRefFile(ref);
        if (file == null || !file.exists()) {
            FAILED.add(k);
            PolarisObjuilder.LOGGER.warn("[Objuilder] 摆放模型引用未找到: {} ({})",
                    ref, file != null ? file.getAbsolutePath() : "无法解析路径");
            return;
        }
        LOADING.add(k);
        String lower = file.getName().toLowerCase();
        if (lower.endsWith(".glb")) {
            loadGlb(ref, placementId, file);
        } else if (lower.endsWith(".obj")) {
            loadObj(ref, placementId, file);
        } else {
            FAILED.add(k);
            LOADING.remove(k);
            PolarisObjuilder.LOGGER.warn("[Objuilder] 不支持的模型格式: {}", ref);
        }
    }

    /** 引用 → 本地文件：绝对路径直接用；相对路径按 config/polarisobjuilder/models/<ref> 解析（ref 缺 models/ 前缀时补齐） */
    private static File resolveRefFile(String ref) {
        File f = new File(ref);
        if (f.isAbsolute()) return f;
        String rel = ref.startsWith("models/") ? ref : "models/" + ref;
        return new File(ObjImportCommand.neoforgePath(), rel);
    }

    // ===================== glb 懒加载 =====================

    private static void loadGlb(String ref, int placementId, File file) {
        CompletableFuture.supplyAsync(() -> {
            try {
                GlbModel model = GlbParser.parse(file);
                GlbModel.FlattenResult flat = model.flatten(1.0f); // 原始尺寸烘焙，scale 由实体应用
                return new GlbJob(model, flat);
            } catch (Exception e) {
                PolarisObjuilder.LOGGER.error("[Objuilder] 摆放 glb 解析失败: {}", file.getAbsolutePath(), e);
                return null;
            }
        }, Util.backgroundExecutor()).thenAcceptAsync(job -> {
            if (job == null) {
                fail(placementId);
                return;
            }
            int modelId = bakeGlb(job.model, job.flat);
            finish(ref, placementId, modelId);
        }, Minecraft.getInstance());
    }

    private record GlbJob(GlbModel model, GlbModel.FlattenResult flat) {
    }

    /** 主线程烘焙 glb 几何/材质到缓存（复用命令层材质转换方法），返回 modelId */
    private static int bakeGlb(GlbModel model, GlbModel.FlattenResult flat) {
        if (flat.triangles().isEmpty()) return -1;
        int modelId = DomainModelCache.nextModelId();
        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                .apply(ResourceLocation.fromNamespaceAndPath("minecraft", "block/white_concrete"));
        List<NativeImage> textures = ObjImportCommand.glbMaterialTextures(model);
        List<NativeImage> normals = ObjImportCommand.glbMaterialNormalImages(model);
        List<NativeImage> speculars = ObjImportCommand.glbMaterialSpecularImages(model);
        ObjImportCommand.fillBaseColorsForGlbPbr(textures, normals, speculars, model);
        Map<Integer, ResourceLocation> texMap = DomainModelCache.registerPbrTextures(textures, normals, speculars);
        DomainModelCache.setMaterialColors(modelId, ObjImportCommand.glbMaterialColors(model));
        DomainModelCache.setMaterialEmissive(modelId, ObjImportCommand.glbMaterialEmissive(model));
        DomainModelCache.setMaterialAlpha(modelId, ObjImportCommand.glbMaterialAlphas(model));
        DomainModelCache.setDoubleSided(modelId, ObjImportCommand.glbDoubleSidedMaterials(model));
        DomainModelCache.setMaterialMasked(modelId, ObjImportCommand.glbMaskedMaterials(model));
        Voxelizer.Domain domain = new Voxelizer.Domain(0, flat.ox(), flat.oy(), flat.oz(),
                new ObjMesh.Vec3(0, 1, 0), flat.triangles());
        DomainModelCache.bake(modelId, List.of(domain), sprite, texMap);
        // 带 node 动画 → 注册（不自动播放：全功能摆放门默认关，几何静止避免每帧重展平噪点；/glbanim 命令才播放）
        GlbAnimationManager.register(modelId, model, 1.0f, sprite, texMap, false);
        // 调试：打印材质解析摘要（emissive/alpha/doubleSided/specular 自发光标志）
        StringBuilder dbg = new StringBuilder();
        for (int i = 0; i < model.materials.size(); i++) {
            GlbModel.Material m = model.materials.get(i);
            NativeImage sp = speculars != null && i < speculars.size() ? speculars.get(i) : null;
            int aFlag = sp != null ? ((sp.getPixelRGBA(0, 0) >> 24) & 0xFF) : -1;
            dbg.append(String.format("  #%d alpha=%s emis=%s ds=%b specA=%d; ",
                    i, m.alphaMode,
                    m.emissiveFactor != null ? java.util.Arrays.toString(m.emissiveFactor) : "null",
                    m.doubleSided, aFlag));
        }
        PolarisObjuilder.LOGGER.info("[Objuilder] glb 材质解析 modelId={}: {}", modelId, dbg);
        return modelId;
    }

    // ===================== OBJ 懒加载 =====================

    private static void loadObj(String ref, int placementId, File file) {
        CompletableFuture.supplyAsync(() -> {
            try {
                ObjMesh mesh = ObjParser.parse(file);
                // 原始尺寸烘焙（不做顶点缩放），scale 由实体应用
                Voxelizer.DomainResult result = Voxelizer.visualize(mesh, null);
                return new ObjJob(mesh, file, result);
            } catch (Exception e) {
                PolarisObjuilder.LOGGER.error("[Objuilder] 摆放 OBJ 解析失败: {}", file.getAbsolutePath(), e);
                return null;
            }
        }, Util.backgroundExecutor()).thenAcceptAsync(job -> {
            if (job == null) {
                fail(placementId);
                return;
            }
            int modelId = bakeObj(job.mesh, job.objFile, job.result);
            finish(ref, placementId, modelId);
        }, Minecraft.getInstance());
    }

    private record ObjJob(ObjMesh mesh, File objFile, Voxelizer.DomainResult result) {
    }

    /** 主线程烘焙 OBJ 几何/材质到缓存（复用命令层材质转换方法），返回 modelId */
    private static int bakeObj(ObjMesh mesh, File objFile, Voxelizer.DomainResult result) {
        if (result.domains().isEmpty()) return -1;
        int modelId = DomainModelCache.nextModelId();
        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                .apply(ResourceLocation.fromNamespaceAndPath("minecraft", "block/white_concrete"));
        List<NativeImage> textures = ObjImportCommand.loadTextures(
                ObjImportCommand.materialTexturePaths(mesh), objFile);
        List<NativeImage> speculars = ObjImportCommand.objMaterialSpecularImages(mesh);
        ObjImportCommand.fillBaseColorsForObjPbr(textures, speculars, mesh);
        Map<Integer, ResourceLocation> texMap = DomainModelCache.registerPbrTextures(textures, null, speculars);
        DomainModelCache.setMaterialColors(modelId, ObjImportCommand.materialColors(mesh));
        DomainModelCache.setMaterialEmissive(modelId, ObjImportCommand.materialEmissive(mesh));
        DomainModelCache.setMaterialAlpha(modelId, ObjImportCommand.materialAlphas(mesh));
        DomainModelCache.setMaterialMasked(modelId, ObjImportCommand.objMaskedMaterials(mesh));
        DomainModelCache.bake(modelId, result.domains(), sprite, texMap);
        return modelId;
    }

    /** 加载完成：写入映射（-1 = 空几何，按失败处理）。若加载期间该摆放已被释放（RELEASED）→ 丢弃刚烘焙的缓存 */
    private static void finish(String ref, int placementId, int modelId) {
        String k = key(placementId);
        if (RELEASED.remove(k)) {
            if (modelId >= 0) {
                GlbAnimationManager.remove(modelId);
                DomainModelCache.release(modelId);
                PolarisObjuilder.LOGGER.info(
                        "[Objuilder] 摆放 pid={} 加载完成但已释放 → 丢弃 modelId {}（剩余缓存 {}）",
                        placementId, modelId, DomainModelCache.size());
            }
            LOADING.remove(k);
            return;
        }
        if (modelId >= 0) {
            PID_TO_MODEL_ID.put(placementId, modelId);
            PolarisObjuilder.LOGGER.info("[Objuilder] 摆放模型已加载: pid={} ({} → modelId {})",
                    placementId, ref, modelId);
        } else {
            FAILED.add(k);
            PolarisObjuilder.LOGGER.warn("[Objuilder] 摆放模型几何为空: pid={} ({})", placementId, ref);
        }
        LOADING.remove(k);
    }

    private static void fail(int placementId) {
        String k = key(placementId);
        FAILED.add(k);
        LOADING.remove(k);
    }
}
