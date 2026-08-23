package com.levodoubt.objuilder.core;

import java.util.ArrayList;
import java.util.List;

/** 网格数据：顶点 + UV + 三角形面 + 贴图路径 */
public class ObjMesh {
    public record Vec3(float x, float y, float z) {
        public Vec3 add(float dx, float dy, float dz) {
            return new Vec3(x + dx, y + dy, z + dz);
        }
        public Vec3 sub(Vec3 o) {
            return new Vec3(x - o.x, y - o.y, z - o.z);
        }
        public Vec3 lerp(Vec3 o, float t) {
            return new Vec3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t);
        }
    }

    public record Vec2(float u, float v) {
        public Vec2 lerp(Vec2 o, float t) {
            return new Vec2(u + (o.u - u) * t, v + (o.v - v) * t);
        }
    }

    public final List<Vec3> vertices = new ArrayList<>();
    public final List<Vec2> uvs = new ArrayList<>();
    public final List<Vec3> normals = new ArrayList<>();
    public final List<Face> faces = new ArrayList<>();
    /** map_Kd 贴图文件路径（可为 null） */
    public String texturePath = null;

    /** 三角形面：3 个顶点索引 + 3 个 UV 索引 + 3 个法线索引（-1 = 无） */
    public static class Face {
        public final int v0, v1, v2;
        public final int t0, t1, t2;
        public final int n0, n1, n2;

        public Face(int v0, int v1, int v2, int t0, int t1, int t2) {
            this(v0, v1, v2, t0, t1, t2, -1, -1, -1);
        }

        public Face(int v0, int v1, int v2, int t0, int t1, int t2, int n0, int n1, int n2) {
            this.v0 = v0; this.v1 = v1; this.v2 = v2;
            this.t0 = t0; this.t1 = t1; this.t2 = t2;
            this.n0 = n0; this.n1 = n1; this.n2 = n2;
        }

        public int v(int i) {
            return switch (i) { case 0 -> v0; case 1 -> v1; default -> v2; };
        }

        public int t(int i) {
            return switch (i) { case 0 -> t0; case 1 -> t1; default -> t2; };
        }

        public int n(int i) {
            return switch (i) { case 0 -> n0; case 1 -> n1; default -> n2; };
        }
    }
}
