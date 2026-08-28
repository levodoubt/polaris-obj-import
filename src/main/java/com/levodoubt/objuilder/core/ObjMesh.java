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
    /** map_Kd 贴图文件路径（可为 null）——兼容旧单材质；多材质见 materials */
    public String texturePath = null;
    /** 材质列表（多材质支持）：按解析顺序，索引即 materialId */
    public final List<Material> materials = new ArrayList<>();

    /** 材质：名称 + map_Kd 贴图路径（可为 null）+ map_d alpha 贴图路径（可为 null，MASK 镂空用）+ Kd 漫反射颜色（默认白）+ Ke 自发光颜色（默认黑=无）+ Ks/Ns 高光（PBR specular 标量）+ d 透明度 */
    public static class Material {
        public final String name;
        public final String mapKd;
        /** MTL map_d：alpha 贴图（B1 收尾·事项 5 OBJ MASK 镂空；map_Kd 同图时 base 自带 alpha 通道） */
        public final String mapD;
        public final float kdR, kdG, kdB;
        public final float keR, keG, keB;
        /** 高光色（Ks，Blender 默认 0.5）；高光指数（Ns，Blender 默认 0 = 无高光） */
        public final float ksR, ksG, ksB, ns;
        /** 溶解/透明度（MTL d，1=不透明，<1=半透明） */
        public final float d;

        public Material(String name, String mapKd) {
            this(name, mapKd, null, 1f, 1f, 1f, 0f, 0f, 0f, 0.5f, 0.5f, 0.5f, 0f, 1f);
        }

        public Material(String name, String mapKd, float kdR, float kdG, float kdB) {
            this(name, mapKd, null, kdR, kdG, kdB, 0f, 0f, 0f, 0.5f, 0.5f, 0.5f, 0f, 1f);
        }

        public Material(String name, String mapKd, float kdR, float kdG, float kdB,
                        float keR, float keG, float keB) {
            this(name, mapKd, null, kdR, kdG, kdB, keR, keG, keB, 0.5f, 0.5f, 0.5f, 0f, 1f);
        }

        public Material(String name, String mapKd, float kdR, float kdG, float kdB,
                        float keR, float keG, float keB,
                        float ksR, float ksG, float ksB, float ns) {
            this(name, mapKd, null, kdR, kdG, kdB, keR, keG, keB, ksR, ksG, ksB, ns, 1f);
        }

        public Material(String name, String mapKd, float kdR, float kdG, float kdB,
                        float keR, float keG, float keB,
                        float ksR, float ksG, float ksB, float ns, float d) {
            this(name, mapKd, null, kdR, kdG, kdB, keR, keG, keB, ksR, ksG, ksB, ns, d);
        }

        public Material(String name, String mapKd, String mapD, float kdR, float kdG, float kdB,
                        float keR, float keG, float keB,
                        float ksR, float ksG, float ksB, float ns, float d) {
            this.name = name;
            this.mapKd = mapKd;
            this.mapD = mapD;
            this.kdR = kdR;
            this.kdG = kdG;
            this.kdB = kdB;
            this.keR = keR;
            this.keG = keG;
            this.keB = keB;
            this.ksR = ksR;
            this.ksG = ksG;
            this.ksB = ksB;
            this.ns = ns;
            this.d = d;
        }
    }

    /** 三角形面：3 个顶点索引 + 3 个 UV 索引 + 3 个法线索引（-1 = 无）+ 材质索引（-1 = 无材质） */
    public static class Face {
        public final int v0, v1, v2;
        public final int t0, t1, t2;
        public final int n0, n1, n2;
        /** 材质索引（-1 = 无材质），指向 mesh.materials */
        public int materialId = -1;

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
