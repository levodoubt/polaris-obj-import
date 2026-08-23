package com.levodoubt.objuilder.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 离线烘焙格式（.objb）：把切分结果（几何 + 摆放数据 + 贴图引用）压缩保存。
 * 大数据量：摆放数据用紧凑二进制，几何用 float 数组。
 */
public class BakeFormat {
    public static final String MAGIC = "POBJB";
    public static final int VERSION = 1;

    /** 烘焙结果：几何 + 摆放 + 统计 */
    public record Baked(Map<Integer, List<Voxelizer.Triangle>> geometry,
                        List<Voxelizer.Placement> placements,
                        List<int[]> interior,
                        Voxelizer.Stats stats,
                        String texturePath) {
    }

    public static void save(File file, Map<Integer, List<Voxelizer.Triangle>> geometry,
                            List<Voxelizer.Placement> placements, List<int[]> interior,
                            Voxelizer.Stats stats, String texturePath) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
            out.writeUTF(MAGIC);
            out.writeInt(VERSION);
            // 统计
            out.writeInt(stats.vertCount());
            out.writeInt(stats.triCount());
            out.writeInt(stats.gridCells());
            out.writeInt(stats.pieceCount());
            out.writeLong(stats.totalRenderTris());
            out.writeInt(stats.interiorCount());
            out.writeUTF(texturePath == null ? "" : texturePath);
            // 几何：模板族 id → 三角形数组
            out.writeInt(geometry.size());
            for (Map.Entry<Integer, List<Voxelizer.Triangle>> e : geometry.entrySet()) {
                out.writeInt(e.getKey());
                out.writeInt(e.getValue().size());
                for (Voxelizer.Triangle t : e.getValue()) {
                    writeVec3(out, t.p(0)); writeVec3(out, t.p(1)); writeVec3(out, t.p(2));
                    writeVec3(out, t.n());
                    writeVec3Opt(out, t.vn(0)); writeVec3Opt(out, t.vn(1)); writeVec3Opt(out, t.vn(2));
                    writeVec2Opt(out, t.uv(0)); writeVec2Opt(out, t.uv(1)); writeVec2Opt(out, t.uv(2));
                }
            }
            // 摆放
            out.writeInt(placements.size());
            for (Voxelizer.Placement p : placements) {
                out.writeInt(p.x()); out.writeInt(p.y()); out.writeInt(p.z()); out.writeInt(p.pieceId());
            }
            // 内部格
            out.writeInt(interior.size());
            for (int[] c : interior) {
                out.writeInt(c[0]); out.writeInt(c[1]); out.writeInt(c[2]);
            }
        }
    }

    public static Baked load(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            if (!in.readUTF().equals(MAGIC)) throw new IOException("非 OBJB 文件: " + file.getName());
            int ver = in.readInt();
            int vert = in.readInt();
            int tri = in.readInt();
            int cells = in.readInt();
            int pieces = in.readInt();
            long renderTris = in.readLong();
            int interiorCount = in.readInt();
            String texPath = in.readUTF();
            Voxelizer.Stats stats = new Voxelizer.Stats(vert, tri, cells, pieces, renderTris, interiorCount);

            Map<Integer, List<Voxelizer.Triangle>> geometry = new HashMap<>();
            int geomCount = in.readInt();
            for (int i = 0; i < geomCount; i++) {
                int id = in.readInt();
                int triCount = in.readInt();
                List<Voxelizer.Triangle> tris = new ArrayList<>(triCount);
                for (int j = 0; j < triCount; j++) {
                    ObjMesh.Vec3 a = readVec3(in), b = readVec3(in), c = readVec3(in);
                    ObjMesh.Vec3 n = readVec3(in);
                    ObjMesh.Vec3 na = readVec3Opt(in), nb = readVec3Opt(in), nc = readVec3Opt(in);
                    ObjMesh.Vec2 ua = readVec2Opt(in), ub = readVec2Opt(in), uc = readVec2Opt(in);
                    tris.add(new Voxelizer.Triangle(a, b, c, n, na, nb, nc, ua, ub, uc));
                }
                geometry.put(id, tris);
            }

            List<Voxelizer.Placement> placements = new ArrayList<>();
            int placeCount = in.readInt();
            for (int i = 0; i < placeCount; i++) {
                placements.add(new Voxelizer.Placement(
                        in.readInt(), in.readInt(), in.readInt(), in.readInt()));
            }

            List<int[]> interior = new ArrayList<>();
            for (int i = 0; i < interiorCount; i++) {
                interior.add(new int[]{in.readInt(), in.readInt(), in.readInt()});
            }
            return new Baked(geometry, placements, interior, stats,
                    texPath.isEmpty() ? null : texPath);
        }
    }

    public static void writeVec3(DataOutputStream out, ObjMesh.Vec3 v) throws IOException {
        out.writeFloat(v.x()); out.writeFloat(v.y()); out.writeFloat(v.z());
    }

    public static void writeVec3Opt(DataOutputStream out, ObjMesh.Vec3 v) throws IOException {
        if (v == null) { out.writeBoolean(false); } else { out.writeBoolean(true); writeVec3(out, v); }
    }

    public static void writeVec2Opt(DataOutputStream out, ObjMesh.Vec2 v) throws IOException {
        if (v == null) { out.writeBoolean(false); } else { out.writeBoolean(true); out.writeFloat(v.u()); out.writeFloat(v.v()); }
    }

    public static ObjMesh.Vec3 readVec3(DataInputStream in) throws IOException {
        return new ObjMesh.Vec3(in.readFloat(), in.readFloat(), in.readFloat());
    }

    public static ObjMesh.Vec3 readVec3Opt(DataInputStream in) throws IOException {
        return in.readBoolean() ? readVec3(in) : null;
    }

    public static ObjMesh.Vec2 readVec2Opt(DataInputStream in) throws IOException {
        return in.readBoolean() ? new ObjMesh.Vec2(in.readFloat(), in.readFloat()) : null;
    }
}
