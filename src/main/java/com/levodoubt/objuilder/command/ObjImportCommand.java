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
import net.minecraft.world.level.block.state.BlockState;

/**
 * /objimport [file] — 客户端命令（单机验证用）
 *  - 无参数：导入内置球体
 *  - 带参数：读取 config/polarisobjuilder/models/<file>
 * 切分 → 烘焙几何 → 清理旧区域 → 摆放子片方块 → 输出面数报告
 */
public class ObjImportCommand {
    private static final int AREA = 10; // 清理/摆放范围

    public static int run(CommandSourceStack source, String file) {
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

        // 2. 切分（表面格检测 + 模板族聚类）
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

        // 4. 清理旧区域 + 摆放子片方块
        BlockPos base = player.blockPosition().offset(7, 1, 7);
        clearArea(level, base);
        for (Voxelizer.Placement p : result.placements()) {
            BlockState state = PolarisObjuilder.OBJ_PIECE.get().defaultBlockState()
                    .setValue(PieceBlock.PIECE_A, p.pieceId() / 256)
                    .setValue(PieceBlock.PIECE_B, (p.pieceId() / 16) % 16)
                    .setValue(PieceBlock.PIECE_C, p.pieceId() % 16);
            level.setBlock(base.offset(p.x(), p.y(), p.z()), state, 3);
        }

        // 5. 报告 + 写入调试统计
        Voxelizer.Stats s = result.stats();
        LastImportStats.modelName = modelName;
        LastImportStats.vertices = s.vertCount();
        LastImportStats.triangles = s.triCount();
        LastImportStats.gridCells = s.gridCells();
        LastImportStats.pieceCount = s.pieceCount();
        LastImportStats.renderTris = s.totalRenderTris();
        LastImportStats.importTimeMs = System.currentTimeMillis() - start;
        source.sendSuccess(() -> Component.literal(String.format(
                "§a[Objuilder] 导入完成 → 顶点 %d · 三角形 %d · 表面格 %d · 模板族 %d · 渲染三角形 %d · 耗时 %dms",
                s.vertCount(), s.triCount(), s.gridCells(), s.pieceCount(), s.totalRenderTris(),
                LastImportStats.importTimeMs)), true);
        return 1;
    }

    private static String neoforgePath() {
        return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                .resolve("polarisobjuilder").toString();
    }

    private static void clearArea(ClientLevel level, BlockPos base) {
        for (int x = -AREA; x <= AREA; x++) {
            for (int y = -AREA; y <= AREA; y++) {
                for (int z = -AREA; z <= AREA; z++) {
                    BlockPos pos = base.offset(x, y, z);
                    if (level.getBlockState(pos).getBlock() == PolarisObjuilder.OBJ_PIECE.get()) {
                        level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }
    }
}
