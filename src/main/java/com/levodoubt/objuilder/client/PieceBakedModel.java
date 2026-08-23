package com.levodoubt.objuilder.client;

import java.util.List;

import com.levodoubt.objuilder.block.PieceBlock;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 子片 BakedModel：按 BlockState 的模板族 id 从缓存取几何。
 * 关闭 AO（避免斜面光照格子化），usesBlockLight 保持方块光照。
 */
public class PieceBakedModel implements BakedModel {
    private final TextureAtlasSprite particle;

    public PieceBakedModel(TextureAtlasSprite particle) {
        this.particle = particle;
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random) {
        if (side != null || state == null) return List.of();
        int id = state.getValue(PieceBlock.PIECE_A) * 256
                + state.getValue(PieceBlock.PIECE_B) * 16
                + state.getValue(PieceBlock.PIECE_C);
        List<BakedQuad> quads = PieceModelCache.getQuads(id);
        return quads != null ? quads : List.of();
    }

    @Override
    public boolean useAmbientOcclusion() {
        return false;
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
