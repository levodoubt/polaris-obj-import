package com.levodoubt.objuilder;

import org.slf4j.Logger;

import com.levodoubt.objuilder.block.PieceBlock;
import com.levodoubt.objuilder.entity.DomainEntity;
import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * Polaris Objuilder — 外部 OBJ 模型导入模组（B1）
 *
 * 首版（M1+M2 最小闭环）：
 *  - /objimport 命令从 config/polarisobjuilder/models 读取 .obj（无参数时内置球体）
 *  - 切分器：按 MC 网格离散表面 → 生成"子片"并聚类为模板族
 *  - 子片以"无物品方块 + BakedModel"渲染进区块（BakedQuad 路线）
 *
 * 共面域（纯视觉模式）：
 *  - 表面格按共面连通域合并 → 域实体（DomainEntity）→ 实体渲染（平滑法线）
 *  - 解决"一格一几何"渲染量爆炸（1.5 亿 → 几十万）
 */
@Mod(PolarisObjuilder.MODID)
public class PolarisObjuilder {
    public static final String MODID = "polarisobjuilder";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, MODID);

    /**
     * 不规则非物品方块：无物品（不进 JEI / 创造栏），BlockState 编码模板族 id。
     * 带整格碰撞 + 正常挖掘强度 → 可像正常方块一样左键破坏。
     * 不体素化：几何只含该格表面面（直接按面渲染）。
     */
    public static final DeferredBlock<Block> OBJ_PIECE = BLOCKS.register("obj_piece",
            () -> new PieceBlock(BlockBehaviour.Properties.of()
                    .noOcclusion()
                    .strength(1.5f)));

    /** 共面域实体：一个实体承载一个共面大平面几何（纯视觉，无碰撞） */
    public static final DeferredHolder<EntityType<?>, EntityType<DomainEntity>> DOMAIN_ENTITY =
            ENTITIES.register("domain", () -> EntityType.Builder.of(DomainEntity::new, MobCategory.MISC)
                    .sized(1.0f, 1.0f)
                    .noSummon()
                    .setUpdateInterval(3)
                    .setShouldReceiveVelocityUpdates(false)
                    .setTrackingRange(1024)
                    .build(MODID + ":domain"));

    public PolarisObjuilder(IEventBus modEventBus, ModContainer modContainer) {
        BLOCKS.register(modEventBus);
        ENTITIES.register(modEventBus);
        com.levodoubt.objuilder.block.ObjPieceBlockEntity.BLOCK_ENTITIES.register(modEventBus);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }
}
