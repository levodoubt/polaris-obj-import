package com.levodoubt.objuilder.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.levodoubt.objuilder.PolarisObjuilder;

/**
 * 切分器：把三角网格按 MC 网格离散为"子片"，并聚合成模板族。
 * 每个子片三角形携带 UV（裁剪时随顶点插值），供渲染阶段采样贴图/烘顶点色。
 */
public class Voxelizer {
    public record Triangle(ObjMesh.Vec3 a, ObjMesh.Vec3 b, ObjMesh.Vec3 c, ObjMesh.Vec3 n,
                           ObjMesh.Vec3 na, ObjMesh.Vec3 nb, ObjMesh.Vec3 nc,
                           ObjMesh.Vec2 uva, ObjMesh.Vec2 uvb, ObjMesh.Vec2 uvc,
                           int materialId) {
        /** 兼容旧构造：无材质（materialId = -1） */
        public Triangle(ObjMesh.Vec3 a, ObjMesh.Vec3 b, ObjMesh.Vec3 c, ObjMesh.Vec3 n,
                        ObjMesh.Vec3 na, ObjMesh.Vec3 nb, ObjMesh.Vec3 nc,
                        ObjMesh.Vec2 uva, ObjMesh.Vec2 uvb, ObjMesh.Vec2 uvc) {
            this(a, b, c, n, na, nb, nc, uva, uvb, uvc, -1);
        }

        public ObjMesh.Vec3 p(int i) {
            return switch (i) { case 0 -> a; case 1 -> b; default -> c; };
        }

        public ObjMesh.Vec2 uv(int i) {
            return switch (i) { case 0 -> uva; case 1 -> uvb; default -> uvc; };
        }

        /** 顶点法线（平滑着色），无顶点法线时回退面法线 */
        public ObjMesh.Vec3 vn(int i) {
            return switch (i) { case 0 -> na; case 1 -> nb; default -> nc; };
        }
    }

    public record Placement(int x, int y, int z, int pieceId) {}

    public record Stats(int vertCount, int triCount, int gridCells, int pieceCount, long totalRenderTris,
                        int interiorCount) {}

    public record Result(Map<Integer, List<Triangle>> geometry, List<Placement> placements,
                         List<int[]> interior, Stats stats) {}

    /** 进度回调：处理面数 → 总面数（0.0~1.0 百分比），null 表示禁用 */
    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int done, int total);
    }

    /** @param quant 量化精度（顶点坐标 × quant 取整），越大模板族越细 */
    public static Result voxelize(ObjMesh mesh, int quant) {
        return voxelize(mesh, quant, null);
    }

    /** 带进度回调的切分 */
    public static Result voxelize(ObjMesh mesh, int quant, ProgressCallback progress) {
        Map<Long, List<Triangle>> cellTris = new HashMap<>();
        Map<Long, int[]> cellPos = new HashMap<>();
        int totalFaces = mesh.faces.size();
        int faceCount = 0;
        for (ObjMesh.Face f : mesh.faces) {
            ObjMesh.Vec3 v0 = mesh.vertices.get(f.v0);
            ObjMesh.Vec3 v1 = mesh.vertices.get(f.v1);
            ObjMesh.Vec3 v2 = mesh.vertices.get(f.v2);
            ObjMesh.Vec2 u0 = f.t0 >= 0 ? mesh.uvs.get(f.t0) : null;
            ObjMesh.Vec2 u1 = f.t1 >= 0 ? mesh.uvs.get(f.t1) : null;
            ObjMesh.Vec2 u2 = f.t2 >= 0 ? mesh.uvs.get(f.t2) : null;
            ObjMesh.Vec3 n0 = f.n0 >= 0 ? mesh.normals.get(f.n0) : null;
            ObjMesh.Vec3 n1 = f.n1 >= 0 ? mesh.normals.get(f.n1) : null;
            ObjMesh.Vec3 n2 = f.n2 >= 0 ? mesh.normals.get(f.n2) : null;
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
                        List<VertexUVN> poly = clipTriangleToBox(v0, v1, v2, u0, u1, u2, n0, n1, n2, gx, gy, gz);
                        if (poly.size() < 3) continue;
                        long key = cellKey(gx, gy, gz);
                        List<Triangle> list = cellTris.computeIfAbsent(key, k -> new ArrayList<>());
                        for (int i = 1; i + 1 < poly.size(); i++) {
                            list.add(new Triangle(
                                    poly.get(0).pos.add(-gx, -gy, -gz),
                                    poly.get(i).pos.add(-gx, -gy, -gz),
                                    poly.get(i + 1).pos.add(-gx, -gy, -gz),
                                    n,
                                    poly.get(0).vn,
                                    poly.get(i).vn,
                                    poly.get(i + 1).vn,
                                    poly.get(0).uv,
                                    poly.get(i).uv,
                                    poly.get(i + 1).uv));
                        }
                        cellPos.putIfAbsent(key, new int[]{gx, gy, gz});
                    }
                }
            }
            faceCount++;
            if (progress != null && (faceCount % 5000 == 0 || faceCount == totalFaces)) {
                progress.onProgress(faceCount, totalFaces);
            }
        }

        // 聚类：位置哈希 → 模板族（每格独立，几何不混叠）
        // 注：旋转归一化聚类（hashNormalizedGeometry）已实测无效（曲面碎片形状互不相同），
        // 且会把不同格子的原始几何混入同一族 → 渲染解离分散。改回位置哈希。
        Map<Long, Integer> pieceIds = new HashMap<>();
        Map<Integer, List<Triangle>> geometry = new HashMap<>();
        List<Placement> placements = new ArrayList<>();
        for (Map.Entry<Long, List<Triangle>> e : cellTris.entrySet()) {
            List<Triangle> tris = e.getValue();
            long hash = hashGeometry(tris, quant);
            int id = pieceIds.computeIfAbsent(hash, k -> pieceIds.size());
            geometry.computeIfAbsent(id, k -> new ArrayList<>()).addAll(tris);
            int[] p = cellPos.get(e.getKey());
            placements.add(new Placement(p[0], p[1], p[2], id));
        }

        // 内部格检测：被表面格完全包围的格 → 子块模式填石头
        // 大包围盒（如放大模型 1600 万格）跳过——洪水填充遍历成本极高且无意义（薄壳无封闭内部）
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (int[] p : cellPos.values()) {
            minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
            minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]);
            minZ = Math.min(minZ, p[2]); maxZ = Math.max(maxZ, p[2]);
        }
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        List<int[]> interior;
        if (volume > 1_000_000L) {
            PolarisObjuilder.LOGGER.info("[Voxelizer] 包围盒体积 {} 超过阈值，跳过内部格检测", volume);
            interior = new ArrayList<>();
        } else {
            interior = findInteriorCells(cellTris.keySet(), minX, maxX, minY, maxY, minZ, maxZ);
        }

        // 统计（渲染三角形 = Σ 每族几何三角形 × 实例数；收敛后此值应骤降）
        long totalTris = 0;
        int[] instanceCount = new int[pieceIds.size()];
        for (Placement pl : placements) {
            instanceCount[pl.pieceId()]++;
        }
        for (Map.Entry<Integer, List<Triangle>> e : geometry.entrySet()) {
            totalTris += (long) instanceCount[e.getKey()] * e.getValue().size();
        }
        Stats stats = new Stats(mesh.vertices.size(), mesh.faces.size(), placements.size(),
                geometry.size(), totalTris, interior.size());
        return new Result(geometry, placements, interior, stats);
    }

    /** 位置 + 法线 + UV 组合（裁剪时同步插值） */
    private record VertexUVN(ObjMesh.Vec3 pos, ObjMesh.Vec3 vn, ObjMesh.Vec2 uv) {
    }

    private static List<int[]> findInteriorCells(Set<Long> surface, int minX, int maxX,
                                                 int minY, int maxY, int minZ, int maxZ) {
        Set<Long> reachable = new java.util.HashSet<>();
        java.util.ArrayDeque<long[]> queue = new java.util.ArrayDeque<>();
        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int y = minY - 1; y <= maxY + 1; y++) {
                for (int z = minZ - 1; z <= maxZ + 1; z++) {
                    if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) {
                        long key = cellKey(x, y, z);
                        if (!surface.contains(key) && reachable.add(key)) {
                            queue.add(new long[]{x, y, z});
                        }
                    }
                }
            }
        }
        int[] dx = {1, -1, 0, 0, 0, 0};
        int[] dy = {0, 0, 1, -1, 0, 0};
        int[] dz = {0, 0, 0, 0, 1, -1};
        while (!queue.isEmpty()) {
            long[] c = queue.poll();
            for (int d = 0; d < 6; d++) {
                int nx = (int) c[0] + dx[d];
                int ny = (int) c[1] + dy[d];
                int nz = (int) c[2] + dz[d];
                if (nx < minX - 1 || nx > maxX + 1 || ny < minY - 1 || ny > maxY + 1
                        || nz < minZ - 1 || nz > maxZ + 1) continue;
                long key = cellKey(nx, ny, nz);
                if (!surface.contains(key) && reachable.add(key)) {
                    queue.add(new long[]{nx, ny, nz});
                }
            }
        }
        List<int[]> interior = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    long key = cellKey(x, y, z);
                    if (!surface.contains(key) && !reachable.contains(key)) {
                        interior.add(new int[]{x, y, z});
                    }
                }
            }
        }
        return interior;
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

    /** 三角形裁剪到格 AABB（世界坐标），UV/法线随顶点同步插值 */
    private static List<VertexUVN> clipTriangleToBox(ObjMesh.Vec3 a, ObjMesh.Vec3 b, ObjMesh.Vec3 c,
                                                     ObjMesh.Vec2 ua, ObjMesh.Vec2 ub, ObjMesh.Vec2 uc,
                                                     ObjMesh.Vec3 na, ObjMesh.Vec3 nb, ObjMesh.Vec3 nc,
                                                     int gx, int gy, int gz) {
        List<VertexUVN> poly = new ArrayList<>(3);
        poly.add(new VertexUVN(a, na, ua));
        poly.add(new VertexUVN(b, nb, ub));
        poly.add(new VertexUVN(c, nc, uc));
        poly = clipPlane(poly, 0, gx, true);
        poly = clipPlane(poly, 0, gx + 1, false);
        poly = clipPlane(poly, 1, gy, true);
        poly = clipPlane(poly, 1, gy + 1, false);
        poly = clipPlane(poly, 2, gz, true);
        poly = clipPlane(poly, 2, gz + 1, false);
        return poly;
    }

    private static List<VertexUVN> clipPlane(List<VertexUVN> poly, int axis, float val, boolean keepGreater) {
        if (poly.size() < 3) return poly;
        List<VertexUVN> out = new ArrayList<>();
        for (int i = 0; i < poly.size(); i++) {
            VertexUVN cur = poly.get(i);
            VertexUVN next = poly.get((i + 1) % poly.size());
            float cv = coord(cur.pos, axis);
            float nv = coord(next.pos, axis);
            boolean cin = keepGreater ? cv >= val : cv <= val;
            boolean nin = keepGreater ? nv >= val : nv <= val;
            if (cin) out.add(cur);
            if (cin != nin) {
                float t = (val - cv) / (nv - cv);
                ObjMesh.Vec3 pos = cur.pos.lerp(next.pos, t);
                ObjMesh.Vec2 uv = (cur.uv != null && next.uv != null) ? cur.uv.lerp(next.uv, t) : null;
                ObjMesh.Vec3 vn = (cur.vn != null && next.vn != null) ? cur.vn.lerp(next.vn, t) : null;
                out.add(new VertexUVN(pos, vn, uv));
            }
        }
        return out;
    }

    private static float coord(ObjMesh.Vec3 v, int axis) {
        return switch (axis) { case 0 -> v.x(); case 1 -> v.y(); default -> v.z(); };
    }

    /** 无碰撞格编码：每坐标 21 位（支持 ±1,048,575），按位拼接 */
    private static long cellKey(int x, int y, int z) {
        return ((long) x & 0x1FFFFF) | (((long) y & 0x1FFFFF) << 21) | (((long) z & 0x1FFFFF) << 42);
    }

    /**
     * 旋转归一化哈希：把三角形集合法线对齐到 +Y 后量化哈希。
     * 主法线 = 所有面法线平均；构造旋转使主法线 → (0,1,0)，对每个顶点旋转后哈希。
     * 相同形状的曲面（不同朝向/位置）归一化后哈希相同 → 归为一族。
     */
    private static long hashNormalizedGeometry(List<Triangle> tris, int quant) {
        // 平均法线（主朝向）
        float ax = 0, ay = 0, az = 0;
        for (Triangle t : tris) {
            ax += t.n().x(); ay += t.n().y(); az += t.n().z();
        }
        float len = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        if (len < 1e-6f) { ax = 0; ay = 1; az = 0; } else { ax /= len; ay /= len; az /= len; }
        // 旋转：主法线 → +Y。用罗德里格斯旋转，或简化为：绕 X/Z 的复合
        // 构造正交基：newY = 主法线，newZ = 任选垂直向量，newX = cross
        // 这里用简化：只做绕 X 轴（pitch）与绕 Z 轴（roll）修正
        float pitch = (float) Math.atan2(az, ay);        // 绕 X 使 yz 分量为正
        float roll = (float) Math.atan2(ax, Math.sqrt(ay * ay + az * az)); // 绕 Z 修正 x
        long h = 0xcbf29ce484222325L;
        for (Triangle t : tris) {
            ObjMesh.Vec3 n = rotate(t.n(), pitch, roll);
            h = mixNormal(h, n);
            for (int i = 0; i < 3; i++) {
                ObjMesh.Vec3 p = rotate(t.p(i), pitch, roll);
                h = mix(h, Math.round(p.x() * quant));
                h = mix(h, Math.round(p.y() * quant));
                h = mix(h, Math.round(p.z() * quant));
            }
        }
        return h;
    }

    /** 绕 X 轴 pitch、绕 Z 轴 roll 的旋转 */
    private static ObjMesh.Vec3 rotate(ObjMesh.Vec3 v, float pitch, float roll) {
        float x = v.x(), y = v.y(), z = v.z();
        // 绕 X
        float cosP = (float) Math.cos(pitch), sinP = (float) Math.sin(pitch);
        float y1 = y * cosP - z * sinP;
        float z1 = y * sinP + z * cosP;
        // 绕 Z
        float cosR = (float) Math.cos(roll), sinR = (float) Math.sin(roll);
        float x2 = x * cosR - y1 * sinR;
        float y2 = x * sinR + y1 * cosR;
        return new ObjMesh.Vec3(x2, y2, z1);
    }

    private static long mixNormal(long h, ObjMesh.Vec3 n) {
        h = mix(h, Math.round(n.x() * 127));
        h = mix(h, Math.round(n.y() * 127));
        h = mix(h, Math.round(n.z() * 127));
        return h;
    }

    /** FNV-1a 量化哈希：顶点坐标 × quant 取整后逐字节散列 */
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

    // ===================== 共面域合并（纯视觉模式） =====================
    //
    // 目标：把"一格一几何"的碎片按【共面连通域】合并成大平面，每个域一份几何。
    // 关键：不做格裁剪，直接在 OBJ 原始三角形上做"共面连通域"聚类。
    //   - 相邻三角形（共享边）+ 法线相近 → 同一平面 → 同一域
    //   - 每个域保留原始三角形（几何量 = 原始面数，骤降 99%+）
    //   - 无裁剪 → 无 T-junction 裂缝 → 无"连接紊乱"
    // 旋转大平面（非轴对齐）无法用格方块表达 → 域几何走实体渲染（BER），
    // 顺带保留顶点法线 → 光影下平滑光照。

    /** 共面域：一个共面连通区域。tris 为域局部坐标（相对域原点 ox,oy,oz），法线为域平面法线 */
    public record Domain(int id, int ox, int oy, int oz, ObjMesh.Vec3 normal, List<Triangle> tris) {}

    /** 自发光格：自发光材质（Ke>0）三角形覆盖的格（世界格坐标）+ 光照等级 1~15 */
    public record LightCell(int x, int y, int z, int level) {}

    /** 共面域合并结果：域列表 + 统计 + 自发光格 */
    public record DomainResult(List<Domain> domains, int totalTris, int vertCount, int faceCount,
                               List<LightCell> lightCells) {
    }

    /**
     * 纯视觉切分：把整个 OBJ 模型作为【单个整体】渲染（不切格、不分域）。
     * - 几何 = 原始三角形（局部坐标，相对模型 AABB 中心）
     * - 实体位置 = 模型 AABB 中心；1 个实体承载全部几何
     * - 保留顶点法线（无则回退面法线）→ 光影下平滑光照
     * 关键：实体位置取【模型中心】而非最小角，使实体离模型各部分距离最小，
     * 避免大模型因实体位置落在模型一角、超出渲染/模拟距离而被剔除 → 模型消失。
     */
    public static DomainResult visualize(ObjMesh mesh, ProgressCallback progress) {
        int n = mesh.faces.size();

        // 几何基准 = 模型原点（OBJ 文件坐标原点 0,0,0），非 AABB 中心——
        // 摆放坐标对齐模型原点（与 glb flatten 修复一致），大模型剔除由 noCulling + getBoundingBoxForCulling 兜底
        int ox = 0, oy = 0, oz = 0;

        List<Triangle> tris = new ArrayList<>(n);
        // 自发光格收集：自发光材质（Ke>0）三角形顶点所在格（世界格坐标），level 取 Ke 最大分量
        Map<Long, LightCell> lightMap = new HashMap<>();
        for (int fi = 0; fi < n; fi++) {
            ObjMesh.Face f = mesh.faces.get(fi);
            ObjMesh.Vec3 wa = mesh.vertices.get(f.v0);
            ObjMesh.Vec3 wb = mesh.vertices.get(f.v1);
            ObjMesh.Vec3 wc = mesh.vertices.get(f.v2);
            ObjMesh.Vec3 a = wa.add(-ox, -oy, -oz);
            ObjMesh.Vec3 b = wb.add(-ox, -oy, -oz);
            ObjMesh.Vec3 c = wc.add(-ox, -oy, -oz);
            ObjMesh.Vec3 fn = normal(a, b, c);
            ObjMesh.Vec3 na = f.n0 >= 0 ? mesh.normals.get(f.n0) : fn;
            ObjMesh.Vec3 nb = f.n1 >= 0 ? mesh.normals.get(f.n1) : fn;
            ObjMesh.Vec3 nc = f.n2 >= 0 ? mesh.normals.get(f.n2) : fn;
            ObjMesh.Vec2 ua = f.t0 >= 0 ? mesh.uvs.get(f.t0) : null;
            ObjMesh.Vec2 ub = f.t1 >= 0 ? mesh.uvs.get(f.t1) : null;
            ObjMesh.Vec2 uc = f.t2 >= 0 ? mesh.uvs.get(f.t2) : null;
            int matId = f.materialId >= 0 ? f.materialId : -1;
            tris.add(new Triangle(a, b, c, fn, na, nb, nc, ua, ub, uc, matId));

            // 自发光材质 → 标记三角形顶点所在格为发光格
            if (matId >= 0 && matId < mesh.materials.size()) {
                ObjMesh.Material m = mesh.materials.get(matId);
                if (m.keR > 0 || m.keG > 0 || m.keB > 0) {
                    float mx = Math.max(m.keR, Math.max(m.keG, m.keB));
                    int level = Math.max(1, Math.min(15, (int) Math.round(mx)));
                    addLightCell(lightMap, (int) Math.floor(wa.x()), (int) Math.floor(wa.y()), (int) Math.floor(wa.z()), level);
                    addLightCell(lightMap, (int) Math.floor(wb.x()), (int) Math.floor(wb.y()), (int) Math.floor(wb.z()), level);
                    addLightCell(lightMap, (int) Math.floor(wc.x()), (int) Math.floor(wc.y()), (int) Math.floor(wc.z()), level);
                }
            }
            if (progress != null && (fi % 5000 == 0 || fi == n - 1)) {
                progress.onProgress(fi + 1, n);
            }
        }

        List<Domain> domains = tris.isEmpty() ? List.of()
                : List.of(new Domain(0, ox, oy, oz, new ObjMesh.Vec3(0, 1, 0), tris));
        return new DomainResult(domains, tris.size(), mesh.vertices.size(), n,
                new ArrayList<>(lightMap.values()));
    }

    /** 合并发光格：同格取最大光照等级 */
    private static void addLightCell(Map<Long, LightCell> map, int x, int y, int z, int level) {
        long key = cellKey(x, y, z);
        LightCell prev = map.get(key);
        if (prev == null || prev.level() < level) {
            map.put(key, new LightCell(x, y, z, level));
        }
    }
}
