package com.levodoubt.objuilder.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.joml.Matrix4f;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * 静态模型 VRAM 常驻渲染缓冲（2.1.11 性能优化·方案 A）。
 *
 * <p>背景：实体渲染路线的几何每帧都要「CPU 写顶点 → 上传 GPU」。本类把静态模型的每个材质 run
 * 烘焙成一份 {@link MeshData}，上传到 {@code VertexBuffer(Usage.STATIC)}（GPU 常驻），之后每帧只
 * bind + drawWithShader，彻底消除「每帧 CPU 顶点提交 + 每帧 GPU 上传」。
 *
 * <p>关键点：
 * <ul>
 *   <li>顶点烘焙在<b>模型局部空间</b>（addVertex(x,y,z) 不乘矩阵）；每帧用显式模型视图矩阵
 *       {@code pose.last().pose()}（含相机 + 实体 yaw/scale）在 drawWithShader 里变换——实体渲染阶段
 *       RenderSystem 的模型视图矩阵是恒等（顶点已被 PoseStack 预变换），不能用 RenderType.draw(MeshData) 的隐式矩阵。</li>
 *   <li>着色器状态用 {@link RenderType#setupRenderState()} / {@link RenderType#clearRenderState()}（public，
 *       走 RenderStateShard，Iris 兼容）；shader 以 getter 形式持有、每帧现取并经 {@code RenderSystem.getShader()}
 *       解析——【不可缓存 ShaderInstance 实例】：Iris 重进世界/维度切换会销毁重建 pipeline（全部 ExtendedShader
 *       与其 GlFramebuffer），缓存实例变野引用 → "Tried to use a destroyed GlResource" 崩溃。</li>
 *   <li>光照烘焙在构建期（自发光 → 满光照；否则用实体 packedLight）。与 MC 区块网格一样是「烘焙光」，
 *       动态光照变化不会自动反映（静态装饰模型可接受；后续可按 packedLight 变化触发重建）。</li>
 * </ul>
 */
public final class StaticModelBuffer {
    private static final class Part {
        final VertexBuffer vbo;
        final RenderType renderType;
        /** shader getter（如 GameRenderer::getRendertypeEntitySolidShader）——【禁止缓存 ShaderInstance 实例】：
         * Iris 在重进世界/维度切换时销毁重建整个 pipeline（所有 ExtendedShader 及其 GlFramebuffer），
         * 缓存的实例会变成野引用 → drawWithShader 时 apply() 绑定已销毁 framebuffer → 崩溃。每帧现取。 */
        final Supplier<ShaderInstance> shader;

        Part(VertexBuffer vbo, RenderType renderType, Supplier<ShaderInstance> shader) {
            this.vbo = vbo;
            this.renderType = renderType;
            this.shader = shader;
        }
    }

    private final List<Part> parts = new ArrayList<>();
    /** 构建时烘焙的实体光照（非自发光材质用）；用于检测光照变化触发重建 */
    private final int bakedLight;

    private StaticModelBuffer(int bakedLight) {
        this.bakedLight = bakedLight;
    }

    /** 是否因实体光照变化需要重建（非自发光材质烘焙了 packedLight） */
    public boolean lightChanged(int packedLight) {
        return packedLight != bakedLight;
    }

    /** 从紧凑网格构建每材质一份 GPU 常驻 VBO；几何为空返回 null */
    public static StaticModelBuffer build(int modelId, RenderMesh mesh, boolean shadowPass, int packedLight) {
        if (mesh == null || mesh.vertexCount == 0) return null;
        StaticModelBuffer result = new StaticModelBuffer(packedLight);
        int vertexSize = DefaultVertexFormat.NEW_ENTITY.getVertexSize();
        for (int run = 0; run < mesh.runCount; run++) {
            int matId = mesh.runMaterial[run];
            int start = mesh.runStart[run];
            int len = mesh.runLength[run];

            ResourceLocation matTex = DomainModelCache.texture(modelId, matId);
            boolean noTex = matTex == null;
            float alpha = DomainModelCache.alpha(modelId, matId);
            boolean translucent = alpha < 1f;
            boolean doubleSided = DomainModelCache.isDoubleSided(modelId, matId) && !shadowPass;
            boolean masked = DomainModelCache.isMasked(modelId, matId);
            RenderType rt;
            Supplier<ShaderInstance> shader;
            if (masked) {
                rt = (doubleSided ? CustomRenderTypes.ENTITY_TRIANGLES_CUTOUT_DOUBLE_SIDED
                        : CustomRenderTypes.ENTITY_TRIANGLES_CUTOUT)
                        .apply(noTex ? DomainModelCache.defaultTexture() : matTex);
                shader = GameRenderer::getRendertypeEntityCutoutShader;
            } else {
                rt = (doubleSided
                        ? (translucent ? CustomRenderTypes.ENTITY_TRIANGLES_TRANSLUCENT_DOUBLE_SIDED : CustomRenderTypes.ENTITY_TRIANGLES_DOUBLE_SIDED)
                        : (translucent ? CustomRenderTypes.ENTITY_TRIANGLES_TRANSLUCENT : CustomRenderTypes.ENTITY_TRIANGLES)).apply(
                        noTex ? DomainModelCache.defaultTexture() : matTex);
                shader = translucent
                        ? GameRenderer::getRendertypeEntityTranslucentShader
                        : GameRenderer::getRendertypeEntitySolidShader;
            }

            // 颜色/光照（每 run 常量，与 CPU 路径一致）
            int packed = mesh.runColor[run];
            int cr = (packed >> 16) & 0xFF;
            int cg = (packed >> 8) & 0xFF;
            int cb = packed & 0xFF;
            int ca = (packed >>> 24) & 0xFF;
            int light = mesh.runLight[run] < 0 ? packedLight : mesh.runLight[run];

            // 构建 model-space 网格（addVertex(FFF) 不乘矩阵，局部空间；drawWithShader 时再乘 pose）
            ByteBufferBuilder bbb = new ByteBufferBuilder(len * vertexSize);
            BufferBuilder builder = new BufferBuilder(bbb, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
            int end = start + len;
            for (int vi = start; vi < end; vi++) {
                int i3 = vi * 3;
                int i2 = vi * 2;
                builder.addVertex(mesh.pos[i3], mesh.pos[i3 + 1], mesh.pos[i3 + 2])
                        .setColor(cr, cg, cb, ca)
                        .setUv(mesh.uv[i2], mesh.uv[i2 + 1])
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(light)
                        .setNormal(mesh.nrm[i3], mesh.nrm[i3 + 1], mesh.nrm[i3 + 2]);
            }
            MeshData meshData = builder.buildOrThrow();
            VertexBuffer vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
            vbo.bind();
            vbo.upload(meshData);
            VertexBuffer.unbind();
            meshData.close();
            bbb.close();
            result.parts.add(new Part(vbo, rt, shader));
        }
        return result;
    }

    /** 每帧绘制：显式矩阵 + setupRenderState/clearRenderState 包住的状态 + 每帧现取 shader */
    public void draw(PoseStack.Pose pose) {
        Matrix4f proj = RenderSystem.getProjectionMatrix();
        // 实体渲染两段式：pose.last().pose() 只含【实体变换】（相机相对平移 + yaw/scale），
        // 相机旋转在 RenderSystem 的模型视图矩阵里（CPU 路径 = addVertex(pose) 预变换 + draw 时乘 RenderSystem 矩阵）。
        // 完整模型视图 = RenderSystem.getModelViewMatrix() × pose.pose()，与 CPU 路径逐顶点等效。
        Matrix4f mv = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose.pose());
        for (Part p : parts) {
            // 每帧现取 shader（Iris pipeline 重载后 getter 返回新程序，杜绝缓存实例失效）
            ShaderInstance shader = p.shader.get();
            if (shader == null) continue;
            p.renderType.setupRenderState();
            RenderSystem.setupShaderLights(shader);
            p.vbo.bind();
            // 用 RenderSystem.getShader() 解析（与 CPU 路径 setupRenderState→getShader 同一条链）：
            // 主 pass = 该实体程序；Iris 阴影 pass 下由其重定向为 shadow 程序 → 绑定 shadow framebuffer 正确。
            ShaderInstance active = RenderSystem.getShader();
            p.vbo.drawWithShader(mv, proj, active != null ? active : shader);
            VertexBuffer.unbind();
            p.renderType.clearRenderState();
        }
    }

    /** 释放全部 GPU 缓冲（缓存释放时调用） */
    public void close() {
        for (Part p : parts) {
            p.vbo.close();
        }
        parts.clear();
    }
}
