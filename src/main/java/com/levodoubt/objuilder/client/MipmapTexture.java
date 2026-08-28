package com.levodoubt.objuilder.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * 带完整 mipmap 链的动态贴图（PBR 法线/高光贴图与模型主贴图共用）。
 *
 * <p>与 MC 官方 SimpleTexture 一致：上传前必须 {@code TextureUtil.prepareImage} 分配纹理存储
 * （NativeImage.upload 内部是 texSubImage2D，对未分配纹理属未定义行为 → 全黑），
 * 且 prepareImage 的 mipmapLevels 决定 GL_TEXTURE_MAX_LEVEL（传 0 则 glGenerateMipmap 无效）。
 * MC 1.21.1 的 NativeImage.upload 只上传 level 0 不生成 mipmap 链，
 * 4K 高频法线贴图无 mipmap → 远距离混叠摩尔纹 → 这里显式 glGenerateMipmap 补全。
 */
public class MipmapTexture extends AbstractTexture {
    private final NativeImage image;

    public MipmapTexture(NativeImage image) {
        this.image = image;
        if (!RenderSystem.isOnRenderThreadOrInit()) {
            RenderSystem.recordRenderCall(this::upload);
        } else {
            upload();
        }
    }

    private void upload() {
        if (!RenderSystem.isOnRenderThread()) {
            throw new IllegalStateException("Uploading to OpenGL on non-render thread");
        }
        int w = image.getWidth();
        int h = image.getHeight();
        int mipLevels = (int) (Math.log(Math.max(w, h)) / Math.log(2)) + 1;
        // 分配 level0..max 存储并设置 GL_TEXTURE_MAX_LEVEL（>0 → mipmap 过滤生效）
        TextureUtil.prepareImage(getId(), mipLevels, w, h);
        // 上传 level 0（upload 内 setFilter 会设成 NEAREST，下面再覆盖）
        image.upload(0, 0, 0, 0, 0, w, h, false, false, false, true);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        GlStateManager._bindTexture(0);
    }

    /** 动态纹理：不随资源包重载重建（与 DynamicTexture 行为一致） */
    @Override
    public void load(ResourceManager resourceManager) {
    }

    @Override
    public void close() {
        image.close();
        super.close();
    }
}
