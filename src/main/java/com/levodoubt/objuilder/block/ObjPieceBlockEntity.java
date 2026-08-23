package com.levodoubt.objuilder.block;

import com.levodoubt.objuilder.PolarisObjuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 子片方块实体：存 pieceId（模板族 id，int 范围，突破 BlockState 4096 上限）。
 * 通过 ModelData 把 pieceId 提供给 BakedModel 渲染。
 */
public class ObjPieceBlockEntity extends BlockEntity {
    /** 注册到 DeferredRegister（在主类初始化） */
    public static DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(net.minecraft.core.registries.Registries.BLOCK_ENTITY_TYPE, PolarisObjuilder.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ObjPieceBlockEntity>> TYPE =
            BLOCK_ENTITIES.register("obj_piece", () ->
                    BlockEntityType.Builder.of(ObjPieceBlockEntity::new, PolarisObjuilder.OBJ_PIECE.get()).build(null));

    /** ModelData 属性：pieceId */
    public static final ModelProperty<Integer> PIECE_ID = new ModelProperty<>();

    private int pieceId = -1;

    public ObjPieceBlockEntity(BlockPos pos, BlockState state) {
        super(TYPE.get(), pos, state);
    }

    public void setPieceId(int id) {
        if (this.pieceId != id) {
            this.pieceId = id;
            setChanged();
            // 请求渲染更新（几何变化）
            if (level != null && level.isClientSide) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }
        }
    }

    public int getPieceId() {
        return pieceId;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("pieceId", pieceId);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.pieceId = tag.getInt("pieceId");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putInt("pieceId", pieceId);
        return tag;
    }

    @Override
    public ModelData getModelData() {
        return ModelData.builder().with(PIECE_ID, pieceId).build();
    }
}
