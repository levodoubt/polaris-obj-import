package com.levodoubt.objuilder.command;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.levodoubt.objuilder.PolarisObjuilder;
import com.levodoubt.objuilder.block.PieceBlock;
import com.levodoubt.objuilder.client.PieceModelCache;
import com.levodoubt.objuilder.core.BakeFormat;
import com.levodoubt.objuilder.core.ObjMesh;
import com.levodoubt.objuilder.core.ObjParser;
import com.levodoubt.objuilder.core.Schematic;
import com.levodoubt.objuilder.core.SphereGenerator;
import com.levodoubt.objuilder.core.Voxelizer;
import com.levodoubt.objuilder.debug.LastImportStats;
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
        // 后台线程：切分 + 烘焙几何缓存
        CompletableFuture.supplyAsync(() -> {
            Voxelizer.Result result = Voxelizer.voxelize(finalMesh, 16);
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
            Voxelizer.Result result = Voxelizer.voxelize(mesh, 16);
            try {
                Schematic.export(targetFile, result.placements(), result.interior(), mesh.texturePath);
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

            // 烘焙贴图到缓存（若有）
            String texPath = data.texturePath();
            if (texPath != null && !texPath.isBlank()) {
                TextureAtlasSprite sprite = Minecraft.getInstance()
                        .getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                        .apply(ResourceLocation.fromNamespaceAndPath("minecraft", "block/white_concrete"));
                NativeImage texture = loadTexturePath(texPath);
                // 注：schematic 不含几何，仅含方块状态；几何需另行加载（这里只放方块）
                PolarisObjuilder.LOGGER.info("[Objuilder] schematic 贴图引用: {}", texPath);
            }

            // 分块放置
            Minecraft mc = Minecraft.getInstance();
            var idx = new java.util.concurrent.atomic.AtomicInteger(0);
            int total = w * h * d;
            mc.execute(() -> {
                int placed = 0;
                while (idx.get() < total && placed < BLOCKS_PER_TICK) {
                    int i = idx.getAndIncrement();
                    int x = i % w;
                    int y = (i / w) % h;
                    int z = i / (w * h);
                    byte paletteIdx = data.blockData()[i];
                    if (paletteIdx == 0) continue; // air
                    BlockState state = Schematic.resolveBlockState(data, paletteIdx & 0xFF);
                    level.setBlock(base.offset(x, y, z), state, 3);
                    placed++;
                }
                if (idx.get() < total) {
                    mc.execute(() -> {
                        int placed2 = 0;
                        while (idx.get() < total && placed2 < BLOCKS_PER_TICK) {
                            int i = idx.getAndIncrement();
                            int x = i % w;
                            int y = (i / w) % h;
                            int z = i / (w * h);
                            byte paletteIdx = data.blockData()[i];
                            if (paletteIdx == 0) continue;
                            level.setBlock(base.offset(x, y, z),
                                    Schematic.resolveBlockState(data, paletteIdx & 0xFF), 3);
                            placed2++;
                        }
                        if (idx.get() >= total) {
                            source.sendSuccess(() -> Component.literal(
                                    "§a[Objuilder] schematic 放置完成 → " + total + " 格"), true);
                        }
                    });
                } else {
                    source.sendSuccess(() -> Component.literal(
                            "§a[Objuilder] schematic 放置完成 → " + total + " 格"), true);
                }
            });
        }, Minecraft.getInstance());
        return 1;
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
                BlockState state = PolarisObjuilder.OBJ_PIECE.get().defaultBlockState()
                        .setValue(PieceBlock.PIECE_A, p.pieceId() / 256)
                        .setValue(PieceBlock.PIECE_B, (p.pieceId() / 16) % 16)
                        .setValue(PieceBlock.PIECE_C, p.pieceId() % 16);
                level.setBlock(base.offset(p.x(), p.y(), p.z()), state, 3);
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
}
