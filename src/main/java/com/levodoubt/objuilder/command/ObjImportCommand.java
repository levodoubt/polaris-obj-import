package com.levodoubt.objuilder.command;

import java.io.File;

import com.levodoubt.objuilder.PolarisObjuilder;
import com.levodoubt.objuilder.block.PieceBlock;
import com.levodoubt.objuilder.client.PieceModelCache;
import com.levodoubt.objuilder.core.ObjMesh;
import com.levodoubt.objuilder.core.ObjParser;
import com.levodoubt.objuilder.core.SphereGenerator;
import com.levodoubt.objuilder.core.Voxelizer;
import com.levodoubt.objuilder.debug.LastImportStats;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * /objimport <mode> [file] — 客户端命令（单机验证用）
 *   mode: slice（子片空壳） / block（子块：子片 + 内部石头填充）
 *   file: 可选，读取 config/polarisobjuilder/models/<file> 或绝对路径；缺省内置球体
 * 流程：切分 → 烘焙几何 → 清理旧区域（按模型 AABB）→ 摆放子片（+ 可选石头）→ 输出报告
 */
public class ObjImportCommand {

    public static int run(CommandSourceStack source, String file, boolean fillMode) {
        ClientLevel level = Minecraft.getInstance().level;
        var player = Minecraft.getInstance().player;
        if (level == null || player == null) return 0;
        long start = System.currentTimeMillis();

        // 1. 读取 / 生成网格
        ObjMesh mesh;
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
            modelName = f.getName();
        } else {
            mesh = SphereGenerator.sphere(4.5f, 24, 48, 0, 0, 0);
        }

        // 2. 切分（表面格检测 + 模板族聚类 + 内部格检测）
        Voxelizer.Result result = Voxelizer.voxelize(mesh, 16);
        if (result.placements().isEmpty()) {
            source.sendFailure(Component.literal("切分为空：模型未覆盖任何完整格子"));
            return 0;
        }

        // 3. 烘焙几何到客户端缓存（BakedModel 动态查询，即时生效）
        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                .apply(ResourceLocation.withDefaultNamespace("white"));
        PieceModelCache.bake(result.geometry(), sprite);

        // 4. 清理旧区域（按模型 AABB 区域，重启后也能清掉上次残留的石头）+ 摆放
        BlockPos base = player.blockPosition().offset(7, 1, 7);
        int[] bounds = computeBounds(result);
        clearArea(level, base, bounds);
        for (Voxelizer.Placement p : result.placements()) {
            BlockState state = PolarisObjuilder.OBJ_PIECE.get().defaultBlockState()
                    .setValue(PieceBlock.PIECE_A, p.pieceId() / 256)
                    .setValue(PieceBlock.PIECE_B, (p.pieceId() / 16) % 16)
                    .setValue(PieceBlock.PIECE_C, p.pieceId() % 16);
            level.setBlock(base.offset(p.x(), p.y(), p.z()), state, 3);
        }
        // 子块模式：内部格填石头
        if (fillMode) {
            for (int[] cell : result.interior()) {
                level.setBlock(base.offset(cell[0], cell[1], cell[2]), Blocks.STONE.defaultBlockState(), 3);
            }
        }

        // 5. 报告 + 写入调试统计
        Voxelizer.Stats s = result.stats();
        LastImportStats.modelName = (fillMode ? "[子块] " : "[子片] ") + modelName;
        LastImportStats.vertices = s.vertCount();
        LastImportStats.triangles = s.triCount();
        LastImportStats.gridCells = s.gridCells();
        LastImportStats.pieceCount = s.pieceCount();
        LastImportStats.renderTris = s.totalRenderTris();
        LastImportStats.importTimeMs = System.currentTimeMillis() - start;
        source.sendSuccess(() -> Component.literal(String.format(
                "§a[Objuilder] %s 导入完成 → 顶点 %d · 三角形 %d · 表面格 %d · 模板族 %d · 渲染三角形 %d · 内部石头 %d · 耗时 %dms",
                fillMode ? "子块" : "子片",
                s.vertCount(), s.triCount(), s.gridCells(), s.pieceCount(), s.totalRenderTris(),
                s.interiorCount(), LastImportStats.importTimeMs)), true);
        return 1;
    }

    private static String neoforgePath() {
        return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                .resolve("polarisobjuilder").toString();
    }

    /** 从摆放数据计算模型 AABB 范围 */
    private static int[] computeBounds(Voxelizer.Result result) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Voxelizer.Placement p : result.placements()) {
            minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x());
            minY = Math.min(minY, p.y()); maxY = Math.max(maxY, p.y());
            minZ = Math.min(minZ, p.z()); maxZ = Math.max(maxZ, p.z());
        }
        return new int[]{minX, maxX, minY, maxY, minZ, maxZ};
    }

    /** 按模型 AABB（膨胀 2 格）区域清理子片与石头——重启后也能清掉残留 */
    private static void clearArea(ClientLevel level, BlockPos base, int[] b) {
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
