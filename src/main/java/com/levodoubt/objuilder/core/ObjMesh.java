package com.levodoubt.objuilder.core;

/** 简单网格数据：顶点 + 三角形面 */
public class ObjMesh {
    public record Vec3(float x, float y, float z) {
        public Vec3 add(float dx, float dy, float dz) {
            return new Vec3(x + dx, y + dy, z + dz);
        }
    }

    public final java.util.List<Vec3> vertices = new java.util.ArrayList<>();
    /** 每个面 3 个顶点索引（逆时针/外法线） */
    public final java.util.List<int[]> faces = new java.util.ArrayList<>();
}
