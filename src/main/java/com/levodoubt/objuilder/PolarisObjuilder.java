package com.levodoubt.objuilder;

import org.slf4j.Logger;

import com.levodoubt.objuilder.block.PieceBlock;
import com.mojang.logging.LogUtils;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Polaris Objuilder — 外部 OBJ 模型导入模组（B1）
 *
 * 首版（M1+M2 最小闭环）：
 *  - /objimport 命令从 config/polarisobjuilder/models 读取 .obj（无参数时内置球体）
 *  - 切分器：按 MC 网格离散表面 → 生成"子片"并聚类为模板族
 *  - 子片以"无物品方块 + BakedModel"渲染进区块（BakedQuad 路线）
 */
@Mod(PolarisObjuilder.MODID)
public class PolarisObjuilder {
    public static final String MODID = "polarisobjuilder";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);

    /** 子片方块：无物品（不进 JEI / 创造栏），BlockState 编码模板族 id */
    public static final DeferredBlock<Block> OBJ_PIECE = BLOCKS.register("obj_piece",
            () -> new PieceBlock(BlockBehaviour.Properties.of()
                    .noOcclusion()
                    .noCollission()
                    .instabreak()));

    public PolarisObjuilder(IEventBus modEventBus, ModContainer modContainer) {
        BLOCKS.register(modEventBus);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }
}
