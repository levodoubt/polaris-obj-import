package com.levodoubt.objuilder.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 不规则非物品方块：无物品、光照上透明（光穿透空壳照亮内部）。
 * BlockState 用 PIECE_A/B/C 三个 4-bit 属性编码模板族 id（0~4095）。
 * 整格碰撞 + 正常挖掘强度 → 左键可像正常方块一样破坏。
 */
public class PieceBlock extends Block {
    public static final IntegerProperty PIECE_A = IntegerProperty.create("piece_a", 0, 15);
    public static final IntegerProperty PIECE_B = IntegerProperty.create("piece_b", 0, 15);
    public static final IntegerProperty PIECE_C = IntegerProperty.create("piece_c", 0, 15);

    public PieceBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(PIECE_A, 0)
                .setValue(PIECE_B, 0)
                .setValue(PIECE_C, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PIECE_A, PIECE_B, PIECE_C);
    }

    @Override
    public int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
        return 0; // 光照上透明
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter reader, BlockPos pos) {
        return true; // 天空光穿透
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.block(); // 整格碰撞 → 准星可瞄准 → 可破坏
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.block();
    }
}
