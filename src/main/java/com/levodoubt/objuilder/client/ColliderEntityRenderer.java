package com.levodoubt.objuilder.client;

import com.levodoubt.objuilder.entity.ColliderEntity;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;

/**
 * 隐形碰撞体渲染器（子工程 6）：render 为空实现 → 玩家看不到碰撞体。
 * 注册此渲染器只为避免 EntityRenderDispatcher 因缺渲染器抛错。
 */
public class ColliderEntityRenderer extends EntityRenderer<ColliderEntity> {
    public ColliderEntityRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
    }

    @Override
    public void render(ColliderEntity entity, float entityYaw, float partialTick,
                       PoseStack pose, MultiBufferSource buffer, int packedLight) {
        // 空：隐形
    }

    @Override
    public ResourceLocation getTextureLocation(ColliderEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
