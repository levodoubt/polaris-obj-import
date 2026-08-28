package com.levodoubt.objuilder.animation;

/**
 * 四元数 SLERP 插值（rotation 用）。处理 q 与 -q 同旋转：取夹角小的方向（短路径）。
 * 照搬 MCglTF（MIT），算法源自 javax.vecmath.Quat4f。
 */
public abstract class SphericalLinearInterpolatedChannel extends InterpolatedChannel {
    /** 每帧一个四元数 [x,y,z,w] */
    protected final float[][] values;

    public SphericalLinearInterpolatedChannel(float[] timesS, float[][] values) {
        super(timesS);
        this.values = values;
    }

    @Override
    public void update(float timeS) {
        float[] output = getListener();
        if (timeS <= timesS[0]) {
            System.arraycopy(values[0], 0, output, 0, output.length);
        } else if (timeS >= timesS[timesS.length - 1]) {
            System.arraycopy(values[timesS.length - 1], 0, output, 0, output.length);
        } else {
            int previousIndex = computeIndex(timeS, timesS);
            int nextIndex = previousIndex + 1;

            float local = timeS - timesS[previousIndex];
            float delta = timesS[nextIndex] - timesS[previousIndex];
            float alpha = local / delta;

            float[] previousPoint = values[previousIndex];
            float[] nextPoint = values[nextIndex];

            float ax = previousPoint[0];
            float ay = previousPoint[1];
            float az = previousPoint[2];
            float aw = previousPoint[3];
            float bx = nextPoint[0];
            float by = nextPoint[1];
            float bz = nextPoint[2];
            float bw = nextPoint[3];

            // 短路径：若点积 < 0，取 -q 使夹角 < 180°
            float dot = ax * bx + ay * by + az * bz + aw * bw;
            if (dot < 0) {
                bx = -bx;
                by = -by;
                bz = -bz;
                bw = -bw;
                dot = -dot;
            }
            float epsilon = 1e-6f;
            float s0, s1;
            if ((1.0f - dot) > epsilon) {
                float omega = (float) Math.acos(dot);
                float invSinOmega = 1.0f / (float) Math.sin(omega);
                s0 = (float) Math.sin((1.0f - alpha) * omega) * invSinOmega;
                s1 = (float) Math.sin(alpha * omega) * invSinOmega;
            } else {
                s0 = 1.0f - alpha;
                s1 = alpha;
            }

            output[0] = s0 * ax + s1 * bx;
            output[1] = s0 * ay + s1 * by;
            output[2] = s0 * az + s1 * bz;
            output[3] = s0 * aw + s1 * bw;
        }
    }
}
