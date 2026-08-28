package com.levodoubt.objuilder.client;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * 携带 PBR 贴图数据的动态纹理（glb/OBJ 材质主贴图的运行时注册形态）。
 *
 * <p>baseColor 由 {@link MipmapTexture} 承载（完整 mipmap 链，远距离无摩尔纹）；
 * normal/specular 为额外持有的 NativeImage 引用，供 Iris 的 PBR loader 读取——
 * 光影（Iris/Oculus）渲染时按当前绑定的纹理 GL id 反查到本类实例，
 * 再经 {@code PBRTextureLoaderRegistry} 精确匹配本类注册的 loader，
 * 由 loader 把法线贴图 / labPBR specular 贴图交给光影的 gbuffers（texture_n / texture_s）。
 *
 * <p>无光影时本类退化为普通 mipmap 纹理，不影响现有渲染。
 */
public class PbrCapableTexture extends MipmapTexture {
    /** 法线贴图（glTF normalTexture 的 OpenGL 风格 RGB），可为 null */
    public final NativeImage normalImage;
    /** labPBR specular 贴图（R=高光强度, G=F0/金属, A=自发光），可为 null */
    public final NativeImage specularImage;

    public PbrCapableTexture(NativeImage base, NativeImage normal, NativeImage specular) {
        super(base);
        this.normalImage = normal;
        this.specularImage = specular;
    }
}
