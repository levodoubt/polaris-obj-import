package com.levodoubt.objuilder.core;

/** 程序生成球体网格（测试模型），1 Blender 单位 = 1 格。绕序保证外法线朝外。 */
public class SphereGenerator {
    public static ObjMesh sphere(float radius, int stacks, int slices, float cx, float cy, float cz) {
        ObjMesh mesh = new ObjMesh();
        // 顶点：北极单点 + (stacks-1) 个中间环 × slices + 南极单点
        mesh.vertices.add(new ObjMesh.Vec3(cx, cy + radius, cz));       // 北极 idx 0
        for (int i = 1; i < stacks; i++) {
            double phi = Math.PI * i / stacks;
            double sinPhi = Math.sin(phi), cosPhi = Math.cos(phi);
            for (int j = 0; j < slices; j++) {
                double theta = 2 * Math.PI * j / slices;
                mesh.vertices.add(new ObjMesh.Vec3(
                        cx + radius * (float) (sinPhi * Math.cos(theta)),
                        cy + radius * (float) cosPhi,
                        cz + radius * (float) (sinPhi * Math.sin(theta))));
            }
        }
        int southIdx = mesh.vertices.size();
        mesh.vertices.add(new ObjMesh.Vec3(cx, cy - radius, cz));       // 南极 idx southIdx

        // 环带（四边形 → 两三角形，外法线绕序）
        // 中间环共 stacks-1 个 → 相邻环对 stacks-2 对；南北极单独用扇处理
        for (int i = 0; i < stacks - 2; i++) {
            int base = 1 + i * slices;
            int baseN = 1 + (i + 1) * slices;
            for (int j = 0; j < slices; j++) {
                int a = base + j;
                int b = base + (j + 1) % slices;
                int c = baseN + (j + 1) % slices;
                int d = baseN + j;
                mesh.faces.add(new int[]{a, b, c});
                mesh.faces.add(new int[]{a, c, d});
            }
        }
        // 北极扇
        for (int j = 0; j < slices; j++) {
            mesh.faces.add(new int[]{0, 1 + (j + 1) % slices, 1 + j});
        }
        // 南极扇
        int lastRing = 1 + (stacks - 2) * slices;
        for (int j = 0; j < slices; j++) {
            mesh.faces.add(new int[]{southIdx, lastRing + j, lastRing + (j + 1) % slices});
        }
        return mesh;
    }
}
