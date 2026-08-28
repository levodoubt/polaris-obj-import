package com.levodoubt.objuilder.animation;

/**
 * CUBICSPLINE 三次样条插值。glTF 规范：output 每个关键帧是 3 个连续值
 * （in-tangent / value / out-tangent），即 output 总长 = 3 × 关键帧数 × 分量数。
 * 照搬 MCglTF（MIT），算法源自 Khronos glTF 教程。
 */
public abstract class CubicSplineInterpolatedChannel extends InterpolatedChannel {
    /** 每帧 [in-tangent, value, out-tangent] 三个分量数组 */
    protected final float[][][] values;

    public CubicSplineInterpolatedChannel(float[] timesS, float[][][] values) {
        super(timesS);
        this.values = values;
    }

    @Override
    public void update(float timeS) {
        float[] output = getListener();
        if (timeS <= timesS[0]) {
            System.arraycopy(values[0][1], 0, output, 0, output.length);
        } else if (timeS >= timesS[timesS.length - 1]) {
            System.arraycopy(values[timesS.length - 1][1], 0, output, 0, output.length);
        } else {
            int previousIndex = computeIndex(timeS, timesS);
            int nextIndex = previousIndex + 1;

            float local = timeS - timesS[previousIndex];
            float delta = timesS[nextIndex] - timesS[previousIndex];
            float alpha = local / delta;
            float alpha2 = alpha * alpha;
            float alpha3 = alpha2 * alpha;

            // Hermite 基函数
            float aa = 2 * alpha3 - 3 * alpha2 + 1;   // h00
            float ab = alpha3 - 2 * alpha2 + alpha;   // h10
            float ac = -2 * alpha3 + 3 * alpha2;      // h01
            float ad = alpha3 - alpha2;                // h11

            float[][] previous = values[previousIndex];
            float[][] next = values[nextIndex];

            float[] previousPoint = previous[1];        // 前一帧 value
            float[] nextPoint = next[1];                // 后一帧 value
            float[] previousOutputTangent = previous[2];// 前一帧 out-tangent
            float[] nextInputTangent = next[0];         // 后一帧 in-tangent

            for (int i = 0; i < output.length; i++) {
                float p = previousPoint[i];
                float pt = previousOutputTangent[i] * delta;
                float n = nextPoint[i];
                float nt = nextInputTangent[i] * delta;
                output[i] = aa * p + ab * pt + ac * n + ad * nt;
            }
        }
    }
}
