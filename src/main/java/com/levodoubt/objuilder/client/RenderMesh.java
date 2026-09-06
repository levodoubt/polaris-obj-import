package com.levodoubt.objuilder.client;

/**
 * 预烘焙的紧凑渲染网格（纯数据容器，构建逻辑在 {@link DomainModelCache#buildRenderMesh}）。
 *
 * <p>背景（2.1.11 性能优化·CPU 提交路径批量化）：
 * 原渲染循环逐三角形、逐顶点地读 {@link com.levodoubt.objuilder.core.Voxelizer.Triangle} record
 * （每三角形 10 个对象引用 + 7 个小 Vec3/Vec2 对象），每顶点 6 次虚调用 + 2 次 {@code new Vector3f()}
 * 分配 + 每帧重建材质分组 LinkedHashMap。数十万三角形时是 CPU 提交的主瓶颈。
 *
 * <p>本类把三角形展平为连续 {@code float[]}（位置/法线/UV 交错）+ 按材质（materialId）切成连续
 * run，并预计算每 run 的打包颜色（ARGB）与光照（自发光 → pack(15,15)，否则 -1 表示用实体 packedLight）。
 * 渲染时按 run 遍历平面数组、内联 4x4/3x3 矩阵变换，把「每帧百万级虚调用 + 对象分配」压到最低。
 *
 * <p>静态模型 bake 一次；动画模型每次 updateGeometry 失效后懒重建（动画模型体量小，重建开销可忽略）。
 * 阴影 pass 用细分 LOD 三角形单独构建一份（SHADOW_RENDER_MESH）。
 */
public final class RenderMesh {
    /** 材质 run 数（= 该模型 distinct materialId 数） */
    public final int runCount;
    /** 每 run 的 materialId */
    public final int[] runMaterial;
    /** 每 run 的起始顶点下标（pos/nrm 按 ×3、uv 按 ×2 寻址） */
    public final int[] runStart;
    /** 每 run 的顶点数 */
    public final int[] runLength;
    /** 每 run 的打包颜色（ARGB：a<<24 | r<<16 | g<<8 | b，各 0~255） */
    public final int[] runColor;
    /** 每 run 的光照：-1 = 用实体 packedLight，否则 = 预计算 light（自发光 LightTexture.pack(15,15)） */
    public final int[] runLight;
    /** 顶点总数 */
    public final int vertexCount;
    /** 位置 xyz 交错（长度 3*vertexCount） */
    public final float[] pos;
    /** 法线 xyz 交错（长度 3*vertexCount） */
    public final float[] nrm;
    /** UV 交错（长度 2*vertexCount；无贴图材质已在构建期烘焙为位置投影 UV） */
    public final float[] uv;

    RenderMesh(int runCount, int[] runMaterial, int[] runStart, int[] runLength,
               int[] runColor, int[] runLight, int vertexCount,
               float[] pos, float[] nrm, float[] uv) {
        this.runCount = runCount;
        this.runMaterial = runMaterial;
        this.runStart = runStart;
        this.runLength = runLength;
        this.runColor = runColor;
        this.runLight = runLight;
        this.vertexCount = vertexCount;
        this.pos = pos;
        this.nrm = nrm;
        this.uv = uv;
    }
}
