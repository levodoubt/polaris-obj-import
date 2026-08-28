package com.levodoubt.objuilder.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.levodoubt.objuilder.PolarisObjuilder;
import com.levodoubt.objuilder.animation.GlbAnimationController;
import com.levodoubt.objuilder.core.GlbModel;
import com.levodoubt.objuilder.core.ObjMesh;
import com.levodoubt.objuilder.core.Voxelizer;
import com.levodoubt.objuilder.network.GlbAnimPayload;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

/**
 * glb 动画模型管理器（客户端，子工程 2 + 子工程 3）。
 *
 * - 登记带动画的 glb 模型（modelId → 运行时数据：GlbModel + 动画控制器 + 固定中心格）
 * - 每帧由客户端事件驱动：更新动画时间 → 重展平几何 → 更新 DomainModelCache
 *   （方案 A：每帧重展平，渲染器每帧从缓存取几何，无需改渲染器）
 * - 中心格在首次静态展平确定并固定，动画期间模型绕该中心旋转/平移不因 AABB 漂移跳动
 * - 子工程 3：触发动画（play once/loop / stop / reset）本地触发 + 网络包回调。
 *   网络包不含 modelId（服务端不知客户端本地模型 id）→ 按动画名在所有注册模型中匹配触发。
 */
public class GlbAnimationManager {
    /** 动画模型运行时数据 */
    public static final class AnimatedModel {
        public final GlbModel model;
        public final GlbAnimationController anim;
        public final float scale;
        /** 固定中心格（首次静态展平的 AABB 中心） */
        public final int ox, oy, oz;
        public final TextureAtlasSprite sprite;
        public final Map<Integer, ResourceLocation> texMap;
        /** 几何是否需要（重新）展平：动画活跃时每帧重展平；静止后置 false，避免每帧浮点微抖 → 噪点/拖影 */
        public boolean needsRebuild = true;

        AnimatedModel(GlbModel model, GlbAnimationController anim, float scale,
                      int ox, int oy, int oz, TextureAtlasSprite sprite,
                      Map<Integer, ResourceLocation> texMap) {
            this.model = model;
            this.anim = anim;
            this.scale = scale;
            this.ox = ox; this.oy = oy; this.oz = oz;
            this.sprite = sprite;
            this.texMap = texMap;
        }
    }

    /** modelId → 动画运行时数据（保持注册顺序） */
    private static final Map<Integer, AnimatedModel> MODELS = new LinkedHashMap<>();

    private GlbAnimationManager() {
    }

    /**
     * 登记一个 glb 模型并启动循环动画：首次静态展平确定固定中心格，随后立即以动画 t=0 姿势更新几何。
     * autoPlay=false 时注册后所有动画暂停（IDLE 初始姿势）——全功能摆放（objplace）路径用它：
     * 门默认关闭且几何静止（跳过每帧重展平 → 避免浮点微抖光影噪点），收到 /glbanim 命令才播放。
     * 旧流程（/glbdomain 等）用 autoPlay=true（默认 LOOP 循环，向后兼容）。
     * 模型无动画时返回 false，调用方保持静态路径（不注册）。
     */
    public static boolean register(int modelId, GlbModel model, float scale,
                                   TextureAtlasSprite sprite, Map<Integer, ResourceLocation> texMap,
                                   boolean autoPlay) {
        GlbAnimationController anim = new GlbAnimationController(model);
        if (!anim.hasAnimations()) {
            return false;
        }
        if (!autoPlay) {
            anim.pauseAll(); // 初始静止（保持初始姿势），几何就绪后不再每帧重展平
        }
        GlbModel.FlattenResult flat = model.flatten(scale); // 初始姿势 → 固定中心格
        MODELS.put(modelId, new AnimatedModel(model, anim, scale, flat.ox(), flat.oy(), flat.oz(),
                sprite, texMap));
        tick(0f); // 立即以动画首帧刷新几何（与后续 tick 一致，避免首帧跳变）
        return true;
    }

    /** 登记并默认自动循环播放（旧流程向后兼容） */
    public static boolean register(int modelId, GlbModel model, float scale,
                                   TextureAtlasSprite sprite, Map<Integer, ResourceLocation> texMap) {
        return register(modelId, model, scale, sprite, texMap, true);
    }

    /** 是否有正在播放的动画模型 */
    public static boolean hasAnimations() {
        return !MODELS.isEmpty();
    }

    /**
     * 每 tick 调用：按游戏时间（秒）更新所有动画模型几何。
     * 重展平 → 单域整体三角形 → {@link DomainModelCache#updateGeometry}（只动几何，不动纹理）。
     * 静止（无动画播放）的模型跳过重展平：避免每帧重建几何的浮点微抖 → 光影法线噪点 + TAA 拖影。
     */
    public static void tick(float timeS) {
        for (Map.Entry<Integer, AnimatedModel> e : MODELS.entrySet()) {
            AnimatedModel am = e.getValue();
            am.anim.update(timeS);
            boolean animating = am.anim.isAnimating();
            if (!animating && !am.needsRebuild) continue; // 静止且几何已就绪 → 跳过
            GlbModel.FlattenResult flat = am.model.flatten(am.scale, am.anim, am.ox, am.oy, am.oz);
            // 实体位置已在 spawn 时 = 基准点 + 中心格；此处域原点填 0（几何为相对中心的局部坐标）
            Voxelizer.Domain domain = new Voxelizer.Domain(0, 0, 0, 0,
                    new ObjMesh.Vec3(0, 1, 0), flat.triangles());
            DomainModelCache.updateGeometry(e.getKey(), List.of(domain));
            am.needsRebuild = animating; // 活跃 → 下帧继续重展平；静止 → 本次已展平，标记就绪
        }
    }

    /** 移除某模型的动画播放（后续清理命令用） */
    public static void remove(int modelId) {
        MODELS.remove(modelId);
    }

    /** 清空全部动画播放 */
    public static void clear() {
        MODELS.clear();
    }

    // ===================== 子工程 3：触发动画 =====================

    /** 网络包回调（客户端主线程）：按 action 分派到对应动画；placementId>=0 精确触发该摆放，否则按名全局 */
    public static void onPayload(GlbAnimPayload payload) {
        switch (payload.action()) {
            case GlbAnimPayload.ACT_PLAY -> {
                GlbAnimationController.PlayMode mode =
                        payload.mode() >= 0 && payload.mode() < GlbAnimationController.PlayMode.values().length
                                ? GlbAnimationController.PlayMode.values()[payload.mode()]
                                : GlbAnimationController.PlayMode.LOOP;
                // 服务端 gameTime（tick）→ 秒，与渲染时间 (gameTime+partial)/20 对齐，误差仅 partialTick
                float startTime = payload.serverGameTime() / 20.0f;
                if (payload.placementId() >= 0) {
                    // B1 收尾·事项 4：精确触发某个摆放（多同名门独立开关）
                    Integer modelId = PlacementModelLoader.modelIdByPid(payload.placementId());
                    if (modelId != null) {
                        trigger(modelId, payload.name(), mode, startTime, payload.speed());
                    } else {
                        PolarisObjuilder.LOGGER.warn("[Glb] 触发动画 '{}' 未找到摆放 pid={}（可能未加载/已释放）",
                                payload.name(), payload.placementId());
                    }
                } else {
                    triggerByName(payload.name(), mode, startTime, payload.speed());
                }
            }
            case GlbAnimPayload.ACT_STOP -> {
                if (payload.placementId() >= 0) {
                    stopByPid(payload.placementId(), payload.name());
                } else {
                    stopByName(payload.name());
                }
            }
            case GlbAnimPayload.ACT_RESET -> {
                if (payload.placementId() >= 0) {
                    resetByPid(payload.placementId(), payload.name());
                } else {
                    resetByName(payload.name());
                }
            }
            default -> PolarisObjuilder.LOGGER.warn("[Glb] 未知动画控制 action: {}", payload.action());
        }
    }

    /** 按摆放精确停止（B1 收尾·事项 4：多同名门独立控制） */
    public static void stopByPid(int placementId, String name) {
        AnimatedModel am = modelByPid(placementId);
        if (am != null && am.anim.hasAnimation(name)) {
            am.anim.stop(name);
            am.needsRebuild = true;
        }
    }

    /** 按摆放精确重置（B1 收尾·事项 4：多同名门独立控制） */
    public static void resetByPid(int placementId, String name) {
        AnimatedModel am = modelByPid(placementId);
        if (am != null && am.anim.hasAnimation(name)) {
            am.anim.reset(name);
            am.needsRebuild = true;
        }
    }

    /** 摆放 id → 动画运行时数据（null = 未加载/已释放） */
    private static AnimatedModel modelByPid(int placementId) {
        Integer modelId = PlacementModelLoader.modelIdByPid(placementId);
        return modelId == null ? null : MODELS.get(modelId);
    }

    /**
     * 按 modelId + 动画名触发播放（本地 API 层；startTime 秒，建议用服务端 gameTime/20 基准）。
     * 剧情引擎若走 ServiceLoader API 对接，用此入口精确指定模型。
     */
    public static void trigger(int modelId, String name, GlbAnimationController.PlayMode mode,
                               float startTime, float speed) {
        AnimatedModel am = MODELS.get(modelId);
        if (am == null) {
            PolarisObjuilder.LOGGER.warn("[Glb] 触发动画失败：modelId {} 未注册", modelId);
            return;
        }
        if (!am.anim.play(name, mode, startTime, speed)) {
            PolarisObjuilder.LOGGER.warn("[Glb] modelId {} 无动画 '{}'", modelId, name);
        } else {
            am.needsRebuild = true;
        }
    }

    /** 触发动画（speed 默认 1.0） */
    public static void trigger(int modelId, String name, GlbAnimationController.PlayMode mode, float startTime) {
        trigger(modelId, name, mode, startTime, 1.0f);
    }

    /**
     * 按动画名触发：所有注册模型中名字匹配的动画（网络收包路径，服务端不知 modelId）。
     * 跨模型动画名应避免重复，否则会同时触发（按名字广播的已知限制）。
     */
    public static void triggerByName(String name, GlbAnimationController.PlayMode mode,
                                     float startTime, float speed) {
        boolean hit = false;
        for (AnimatedModel am : MODELS.values()) {
            if (am.anim.play(name, mode, startTime, speed)) {
                hit = true;
                am.needsRebuild = true;
            }
        }
        if (!hit) {
            PolarisObjuilder.LOGGER.warn("[Glb] 触发动画 '{}' 未匹配任何已导入模型", name);
        }
    }

    /** 按动画名停止：保持当前姿势（回 IDLE） */
    public static void stopByName(String name) {
        boolean hit = false;
        for (AnimatedModel am : MODELS.values()) {
            if (am.anim.hasAnimation(name)) {
                am.anim.stop(name);
                am.needsRebuild = true;
                hit = true;
            }
        }
        if (!hit) {
            PolarisObjuilder.LOGGER.warn("[Glb] 停止动画 '{}' 未匹配任何已导入模型", name);
        }
    }

    /** 按动画名重置：回初始姿势 */
    public static void resetByName(String name) {
        boolean hit = false;
        for (AnimatedModel am : MODELS.values()) {
            if (am.anim.hasAnimation(name)) {
                am.anim.reset(name);
                am.needsRebuild = true;
                hit = true;
            }
        }
        if (!hit) {
            PolarisObjuilder.LOGGER.warn("[Glb] 重置动画 '{}' 未匹配任何已导入模型", name);
        }
    }
}
