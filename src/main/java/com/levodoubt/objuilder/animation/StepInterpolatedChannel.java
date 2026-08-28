package com.levodoubt.objuilder.animation;

/**
 * STEP 阶跃插值：取时间所在关键帧的值，不做插值。
 * 照搬 MCglTF（MIT）。
 */
public abstract class StepInterpolatedChannel extends InterpolatedChannel {
    /** 每帧一个值数组（如 translation = [x,y,z]，rotation = [x,y,z,w]） */
    protected final float[][] values;

    public StepInterpolatedChannel(float[] timesS, float[][] values) {
        super(timesS);
        this.values = values;
    }

    @Override
    public void update(float timeS) {
        float[] output = getListener();
        System.arraycopy(values[computeIndex(timeS, timesS)], 0, output, 0, output.length);
    }
}
