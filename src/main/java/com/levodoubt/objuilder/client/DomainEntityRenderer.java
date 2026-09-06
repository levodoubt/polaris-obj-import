package com.levodoubt.objuilder.client;

import com.levodoubt.objuilder.entity.DomainEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * 共面域实体渲染器：把域的三角形网格直绘到世界。
 * - 顶点格式 POSITION_COLOR_TEX_LIGHTMAP_OVERLAY_NORMAL，保留顶点法线 → 光影下平滑光照
 * - 贴图：动态纹理（真实 GL 纹理）+ 顶点真实 UV 采样 → 逐像素贴图精度
 * - 光照：自发光 → 满光照；否则用框架传入的实体级光照 packedLight（原版实体同款）
 * - 实体渲染器已 translate 到实体位置（域原点），域几何为域局部坐标 → 直接提交
 *
 * <p>2.1.11 性能优化·CPU 提交批量化：不再逐三角形、逐顶点读 Triangle record，改为从
 * {@link DomainModelCache#getRenderMesh} 取预烘焙的紧凑网格（连续 float[] + 材质 run），
 * 内联 4x4/3x3 矩阵变换（消除每顶点 2 次 {@code new Vector3f()} 分配 + record 访问 + 每帧分组分配）。
 */
public class DomainEntityRenderer extends EntityRenderer<DomainEntity> {
    public DomainEntityRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
    }

    @Override
    public void render(DomainEntity entity, float entityYaw, float partialTick,
                       PoseStack pose, MultiBufferSource buffer, int packedLight) {
        // 几何来源：新流程走 modelRef（引用 → 按摆放独立懒加载缓存），旧流程走 domainId（向后兼容）
        String ref = entity.getModelRef();
        boolean refPath = ref != null && !ref.isEmpty() && entity.getPlacementId() >= 0;
        int modelId;
        if (refPath) {
            Integer id = PlacementModelLoader.modelIdOf(ref, entity.getPlacementId());
            if (id == null) {
                PlacementModelLoader.requestLoad(ref, entity.getPlacementId()); // 幂等；加载完成后下一帧自然出现
                if (!warnedMissing) {
                    warnedMissing = true;
                    com.levodoubt.objuilder.PolarisObjuilder.LOGGER.warn(
                            "[Objuilder] 摆放模型几何未就绪 ref={} pid={}（异步加载中或失败）",
                            ref, entity.getPlacementId());
                }
                return;
            }
            modelId = id;
        } else {
            modelId = entity.getDomainId();
        }
        // 先应用摆放变换（与顶点渲染完全一致），之后 pose.last() 就是模型的局部→世界矩阵。
        // 静态 VRAM 直绘与 CPU 紧凑网格两条路径都依赖它，故先 push。
        if (refPath) {
            pose.pushPose();
            pose.mulPose(Axis.YP.rotationDegrees(entity.getYRot()));
            float scale = entity.getModelScale();
            if (scale != 1.0f) {
                pose.scale(scale, scale, scale);
            }
        }

        // 阴影 pass 用细分 LOD（大面拆分，边长 ≤4 格 → 阴影稳定），主 pass 用原始网格。
        boolean shadowPass = ShadowPassDetect.isShadowPass();

        // 方案 A：静态（非动画中）模型走 GPU 常驻 VBO 直绘——每帧仅 bind + drawWithShader，
        // 消除每帧 CPU 顶点提交与 GPU 上传。动画模型返回 null → 回退下方 CPU 紧凑网格路径。
        StaticModelBuffer staticBuf = DomainModelCache.getStaticBuffer(modelId, shadowPass, packedLight);
        if (staticBuf != null) {
            staticBuf.draw(pose.last());
            if (refPath) {
                pose.popPose();
            }
            return;
        }

        RenderMesh mesh = DomainModelCache.getRenderMesh(modelId, shadowPass);
        if (mesh == null || mesh.vertexCount == 0) {
            if (!warnedMissing) {
                warnedMissing = true;
                com.levodoubt.objuilder.PolarisObjuilder.LOGGER.warn(
                        "[Objuilder] 域几何缺失 modelId={} cacheSize={}",
                        modelId, DomainModelCache.size());
            }
            if (refPath) {
                pose.popPose();
            }
            return;
        }

        // 取当前模型视图矩阵（含相机 + 实体变换）；内联变换避免 addVertex(Matrix4f)/setNormal(Pose) 的每顶点 Vector3f 分配
        PoseStack.Pose entry = pose.last();
        Matrix4f mat = entry.pose();
        Matrix3f nrm = entry.normal();

        for (int run = 0; run < mesh.runCount; run++) {
            int matId = mesh.runMaterial[run];
            // 该材质的纹理（null = 无贴图 → 用默认白 + 中性 PBR，避免光影下 texture_s/n 缺失产生噪点）
            ResourceLocation matTex = DomainModelCache.texture(modelId, matId);
            boolean noTex = matTex == null;
            // 该材质的透明度（<1 = 半透明）
            float alpha = DomainModelCache.alpha(modelId, matId);
            boolean translucent = alpha < 1f;
            // MASK 材质（alphaMode=MASK）→ alpha-test cutout；doubleSided 材质 → NO_CULL；
            // 阴影 pass 强制单面 CULL（doubleSided 薄壳两面写深度互搏 → 自阴影斑块）。
            boolean doubleSided = DomainModelCache.isDoubleSided(modelId, matId) && !shadowPass;
            boolean masked = DomainModelCache.isMasked(modelId, matId);
            RenderType rt;
            if (masked) {
                rt = (doubleSided ? CustomRenderTypes.ENTITY_TRIANGLES_CUTOUT_DOUBLE_SIDED
                        : CustomRenderTypes.ENTITY_TRIANGLES_CUTOUT)
                        .apply(noTex ? DomainModelCache.defaultTexture() : matTex);
            } else {
                rt = (doubleSided
                        ? (translucent ? CustomRenderTypes.ENTITY_TRIANGLES_TRANSLUCENT_DOUBLE_SIDED : CustomRenderTypes.ENTITY_TRIANGLES_DOUBLE_SIDED)
                        : (translucent ? CustomRenderTypes.ENTITY_TRIANGLES_TRANSLUCENT : CustomRenderTypes.ENTITY_TRIANGLES)).apply(
                        noTex ? DomainModelCache.defaultTexture() : matTex);
            }
            VertexConsumer vc = buffer.getBuffer(rt);

            // 每 run 预计算的颜色/光照（材质恒定，循环不变量）
            int packed = mesh.runColor[run];
            int cr = (packed >> 16) & 0xFF;
            int cg = (packed >> 8) & 0xFF;
            int cb = packed & 0xFF;
            int ca = (packed >>> 24) & 0xFF;
            int light = mesh.runLight[run] < 0 ? packedLight : mesh.runLight[run];

            int start = mesh.runStart[run];
            int end = start + mesh.runLength[run];
            for (int vi = start; vi < end; vi++) {
                int i3 = vi * 3;
                float x = mesh.pos[i3], y = mesh.pos[i3 + 1], z = mesh.pos[i3 + 2];
                // 内联 Matrix4f.transformPosition（列主序：m00..m02 为第一列/X 基，m30..m32 平移）
                float wx = mat.m00() * x + mat.m10() * y + mat.m20() * z + mat.m30();
                float wy = mat.m01() * x + mat.m11() * y + mat.m21() * z + mat.m31();
                float wz = mat.m02() * x + mat.m12() * y + mat.m22() * z + mat.m32();
                float xn = mesh.nrm[i3], yn = mesh.nrm[i3 + 1], zn = mesh.nrm[i3 + 2];
                // 内联 Matrix3f.transform（法线只乘旋转部分，无需归一化，与原 setNormal(Pose) 一致）
                float nx = nrm.m00() * xn + nrm.m10() * yn + nrm.m20() * zn;
                float ny = nrm.m01() * xn + nrm.m11() * yn + nrm.m21() * zn;
                float nz = nrm.m02() * xn + nrm.m12() * yn + nrm.m22() * zn;
                int i2 = vi * 2;
                vc.addVertex(wx, wy, wz)
                        .setColor(cr, cg, cb, ca)
                        .setUv(mesh.uv[i2], mesh.uv[i2 + 1])
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(light)
                        .setNormal(nx, ny, nz);
            }
        }

        if (refPath) {
            pose.popPose();
        }
    }

    private static boolean warnedMissing = false;

    @Override
    public ResourceLocation getTextureLocation(DomainEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
