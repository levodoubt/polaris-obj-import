package com.levodoubt.objuilder.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.irisshaders.iris.pbr.loader.PBRTextureLoaderRegistry;

/**
 * Iris PBR loader 注册桥（引用 Iris API 的唯一类）。
 *
 * <p>本类只在 {@link PbrTextureSupport#init()} 检测到 Iris 类存在后才被加载；
 * 无 Iris（原版/未装光影加载器）时本类永不加载，避免 NoClassDefFoundError。
 *
 * <p>loader 按 {@link PbrCapableTexture} 精确匹配：读取其持有的法线/specular NativeImage，
 * 复制后构造 {@link MipmapTexture} 上传（含完整 mipmap 链，远距离无摩尔纹；
 * PBRTextureManager 在渲染线程调用 load，且契约允许修改 GL_TEXTURE_2D 绑定）。
 * 复制而非直接引用：上传关闭源 image 前保留副本供重复 load。
 */
final class IrisPbrBridge {
    private IrisPbrBridge() {
    }

    static void register() {
        PBRTextureLoaderRegistry.INSTANCE.register(PbrCapableTexture.class,
                (texture, resourceManager, consumer) -> {
                    if (texture.normalImage != null) {
                        consumer.acceptNormalTexture(new MipmapTexture(copy(texture.normalImage)));
                    }
                    if (texture.specularImage != null) {
                        consumer.acceptSpecularTexture(new MipmapTexture(copy(texture.specularImage)));
                    }
                });
    }

    /** 逐像素复制 NativeImage（源 image 保持可用，副本交给纹理上传） */
    private static NativeImage copy(NativeImage src) {
        NativeImage c = new NativeImage(src.format(), src.getWidth(), src.getHeight(), false);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                c.setPixelRGBA(x, y, src.getPixelRGBA(x, y));
            }
        }
        return c;
    }
}
