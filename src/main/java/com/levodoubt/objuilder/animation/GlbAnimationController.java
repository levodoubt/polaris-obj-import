package com.levodoubt.objuilder.animation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.levodoubt.objuilder.PolarisObjuilder;
import com.levodoubt.objuilder.core.GlbModel;

/**
 * 刚体动画控制器（子工程 3：触发动画 + 状态机）。
 *
 * 相比子工程 2 的无状态纯循环，本版引入【按动画（name）粒度】的播放状态机：
 * - 每动画一个 {@link AnimState}（mode LOOP/ONCE + startTime + speed + playing/done）
 * - LOOP：每 channel 按自己关键帧末时间独立取模循环（多动画并存互不干扰）
 * - ONCE：t = (now - startTime)，播完动画总时长 → done=true 停末帧（各 channel 天然 clamp 到自己末帧）
 * - IDLE（stop）：不更新该动画 channel → 输出残留 = 保持当前姿势
 * - reset：清空该动画所有 channel 输出 → flatten 回初始 TRS（初始姿势）
 *
 * 默认所有动画 PLAYING + LOOP（与子工程 2 行为一致），play(name, mode, startTime) 覆盖。
 * 输入：{@link GlbModel}（含 animations）+ 当前时间（秒）
 * 输出：每个被动画 node 的当前 TRS 覆盖（按 glTF 规范，动画 channel 覆盖 node 初始 TRS，非叠加）
 *
 * interpolation 分派：STEP → StepInterpolatedChannel；rotation 用 SLERP（短路径）；
 * translation/scale 用 LINEAR；CUBICSPLINE 统一走三次样条（output = 3×N×comps 布局）。
 */
public class GlbAnimationController {
    /** 播放模式 */
    public enum PlayMode { LOOP, ONCE }

    /** 单动画播放状态（按动画 name 粒度） */
    private static final class AnimState {
        final String name;
        final List<InterpolatedChannel> channels = new ArrayList<>();
        /** 平行数组：每 channel 关键帧末时间（秒），LOOP 独立取模用 */
        final List<Float> channelDurations = new ArrayList<>();
        /** 平行数组：每 channel 目标 node 下标（reset 清空输出用） */
        final List<Integer> nodeIndices = new ArrayList<>();
        /** 平行数组：每 channel path（translation/rotation/scale） */
        final List<String> paths = new ArrayList<>();
        /** 该动画时长 = 最大 channel 末时间（ONCE 播完判定，秒） */
        float duration = 0f;
        PlayMode mode = PlayMode.LOOP;
        /** 播放开始时间（秒；网络同步时 = 服务端 gameTime tick / 20） */
        float startTime = 0f;
        float speed = 1.0f;
        /** false = IDLE（未触发/已 stop，保持当前姿势） */
        boolean playing = true;
        /** ONCE 播完 = true（停在末帧） */
        boolean done = false;

        AnimState(String name) {
            this.name = name;
        }
    }

    /** 动画 name → 状态（保持模型内声明顺序） */
    private final Map<String, AnimState> anims = new HashMap<>();
    /** nodeIndex → 当前动画 translation（[x,y,z]） */
    private final Map<Integer, float[]> translations = new HashMap<>();
    /** nodeIndex → 当前动画 rotation（四元数 [x,y,z,w]） */
    private final Map<Integer, float[]> rotations = new HashMap<>();
    /** nodeIndex → 当前动画 scale（[x,y,z]） */
    private final Map<Integer, float[]> scales = new HashMap<>();
    /** 所有动画中最大的关键帧末时间（秒），调试/日志用 */
    private final float duration;

    public GlbAnimationController(GlbModel model) {
        this(model, 1.0f);
    }

    public GlbAnimationController(GlbModel model, float speed) {
        float maxDuration = 0;
        for (GlbModel.Animation anim : model.animations) {
            AnimState st = new AnimState(anim.name);
            float animDur = 0;
            for (GlbModel.Channel ch : anim.channels) {
                if (ch.times.length == 0 || ch.values.length == 0) {
                    PolarisObjuilder.LOGGER.warn("[Glb] 动画 '{}' channel(node{} {}) 无关键帧，跳过",
                            anim.name, ch.nodeIndex, ch.path);
                    continue;
                }
                animDur = Math.max(animDur, ch.times[ch.times.length - 1]);
                st.channels.add(buildChannel(ch));
                st.channelDurations.add(ch.times[ch.times.length - 1]);
                st.nodeIndices.add(ch.nodeIndex);
                st.paths.add(ch.path);
            }
            if (st.channels.isEmpty()) continue; // 空动画不登记
            st.duration = animDur;
            anims.put(anim.name, st);
            maxDuration = Math.max(maxDuration, animDur);
        }
        this.duration = maxDuration;
    }

    /** 按 channel 的 interpolation/path 构建对应插值通道（listener 写回输出 map） */
    private InterpolatedChannel buildChannel(GlbModel.Channel ch) {
        int comps = ch.components();
        int keyCount = ch.times.length;
        String interp = ch.interpolation == null ? "LINEAR" : ch.interpolation.toUpperCase();
        switch (interp) {
            case "STEP" -> {
                float[][] v = new float[keyCount][comps];
                for (int k = 0; k < keyCount; k++) System.arraycopy(ch.values, k * comps, v[k], 0, comps);
                return new StepInterpolatedChannel(ch.times, v) {
                    @Override protected float[] getListener() { return outputFor(ch); }
                };
            }
            case "CUBICSPLINE" -> {
                // glTF 规范：output = 3×N×comps（in-tangent / value / out-tangent）
                float[][][] v = new float[keyCount][3][comps];
                for (int k = 0; k < keyCount; k++) {
                    for (int i = 0; i < 3; i++) {
                        System.arraycopy(ch.values, (k * 3 + i) * comps, v[k][i], 0, comps);
                    }
                }
                return new CubicSplineInterpolatedChannel(ch.times, v) {
                    @Override protected float[] getListener() { return outputFor(ch); }
                };
            }
            default -> { // LINEAR（rotation 走 SLERP，其余走普通线性）
                float[][] v = new float[keyCount][comps];
                for (int k = 0; k < keyCount; k++) System.arraycopy(ch.values, k * comps, v[k], 0, comps);
                if ("rotation".equals(ch.path)) {
                    return new SphericalLinearInterpolatedChannel(ch.times, v) {
                        @Override protected float[] getListener() { return outputFor(ch); }
                    };
                }
                return new LinearInterpolatedChannel(ch.times, v) {
                    @Override protected float[] getListener() { return outputFor(ch); }
                };
            }
        }
    }

    /** listener：获取该 channel 的输出缓冲数组 */
    private float[] outputFor(GlbModel.Channel ch) {
        return switch (ch.path) {
            case "rotation" -> rotations.computeIfAbsent(ch.nodeIndex, k -> new float[4]);
            case "translation" -> translations.computeIfAbsent(ch.nodeIndex, k -> new float[3]);
            case "scale" -> scales.computeIfAbsent(ch.nodeIndex, k -> new float[3]);
            default -> new float[0];
        };
    }

    /**
     * 更新到世界时间 nowTime（秒）。按动画状态机计算各动画局部时间并更新其 channel：
     * - IDLE（playing=false）：不更新 → 输出残留 = 保持当前姿势
     * - LOOP：每 channel 按自己关键帧末时间独立取模
     * - ONCE：t = now - startTime，播完动画总时长 → done=true 停末帧
     */
    public void update(float nowTime) {
        for (AnimState st : anims.values()) {
            if (!st.playing) continue; // IDLE：保持当前姿势
            for (int i = 0; i < st.channels.size(); i++) {
                InterpolatedChannel ch = st.channels.get(i);
                float cd = st.channelDurations.get(i);
                float ct;
                if (st.mode == PlayMode.LOOP) {
                    ct = nowTime * st.speed;
                    if (cd > 0) ct = ct % cd; else ct = 0f;
                } else { // ONCE
                    if (st.done) {
                        ct = cd; // 播完：保持末帧
                    } else {
                        float lt = (nowTime - st.startTime) * st.speed;
                        if (lt <= 0f) {
                            ct = 0f; // 起始瞬间：首帧
                        } else if (lt >= st.duration) {
                            st.done = true;
                            ct = cd;
                        } else {
                            ct = lt;
                        }
                    }
                }
                ch.update(ct);
            }
        }
        // rotation 输出归一化（SLERP/CUBICSPLINE 浮点误差可能偏离单位长度）
        for (float[] q : rotations.values()) {
            normalizeQuat(q);
        }
    }

    /** 播放指定动画：mode + startTime + speed 覆盖当前状态。返回是否找到该动画 */
    public boolean play(String name, PlayMode mode, float startTime, float speed) {
        AnimState st = anims.get(name);
        if (st == null) return false;
        st.mode = mode;
        st.startTime = startTime;
        st.speed = speed;
        st.playing = true;
        st.done = false;
        return true;
    }

    /** 播放指定动画（speed 默认 1.0） */
    public boolean play(String name, PlayMode mode, float startTime) {
        return play(name, mode, startTime, 1.0f);
    }

    /** 停止：回 IDLE，保持当前姿势（输出残留保留） */
    public void stop(String name) {
        AnimState st = anims.get(name);
        if (st != null) st.playing = false;
    }

    /** 重置：回初始姿势（清空该动画所有 channel 输出 → flatten 走初始 TRS） */
    public void reset(String name) {
        AnimState st = anims.get(name);
        if (st == null) return;
        for (int i = 0; i < st.nodeIndices.size(); i++) {
            int node = st.nodeIndices.get(i);
            switch (st.paths.get(i)) {
                case "rotation" -> rotations.remove(node);
                case "translation" -> translations.remove(node);
                case "scale" -> scales.remove(node);
            }
        }
        st.playing = false;
        st.done = false;
    }

    /** 是否存在指定动画 */
    public boolean hasAnimation(String name) {
        return anims.containsKey(name);
    }

    /** 暂停所有动画（回 IDLE，保持当前姿势）；用于"初始不自动播放"的场景（全功能摆放：门默认关，避免每帧重展平噪点） */
    public void pauseAll() {
        for (AnimState st : anims.values()) {
            st.playing = false;
            st.done = false;
        }
    }

    public boolean hasAnimations() {
        return !anims.isEmpty();
    }

    /** 是否有动画正在播放（playing=true 且未播完）。用于判断是否需每帧重展平（静止则跳过，避免几何浮点微抖 → 噪点/拖影） */
    public boolean isAnimating() {
        for (AnimState st : anims.values()) {
            if (st.playing && (st.mode == PlayMode.LOOP || !st.done)) return true;
        }
        return false;
    }

    /** 全局动画时长（秒）；无动画时为 0 */
    public float duration() {
        return duration;
    }

    /** node 当前动画 translation（null = 该 node 无 translation 动画） */
    public float[] translation(int nodeIndex) {
        return translations.get(nodeIndex);
    }

    /** node 当前动画 rotation（null = 无） */
    public float[] rotation(int nodeIndex) {
        return rotations.get(nodeIndex);
    }

    /** node 当前动画 scale（null = 无） */
    public float[] scale(int nodeIndex) {
        return scales.get(nodeIndex);
    }

    private static void normalizeQuat(float[] q) {
        float len = (float) Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        if (len > 1e-6f) {
            q[0] /= len;
            q[1] /= len;
            q[2] /= len;
            q[3] /= len;
        } else {
            q[3] = 1f; // 退化 → 单位四元数
        }
    }
}
