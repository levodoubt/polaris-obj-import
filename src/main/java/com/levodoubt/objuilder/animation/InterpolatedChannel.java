package com.levodoubt.objuilder.animation;

import java.util.Arrays;

/**
 * 插值通道基类：关键帧时间数组 + 二分定位。
 * 照搬 MCglTF（MIT，参考模组/MCglTF-1.20.4-Forge），仅改包名，不引入 jgltf。
 * 子类实现 {@link #update} 与 {@link #getListener}（写入目标 TRS 数组）。
 */
public abstract class InterpolatedChannel {
    /** 关键帧时间（秒），升序 */
    protected final float[] timesS;

    public InterpolatedChannel(float[] timesS) {
        this.timesS = timesS;
    }

    public float[] getKeys() {
        return timesS;
    }

    /** 按给定时间更新输出（写入 getListener 返回的数组） */
    public abstract void update(float timeS);

    /** 输出目标数组（每帧被 update 写入） */
    protected abstract float[] getListener();

    /**
     * 二分定位 time 所在关键帧区间：返回前一个关键帧下标。
     * time 小于最小/大于最大时返回 0 或 keys.length-1。
     */
    public static int computeIndex(float time, float[] keys) {
        int index = Arrays.binarySearch(keys, time);
        if (index >= 0) {
            return index;
        }
        return Math.max(0, -index - 2);
    }
}
