package com.levodoubt.objuilder.command;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.levodoubt.objuilder.PolarisObjuilder;
import com.levodoubt.objuilder.client.DomainModelCache;
import com.levodoubt.objuilder.client.GlbAnimationManager;
import com.levodoubt.objuilder.client.PieceModelCache;
import com.levodoubt.objuilder.core.BakeFormat;
import com.levodoubt.objuilder.core.GlbModel;
import com.levodoubt.objuilder.core.GlbParser;
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

    public static String neoforgePath() {
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
    public static List<String> materialTexturePaths(ObjMesh mesh) {
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
    public static List<float[]> materialColors(ObjMesh mesh) {
        List<float[]> colors = new ArrayList<>();
        for (ObjMesh.Material m : mesh.materials) {
            colors.add(new float[]{m.kdR, m.kdG, m.kdB});
        }
        return colors;
    }

    /** 提取模型各材质的自发光颜色（索引 = materialId，[r,g,b]）。 */
    public static List<float[]> materialEmissive(ObjMesh mesh) {
        List<float[]> emissive = new ArrayList<>();
        for (ObjMesh.Material m : mesh.materials) {
            emissive.add(new float[]{m.keR, m.keG, m.keB});
        }
        return emissive;
    }

    /** 提取模型各材质的透明度（索引 = materialId；OBJ MTL d，1=不透明，<1=半透明）。 */
    public static List<Float> materialAlphas(ObjMesh mesh) {
        List<Float> alphas = new ArrayList<>();
        for (ObjMesh.Material m : mesh.materials) {
            alphas.add(m.d);
        }
        return alphas;
    }

    /** OBJ 材质 → MASK 镂空的 materialId 集合（MTL map_d alpha 贴图 → alpha-test cutout 渲染，B1 收尾·事项 5） */
    public static Set<Integer> objMaskedMaterials(ObjMesh mesh) {
        Set<Integer> s = new HashSet<>();
        for (int i = 0; i < mesh.materials.size(); i++) {
            if (mesh.materials.get(i).mapD != null) s.add(i);
        }
        return s;
    }

    /** 批量加载多材质贴图（索引 = materialId，无贴图 = null）。objFile 用于解析相对路径与失效绝对路径回退。 */
    public static List<NativeImage> loadTextures(List<String> paths, File objFile) {
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
            List<NativeImage> speculars = objMaterialSpecularImages(finalMesh);
            fillBaseColorsForObjPbr(textures, speculars, finalMesh);
            Map<Integer, ResourceLocation> texMap = DomainModelCache.registerPbrTextures(textures, null, speculars);
            DomainModelCache.setMaterialColors(modelId, materialColors(finalMesh));
            DomainModelCache.setMaterialEmissive(modelId, materialEmissive(finalMesh));
            DomainModelCache.setMaterialAlpha(modelId, materialAlphas(finalMesh));
            DomainModelCache.setMaterialMasked(modelId, objMaskedMaterials(finalMesh));
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

    // ===================== glb（glTF 2.0）静态导入 =====================

    /**
     * /glbdomain [scale] [file] — glb 静态导入：
     * 解析 node 树 + mesh + 材质 → 展平为单实体整体几何 → 复用纯视觉渲染管线。
     * 与 OBJ 的 /objdomain 并存（modelId 独立缓存，互不干扰）。
     */
    public static int glbDomain(CommandSourceStack source, String file, float scale) {
        ClientLevel clientLevel = Minecraft.getInstance().level;
        var player = Minecraft.getInstance().player;
        if (clientLevel == null || player == null) return 0;
        if (file == null || file.isBlank()) {
            source.sendFailure(Component.literal("请指定 glb 文件路径（无内置模型）"));
            return 0;
        }

        File f = new File(file);
        if (!f.isAbsolute()) {
            f = new File(new File(neoforgePath(), "models"), file);
        }
        if (!f.exists()) {
            source.sendFailure(Component.literal("找不到模型: " + f.getAbsolutePath()));
            return 0;
        }
        final File inFile = f;
        final float finalScale = scale;

        source.sendSuccess(() -> Component.literal("§7[Objuilder] 后台解析 glb 中..."), false);
        CompletableFuture.supplyAsync(() -> {
            try {
                GlbModel model = GlbParser.parse(inFile);
                GlbModel.FlattenResult flat = model.flatten(finalScale);
                logGlbInfo(model, flat); // 成功标准 1：命令行打印 node 层级/顶点三角形数/材质列表
                return new GlbJob(model, flat);
            } catch (Exception e) {
                PolarisObjuilder.LOGGER.error("[Glb] 解析失败: {}", inFile.getAbsolutePath(), e);
                return null;
            }
        }, Util.backgroundExecutor()).thenAcceptAsync(job -> {
            if (job == null) {
                source.sendFailure(Component.literal("glb 解析失败，详见日志"));
                return;
            }
            GlbModel model = job.model;
            GlbModel.FlattenResult flat = job.flat;
            if (flat.triangles().isEmpty()) {
                source.sendFailure(Component.literal("glb 展平为空：模型无三角网格"));
                return;
            }
            // 烘焙几何 + 材质（多材质：baseColorFactor 颜色 / baseColorTexture 动态纹理）
            int modelId = DomainModelCache.nextModelId();
            TextureAtlasSprite sprite = Minecraft.getInstance()
                    .getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                    .apply(ResourceLocation.fromNamespaceAndPath("minecraft", "block/white_concrete"));
            List<NativeImage> textures = glbMaterialTextures(model);
            List<NativeImage> normals = glbMaterialNormalImages(model);
            List<NativeImage> speculars = glbMaterialSpecularImages(model);
            fillBaseColorsForGlbPbr(textures, normals, speculars, model);
            Map<Integer, ResourceLocation> texMap = DomainModelCache.registerPbrTextures(textures, normals, speculars);
            DomainModelCache.setMaterialColors(modelId, glbMaterialColors(model));
            DomainModelCache.setMaterialEmissive(modelId, glbMaterialEmissive(model));
            DomainModelCache.setMaterialAlpha(modelId, glbMaterialAlphas(model));
            DomainModelCache.setDoubleSided(modelId, glbDoubleSidedMaterials(model));
            DomainModelCache.setMaterialMasked(modelId, glbMaskedMaterials(model));
            Voxelizer.Domain domain = new Voxelizer.Domain(0, flat.ox(), flat.oy(), flat.oz(),
                    new ObjMesh.Vec3(0, 1, 0), flat.triangles());
            DomainModelCache.bake(modelId, List.of(domain), sprite, texMap);

            // 摆放实体（实体位置 = 基准点 + 模型中心格；与 OBJ 并存，不清除旧实体）
            Level level = authorityWorld(Minecraft.getInstance().level);
            BlockPos base = Minecraft.getInstance().player.blockPosition().offset(7, 1, 7);
            spawnDomains(level, base, modelId, List.of(domain));

            // 动画：模型带 node TRS 动画 → 注册循环播放（方案 A：每 tick 重展平更新缓存）
            boolean animated = GlbAnimationManager.register(modelId, model, finalScale, sprite, texMap);

            LastImportStats.modelName = "[glb] " + inFile.getName();
            LastImportStats.gridCells = flat.triangles().size();
            LastImportStats.pieceCount = 1;
            LastImportStats.renderTris = flat.triangles().size();
            LastImportStats.importTimeMs = 0;

            source.sendSuccess(() -> Component.literal(String.format(
                    "§a[Objuilder] glb 导入完成 → %s · 三角形 %d（1 实体整体渲染，材质 %d%s）",
                    inFile.getName(), flat.triangles().size(), model.materials.size(),
                    animated ? " · §b循环动画已启动" : "")), true);
        }, Minecraft.getInstance());
        return 1;
    }

    /** 后台 glb 任务结果（model + 展平几何） */
    private record GlbJob(GlbModel model, GlbModel.FlattenResult flat) {
    }

    /** 成功标准 1：打印 node 层级 / mesh 顶点三角形数 / 材质列表 / 展平统计 */
    private static void logGlbInfo(GlbModel model, GlbModel.FlattenResult flat) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n===== Glb 解析信息: ").append(model.name).append(" =====");
        sb.append("\n[场景] 根节点 ").append(model.sceneRoots.size()).append(" 个: ")
                .append(Arrays.toString(model.sceneRoots.toArray()));
        sb.append("\n[node 层级]");
        for (int root : model.sceneRoots) {
            appendGlbNode(sb, model, root, 1);
        }
        int totalTris = 0, totalVerts = 0;
        for (GlbModel.Mesh mesh : model.meshes) {
            for (GlbModel.Primitive p : mesh.primitives) {
                totalVerts += p.vertexCount();
                totalTris += (p.indices != null ? p.indices.length : p.vertexCount()) / 3;
            }
        }
        sb.append("\n[网格] ").append(model.meshes.size()).append(" 个 · 顶点 ")
                .append(totalVerts).append(" · 三角形 ").append(totalTris);
        sb.append("\n[材质] ").append(model.materials.size()).append(" 个");
        for (int i = 0; i < model.materials.size(); i++) {
            GlbModel.Material m = model.materials.get(i);
            sb.append(String.format(
                    "\n  #%d %s · baseColor %s · tex#%d · metallic %.2f · roughness %.2f · normal#%d · emissive %s · alpha %s",
                    i, m.baseColorTexture >= 0 ? "(贴图)" : "(纯色)",
                    m.baseColorFactor != null ? Arrays.toString(m.baseColorFactor) : "默认白",
                    m.baseColorTexture, m.metallicFactor, m.roughnessFactor, m.normalTexture,
                    m.emissiveFactor != null ? Arrays.toString(m.emissiveFactor) : "无",
                    m.alphaMode));
        }
        // 成功标准 1：动画 name / channel 数 / 关键帧数 / interpolation
        sb.append("\n[动画] ").append(model.animations.size()).append(" 个");
        for (int i = 0; i < model.animations.size(); i++) {
            GlbModel.Animation a = model.animations.get(i);
            sb.append("\n  #").append(i).append(" '").append(a.name).append("' · channel ")
                    .append(a.channels.size()).append(" 个");
            for (GlbModel.Channel c : a.channels) {
                sb.append("\n    - node#").append(c.nodeIndex).append(" · ").append(c.path)
                        .append(" · ").append(c.interpolation).append(" · 关键帧 ")
                        .append(c.times.length)
                        .append(" · duration ").append(c.times.length > 0 ? c.times[c.times.length - 1] : 0)
                        .append("s");
            }
        }
        sb.append("\n[展平] 渲染三角形 ").append(flat.triangles().size())
                .append(" · 中心格 ").append(flat.ox()).append(',').append(flat.oy()).append(',').append(flat.oz())
                .append("\n=================================");
        PolarisObjuilder.LOGGER.info("[Glb]{}", sb);
    }

    private static void appendGlbNode(StringBuilder sb, GlbModel model, int idx, int depth) {
        if (idx < 0 || idx >= model.nodes.size()) return;
        GlbModel.Node n = model.nodes.get(idx);
        sb.append("\n");
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append("▸ ").append(n.name.isBlank() ? "<unnamed>" : n.name);
        if (n.meshes.length > 0) sb.append(" [mesh ").append(Arrays.toString(n.meshes)).append("]");
        if (n.translation != null) sb.append(" t=").append(Arrays.toString(n.translation));
        if (n.rotation != null) sb.append(" r=").append(Arrays.toString(n.rotation));
        if (n.scale != null) sb.append(" s=").append(Arrays.toString(n.scale));
        for (int c : n.children) appendGlbNode(sb, model, c, depth + 1);
    }

    /** glb 材质 → 漫反射颜色列表（索引 = materialId；有贴图材质不传颜色=白，由贴图承载） */
    public static List<float[]> glbMaterialColors(GlbModel model) {
        List<float[]> colors = new ArrayList<>();
        for (GlbModel.Material m : model.materials) {
            if (m.baseColorTexture >= 0) {
                colors.add(null); // 有贴图 → 渲染器用贴图
            } else {
                colors.add(m.baseColorFactor != null
                        ? new float[]{m.baseColorFactor[0], m.baseColorFactor[1], m.baseColorFactor[2]}
                        : null);
            }
        }
        return colors;
    }

    /** glb 材质 → 自发光颜色列表（索引 = materialId；emissiveFactor → [r,g,b]，无 emissiveFactor 或全 0 → null） */
    public static List<float[]> glbMaterialEmissive(GlbModel model) {
        List<float[]> emissive = new ArrayList<>();
        for (GlbModel.Material m : model.materials) {
            if (m.emissiveFactor != null
                    && (m.emissiveFactor[0] > 0 || m.emissiveFactor[1] > 0 || m.emissiveFactor[2] > 0)) {
                emissive.add(new float[]{clamp01(m.emissiveFactor[0]),
                        clamp01(m.emissiveFactor[1]), clamp01(m.emissiveFactor[2])});
            } else {
                emissive.add(null);
            }
        }
        return emissive;
    }

    /** glb 材质 → 透明度列表（索引 = materialId；alphaMode=BLEND 取 baseColorFactor 的 alpha，否则 1） */
    public static List<Float> glbMaterialAlphas(GlbModel model) {
        List<Float> alphas = new ArrayList<>();
        for (GlbModel.Material m : model.materials) {
            if ("BLEND".equalsIgnoreCase(m.alphaMode)) {
                alphas.add(m.baseColorFactor != null ? clamp01(m.baseColorFactor[3]) : 1f);
            } else {
                alphas.add(1f); // OPAQUE 不透明 / MASK 走 alpha-test 镂空（另存 MASK 集合，渲染用 cutout）
            }
        }
        return alphas;
    }

    /** glb 材质 → MASK 镂空的 materialId 集合（glTF alphaMode=MASK → alpha-test cutout 渲染，B1 收尾·事项 5） */
    public static Set<Integer> glbMaskedMaterials(GlbModel model) {
        Set<Integer> s = new HashSet<>();
        for (int i = 0; i < model.materials.size(); i++) {
            if ("MASK".equalsIgnoreCase(model.materials.get(i).alphaMode)) s.add(i);
        }
        return s;
    }

    /** glb 材质 → doubleSided 的 materialId 集合（glTF doubleSided=true → 双面渲染 NO_CULL） */
    public static Set<Integer> glbDoubleSidedMaterials(GlbModel model) {
        Set<Integer> s = new HashSet<>();
        for (int i = 0; i < model.materials.size(); i++) {
            if (model.materials.get(i).doubleSided) s.add(i);
        }
        return s;
    }

    /** glb 材质 → 贴图 NativeImage 列表（索引 = materialId；baseColorTexture → image 字节；无 → null） */
    public static List<NativeImage> glbMaterialTextures(GlbModel model) {
        List<NativeImage> images = new ArrayList<>();
        for (GlbModel.Material m : model.materials) {
            images.add(m.baseColorTexture >= 0 ? glbImage(model, m.baseColorTexture) : null);
        }
        return images;
    }

    /** texture 索引 → 内嵌 image 字节 → NativeImage（png/jpeg） */
    public static NativeImage glbImage(GlbModel model, int textureIndex) {
        if (textureIndex < 0 || textureIndex >= model.textures.size()) return null;
        int imgIdx = model.textures.get(textureIndex);
        if (imgIdx < 0 || imgIdx >= model.images.size()) return null;
        byte[] bytes = model.images.get(imgIdx);
        if (bytes == null) return null;
        NativeImage img = decodeGlbImage(bytes, imgIdx);
        if (img != null) {
            PolarisObjuilder.LOGGER.info("[Glb] 贴图解码成功 image#{} ({}x{})",
                    imgIdx, img.getWidth(), img.getHeight());
        }
        return img;
    }

    /**
     * 解码 glb 内嵌贴图字节。
     * MC 1.21.1 的 NativeImage.read(InputStream) 只支持 PNG（内部强制校验 PNG 签名），
     * JPEG 等其它格式需走 ImageIO。按魔数区分：
     * PNG(89 50 4E 47) → NativeImage.read；其它(JPEG FF D8 FF 等) → ImageIO。
     */
    private static NativeImage decodeGlbImage(byte[] bytes, int imgIdx) {
        boolean isPng = bytes.length >= 4
                && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47;
        if (isPng) {
            try (InputStream in = new java.io.ByteArrayInputStream(bytes)) {
                return NativeImage.read(in);
            } catch (Exception e) {
                PolarisObjuilder.LOGGER.warn("[Glb] PNG 解码失败 image#{}", imgIdx, e);
                return null;
            }
        }
        // 非 PNG（JPEG 等）→ ImageIO
        try {
            java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (bi == null) {
                PolarisObjuilder.LOGGER.warn("[Glb] ImageIO 解码失败 image#{}", imgIdx);
                return null;
            }
            NativeImage img = new NativeImage(bi.getWidth(), bi.getHeight(), false);
            for (int y = 0; y < bi.getHeight(); y++) {
                for (int x = 0; x < bi.getWidth(); x++) {
                    int rgb = bi.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    // NativeImage 为 ABGR 布局：A<<24 | B<<16 | G<<8 | R
                    img.setPixelRGBA(x, y, 0xFF000000 | (b << 16) | (g << 8) | r);
                }
            }
            return img;
        } catch (Exception e) {
            PolarisObjuilder.LOGGER.warn("[Glb] 贴图解码失败 image#{}", imgIdx, e);
            return null;
        }
    }

    // ===================== PBR 贴图生成（子工程 4） =====================

    /** glb 材质 → 法线贴图 NativeImage 列表（索引 = materialId；normalTexture → image；无 → null） */
    public static List<NativeImage> glbMaterialNormalImages(GlbModel model) {
        List<NativeImage> images = new ArrayList<>();
        for (GlbModel.Material m : model.materials) {
            images.add(m.normalTexture >= 0 ? glbImage(model, m.normalTexture) : null);
        }
        return images;
    }

    /**
     * glb 材质 → labPBR specular 贴图 NativeImage 列表（索引 = materialId；无金属/粗糙/自发光 → null）。
     * Photon labPBR v1.3 通道：R=高光强度(1-粗糙度²反演) · G=F0/金属编码 · B=SSS · A=自发光(1=无)。
     * glTF metallicRoughnessTexture（R=metalness, G=roughness）× factor 转换后写入。
     */
    public static List<NativeImage> glbMaterialSpecularImages(GlbModel model) {
        List<NativeImage> images = new ArrayList<>();
        for (GlbModel.Material m : model.materials) {
            NativeImage mr = m.metallicRoughnessTexture >= 0
                    ? glbImage(model, m.metallicRoughnessTexture) : null;
            NativeImage em = m.emissiveTexture >= 0
                    ? glbImage(model, m.emissiveTexture) : null;
            images.add(buildLabPbrSpecular(mr, em, m.metallicFactor, m.roughnessFactor,
                    m.emissiveFactor, m.emissiveStrength));
        }
        return images;
    }

    /**
     * OBJ 材质 → labPBR specular 标量贴图列表（索引 = materialId）。
     * 从 MTL 高光参数推导：roughness = sqrt(2/(Ns+2))（Blinn→GGX 近似）；f0 ≈ Ks 亮度。
     * Ns<=0（无高光）→ null（不参与 PBR）。
     */
    public static List<NativeImage> objMaterialSpecularImages(ObjMesh mesh) {
        List<NativeImage> images = new ArrayList<>();
        for (ObjMesh.Material m : mesh.materials) {
            if (m.ns <= 0f) {
                images.add(null);
                continue;
            }
            float rough = clamp01((float) Math.sqrt(2.0 / (m.ns + 2.0)));
            float metal = clamp01(Math.max(m.ksR, Math.max(m.ksG, m.ksB)));
            // OBJ 自发光走渲染器顶点色辉光 Ke；此处 labPBR A 通道也写入 Ke 亮度标志 → Photon 识别自发光 → 强辉光
            float emis = clamp01(Math.max(m.keR, Math.max(m.keG, m.keB)));
            NativeImage img = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
            img.setPixelRGBA(0, 0, packLabPbr(metal, rough, emis));
            images.add(img);
            PolarisObjuilder.LOGGER.info("[Objuilder] 材质 '{}' → specular 标量 metal={} rough={} (Ks={} Ns={})",
                    m.name, metal, rough, clamp01(Math.max(m.ksR, Math.max(m.ksG, m.ksB))), m.ns);
        }
        return images;
    }

    /**
     * 合成 labPBR specular 贴图（Photon v1.3 语义）。
     * 尺寸取 metallicRoughnessTexture 与 emissiveTexture 的较大者（可均无 → 1×1 标量）。
     * 逐像素：metal = mf×mr.B，rough = rf×mr.G；自发光 e = max(emissiveFactor亮度×strength, emissiveTexture 像素亮度)
     * → packLabPbr 反转写入 A 通道（Photon 识别自发光材质 → 强辉光）。
     */
    private static NativeImage buildLabPbrSpecular(NativeImage mr, NativeImage em,
                                                   float metallic, float rough, float[] emissiveFactor,
                                                   float emissiveStrength) {
        float mf = clamp01(metallic);
        float rf = clamp01(rough);
        float eBase = 0f;
        if (emissiveFactor != null) {
            eBase = Math.max(emissiveFactor[0], Math.max(emissiveFactor[1], emissiveFactor[2]));
        }
        eBase *= emissiveStrength;
        int w = 1, h = 1;
        if (mr != null) { w = mr.getWidth(); h = mr.getHeight(); }
        if (em != null) { w = Math.max(w, em.getWidth()); h = Math.max(h, em.getHeight()); }
        NativeImage out = new NativeImage(NativeImage.Format.RGBA, w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float metal = mf, roughP = rf, e = eBase;
                if (mr != null && x < mr.getWidth() && y < mr.getHeight()) {
                    int p = mr.getPixelRGBA(x, y);
                    // NativeImage 为 ABGR 布局：bit0-7=R, bit8-15=G, bit16-23=B, bit24-31=A
                    // glTF metallicRoughnessTexture 通道：G=roughness, B=metallic
                    roughP = ((p >> 8) & 0xFF) / 255f * rf;    // G 通道 = roughness
                    metal = ((p >> 16) & 0xFF) / 255f * mf;    // B 通道 = metallic
                }
                if (em != null && x < em.getWidth() && y < em.getHeight()) {
                    int pe = em.getPixelRGBA(x, y);
                    float r = (pe & 0xFF) / 255f;
                    float g = ((pe >> 8) & 0xFF) / 255f;
                    float b = ((pe >> 16) & 0xFF) / 255f;
                    float lum = r * 0.299f + g * 0.587f + b * 0.114f;
                    e = Math.max(e, lum);
                }
                out.setPixelRGBA(x, y, packLabPbr(clamp01(metal), clamp01(roughP), clamp01(e)));
            }
        }
        return out;
    }

    /**
     * labPBR specular 像素打包（Photon v1.3 语义，NativeImage 为 ABGR 布局）。
     * GL 通道语义：R=smoothness(1-√rough) · G=F0 · B=0(SSS) · A=自发光强度。
     * 关键：Photon 解码（material.glsl）为
     *   emission = albedo * specular_map.a * float(specular_map.a != 1.0)
     * 即 **A=255(1.0)=无自发光（哨兵）；A<255=自发光，emission=albedo×a，a 越接近 254 越亮**。
     * 故 emissive 参数（0=无，1=最强）映射为 A = emissive<=0 ? 255 : round(emissive*254)（上限 254 避开哨兵）。
     * NativeImage ABGR：bit0-7=R · bit8-15=G · bit16-23=B · bit24-31=A。
     */
    private static int packLabPbr(float metal, float rough, float emissive) {
        float smooth = 1f - (float) Math.sqrt(clamp01(rough));
        float f0 = metal >= 0.9f ? 240f / 255f : 0.04f + metal * 0.86f;
        int R = Math.round(clamp01(smooth) * 255f);   // GL R = smoothness
        int G = Math.round(clamp01(f0) * 255f);       // GL G = F0
        int A = emissive <= 0.001f ? 255
                : Math.max(1, Math.min(254, Math.round(clamp01(emissive) * 254f)));
        // ABGR：A<<24 | B<<16 | G<<8 | R（B=SSS=0）
        return (A << 24) | (G << 8) | R;
    }

    public static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }

    /**
     * OBJ 路径：无 map_Kd 但生成过 specular（有高光）的纯色材质 → 补 1×1 Kd 颜色 base。
     * PBR loader 按「纹理实例」关联，纯色材质没有 base 纹理则 specular 无处挂载。
     */
    public static void fillBaseColorsForObjPbr(List<NativeImage> bases, List<NativeImage> speculars,
                                                ObjMesh mesh) {
        if (speculars == null) return;
        for (int i = 0; i < speculars.size(); i++) {
            if (speculars.get(i) == null) continue;
            if (bases != null && bases.size() > i && bases.get(i) != null) continue;
            ObjMesh.Material m = i < mesh.materials.size() ? mesh.materials.get(i) : null;
            float r = m != null ? clamp01(m.kdR) : 1f;
            float g = m != null ? clamp01(m.kdG) : 1f;
            float b = m != null ? clamp01(m.kdB) : 1f;
            NativeImage img = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
            // ABGR 布局：A<<24 | B<<16 | G<<8 | R
            img.setPixelRGBA(0, 0, 0xFF000000
                    | (Math.round(b * 255f) << 16) | (Math.round(g * 255f) << 8) | Math.round(r * 255f));
            bases.set(i, img);
        }
    }

    /**
     * glb 路径：无 baseColorTexture 但带 PBR（法线/金属粗糙）的材质 → 补 1×1 baseColorFactor 颜色 base。
     */
    public static void fillBaseColorsForGlbPbr(List<NativeImage> bases, List<NativeImage> normals,
                                                List<NativeImage> speculars, GlbModel model) {
        if (normals == null && speculars == null) return;
        for (int i = 0; i < model.materials.size(); i++) {
            boolean hasNormal = normals != null && normals.size() > i && normals.get(i) != null;
            boolean hasSpec = speculars != null && speculars.size() > i && speculars.get(i) != null;
            if (!hasNormal && !hasSpec) continue;
            if (bases != null && bases.size() > i && bases.get(i) != null) continue;
            GlbModel.Material m = model.materials.get(i);
            float r = m.baseColorFactor != null ? clamp01(m.baseColorFactor[0]) : 1f;
            float g = m.baseColorFactor != null ? clamp01(m.baseColorFactor[1]) : 1f;
            float b = m.baseColorFactor != null ? clamp01(m.baseColorFactor[2]) : 1f;
            NativeImage img = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
            // ABGR 布局：A<<24 | B<<16 | G<<8 | R
            img.setPixelRGBA(0, 0, 0xFF000000
                    | (Math.round(b * 255f) << 16) | (Math.round(g * 255f) << 8) | Math.round(r * 255f));
            bases.set(i, img);
        }
    }
}
