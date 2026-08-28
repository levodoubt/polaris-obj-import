package com.levodoubt.objuilder.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.levodoubt.objuilder.core.ObjMesh;
import com.levodoubt.objuilder.core.Voxelizer;
import com.levodoubt.objuilder.entity.DomainEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

/**
 * 共面域实体渲染器：把域的三角形网格直绘到世界。
 * - 顶点格式 POSITION_COLOR_TEX_LIGHTMAP_OVERLAY_NORMAL，保留顶点法线 → 光影下平滑光照
 * - 贴图：动态纹理（真实 GL 纹理）+ 顶点真实 UV 采样 → 逐像素贴图精度
 * - 光照：自发光 → 满光照；否则用框架传入的实体级光照 packedLight（原版实体同款），
 *   避免逐顶点采样整格光照造成的离散明暗块噪点
 * - 实体渲染器已 translate 到实体位置（域原点），域几何为域局部坐标 → 直接提交
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
        List<Voxelizer.Triangle> tris = DomainModelCache.get(modelId);
        if (tris == null || tris.isEmpty()) {
            if (!warnedMissing) {
                warnedMissing = true;
                com.levodoubt.objuilder.PolarisObjuilder.LOGGER.warn(
                        "[Objuilder] 域几何缺失 modelId={} cacheSize={}",
                        modelId, DomainModelCache.size());
            }
            return;
        }

        // 摆放（ref 路径）应用 yaw + scale：几何为相对中心格的局部坐标，实体位置 = 摆放坐标
        if (refPath) {
            pose.pushPose();
            pose.mulPose(Axis.YP.rotationDegrees(entity.getYRot()));
            float scale = entity.getModelScale();
            if (scale != 1.0f) {
                pose.scale(scale, scale, scale);
            }
        }

        TextureAtlasSprite fallback = DomainModelCache.fallbackSprite();

        // 无贴图时的白色回退 UV
        float fallbackU = fallback != null ? fallback.getU(0.5f) : 0f;
        float fallbackV = fallback != null ? fallback.getV(0.5f) : 0f;

        PoseStack.Pose mat = pose.last();

        // 按材质分组（保持顺序），每个材质一个 draw call（绑定各自纹理）
        Map<Integer, List<Voxelizer.Triangle>> byMaterial = new LinkedHashMap<>();
        for (Voxelizer.Triangle t : tris) {
            byMaterial.computeIfAbsent(t.materialId(), k -> new ArrayList<>()).add(t);
        }

        for (Map.Entry<Integer, List<Voxelizer.Triangle>> entry : byMaterial.entrySet()) {
            int matId = entry.getKey();
            // 该材质的纹理（null = 无贴图 → 用默认白 + 中性 PBR，避免光影下 texture_s/n 缺失产生噪点）
            ResourceLocation matTex = DomainModelCache.texture(modelId, matId);
            boolean noTex = matTex == null;
            // 该材质的漫反射颜色（无贴图材质用；有贴图则白，颜色由贴图承载）
            float[] kd = noTex ? DomainModelCache.color(modelId, matId) : null;
            // 该材质的自发光 Ke（>0 的材质发光，不受光照影响）
            float[] em = DomainModelCache.emissive(modelId, matId);
            boolean emissive = em != null;
            // 该材质的透明度（<1 = 半透明）
            float alpha = DomainModelCache.alpha(modelId, matId);
            boolean translucent = alpha < 1f;
            float cr, cg, cb;
            if (emissive) {
                float kr = kd != null ? kd[0] : 1f;
                float kg = kd != null ? kd[1] : 1f;
                float kb = kd != null ? kd[2] : 1f;
                cr = Math.min(1f, kr + em[0]);
                cg = Math.min(1f, kg + em[1]);
                cb = Math.min(1f, kb + em[2]);
            } else {
                cr = kd != null ? kd[0] : 1f;
                cg = kd != null ? kd[1] : 1f;
                cb = kd != null ? kd[2] : 1f;
            }
            // MASK 材质（alphaMode=MASK，B1 收尾·事项 5）→ alpha-test cutout 渲染（贴图 alpha 通道镂空，硬裁剪）；
            // doubleSided 材质（glTF doubleSided=true，Blender 双面导出绕序常不一致）→ 双面渲染 NO_CULL；
            // 单面材质 → CULL（避免 z-fighting）；半透明（alpha<1）→ translucent 混合
            boolean doubleSided = DomainModelCache.isDoubleSided(modelId, matId);
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

            for (Voxelizer.Triangle t : entry.getValue()) {
                ObjMesh.Vec3 fn = t.n();
                for (int i = 0; i < 3; i++) {
                    ObjMesh.Vec3 p = t.p(i);
                    ObjMesh.Vec3 vn = t.vn(i) != null ? t.vn(i) : fn;
                    // UV：无贴图材质用「按面法线选投影轴」生成梯度 UV（纯色 glb/obj 的 UV 常全 0 无梯度，
                    // 光影下 tangent 退化 → 法线贴图解码乱 → 噪点；固定 x+z/y 投影在水平面会 v 梯度为 0，
                    // 故按法线主分量选面内两轴，保证任意朝向的面都有梯度）；有贴图才用真实 UV。
                    // 注意：UV 不做逐顶点 wrap01（取小数）——纹理为 REPEAT 模式由 GPU 自动平铺；
                    // 取模会破坏同一三角形三个顶点 UV 的线性连续性（跨整数边界时插值横扫整张纹理 → 贴图拉伸 + 光影 tangent 突变阴影）
                    float u, v;
                    if (noTex) {
                        float ax = Math.abs(fn.x()), ay = Math.abs(fn.y()), az = Math.abs(fn.z());
                        if (ay >= ax && ay >= az) {
                            // 法线朝 Y（水平面）→ 用 XZ 投影
                            u = p.x();
                            v = p.z();
                        } else if (ax >= az) {
                            // 法线朝 X → 用 ZY 投影
                            u = p.z();
                            v = p.y();
                        } else {
                            // 法线朝 Z → 用 XY 投影
                            u = p.x();
                            v = p.y();
                        }
                    } else if (t.uv(i) != null) {
                        u = t.uv(i).u();
                        v = 1f - t.uv(i).v(); // glb UV 已在展平时翻转过一次，此处再翻 = 净不翻（保持 transform 后 glTF v 原样，REPEAT 平铺）
                    } else {
                        u = fallbackU;
                        v = fallbackV;
                    }
                    // 自发光：满光照（不受环境明暗影响）；否则用框架传入的实体级光照（原版实体同款）
                    int light = emissive ? LightTexture.pack(15, 15) : packedLight;
                    vc.addVertex(mat, p.x(), p.y(), p.z())
                            .setColor(cr, cg, cb, alpha) // 无贴图用 Kd 颜色，自发光加 Ke，有贴图白；alpha=材质透明度
                            .setUv(u, v)
                            .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                            .setLight(light)
                            .setNormal(mat, vn.x(), vn.y(), vn.z());
                }
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
