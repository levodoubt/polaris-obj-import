package com.levodoubt.objuilder.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.Util;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Function;

/**
 * 自定义渲染类型：MC 的 entitySolid 使用 QUADS（四边形）模式，而 OBJ 是三角形网格。
 * 用 QUADS 渲染三角形会导致顶点错位（每 4 顶点拼一个四边形）→ 三角面乱连接。
 * 这里提供 TRIANGLES 模式 + 背面剔除（CULL）的实体渲染类型。
 *
 * 背面剔除（CULL）而非双面（NO_CULL）：OBJ/glb 的封闭模型（如猴头）若双面渲染，
 * 正面与背面三角形深度几乎相等 → 深度冲突（z-fighting）→ 轮廓/明暗交界处动态噪点、
 * "阴影随视角移动"。开背面剔除后只画正面，彻底消除。（B1 设计文档 §5.2 同样要求背面剔除）
 */
public class CustomRenderTypes extends RenderType {
    private CustomRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode,
                              int bufferSize, boolean affectsCrumbling, boolean sortOnUpload,
                              Runnable setupState, Runnable clearState) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setupState, clearState);
    }

    /** 三角形实体渲染：保留法线（NEW_ENTITY）+ 背面剔除 + 不透明 */
    public static final Function<ResourceLocation, RenderType> ENTITY_TRIANGLES = Util.memoize(location ->
            create("polaris_objuilder_entity_triangles", DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.TRIANGLES, 1_536, false, false,
                    CompositeState.builder()
                            .setShaderState(new ShaderStateShard(GameRenderer::getRendertypeEntitySolidShader))
                            .setTextureState(new RenderStateShard.TextureStateShard(location, false, false))
                            .setTransparencyState(NO_TRANSPARENCY)
                            .setLightmapState(LIGHTMAP)
                            .setOverlayState(OVERLAY)
                            .setCullState(CULL)
                            .createCompositeState(false)));

    /** 三角形实体渲染（半透明）：TRANSLUCENT_TRANSPARENCY 混合 + sortOnUpload，用于 alpha<1 的材质 */
    public static final Function<ResourceLocation, RenderType> ENTITY_TRIANGLES_TRANSLUCENT = Util.memoize(location ->
            create("polaris_objuilder_entity_triangles_translucent", DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.TRIANGLES, 1_536, false, true,
                    CompositeState.builder()
                            .setShaderState(new ShaderStateShard(GameRenderer::getRendertypeEntityTranslucentShader))
                            .setTextureState(new RenderStateShard.TextureStateShard(location, false, false))
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setLightmapState(LIGHTMAP)
                            .setOverlayState(OVERLAY)
                            .setCullState(CULL)
                            // 只写颜色不写深度（原版半透明实体同款）：否则半透明面写入 depthtex，
                            // 光影（deferred）下会遮挡后续光照/阴影，表现为"光线无法穿透 alpha<1 模型"
                            .setWriteMaskState(COLOR_WRITE)
                            .createCompositeState(true)));

    /**
     * 三角形实体渲染（MASK 镂空，B1 收尾·事项 5）：alpha-test cutout。
     * 用 rendertype_entity_cutout shader（内部 alpha<0.5 discard，读贴图 alpha 通道镂空，
     * 如铁丝网/树叶），与 BLEND 半透明的区别是"硬裁剪"而非"混合"。
     */
    public static final Function<ResourceLocation, RenderType> ENTITY_TRIANGLES_CUTOUT = Util.memoize(location ->
            create("polaris_objuilder_entity_triangles_cutout", DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.TRIANGLES, 1_536, false, false,
                    CompositeState.builder()
                            .setShaderState(new ShaderStateShard(GameRenderer::getRendertypeEntityCutoutShader))
                            .setTextureState(new RenderStateShard.TextureStateShard(location, false, false))
                            .setTransparencyState(NO_TRANSPARENCY)
                            .setLightmapState(LIGHTMAP)
                            .setOverlayState(OVERLAY)
                            .setCullState(CULL)
                            .createCompositeState(false)));

    /** 三角形实体渲染（MASK 镂空，双面 NO_CULL） */
    public static final Function<ResourceLocation, RenderType> ENTITY_TRIANGLES_CUTOUT_DOUBLE_SIDED = Util.memoize(location ->
            create("polaris_objuilder_entity_triangles_cutout_double_sided", DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.TRIANGLES, 1_536, false, false,
                    CompositeState.builder()
                            .setShaderState(new ShaderStateShard(GameRenderer::getRendertypeEntityCutoutShader))
                            .setTextureState(new RenderStateShard.TextureStateShard(location, false, false))
                            .setTransparencyState(NO_TRANSPARENCY)
                            .setLightmapState(LIGHTMAP)
                            .setOverlayState(OVERLAY)
                            .setCullState(NO_CULL)
                            .createCompositeState(false)));

    /** 双面三角形实体渲染（doubleSided 材质，NO_CULL 不剔除背面），不透明 */
    public static final Function<ResourceLocation, RenderType> ENTITY_TRIANGLES_DOUBLE_SIDED = Util.memoize(location ->
            create("polaris_objuilder_entity_triangles_double_sided", DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.TRIANGLES, 1_536, false, false,
                    CompositeState.builder()
                            .setShaderState(new ShaderStateShard(GameRenderer::getRendertypeEntitySolidShader))
                            .setTextureState(new RenderStateShard.TextureStateShard(location, false, false))
                            .setTransparencyState(NO_TRANSPARENCY)
                            .setLightmapState(LIGHTMAP)
                            .setOverlayState(OVERLAY)
                            .setCullState(NO_CULL)
                            .createCompositeState(false)));

    /** 双面三角形实体渲染（doubleSided 材质），半透明 */
    public static final Function<ResourceLocation, RenderType> ENTITY_TRIANGLES_TRANSLUCENT_DOUBLE_SIDED = Util.memoize(location ->
            create("polaris_objuilder_entity_triangles_translucent_double_sided", DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.TRIANGLES, 1_536, false, true,
                    CompositeState.builder()
                            .setShaderState(new ShaderStateShard(GameRenderer::getRendertypeEntityTranslucentShader))
                            .setTextureState(new RenderStateShard.TextureStateShard(location, false, false))
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setLightmapState(LIGHTMAP)
                            .setOverlayState(OVERLAY)
                            .setCullState(NO_CULL)
                            // 只写颜色不写深度（同上，避免光影下半透明面遮挡光照）
                            .setWriteMaskState(COLOR_WRITE)
                            .createCompositeState(true)));

    /**
     * 三角形实体渲染（半透明 + <b>写深度</b>，2.1.15）。
     *
     * <p>专用于"贴图驱动半透明"（glTF {@code alphaMode=BLEND} 且透明度来自 base 贴图 alpha 通道，
     * 例如灯罩玻璃）。与 {@link #ENTITY_TRIANGLES_TRANSLUCENT} 的唯一区别是<b>写深度</b>：
     * 不写深度时，玻璃后面的不透明面（灯罩里的自发光灯泡）会通过深度测试被画到玻璃之上 ——
     * 实测就是"光斑浮在灯罩/其它模型之上"（原版方块有几何且写深度，故能正常遮挡）。
     * 标量 alpha 的半透明材质仍走不写深度的旧类型（2.1.10 的"光影下不遮挡后方光照"设计）。
     */
    public static final Function<ResourceLocation, RenderType> ENTITY_TRIANGLES_TRANSLUCENT_DEPTH = Util.memoize(location ->
            create("polaris_objuilder_entity_triangles_translucent_depth", DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.TRIANGLES, 1_536, false, true,
                    CompositeState.builder()
                            .setShaderState(new ShaderStateShard(GameRenderer::getRendertypeEntityTranslucentShader))
                            .setTextureState(new RenderStateShard.TextureStateShard(location, false, false))
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setLightmapState(LIGHTMAP)
                            .setOverlayState(OVERLAY)
                            .setCullState(CULL)
                            .createCompositeState(true)));

    /** 三角形实体渲染（半透明 + 写深度 + 双面 NO_CULL），语义同 {@link #ENTITY_TRIANGLES_TRANSLUCENT_DEPTH} */
    public static final Function<ResourceLocation, RenderType> ENTITY_TRIANGLES_TRANSLUCENT_DOUBLE_SIDED_DEPTH = Util.memoize(location ->
            create("polaris_objuilder_entity_triangles_translucent_double_sided_depth", DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.TRIANGLES, 1_536, false, true,
                    CompositeState.builder()
                            .setShaderState(new ShaderStateShard(GameRenderer::getRendertypeEntityTranslucentShader))
                            .setTextureState(new RenderStateShard.TextureStateShard(location, false, false))
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setLightmapState(LIGHTMAP)
                            .setOverlayState(OVERLAY)
                            .setCullState(NO_CULL)
                            .createCompositeState(true)));
}
