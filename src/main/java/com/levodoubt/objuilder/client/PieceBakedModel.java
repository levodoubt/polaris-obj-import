package com.levodoubt.objuilder.client;

import java.util.List;

import com.levodoubt.objuilder.block.ObjPieceBlockEntity;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * 子片 BakedModel：从 ModelData（BlockEntity 提供）读 pieceId 取几何。
 * 突破 BlockState 4096 上限，支持任意大的族数。
 */
public class PieceBakedModel implements BakedModel {
    private final TextureAtlasSprite particle;

    public PieceBakedModel(TextureAtlasSprite particle) {
        this.particle = particle;
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random) {
        return List.of();
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource rand,
                                    ModelData data, RenderType renderType) {
        if (side != null || state == null) return List.of();
        int id = data.has(ObjPieceBlockEntity.PIECE_ID) ? data.get(ObjPieceBlockEntity.PIECE_ID) : -1;
        if (id < 0) return List.of();
        List<BakedQuad> quads = PieceModelCache.getQuads(id);
        return quads != null ? quads : List.of();
    }

    @Override
    public boolean useAmbientOcclusion() {
        return true;
    }

    @Override
    public boolean isGui3d() {
        return false;
    }

    @Override
    public boolean usesBlockLight() {
        return true;
    }

    @Override
    public boolean isCustomRenderer() {
        return false;
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return particle;
    }

    @Override
    public ItemTransforms getTransforms() {
        return new ItemTransforms(
                ItemTransform.NO_TRANSFORM, ItemTransform.NO_TRANSFORM,
                ItemTransform.NO_TRANSFORM, ItemTransform.NO_TRANSFORM,
                ItemTransform.NO_TRANSFORM, ItemTransform.NO_TRANSFORM,
                ItemTransform.NO_TRANSFORM, ItemTransform.NO_TRANSFORM);
    }

    @Override
    public ItemOverrides getOverrides() {
        return ItemOverrides.EMPTY;
    }
}
