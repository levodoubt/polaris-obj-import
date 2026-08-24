package com.levodoubt.objuilder.command;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.levodoubt.objuilder.PolarisObjuilder;
import com.levodoubt.objuilder.client.DomainModelCache;
import com.levodoubt.objuilder.client.PieceModelCache;
import com.levodoubt.objuilder.core.BakeFormat;
import com.levodoubt.objuilder.core.ObjMesh;
import com.levodoubt.objuilder.core.ObjParser;
import com.levodoubt.objuilder.core.Schematic;
import com.levodoubt.objuilder.core.SphereGenerator;
import com.levodoubt.objuilder.core.Voxelizer;
import com.levodoubt.objuilder.debug.LastImportStats;
import com.levodoubt.objuilder.entity.DomainEntity;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * /objimport <mode> [scale] [file] — 客户端命令（单机验证用）
 *   mode: slice（子片空壳）/ block（子片 + 内部石头）
 *   scale: 可选浮点缩放
 *   file: 可选，读取 config/polarisobjuilder/models/<file> 或绝对路径；缺省内置球体
 *
 * 重负载（切分/烘焙）在后台线程执行，摆放按每 tick 分块 → 不阻塞主线程。
 * /objexport <file> <out> — 离线烘焙：切分结果保存为 .objb
 * /objload <file> <out> — 快速摆放：读取 .objb 直接摆放（不切分）
 */
public class ObjImportCommand {
    /** 每 tick 摆放的最大方块数（分块，避免一次性 setBlock 卡顿） */
    private static final int BLOCKS_PER_TICK = 200;

    public static int run(CommandSourceStack source, String file, boolean fillMode, float scale) {
        ClientLevel clientLevel = Minecraft.getInstance().level;
        var player = Minecraft.getInstance().player;
        if (clientLevel == null || player == null) return 0;

        // 解析输入（轻量，主线程）
        ObjMesh mesh;
        File objFile = null;
        String modelName = "内置球体";
        if (file != null && !file.isBlank()) {
            File f = new File(file);
            if (!f.isAbsolute()) {
                f = new File(new File(neoforgePath(), "models"), file);
            }
            if (!f.exists()) {
                source.sendFailure(Component.literal("找不到模型: " + f.getAbsolutePath()));
                return 0;
            }
            mesh = ObjParser.parse(f);
            objFile = f;
            modelName = f.getName();
        } else {
            mesh = SphereGenerator.sphere(4.5f, 24, 48, 0, 0, 0);
        }
        if (scale != 1.0f) {
            for (int i = 0; i < mesh.vertices.size(); i++) {
                ObjMesh.Vec3 v = mesh.vertices.get(i);
                mesh.vertices.set(i, new ObjMesh.Vec3(v.x() * scale, v.y() * scale, v.z() * scale));
            }
        }
        ObjMesh finalMesh = mesh;
        String finalName = modelName;
        File finalObjFile = objFile;

        source.sendSuccess(() -> Component.literal("§7[Objuilder] 后台切分中..."), false);
        // 后台线程：切分 + 烘焙几何缓存（带进度）
        CompletableFuture.supplyAsync(() -> {
            final int[] lastPct = {0};
            Voxelizer.Result result = Voxelizer.voxelize(finalMesh, 16, (done, total) -> {
                int pct = total > 0 ? done * 100 / total : 0;
                if (pct - lastPct[0] >= 10 || pct == 100) {
                    lastPct[0] = pct;
                    int fp = pct;
                    Minecraft.getInstance().execute(() ->
                            source.sendSuccess(() -> Component.literal(
                                    "§7[Objuilder] 切分中... " + fp + "%"), false));
                }
            });
            return result;
        }, Util.backgroundExecutor()).thenAcceptAsync(result -> {
            if (result == null || result.placements().isEmpty()) {
                source.sendFailure(Component.literal("切分为空：模型未覆盖任何完整格子"));
                return;
            }
            // 烘焙几何（渲染线程）
            TextureAtlasSprite sprite = Minecraft.getInstance()
                    .getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                    .apply(ResourceLocation.fromNamespaceAndPath("minecraft", "block/white_concrete"));
            NativeImage texture = loadTexture(finalMesh, finalObjFile);
            PieceModelCache.bake(result.geometry(), sprite, texture);

            // 摆放（分块，每 tick 摆一部分）
            Level level = authorityWorld(Minecraft.getInstance().level);
            BlockPos base = Minecraft.getInstance().player.blockPosition().offset(7, 1, 7);
            int[] bounds = computeBounds(result);
            clearArea(level, base, bounds);

            Voxelizer.Stats s = result.stats();
            LastImportStats.modelName = (fillMode ? "[子块] " : "[子片] ") + finalName;
            LastImportStats.vertices = s.vertCount();
            LastImportStats.triangles = s.triCount();
            LastImportStats.gridCells = s.gridCells();
            LastImportStats.pieceCount = s.pieceCount();
            LastImportStats.renderTris = s.totalRenderTris();
            LastImportStats.importTimeMs = 0;

            // 分块摆放：表面片 + 内部石头
            placeBatched(level, base, result.placements(), result.interior(), fillMode,
                    () -> source.sendSuccess(() -> Component.literal(String.format(
                            "§a[Objuilder] %s 导入完成 → 顶点 %d · 三角形 %d · 表面格 %d · 模板族 %d · 渲染三角形 %d · 内部石头 %d",
                            fillMode ? "子块" : "子片",
                            s.vertCount(), s.triCount(), s.gridCells(), s.pieceCount(), s.totalRenderTris(),
                            s.interiorCount())), true));
        }, Minecraft.getInstance());
        return 1;
    }

    /** 离线烘焙导出：后台切分 → 保存 .objb */
    public static int export(CommandSourceStack source, String file, String out) {
        File f = new File(file);
        if (!f.isAbsolute()) f = new File(new File(neoforgePath(), "models"), file);
        if (!f.exists()) {
            source.sendFailure(Component.literal("找不到模型: " + f.getAbsolutePath()));
            return 0;
        }
        File outFile = new File(out);
        if (!outFile.isAbsolute()) outFile = new File(neoforgePath(), out);
        final File inFile = f;
        final File targetFile = outFile;

        source.sendSuccess(() -> Component.literal("§7[Objuilder] 后台烘焙中..."), false);
        CompletableFuture.supplyAsync(() -> {
            ObjMesh mesh = ObjParser.parse(inFile);
            Voxelizer.Result result = Voxelizer.voxelize(mesh, 16);
            try {
                BakeFormat.save(targetFile, result.geometry(), result.placements(), result.interior(),
                        result.stats(), mesh.texturePath);
                return result.stats();
            } catch (Exception e) {
                PolarisObjuilder.LOGGER.error("[Objuilder] 烘焙导出失败", e);
                return null;
            }
        }, Util.backgroundExecutor()).thenAcceptAsync(stats -> {
            if (stats == null) {
                source.sendFailure(Component.literal("烘焙导出失败，详见日志"));
            } else {
                source.sendSuccess(() -> Component.literal(String.format(
                        "§a[Objuilder] 烘焙完成 → %s (表面格 %d · 模板族 %d · 渲染三角形 %d)",
                        targetFile.getAbsolutePath(), stats.gridCells(), stats.pieceCount(), stats.totalRenderTris())), true);
            }
        }, Minecraft.getInstance());
        return 1;
    }

    /** schematic 导出：后台切分 → 保存 .schem（Sponge v2，结构方块/WorldEdit 兼容） */
    public static int exportSchem(CommandSourceStack source, String file, String out) {
        File f = new File(file);
        if (!f.isAbsolute()) f = new File(new File(neoforgePath(), "models"), file);
        if (!f.exists()) {
            source.sendFailure(Component.literal("找不到模型: " + f.getAbsolutePath()));
            return 0;
        }
        File outFile = new File(out);
        if (!outFile.isAbsolute()) outFile = new File(neoforgePath(), out);
        final File inFile = f;
        final File targetFile = outFile;

        source.sendSuccess(() -> Component.literal("§7[Objuilder] 后台生成 schematic..."), false);
        CompletableFuture.supplyAsync(() -> {
            ObjMesh mesh = ObjParser.parse(inFile);
            // 进度回调：后台线程 → 主线程 → 聊天栏（每 5% 报一次）
            final int[] lastPct = {0};
            Voxelizer.Result result = Voxelizer.voxelize(mesh, 16, (done, total) -> {
                int pct = total > 0 ? done * 100 / total : 0;
                if (pct - lastPct[0] >= 5 || pct == 100) {
                    lastPct[0] = pct;
                    int fp = pct;
                    Minecraft.getInstance().execute(() ->
                            source.sendSuccess(() -> Component.literal(
                                    "§7[Objuilder] 切分中... " + fp + "% (" + done + "/" + total + " 面)"), false));
                }
            });
            try {
                Schematic.export(targetFile, result.geometry(), result.placements(),
                        result.interior(), result.stats(), mesh.texturePath, pct -> {
                    // 写文件阶段进度（后台线程 → 主线程 → 聊天栏）
                    if (pct == 100 || pct % 10 == 0) {
                        int fp = pct;
                        Minecraft.getInstance().execute(() ->
                                source.sendSuccess(() -> Component.literal(
                                        "§7[Objuilder] 写文件... " + fp + "%"), false));
                    }
                });
                return result.stats();
            } catch (Exception e) {
                PolarisObjuilder.LOGGER.error("[Objuilder] schematic 导出失败", e);
                return null;
            }
        }, Util.backgroundExecutor()).thenAcceptAsync(stats -> {
            if (stats == null) {
                source.sendFailure(Component.literal("schematic 导出失败，详见日志"));
            } else {
                source.sendSuccess(() -> Component.literal(String.format(
                        "§a[Objuilder] schematic 完成 → %s (表面格 %d · 内部石头 %d)",
                        targetFile.getAbsolutePath(), stats.gridCells(), stats.interiorCount())), true);
            }
        }, Minecraft.getInstance());
        return 1;
    }

    /** schematic 导入：读取 .schem → 分块放置 */
    public static int loadSchem(CommandSourceStack source, String file) {
        File f = new File(file);
        if (!f.isAbsolute()) f = new File(neoforgePath(), file);
        if (!f.exists()) {
            source.sendFailure(Component.literal("找不到 schematic 文件: " + f.getAbsolutePath()));
            return 0;
        }
        final File inFile = f;
        source.sendSuccess(() -> Component.literal("§7[Objuilder] 读取 schematic..."), false);
        CompletableFuture.supplyAsync(() -> {
            try {
                return Schematic.load(inFile);
            } catch (Exception e) {
                PolarisObjuilder.LOGGER.error("[Objuilder] 读取 schematic 失败", e);
                return null;
            }
        }, Util.backgroundExecutor()).thenAcceptAsync(data -> {
            if (data == null) {
                source.sendFailure(Component.literal("读取 schematic 失败，详见日志"));
                return;
            }
            Level level = authorityWorld(Minecraft.getInstance().level);
            BlockPos base = Minecraft.getInstance().player.blockPosition().offset(3, 0, 3);
            int w = data.width(), h = data.height(), d = data.length();

            // 烘焙几何 + 贴图到缓存（若 schematic 携带几何 → 与 /objimport 一致效果）
            if (data.geometry() != null && !data.geometry().isEmpty()) {
                TextureAtlasSprite sprite = Minecraft.getInstance()
                        .getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                        .apply(ResourceLocation.fromNamespaceAndPath("minecraft", "block/white_concrete"));
                NativeImage texture = loadTexturePath(data.texturePath());
                PieceModelCache.bake(data.geometry(), sprite, texture);
                PolarisObjuilder.LOGGER.info("[Objuilder] schematic 几何已烘焙: {} 族",
                        data.geometry().size());
            } else {
                PolarisObjuilder.LOGGER.warn("[Objuilder] schematic 无几何数据，子片显示为占位");
            }

            // 诊断日志：打印 sparse 坐标范围 + pieceId 范围
            PolarisObjuilder.LOGGER.info("[Objuilder] schematic 尺寸 {}x{}x{} · 稀疏 {} 条 · 几何 {} 族",
                    data.width(), data.height(), data.length(), data.sparsePieceId().length,
                    data.geometry() != null ? data.geometry().size() : 0);
            int[] mmx = {Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE};
            int pmn = Integer.MAX_VALUE, pmx = Integer.MIN_VALUE;
            for (int i = 0; i < data.sparsePieceId().length; i++) {
                mmx[0] = Math.min(mmx[0], data.sparseX()[i]); mmx[1] = Math.max(mmx[1], data.sparseX()[i]);
                mmx[2] = Math.min(mmx[2], data.sparseY()[i]); mmx[3] = Math.max(mmx[3], data.sparseY()[i]);
                mmx[4] = Math.min(mmx[4], data.sparseZ()[i]); mmx[5] = Math.max(mmx[5], data.sparseZ()[i]);
                pmn = Math.min(pmn, data.sparsePieceId()[i]); pmx = Math.max(pmx, data.sparsePieceId()[i]);
            }
            PolarisObjuilder.LOGGER.info("[Objuilder] sparse 坐标范围 X[{}..{}] Y[{}..{}] Z[{}..{}] · pieceId[{}..{}]",
                    mmx[0], mmx[1], mmx[2], mmx[3], mmx[4], mmx[5], pmn, pmx);

            // 分块放置（稀疏：pieceId >= 0 → obj_piece+BE，-1 → 石头）
            Minecraft mc = Minecraft.getInstance();
            var idx = new java.util.concurrent.atomic.AtomicInteger(0);
            int total = data.sparsePieceId().length;
            mc.execute(() -> placeSchemBatch(level, base, data, idx, total, source, mc));
        }, Minecraft.getInstance());
        return 1;
    }

    private static void placeSchemBatch(Level level, BlockPos base, Schematic.SchematicData data,
                                        java.util.concurrent.atomic.AtomicInteger idx, int total,
                                        CommandSourceStack source, Minecraft mc) {
        int placed = 0;
        while (idx.get() < total && placed < BLOCKS_PER_TICK) {
            int i = idx.getAndIncrement();
            int x = data.sparseX()[i];
            int y = data.sparseY()[i];
            int z = data.sparseZ()[i];
            int pieceId = data.sparsePieceId()[i];
            BlockPos pos = base.offset(x, y, z);
            if (pieceId == Schematic.STONE_MARKER) {
                level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
            } else {
                level.setBlock(pos, PolarisObjuilder.OBJ_PIECE.get().defaultBlockState(), 3);
                if (level.getBlockEntity(pos) instanceof com.levodoubt.objuilder.block.ObjPieceBlockEntity be) {
                    be.setPieceId(pieceId);
                }
            }
            placed++;
        }
        if (idx.get() < total) {
            mc.execute(() -> placeSchemBatch(level, base, data, idx, total, source, mc));
        } else {
            source.sendSuccess(() -> Component.literal(
                    "§a[Objuilder] schematic 放置完成 → " + total + " 格"), true);
        }
    }

    /** 快速摆放：读取 .objb → 分块摆放（不切分） */
    public static int load(CommandSourceStack source, String file) {
        File f = new File(file);
        if (!f.isAbsolute()) f = new File(neoforgePath(), file);
        if (!f.exists()) {
            source.sendFailure(Component.literal("找不到烘焙文件: " + f.getAbsolutePath()));
            return 0;
        }
        final File inFile = f;
        source.sendSuccess(() -> Component.literal("§7[Objuilder] 读取烘焙文件中..."), false);
        CompletableFuture.supplyAsync(() -> {
            try {
                return BakeFormat.load(inFile);
            } catch (Exception e) {
                PolarisObjuilder.LOGGER.error("[Objuilder] 读取烘焙文件失败", e);
                return null;
            }
        }, Util.backgroundExecutor()).thenAcceptAsync(baked -> {
            if (baked == null) {
                source.sendFailure(Component.literal("读取烘焙文件失败，详见日志"));
                return;
            }
            // 烘焙几何到缓存（渲染线程）
            TextureAtlasSprite sprite = Minecraft.getInstance()
                    .getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                    .apply(ResourceLocation.fromNamespaceAndPath("minecraft", "block/white_concrete"));
            NativeImage texture = loadTexturePath(baked.texturePath());
            PieceModelCache.bake(baked.geometry(), sprite, texture);

            Level level = authorityWorld(Minecraft.getInstance().level);
            BlockPos base = Minecraft.getInstance().player.blockPosition().offset(7, 1, 7);
            int[] bounds = computeBoundsFromPlacements(baked.placements());
            clearArea(level, base, bounds);

            placeBatched(level, base, baked.placements(), baked.interior(), true,
                    () -> source.sendSuccess(() -> Component.literal(String.format(
                            "§a[Objuilder] 摆放完成 → 表面格 %d · 模板族 %d · 渲染三角形 %d",
                            baked.stats().gridCells(), baked.stats().pieceCount(), baked.stats().totalRenderTris())), true));
        }, Minecraft.getInstance());
        return 1;
    }

    /** 分块摆放：每 tick 摆 BLOCKS_PER_TICK 个，避免主线程卡顿 */
    private static void placeBatched(Level level, BlockPos base,
                                     List<Voxelizer.Placement> placements, List<int[]> interior,
                                     boolean fillMode, Runnable done) {
        Minecraft mc = Minecraft.getInstance();
        var it = placements.iterator();
        var intIt = fillMode ? interior.iterator() : null;
        java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean(false);
        mc.execute(() -> {
            // 先摆表面片，再摆石头
            int placed = 0;
            while (it.hasNext() && placed < BLOCKS_PER_TICK) {
                Voxelizer.Placement p = it.next();
                BlockPos pos = base.offset(p.x(), p.y(), p.z());
                level.setBlock(pos, PolarisObjuilder.OBJ_PIECE.get().defaultBlockState(), 3);
                if (level.getBlockEntity(pos) instanceof com.levodoubt.objuilder.block.ObjPieceBlockEntity be) {
                    be.setPieceId(p.pieceId());
                }
                placed++;
            }
            if (!it.hasNext() && intIt != null) {
                // 表面片摆完，摆石头
                int stonePlaced = 0;
                while (intIt.hasNext() && stonePlaced < BLOCKS_PER_TICK) {
                    int[] c = intIt.next();
                    level.setBlock(base.offset(c[0], c[1], c[2]), Blocks.STONE.defaultBlockState(), 3);
                    stonePlaced++;
                }
            }
            if (it.hasNext() || (intIt != null && intIt.hasNext())) {
                // 还有剩余 → 下一 tick 继续
                mc.execute(() -> placeBatched(level, base, placements, interior, fillMode, done));
            } else if (finished.compareAndSet(false, true)) {
                done.run();
            }
        });
    }

    private static int[] computeBoundsFromPlacements(List<Voxelizer.Placement> placements) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Voxelizer.Placement p : placements) {
            minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x());
            minY = Math.min(minY, p.y()); maxY = Math.max(maxY, p.y());
            minZ = Math.min(minZ, p.z()); maxZ = Math.max(maxZ, p.z());
        }
        return new int[]{minX, maxX, minY, maxY, minZ, maxZ};
    }

    private static int[] computeBounds(Voxelizer.Result result) {
        return computeBoundsFromPlacements(result.placements());
    }

    private static String neoforgePath() {
        return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                .resolve("polarisobjuilder").toString();
    }

    /** 加载模型 map_Kd 贴图：绝对路径直接用；相对路径解析到 OBJ 目录。png/jpg 均支持。 */
    private static NativeImage loadTexture(ObjMesh mesh, File objFile) {
        if (mesh.texturePath == null || objFile == null) {
            return null;
        }
        File texFile = new File(mesh.texturePath);
        if (!texFile.isAbsolute()) {
            texFile = new File(objFile.getParentFile(), mesh.texturePath);
        }
        return loadTexturePath(texFile.getAbsolutePath());
    }

    /** 提取模型各材质的贴图路径（索引 = materialId，无贴图 = null）。单材质模型回退 texturePath。 */
    private static List<String> materialTexturePaths(ObjMesh mesh) {
        List<String> paths = new ArrayList<>();
        if (mesh.materials.isEmpty()) {
            if (mesh.texturePath != null) paths.add(mesh.texturePath);
            return paths;
        }
        for (ObjMesh.Material m : mesh.materials) {
            paths.add(m.mapKd);
        }
        return paths;
    }

    /** 提取模型各材质的漫反射颜色（索引 = materialId，[r,g,b]）。单材质/无材质 → 空列表。 */
    private static List<float[]> materialColors(ObjMesh mesh) {
        List<float[]> colors = new ArrayList<>();
        for (ObjMesh.Material m : mesh.materials) {
            colors.add(new float[]{m.kdR, m.kdG, m.kdB});
        }
        return colors;
    }

    /** 提取模型各材质的自发光颜色（索引 = materialId，[r,g,b]）。 */
    private static List<float[]> materialEmissive(ObjMesh mesh) {
        List<float[]> emissive = new ArrayList<>();
        for (ObjMesh.Material m : mesh.materials) {
            emissive.add(new float[]{m.keR, m.keG, m.keB});
        }
        return emissive;
    }

    /** 批量加载多材质贴图（索引 = materialId，无贴图 = null）。objFile 用于解析相对路径与失效绝对路径回退。 */
    private static List<NativeImage> loadTextures(List<String> paths, File objFile) {
        List<NativeImage> images = new ArrayList<>();
        if (paths == null) return images;
        for (String path : paths) {
            if (path == null || path.isBlank()) {
                images.add(null);
                continue;
            }
            images.add(loadTextureResolved(path, objFile));
        }
        return images;
    }

    /**
     * 解析贴图路径并加载，依次尝试：
     * 1. 原路径（绝对或相对）
     * 2. 相对 OBJ 目录（objFile 父目录 + path）
     * 3. OBJ 目录下按文件名（处理 MTL 中失效的绝对路径，如 C:/1.png → <obj目录>/1.png）
     * 4. OBJ 目录/Textures 下按文件名（Blender 常见贴图子目录）
     */
    private static NativeImage loadTextureResolved(String path, File objFile) {
        File p = new File(path);
        List<File> candidates = new ArrayList<>();
        candidates.add(p);
        if (objFile != null) {
            File parent = objFile.getParentFile();
            candidates.add(new File(parent, path));
            candidates.add(new File(parent, p.getName()));
            candidates.add(new File(new File(parent, "Textures"), p.getName()));
        }
        for (File c : candidates) {
            if (!c.exists()) continue;
            NativeImage img = loadTexturePath(c.getAbsolutePath());
            if (img != null) return img;
        }
        return null;
    }

    private static NativeImage loadTexturePath(String path) {
        if (path == null || path.isBlank()) return null;
        File texFile = new File(path);
        if (!texFile.exists()) {
            PolarisObjuilder.LOGGER.warn("[Objuilder] 贴图文件不存在: {}", texFile.getAbsolutePath());
            return null;
        }
        try {
            String lower = texFile.getName().toLowerCase();
            if (lower.endsWith(".png")) {
                try (InputStream in = new FileInputStream(texFile)) {
                    NativeImage img = NativeImage.read(in);
                    logLoaded(texFile, img);
                    return img;
                }
            } else {
                java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(texFile);
                if (bi == null) {
                    PolarisObjuilder.LOGGER.warn("[Objuilder] 贴图解码失败: {}", texFile.getAbsolutePath());
                    return null;
                }
                NativeImage img = new NativeImage(bi.getWidth(), bi.getHeight(), false);
                for (int y = 0; y < bi.getHeight(); y++) {
                    for (int x = 0; x < bi.getWidth(); x++) {
                        int rgb = bi.getRGB(x, y);
                        int a = (rgb >> 24) & 0xFF;
                        int r = (rgb >> 16) & 0xFF;
                        int g = (rgb >> 8) & 0xFF;
                        int b = rgb & 0xFF;
                        img.setPixelRGBA(x, y, 0xFF000000 | (b << 16) | (g << 8) | r);
                    }
                }
                logLoaded(texFile, img);
                return img;
            }
        } catch (Exception e) {
            PolarisObjuilder.LOGGER.warn("[Objuilder] 贴图加载失败: {}", texFile.getAbsolutePath(), e);
            return null;
        }
    }

    private static void logLoaded(File texFile, NativeImage img) {
        PolarisObjuilder.LOGGER.info("[Objuilder] 贴图加载成功: {} ({}x{})",
                texFile.getName(), img.getWidth(), img.getHeight());
    }

    /** 单机集成服务器下，客户端摆放必须走服务端 Level（setBlock 自动同步客户端）；否则仅客户端可见，服务端挖不到 */
    private static Level authorityWorld(ClientLevel clientLevel) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null) {
            Level lvl = server.getLevel(clientLevel.dimension());
            if (lvl != null) return lvl;
        }
        return clientLevel;
    }

    /** 按模型 AABB（膨胀 2 格）区域清理子片与石头——重启后也能清掉残留 */
    private static void clearArea(Level level, BlockPos base, int[] b) {
        int pad = 2;
        for (int x = b[0] - pad; x <= b[1] + pad; x++) {
            for (int y = b[2] - pad; y <= b[3] + pad; y++) {
                for (int z = b[4] - pad; z <= b[5] + pad; z++) {
                    BlockPos pos = base.offset(x, y, z);
                    var block = level.getBlockState(pos).getBlock();
                    if (block == PolarisObjuilder.OBJ_PIECE.get() || block == Blocks.STONE) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }
    }

    // ===================== 共面域（纯视觉模式） =====================

    /**
     * /objdomain [scale] [file] — 共面域纯视觉导入：
     * 切分 → 共面域合并 → 域实体摆放（无碰撞，平滑法线，光影兼容）。
     * 解决"一格一几何"渲染量爆炸（大平面 1.5 亿 → 几十万三角形）。
     */
    public static int domain(CommandSourceStack source, String file, float scale) {
        ClientLevel clientLevel = Minecraft.getInstance().level;
        var player = Minecraft.getInstance().player;
        if (clientLevel == null || player == null) return 0;

        ObjMesh mesh;
        File objFile = null;
        String modelName = "内置球体";
        if (file != null && !file.isBlank()) {
            File f = new File(file);
            if (!f.isAbsolute()) {
                f = new File(new File(neoforgePath(), "models"), file);
            }
            if (!f.exists()) {
                source.sendFailure(Component.literal("找不到模型: " + f.getAbsolutePath()));
                return 0;
            }
            mesh = ObjParser.parse(f);
            objFile = f;
            modelName = f.getName();
        } else {
            mesh = SphereGenerator.sphere(4.5f, 24, 48, 0, 0, 0);
        }
        if (scale != 1.0f) {
            for (int i = 0; i < mesh.vertices.size(); i++) {
                ObjMesh.Vec3 v = mesh.vertices.get(i);
                mesh.vertices.set(i, new ObjMesh.Vec3(v.x() * scale, v.y() * scale, v.z() * scale));
            }
        }
        ObjMesh finalMesh = mesh;
        String finalName = modelName;
        File finalObjFile = objFile;

        source.sendSuccess(() -> Component.literal("§7[Objuilder] 后台共面域切分中..."), false);
        CompletableFuture.supplyAsync(() -> {
            final int[] lastPct = {0};
            Voxelizer.DomainResult result = Voxelizer.visualize(finalMesh, (done, total) -> {
                int pct = total > 0 ? done * 100 / total : 0;
                if (pct - lastPct[0] >= 10 || pct == 100) {
                    lastPct[0] = pct;
                    int fp = pct;
                    Minecraft.getInstance().execute(() ->
                            source.sendSuccess(() -> Component.literal(
                                    "§7[Objuilder] 切分中... " + fp + "%"), false));
                }
            });
            return result;
        }, Util.backgroundExecutor()).thenAcceptAsync(result -> {
            if (result == null || result.domains().isEmpty()) {
                source.sendFailure(Component.literal("切分为空：模型未覆盖任何共面表面"));
                return;
            }
            // 烘焙域几何到缓存：注册贴图为动态纹理（逐像素贴图），几何与贴图分离（多材质）
            int modelId = DomainModelCache.nextModelId();
            TextureAtlasSprite sprite = Minecraft.getInstance()
                    .getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                    .apply(ResourceLocation.fromNamespaceAndPath("minecraft", "block/white_concrete"));
            List<NativeImage> textures = loadTextures(materialTexturePaths(finalMesh), finalObjFile);
            Map<Integer, ResourceLocation> texMap = DomainModelCache.registerTextures(textures);
            DomainModelCache.setMaterialColors(modelId, materialColors(finalMesh));
            DomainModelCache.setMaterialEmissive(modelId, materialEmissive(finalMesh));
            DomainModelCache.bake(modelId, result.domains(), sprite, texMap);

            // 摆放域实体（多模型共存，独立缓存，不清除旧实体）
            Level level = authorityWorld(Minecraft.getInstance().level);
            BlockPos base = Minecraft.getInstance().player.blockPosition().offset(7, 1, 7);
            spawnDomains(level, base, modelId, result.domains());
            spawnLightCells(level, base, result.lightCells());

            LastImportStats.modelName = "[纯视觉] " + finalName;
            LastImportStats.gridCells = result.faceCount();
            LastImportStats.pieceCount = result.domains().size();
            LastImportStats.renderTris = result.totalTris();
            LastImportStats.importTimeMs = 0;

            source.sendSuccess(() -> Component.literal(String.format(
                    "§a[Objuilder] 纯视觉导入完成 → %s · 三角形 %d（1 实体整体渲染）",
                    finalName, result.totalTris())), true);
        }, Minecraft.getInstance());
        return 1;
    }

    /** 共面域离线导出：后台切分+合并 → 保存 .objb（POBJD） */
    public static int domainExport(CommandSourceStack source, String file, String out) {
        File f = new File(file);
        if (!f.isAbsolute()) f = new File(new File(neoforgePath(), "models"), file);
        if (!f.exists()) {
            source.sendFailure(Component.literal("找不到模型: " + f.getAbsolutePath()));
            return 0;
        }
        File outFile = new File(out);
        if (!outFile.isAbsolute()) outFile = new File(neoforgePath(), out);
        final File inFile = f;
        final File targetFile = outFile;

        source.sendSuccess(() -> Component.literal("§7[Objuilder] 后台共面域烘焙中..."), false);
        CompletableFuture.supplyAsync(() -> {
            ObjMesh mesh = ObjParser.parse(inFile);
            Voxelizer.DomainResult result = Voxelizer.visualize(mesh, null);
            try {
                BakeFormat.saveDomains(targetFile, result, materialTexturePaths(mesh),
                        materialColors(mesh), materialEmissive(mesh));
                return result;
            } catch (Exception e) {
                PolarisObjuilder.LOGGER.error("[Objuilder] 共面域烘焙导出失败", e);
                return null;
            }
        }, Util.backgroundExecutor()).thenAcceptAsync(result -> {
            if (result == null) {
                source.sendFailure(Component.literal("共面域烘焙导出失败，详见日志"));
            } else {
                source.sendSuccess(() -> Component.literal(String.format(
                        "§a[Objuilder] 纯视觉烘焙完成 → %s (三角形 %d)",
                        targetFile.getAbsolutePath(), result.totalTris())), true);
            }
        }, Minecraft.getInstance());
        return 1;
    }

    /** 共面域加载：读取 POBJD → 域实体摆放 */
    public static int domainLoad(CommandSourceStack source, String file) {
        File f = new File(file);
        if (!f.isAbsolute()) f = new File(neoforgePath(), file);
        if (!f.exists()) {
            source.sendFailure(Component.literal("找不到烘焙文件: " + f.getAbsolutePath()));
            return 0;
        }
        final File inFile = f;
        source.sendSuccess(() -> Component.literal("§7[Objuilder] 读取共面域烘焙文件中..."), false);
        CompletableFuture.supplyAsync(() -> {
            try {
                return BakeFormat.loadDomains(inFile);
            } catch (Exception e) {
                PolarisObjuilder.LOGGER.error("[Objuilder] 读取共面域烘焙文件失败", e);
                return null;
            }
        }, Util.backgroundExecutor()).thenAcceptAsync(baked -> {
            if (baked == null) {
                source.sendFailure(Component.literal("读取共面域烘焙文件失败，详见日志"));
                return;
            }
            int modelId = DomainModelCache.nextModelId();
            TextureAtlasSprite sprite = Minecraft.getInstance()
                    .getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                    .apply(ResourceLocation.fromNamespaceAndPath("minecraft", "block/white_concrete"));
            List<NativeImage> textures = loadTextures(baked.texturePaths(), null);
            Map<Integer, ResourceLocation> texMap = DomainModelCache.registerTextures(textures);
            DomainModelCache.setMaterialColors(modelId, baked.materialColors());
            DomainModelCache.setMaterialEmissive(modelId, baked.materialEmissive());
            DomainModelCache.bake(modelId, baked.domains(), sprite, texMap);

            Level level = authorityWorld(Minecraft.getInstance().level);
            BlockPos base = Minecraft.getInstance().player.blockPosition().offset(7, 1, 7);
            spawnDomains(level, base, modelId, baked.domains());
            spawnLightCells(level, base, baked.stats().lightCells());

            Voxelizer.DomainResult s = baked.stats();
            LastImportStats.modelName = "[纯视觉] " + inFile.getName();
            LastImportStats.gridCells = s.faceCount();
            LastImportStats.pieceCount = s.domains().size();
            LastImportStats.renderTris = s.totalTris();
            source.sendSuccess(() -> Component.literal(String.format(
                    "§a[Objuilder] 纯视觉摆放完成 → 三角形 %d（1 实体）",
                    s.totalTris())), true);
        }, Minecraft.getInstance());
        return 1;
    }

    /** 摆放域实体：实体位置 = 基准点 + 域原点，域几何为域局部坐标。所有实体共享同一 modelId（独立缓存） */
    private static void spawnDomains(Level level, BlockPos base, int modelId, List<Voxelizer.Domain> domains) {
        double bx = base.getX(), by = base.getY(), bz = base.getZ();
        for (Voxelizer.Domain d : domains) {
            DomainEntity e = new DomainEntity(PolarisObjuilder.DOMAIN_ENTITY.get(), level);
            e.setPos(bx + d.ox(), by + d.oy(), bz + d.oz());
            e.setDomainId(modelId);
            level.addFreshEntity(e);
        }
    }

    /** 摆放光源方块：在自发光格放置 minecraft:light（level=光照等级，跟随 Ke 强度） */
    private static void spawnLightCells(Level level, BlockPos base, List<Voxelizer.LightCell> lightCells) {
        if (lightCells == null || lightCells.isEmpty()) return;
        BlockState lightState = Blocks.LIGHT.defaultBlockState();
        for (Voxelizer.LightCell lc : lightCells) {
            BlockPos pos = base.offset(lc.x(), lc.y(), lc.z());
            level.setBlock(pos, lightState.setValue(LightBlock.LEVEL, lc.level()), 3);
        }
    }
}
