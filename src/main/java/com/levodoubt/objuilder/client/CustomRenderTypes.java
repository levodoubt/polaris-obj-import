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
 * 这里提供 TRIANGLES 模式 + 双面（NO_CULL）的实体渲染类型。
 */
public class CustomRenderTypes extends RenderType {
    private CustomRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode,
                              int bufferSize, boolean affectsCrumbling, boolean sortOnUpload,
                              Runnable setupState, Runnable clearState) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setupState, clearState);
    }

    /** 三角形实体渲染：保留法线（NEW_ENTITY）+ 双面 + 不透明 */
    public static final Function<ResourceLocation, RenderType> ENTITY_TRIANGLES = Util.memoize(location ->
            create("polaris_objuilder_entity_triangles", DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.TRIANGLES, 1_536, false, false,
                    CompositeState.builder()
                            .setShaderState(new ShaderStateShard(GameRenderer::getRendertypeEntitySolidShader))
                            .setTextureState(new RenderStateShard.TextureStateShard(location, false, false))
                            .setTransparencyState(NO_TRANSPARENCY)
                            .setLightmapState(LIGHTMAP)
                            .setOverlayState(OVERLAY)
                            .setCullState(NO_CULL)
                            .createCompositeState(false)));
}
