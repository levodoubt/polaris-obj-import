package com.levodoubt.objuilder.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 切分器：把三角网格按 MC 网格离散为"子片"，并聚合成模板族。
 *
 * 算法（首版简化）：
 *  1. 每个三角形按"质心"归属到一个格子，坐标转格内局部 [0,1]
 *  2. 对每个格子的几何做量化哈希，相同哈希 → 同一模板族（共享模型定义）
 *  3. 产出：模板族几何（局部坐标）+ 摆放数据（格坐标 + 族 id）
 */
public class Voxelizer {
    public record Triangle(ObjMesh.Vec3 a, ObjMesh.Vec3 b, ObjMesh.Vec3 c, ObjMesh.Vec3 n) {
        public ObjMesh.Vec3 p(int i) {
            return switch (i) { case 0 -> a; case 1 -> b; default -> c; };
        }
    }

    public record Placement(int x, int y, int z, int pieceId) {}

    public record Stats(int vertCount, int triCount, int gridCells, int pieceCount, long totalRenderTris) {}

    public record Result(Map<Integer, List<Triangle>> geometry, List<Placement> placements, Stats stats) {}

    /** @param quant 量化精度（顶点坐标 × quant 取整），越大模板族越细 */
    public static Result voxelize(ObjMesh mesh, int quant) {
        // 格 → 格内三角形（局部坐标）
        Map<Long, List<Triangle>> cellTris = new HashMap<>();
        Map<Long, int[]> cellPos = new HashMap<>();
        for (int[] f : mesh.faces) {
            ObjMesh.Vec3 v0 = mesh.vertices.get(f[0]);
            ObjMesh.Vec3 v1 = mesh.vertices.get(f[1]);
            ObjMesh.Vec3 v2 = mesh.vertices.get(f[2]);
            ObjMesh.Vec3 n = normal(v0, v1, v2);
            // AABB 覆盖的所有格子（同三角形可跨多个格）
            int g0x = (int) Math.floor(Math.min(v0.x(), Math.min(v1.x(), v2.x())));
            int g1x = (int) Math.floor(Math.max(v0.x(), Math.max(v1.x(), v2.x())));
            int g0y = (int) Math.floor(Math.min(v0.y(), Math.min(v1.y(), v2.y())));
            int g1y = (int) Math.floor(Math.max(v0.y(), Math.max(v1.y(), v2.y())));
            int g0z = (int) Math.floor(Math.min(v0.z(), Math.min(v1.z(), v2.z())));
            int g1z = (int) Math.floor(Math.max(v0.z(), Math.max(v1.z(), v2.z())));
            for (int gx = g0x; gx <= g1x; gx++) {
                for (int gy = g0y; gy <= g1y; gy++) {
                    for (int gz = g0z; gz <= g1z; gz++) {
                        // 精确裁剪：三角形裁剪到该格 AABB（世界坐标），相邻格共享一致的边界边 → 消除 T-junction 缝隙
                        List<ObjMesh.Vec3> poly = clipTriangleToBox(v0, v1, v2, gx, gy, gz);
                        if (poly.size() < 3) continue;
                        long key = cellKey(gx, gy, gz);
                        List<Triangle> list = cellTris.computeIfAbsent(key, k -> new ArrayList<>());
                        // 凸多边形扇形三角化，转格内局部坐标
                        for (int i = 1; i + 1 < poly.size(); i++) {
                            list.add(new Triangle(
                                    poly.get(0).add(-gx, -gy, -gz),
                                    poly.get(i).add(-gx, -gy, -gz),
                                    poly.get(i + 1).add(-gx, -gy, -gz),
                                    n));
                        }
                        cellPos.putIfAbsent(key, new int[]{gx, gy, gz});
                    }
                }
            }
        }

        // 聚类：量化哈希 → 模板族
        Map<Long, Integer> pieceIds = new HashMap<>();
        Map<Integer, List<Triangle>> geometry = new HashMap<>();
        List<Placement> placements = new ArrayList<>();
        for (Map.Entry<Long, List<Triangle>> e : cellTris.entrySet()) {
            long hash = hashGeometry(e.getValue(), quant);
            int id = pieceIds.computeIfAbsent(hash, k -> pieceIds.size());
            geometry.computeIfAbsent(id, k -> new ArrayList<>()).addAll(e.getValue());
            int[] p = cellPos.get(e.getKey());
            placements.add(new Placement(p[0], p[1], p[2], id));
        }

        // 统计
        long totalTris = 0;
        for (Map.Entry<Integer, List<Triangle>> e : geometry.entrySet()) {
            long instances = placements.stream().filter(pl -> pl.pieceId() == e.getKey()).count();
            totalTris += instances * e.getValue().size();
        }
        Stats stats = new Stats(mesh.vertices.size(), mesh.faces.size(), placements.size(),
                geometry.size(), totalTris);
        return new Result(geometry, placements, stats);
    }

    private static ObjMesh.Vec3 normal(ObjMesh.Vec3 a, ObjMesh.Vec3 b, ObjMesh.Vec3 c) {
        float ux = b.x() - a.x(), uy = b.y() - a.y(), uz = b.z() - a.z();
        float vx = c.x() - a.x(), vy = c.y() - a.y(), vz = c.z() - a.z();
        float nx = uy * vz - uz * vy;
        float ny = uz * vx - ux * vz;
        float nz = ux * vy - uy * vx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-6f) return new ObjMesh.Vec3(0, 1, 0);
        return new ObjMesh.Vec3(nx / len, ny / len, nz / len);
    }

    /** 三角形裁剪到格 AABB [gx,gx+1]×[gy,gy+1]×[gz,gz+1]（世界坐标），返回凸多边形顶点 */
    private static List<ObjMesh.Vec3> clipTriangleToBox(ObjMesh.Vec3 a, ObjMesh.Vec3 b, ObjMesh.Vec3 c,
                                                       int gx, int gy, int gz) {
        List<ObjMesh.Vec3> poly = new ArrayList<>(3);
        poly.add(a); poly.add(b); poly.add(c);
        poly = clipPlane(poly, 0, gx, true);      // x >= gx
        poly = clipPlane(poly, 0, gx + 1, false); // x <= gx+1
        poly = clipPlane(poly, 1, gy, true);      // y >= gy
        poly = clipPlane(poly, 1, gy + 1, false);
        poly = clipPlane(poly, 2, gz, true);      // z >= gz
        poly = clipPlane(poly, 2, gz + 1, false);
        return poly;
    }

    /** Sutherland-Hodgman 单平面裁剪（keepGreater=true 保留坐标 >= val 一侧） */
    private static List<ObjMesh.Vec3> clipPlane(List<ObjMesh.Vec3> poly, int axis, float val, boolean keepGreater) {
        if (poly.size() < 3) return poly;
        List<ObjMesh.Vec3> out = new ArrayList<>();
        for (int i = 0; i < poly.size(); i++) {
            ObjMesh.Vec3 cur = poly.get(i);
            ObjMesh.Vec3 next = poly.get((i + 1) % poly.size());
            float cv = coord(cur, axis);
            float nv = coord(next, axis);
            boolean cin = keepGreater ? cv >= val : cv <= val;
            boolean nin = keepGreater ? nv >= val : nv <= val;
            if (cin) out.add(cur);
            if (cin != nin) {
                float t = (val - cv) / (nv - cv);
                out.add(lerp(cur, next, t));
            }
        }
        return out;
    }

    private static float coord(ObjMesh.Vec3 v, int axis) {
        return switch (axis) { case 0 -> v.x(); case 1 -> v.y(); default -> v.z(); };
    }

    private static ObjMesh.Vec3 lerp(ObjMesh.Vec3 a, ObjMesh.Vec3 b, float t) {
        return new ObjMesh.Vec3(
                a.x() + (b.x() - a.x()) * t,
                a.y() + (b.y() - a.y()) * t,
                a.z() + (b.z() - a.z()) * t);
    }

    private static long cellKey(int x, int y, int z) {
        return (x * 73856093L) ^ (y * 19349663L) ^ (z * 83492791L);
    }

    /** FNV-1a 量化哈希：顶点坐标 × quant 取整后逐字节散列（含绕序，法线不同则族不同） */
    private static long hashGeometry(List<Triangle> tris, int quant) {
        long h = 0xcbf29ce484222325L;
        for (Triangle t : tris) {
            for (int i = 0; i < 3; i++) {
                ObjMesh.Vec3 p = t.p(i);
                h = mix(h, Math.round(p.x() * quant));
                h = mix(h, Math.round(p.y() * quant));
                h = mix(h, Math.round(p.z() * quant));
            }
        }
        return h;
    }

    private static long mix(long h, long v) {
        h ^= v & 0xFF; h *= 0x100000001b3L;
        h ^= (v >>> 8) & 0xFF; h *= 0x100000001b3L;
        h ^= (v >>> 16) & 0xFF; h *= 0x100000001b3L;
        h ^= (v >>> 24) & 0xFF; h *= 0x100000001b3L;
        return h;
    }
}
