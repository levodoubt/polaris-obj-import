package com.levodoubt.objuilder.core;

import java.util.ArrayList;
import java.util.List;

import com.levodoubt.objuilder.PolarisObjuilder;

/**
 * glb（glTF 2.0 二进制）解析结果模型。
 *
 * 设计目标（B1 glb 动画 · 子工程 1 静态渲染）：
 * - 保留 node 层级（子工程 2 刚体动画需按 node 做 TRS 插值，这里原样留存）
 * - mesh/primitive 顶点数据已解码为 float 数组（位置/法线/UV/索引），可独立于原始 buffer
 * - 材质 PBR 参数：本子工程只消费 baseColor（颜色/贴图）；金属/粗糙/法线/自发光暂存，子工程 4 用
 * - 贴图像素字节（bufferView 内嵌 png/jpeg），由命令层转 NativeImage
 *
 * 展平（flatten）：把整个 node 树按初始 TRS 变换合成为"单实体整体几何"
 * （相对模型 AABB 中心局部坐标），直接复用现有 DomainEntity 渲染管线。
 */
public class GlbModel {
    /** 节点：层级结构（children）+ 初始 TRS/matrix 变换 + 引用的 mesh */
    public static final class Node {
        public final String name;
        public final float[] matrix;      // 列主序 4x4，或 null
        public final float[] translation; // [x,y,z] 或 null
        public final float[] rotation;    // 四元数 [x,y,z,w] 或 null
        public final float[] scale;       // [x,y,z] 或 null
        public final int[] children;      // 子 node 索引（nodes 全局下标）
        public final int[] meshes;        // mesh 索引（空 = 纯变换节点）

        public Node(String name, float[] matrix, float[] translation, float[] rotation,
                    float[] scale, int[] children, int[] meshes) {
            this.name = name;
            this.matrix = matrix;
            this.translation = translation;
            this.rotation = rotation;
            this.scale = scale;
            this.children = children;
            this.meshes = meshes;
        }
    }

    /** 图元：一个网格的几何 + 材质引用（mode=4 TRIANGLES） */
    public static final class Primitive {
        public final int[] indices;      // 三角形顶点索引（null = 非索引网格，按序每 3 个一组）
        public final float[] positions;  // count*3
        public final float[] normals;    // count*3 或 null（渲染回退面法线）
        public final float[] uvs;        // count*2 或 null
        public final int materialIndex;  // materials 下标，-1 = 无材质
        public final int mode;           // glTF primitive mode（4 = TRIANGLES）

        public Primitive(int[] indices, float[] positions, float[] normals, float[] uvs,
                         int materialIndex, int mode) {
            this.indices = indices;
            this.positions = positions;
            this.normals = normals;
            this.uvs = uvs;
            this.materialIndex = materialIndex;
            this.mode = mode;
        }

        public int vertexCount() {
            return positions.length / 3;
        }
    }

    public static final class Mesh {
        public final String name;
        public final List<Primitive> primitives;

        public Mesh(String name, List<Primitive> primitives) {
            this.name = name;
            this.primitives = primitives;
        }
    }

    /** 动画（子工程 2：node TRS 关键帧）。一个模型可有多个动画并存，全部循环播放。 */
    public static final class Animation {
        public final String name;
        public final List<Channel> channels;

        public Animation(String name, List<Channel> channels) {
            this.name = name;
            this.channels = channels;
        }
    }

    /** 动画通道：单个 node 的单个 TRS 路径关键帧。 */
    public static final class Channel {
        /** 目标 node 下标（nodes 全局下标） */
        public final int nodeIndex;
        /** translation / rotation / scale（weights=morph 已在解析层跳过） */
        public final String path;
        /** STEP / LINEAR / CUBICSPLINE */
        public final String interpolation;
        /** 关键帧时间（秒） */
        public final float[] times;
        /** 值（扁平布局：LINEAR/STEP = N×comps；CUBICSPLINE = 3×N×comps（in-tangent/value/out-tangent）） */
        public final float[] values;

        public Channel(int nodeIndex, String path, String interpolation, float[] times, float[] values) {
            this.nodeIndex = nodeIndex;
            this.path = path;
            this.interpolation = interpolation;
            this.times = times;
            this.values = values;
        }

        /** 每帧分量数：translation/scale = 3，rotation = 4 */
        public int components() {
            return switch (path) {
                case "rotation" -> 4;
                default -> 3;
            };
        }
    }

    /** 材质：PBR 参数。本子工程只用 baseColor；金属/粗糙/法线/自发光暂存供子工程 4 */
    public static final class Material {
        public final float[] baseColorFactor; // RGBA 或 null（默认白 [1,1,1,1]）
        public final int baseColorTexture;    // texture 索引，-1 = 无
        public final float metallicFactor;
        public final float roughnessFactor;
        public final int metallicRoughnessTexture; // texture 索引（R=metalness, G=roughness），-1 = 无
        public final int normalTexture;       // texture 索引，-1 = 无
        public final float[] emissiveFactor;  // RGB 或 null（默认黑 = 无自发光）
        public final int emissiveTexture;     // texture 索引，-1 = 无
        /** 自发光强度（KHR_materials_emissive_strength 扩展 emissiveStrength，默认 1；如 Blender 导出 10） */
        public final float emissiveStrength;
        public final String alphaMode;        // OPAQUE / MASK / BLEND
        /** doubleSided：true = 双面渲染（禁用背面剔除）。glTF 语义，Blender 双面材质导出后绕序常不一致 */
        public boolean doubleSided = false;
        /** baseColorTexture 的 KHR_texture_transform（Blender Mapping 导出）：uv' = R(rot)*(uv*scale)+offset，作用于 glTF UV 空间（v 左上） */
        public float texOffsetU = 0f, texOffsetV = 0f, texScaleU = 1f, texScaleV = 1f, texRotation = 0f;

        public Material(float[] baseColorFactor, int baseColorTexture,
                        float metallicFactor, float roughnessFactor, int metallicRoughnessTexture,
                        int normalTexture,
                        float[] emissiveFactor, int emissiveTexture, float emissiveStrength,
                        String alphaMode) {
            this.baseColorFactor = baseColorFactor;
            this.baseColorTexture = baseColorTexture;
            this.metallicFactor = metallicFactor;
            this.roughnessFactor = roughnessFactor;
            this.metallicRoughnessTexture = metallicRoughnessTexture;
            this.normalTexture = normalTexture;
            this.emissiveFactor = emissiveFactor;
            this.emissiveTexture = emissiveTexture;
            this.emissiveStrength = emissiveStrength;
            this.alphaMode = alphaMode;
        }
    }

    /**
     * 碰撞盒定义（子工程 6）：Blender 里 `col:` 前缀命名的 box 物体。
     * - localAabb = 模型空间 AABB（已应用 node 自身 + 父链变换，未乘摆放 scale/yaw/pos）
     * - animBinding = `col:xxx@AnimName` 中的动画名（动态盒），null = 静态盒始终阻挡
     */
    public static final class ColliderDef {
        /** 去掉 col: 前缀后的名字（如 wall / gate_door） */
        public final String name;
        public final float minX, minY, minZ, maxX, maxY, maxZ;
        /** 绑定的动画名（@ 后缀），null = 静态碰撞盒 */
        public final String animBinding;

        public ColliderDef(String name, float minX, float minY, float minZ,
                           float maxX, float maxY, float maxZ, String animBinding) {
            this.name = name;
            this.minX = minX; this.minY = minY; this.minZ = minZ;
            this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
            this.animBinding = animBinding;
        }

        public boolean isDynamic() {
            return animBinding != null && !animBinding.isEmpty();
        }
    }

    public final String name;
    public final List<Node> nodes = new ArrayList<>();
    public final List<Mesh> meshes = new ArrayList<>();
    public final List<Material> materials = new ArrayList<>();
    public final List<Integer> sceneRoots = new ArrayList<>();
    /** 动画（子工程 2；空 = 静态模型） */
    public final List<Animation> animations = new ArrayList<>();
    /** 碰撞盒（子工程 6；空 = 无 col 盒的纯视觉模型） */
    public final List<ColliderDef> colliders = new ArrayList<>();
    /** 每个 image 的原始字节（png/jpeg），null = 无（外部 uri 等，本子工程不支持） */
    public final List<byte[]> images = new ArrayList<>();
    /** texture 索引 → image 索引（-1 = 无 source） */
    public final List<Integer> textures = new ArrayList<>();

    public GlbModel(String name) {
        this.name = name;
    }

    // ===================== 碰撞盒（子工程 6） =====================

    /**
     * 解析 `col:` 前缀 node → 模型空间 AABB 列表（colliders）。
     * 顶点应用 node 自身 + 父链的初始变换（anim=null 初始姿势），scale=1（摆放 scale/yaw 由服务端另行应用）。
     * 渲染（flatten）会跳过 col: node，此处只供服务端 objplace/恢复生成碰撞体。
     */
    public void computeColliders() {
        colliders.clear();
        for (int root : sceneRoots) {
            collectColliderAabb(root, null);
        }
        // 兜底：col node 不在 scene 树中（孤立节点）时按无父节点直接处理
        if (colliders.isEmpty()) {
            for (int i = 0; i < nodes.size(); i++) {
                Node n = nodes.get(i);
                if (n.name != null && n.name.startsWith("col:") && n.meshes.length > 0) {
                    collectColliderAabb(i, null);
                }
            }
        }
        if (!colliders.isEmpty()) {
            PolarisObjuilder.LOGGER.info("[Glb] '{}' 解析到 {} 个碰撞盒: {}",
                    name, colliders.size(),
                    colliders.stream().map(c -> c.name + (c.isDynamic() ? "@" + c.animBinding : "")).toList());
        }
    }

    /** DFS 收集 col: node 的 mesh 顶点 → 模型空间 AABB（应用父链变换） */
    private void collectColliderAabb(int nodeIdx, float[] parent) {
        Node n = nodes.get(nodeIdx);
        float[] world = composeWorld(n, nodeIdx, parent, 1.0f, null);
        if (n.name != null && n.name.startsWith("col:") && n.meshes.length > 0) {
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (int mi : n.meshes) {
                Mesh mesh = meshes.get(mi);
                for (Primitive p : mesh.primitives) {
                    if (p.positions == null || p.positions.length < 9) continue;
                    for (int v = 0; v < p.positions.length; v += 3) {
                        float x = p.positions[v], y = p.positions[v + 1], z = p.positions[v + 2];
                        float wx = world[0] * x + world[4] * y + world[8] * z + world[12];
                        float wy = world[1] * x + world[5] * y + world[9] * z + world[13];
                        float wz = world[2] * x + world[6] * y + world[10] * z + world[14];
                        minX = Math.min(minX, wx); maxX = Math.max(maxX, wx);
                        minY = Math.min(minY, wy); maxY = Math.max(maxY, wy);
                        minZ = Math.min(minZ, wz); maxZ = Math.max(maxZ, wz);
                    }
                }
            }
            if (minX <= maxX) {
                String rest = n.name.substring(4); // 去掉 "col:"
                String animBinding = null;
                int at = rest.indexOf('@');
                if (at >= 0) {
                    animBinding = rest.substring(at + 1);
                    rest = rest.substring(0, at);
                }
                colliders.add(new ColliderDef(rest.trim(), minX, minY, minZ, maxX, maxY, maxZ, animBinding));
            }
        }
        for (int c : n.children) {
            collectColliderAabb(c, world);
        }
    }

    // ===================== 展平（静态显示） =====================

    /** 展平结果：三角形（相对模型 AABB 中心）+ 中心格坐标（实体摆放基准） */
    public record FlattenResult(List<Voxelizer.Triangle> triangles, int ox, int oy, int oz) {}

    /** 展平过程中收集的单个图元的世界几何（已应用 node 全局变换） */
    private static final class WorldPrim {
        final float[] pos;  // count*3 世界坐标
        final float[] nrm;  // count*3 世界法线（已归一化），或 null
        final float[] uv;   // count*2，或 null
        final int[] idx;    // 或 null
        final int materialId;
        final boolean triangleMode;
        WorldPrim(float[] pos, float[] nrm, float[] uv, int[] idx, int materialId, boolean triangleMode) {
            this.pos = pos; this.nrm = nrm; this.uv = uv; this.idx = idx;
            this.materialId = materialId; this.triangleMode = triangleMode;
        }
    }

    /**
     * 把整个 node 树按初始 TRS 变换合成为单实体几何（静态姿势）。
     * - 几何基准 = **模型原点**（glTF/Blender 世界原点 0,0,0），非 AABB 中心——
     *   摆放坐标对齐模型原点（objplace/glbdomain 的指定坐标即 Blender 世界原点对应点）
     * - UV 做 v 翻转（glTF v 原点在左上；渲染管线按 OBJ 约定做 1-v，展平时先翻转以抵消）
     * - 法线用全局变换 3x3 旋转部分（忽略非均匀 scale 的逆转置，静态显示足够）
     */
    public FlattenResult flatten(float scale) {
        List<WorldPrim> prims = new ArrayList<>();
        for (int root : sceneRoots) {
            walk(root, null, scale, null, prims);
        }
        if (prims.isEmpty()) {
            return new FlattenResult(List.of(), 0, 0, 0);
        }
        // 模型原点 = 0,0,0（glTF 世界原点）：几何即世界坐标，摆放时实体位置 = 目标坐标即对齐模型原点
        return build(prims, 0, 0, 0, hasTexFlags(prims));
    }

    /**
     * 动画版展平：按给定动画控制器当前 TRS 覆盖重算几何，相对【固定】中心格。
     * 中心格在首次静态展平时确定并保持，否则动画中模型绕自身旋转时 AABB 漂移导致实体跳动。
     */
    public FlattenResult flatten(float scale, com.levodoubt.objuilder.animation.GlbAnimationController anim,
                                 int fx, int fy, int fz) {
        List<WorldPrim> prims = new ArrayList<>();
        for (int root : sceneRoots) {
            walk(root, null, scale, anim, prims);
        }
        return build(prims, fx, fy, fz, hasTexFlags(prims));
    }

    /** prims 下标 → 该图元材质是否有贴图（无贴图材质的原始 UV 常为全 0 → 展平时用位置投影替换） */
    private boolean[] hasTexFlags(List<WorldPrim> prims) {
        boolean[] flags = new boolean[prims.size()];
        for (int i = 0; i < prims.size(); i++) {
            WorldPrim p = prims.get(i);
            flags[i] = p.materialId >= 0 && p.materialId < materials.size()
                    && materials.get(p.materialId).baseColorTexture >= 0;
        }
        return flags;
    }

    /** 世界几何 → 局部三角形（局部 = 世界 - 基准原点）。hasTexByPrim = prims 下标 → 该图元材质是否有贴图 */
    private FlattenResult build(List<WorldPrim> prims, int ox, int oy, int oz, boolean[] hasTexByPrim) {
        // 生成三角形（局部坐标 = 世界 - 基准原点）
        List<Voxelizer.Triangle> tris = new ArrayList<>();
        for (int pi = 0; pi < prims.size(); pi++) {
            WorldPrim p = prims.get(pi);
            if (!p.triangleMode) continue; // 非三角图元跳过（POINTS/LINES 等，本子工程不渲染）
            boolean hasTex = hasTexByPrim != null && pi < hasTexByPrim.length && hasTexByPrim[pi];
            // 该图元材质的 baseColorTexture KHR_texture_transform（无贴图材质不应用，位置投影 UV）
            float tou = 0f, tov = 0f, tsu = 1f, tsv = 1f, trot = 0f;
            if (hasTex && p.materialId >= 0 && p.materialId < materials.size()) {
                Material m = materials.get(p.materialId);
                tou = m.texOffsetU; tov = m.texOffsetV; tsu = m.texScaleU; tsv = m.texScaleV; trot = m.texRotation;
            }
            int[] idx = p.idx != null ? p.idx : sequentialIndices(p.pos.length / 3);
            for (int i = 0; i + 2 < idx.length; i += 3) {
                int i0 = idx[i], i1 = idx[i + 1], i2 = idx[i + 2];
                ObjMesh.Vec3 a = local(p.pos, i0, ox, oy, oz);
                ObjMesh.Vec3 b = local(p.pos, i1, ox, oy, oz);
                ObjMesh.Vec3 c = local(p.pos, i2, ox, oy, oz);
                ObjMesh.Vec3 fn = normal(a, b, c);
                ObjMesh.Vec3 na = p.nrm != null ? norm(p.nrm, i0) : null;
                ObjMesh.Vec3 nb = p.nrm != null ? norm(p.nrm, i1) : null;
                ObjMesh.Vec3 nc = p.nrm != null ? norm(p.nrm, i2) : null;
                // 无贴图材质：原始 UV 常为全 0（Blender 纯色导出未展开）→ 无梯度 → 光影下 tangent 退化 → 法线贴图解码乱 → 噪点。
                // 用"按面法线投影"的位置 UV 替换，保证任意朝向的面都有梯度。
                ObjMesh.Vec2 ua, ub, uc;
                if (hasTex && p.uv != null) {
                    ua = uv(p.uv, i0, tou, tov, tsu, tsv, trot);
                    ub = uv(p.uv, i1, tou, tov, tsu, tsv, trot);
                    uc = uv(p.uv, i2, tou, tov, tsu, tsv, trot);
                } else if (!hasTex) {
                    ua = projectUv(a, fn); ub = projectUv(b, fn); uc = projectUv(c, fn);
                } else {
                    ua = ub = uc = null;
                }
                tris.add(new Voxelizer.Triangle(a, b, c, fn, na, nb, nc, ua, ub, uc, p.materialId));
            }
        }
        return new FlattenResult(tris, ox, oy, oz);
    }

    /** 按面法线主分量选投影轴生成 UV（保证梯度）：法线朝 Y → XZ 投影；朝 X → ZY；朝 Z → XY */
    private static ObjMesh.Vec2 projectUv(ObjMesh.Vec3 p, ObjMesh.Vec3 n) {
        float ax = Math.abs(n.x()), ay = Math.abs(n.y()), az = Math.abs(n.z());
        if (ay >= ax && ay >= az) {
            return new ObjMesh.Vec2(p.x(), p.z());
        } else if (ax >= az) {
            return new ObjMesh.Vec2(p.z(), p.y());
        } else {
            return new ObjMesh.Vec2(p.x(), p.y());
        }
    }

    /** DFS 遍历 node 树，把每个 mesh primitive 变换到世界坐标（col: 碰撞盒 node 不参与渲染，整棵子树跳过） */
    private void walk(int nodeIdx, float[] parent, float scale,
                      com.levodoubt.objuilder.animation.GlbAnimationController anim, List<WorldPrim> out) {
        Node n = nodes.get(nodeIdx);
        if (n.name != null && n.name.startsWith("col:")) return; // 碰撞盒不渲染
        float[] world = composeWorld(n, nodeIdx, parent, scale, anim);
        for (int mi : n.meshes) {
            Mesh mesh = meshes.get(mi);
            for (Primitive p : mesh.primitives) {
                out.add(transformPrimitive(p, world));
            }
        }
        for (int c : n.children) {
            walk(c, world, scale, anim, out);
        }
    }

    /** 图元顶点应用全局变换（位置 + 法线 3x3 旋转），生成世界几何 */
    private static WorldPrim transformPrimitive(Primitive p, float[] world) {
        int count = p.vertexCount();
        float[] pos = new float[p.positions.length];
        for (int i = 0; i < count; i++) {
            float x = p.positions[i * 3], y = p.positions[i * 3 + 1], z = p.positions[i * 3 + 2];
            pos[i * 3] = world[0] * x + world[4] * y + world[8] * z + world[12];
            pos[i * 3 + 1] = world[1] * x + world[5] * y + world[9] * z + world[13];
            pos[i * 3 + 2] = world[2] * x + world[6] * y + world[10] * z + world[14];
        }
        float[] nrm = null;
        if (p.normals != null) {
            nrm = new float[p.normals.length];
            for (int i = 0; i < count; i++) {
                float x = p.normals[i * 3], y = p.normals[i * 3 + 1], z = p.normals[i * 3 + 2];
                float nx = world[0] * x + world[4] * y + world[8] * z;
                float ny = world[1] * x + world[5] * y + world[9] * z;
                float nz = world[2] * x + world[6] * y + world[10] * z;
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len > 1e-6f) {
                    nrm[i * 3] = nx / len; nrm[i * 3 + 1] = ny / len; nrm[i * 3 + 2] = nz / len;
                } else {
                    nrm[i * 3] = 0; nrm[i * 3 + 1] = 1; nrm[i * 3 + 2] = 0;
                }
            }
        }
        return new WorldPrim(pos, nrm, p.uvs, p.indices, p.materialIndex, p.mode == 4);
    }

    /**
     * node 全局变换（列主序，v' = M * v）。
     * 动画覆盖：channel 目标 node 的 TRS 分量用动画当前值替换初始值（按 glTF 规范，动画覆盖非叠加）。
     * node 有 matrix 且无动画覆盖 → 走 matrix 路径；有动画覆盖 → 走 TRS 路径（其余未覆盖分量用默认值）。
     */
    private static float[] composeWorld(Node n, int nodeIdx, float[] parent, float scale,
                                        com.levodoubt.objuilder.animation.GlbAnimationController anim) {
        float[] animT = anim != null ? anim.translation(nodeIdx) : null;
        float[] animR = anim != null ? anim.rotation(nodeIdx) : null;
        float[] animS = anim != null ? anim.scale(nodeIdx) : null;

        if (n.matrix != null && animT == null && animR == null && animS == null) {
            float[] local = n.matrix.clone();
            // 缩放并入全局矩阵（对整矩阵乘 scale，旋转分量无影响）
            local[0] *= scale; local[1] *= scale; local[2] *= scale;
            local[4] *= scale; local[5] *= scale; local[6] *= scale;
            local[8] *= scale; local[9] *= scale; local[10] *= scale;
            local[12] *= scale; local[13] *= scale; local[14] *= scale;
            return parent == null ? local : mul4(parent, local);
        }

        // TRS 合成：动画覆盖分量优先，其余用初始 TRS（缺省平移 0 / 旋转单位 / 缩放 1）
        float[] t = animT != null ? animT
                : n.translation != null ? n.translation : new float[]{0, 0, 0};
        float[] r = animR != null ? animR
                : n.rotation != null ? n.rotation : new float[]{0, 0, 0, 1};
        float[] s = animS != null ? animS
                : n.scale != null ? n.scale : new float[]{1, 1, 1};
        float[] local = mul4(mul4(translate4(t, scale), quaternionMatrix(r[0], r[1], r[2], r[3])), scale4(s, scale));
        return parent == null ? local : mul4(parent, local);
    }

    // ===================== 矩阵工具（glTF 列主序，v' = M * v） =====================

    /** 平移矩阵（列主序），平移量 = translation × scale */
    private static float[] translate4(float[] t, float scale) {
        return new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0,
                t[0]*scale, t[1]*scale, t[2]*scale, 1};
    }

    /** 缩放矩阵（列主序），缩放量 = scale 分量 × 整体 scale */
    private static float[] scale4(float[] s, float scale) {
        return new float[]{s[0]*scale,0,0,0, 0,s[1]*scale,0,0, 0,0,s[2]*scale,0, 0,0,0,1};
    }

    /** 四元数 [x,y,z,w] → 旋转矩阵（列主序存储） */
    private static float[] quaternionMatrix(float x, float y, float z, float w) {
        return new float[]{
                1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0,
                2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0,
                2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0,
                0, 0, 0, 1};
    }

    /** 列主序 4x4 乘法：r = a * b（先应用 b，再应用 a） */
    private static float[] mul4(float[] a, float[] b) {
        float[] r = new float[16];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float s = 0;
                for (int k = 0; k < 4; k++) {
                    s += a[k * 4 + row] * b[col * 4 + k];
                }
                r[col * 4 + row] = s;
            }
        }
        return r;
    }

    private static ObjMesh.Vec3 local(float[] pos, int i, int ox, int oy, int oz) {
        return new ObjMesh.Vec3(pos[i * 3] - ox, pos[i * 3 + 1] - oy, pos[i * 3 + 2] - oz);
    }

    private static ObjMesh.Vec3 norm(float[] nrm, int i) {
        return new ObjMesh.Vec3(nrm[i * 3], nrm[i * 3 + 1], nrm[i * 3 + 2]);
    }

    /**
     * glTF UV → 渲染 UV：先应用 KHR_texture_transform（作用于 glTF UV 空间，v 原点左上）
     * uv' = R(rot) * (uv * scale) + offset；再做 v 翻转（glTF 左上 → OBJ/渲染管线左下 1-v）
     */
    private static ObjMesh.Vec2 uv(float[] uv, int i, float tou, float tov, float tsu, float tsv, float trot) {
        float u = uv[i * 2] * tsu;
        float v = uv[i * 2 + 1] * tsv;
        if (trot != 0f) {
            float c = (float) Math.cos(trot), s = (float) Math.sin(trot);
            float nu = c * u - s * v;
            float nv = s * u + c * v;
            u = nu; v = nv;
        }
        return new ObjMesh.Vec2(u + tou, 1f - (v + tov));
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

    private static int[] sequentialIndices(int vertexCount) {
        int[] idx = new int[vertexCount];
        for (int i = 0; i < vertexCount; i++) idx[i] = i;
        return idx;
    }
}
