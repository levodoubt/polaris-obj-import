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

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;

/**
 * 共面域实体渲染器：把域的三角形网格直绘到世界。
 * - 顶点格式 POSITION_COLOR_TEX_LIGHTMAP_OVERLAY_NORMAL，保留顶点法线 → 光影下平滑光照
 * - 贴图：动态纹理（真实 GL 纹理）+ 顶点真实 UV 采样 → 逐像素贴图精度
 * - 逐顶点光照：每个顶点采样其世界位置所在格的光照，GPU 光栅化时在三角形内插值 → 平滑光照
 * - 实体渲染器已 translate 到实体位置（域原点），域几何为域局部坐标 → 直接提交
 */
public class DomainEntityRenderer extends EntityRenderer<DomainEntity> {
    public DomainEntityRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
    }

    @Override
    public void render(DomainEntity entity, float entityYaw, float partialTick,
                       PoseStack pose, MultiBufferSource buffer, int packedLight) {
        int modelId = entity.getDomainId();
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

        TextureAtlasSprite fallback = DomainModelCache.fallbackSprite();

        // 无贴图时的白色回退 UV
        float fallbackU = fallback != null ? fallback.getU(0.5f) : 0f;
        float fallbackV = fallback != null ? fallback.getV(0.5f) : 0f;

        // 实体世界位置（脚底，静态实体 = 插值位置）
        double ex = entity.getX();
        double ey = entity.getY();
        double ez = entity.getZ();
        Level level = entity.level();
        PoseStack.Pose mat = pose.last();

        // 按材质分组（保持顺序），每个材质一个 draw call（绑定各自纹理）
        Map<Integer, List<Voxelizer.Triangle>> byMaterial = new LinkedHashMap<>();
        for (Voxelizer.Triangle t : tris) {
            byMaterial.computeIfAbsent(t.materialId(), k -> new ArrayList<>()).add(t);
        }

        for (Map.Entry<Integer, List<Voxelizer.Triangle>> entry : byMaterial.entrySet()) {
            int matId = entry.getKey();
            // 该材质的纹理（null = 无贴图 → 回退 block atlas 白色）
            ResourceLocation matTex = DomainModelCache.texture(modelId, matId);
            // 该材质的漫反射颜色（无贴图材质用；有贴图则白，颜色由贴图承载）
            float[] kd = matTex == null ? DomainModelCache.color(modelId, matId) : null;
            // 自发光 Ke（>0 的材质发光，不受光照影响）
            float[] em = DomainModelCache.emissive(modelId, matId);
            boolean emissive = em != null;
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
            RenderType rt = CustomRenderTypes.ENTITY_TRIANGLES.apply(
                    matTex != null ? matTex : TextureAtlas.LOCATION_BLOCKS);
            VertexConsumer vc = buffer.getBuffer(rt);

            for (Voxelizer.Triangle t : entry.getValue()) {
                ObjMesh.Vec3 fn = t.n();
                for (int i = 0; i < 3; i++) {
                    ObjMesh.Vec3 p = t.p(i);
                    ObjMesh.Vec3 vn = t.vn(i) != null ? t.vn(i) : fn;
                    // UV：该材质有贴图且有 UV → 真实 UV（v 翻转：OBJ 底=0 → MC 顶=0）；否则白色回退
                    float u, v;
                    if (matTex != null && t.uv(i) != null) {
                        u = wrap01(t.uv(i).u());
                        v = 1f - wrap01(t.uv(i).v());
                    } else {
                        u = fallbackU;
                        v = fallbackV;
                    }
                    // 自发光：满光照（不受环境明暗影响）；否则逐顶点采样世界光照
                    int light = emissive
                            ? LightTexture.pack(15, 15)
                            : sampleVertexLight(level, ex + p.x(), ey + p.y(), ez + p.z());
                    vc.addVertex(mat, p.x(), p.y(), p.z())
                            .setColor(cr, cg, cb, 1f) // 无贴图用 Kd 颜色，自发光加 Ke，有贴图白
                            .setUv(u, v)
                            .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                            .setLight(light)
                            .setNormal(mat, vn.x(), vn.y(), vn.z());
                }
            }
        }
    }

    /** 采样顶点世界位置所在格的光照（sky + block 打包）。GPU 光栅化时在三角形内插值 → 平滑光照 */
    private static int sampleVertexLight(Level level, double x, double y, double z) {
        BlockPos pos = BlockPos.containing(x, y, z);
        int blockLight = level.getBrightness(LightLayer.BLOCK, pos);
        int skyLight = level.getBrightness(LightLayer.SKY, pos);
        return LightTexture.pack(blockLight, skyLight);
    }

    /** UV 平铺：取小数部分（UV 超出 [0,1] 时平铺，与旧顶点色采样行为一致） */
    private static float wrap01(float v) {
        return v - (float) Math.floor(v);
    }

    private static boolean warnedMissing = false;

    @Override
    public ResourceLocation getTextureLocation(DomainEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
