package com.levodoubt.objuilder.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import com.levodoubt.objuilder.PolarisObjuilder;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

/**
 * Schematic 导出/导入（自研稀疏格式）。
 *
 * 稀疏数据直接存 pieceId（int），突破 BlockState 4096 上限；pieceId 由 BlockEntity 存储。
 * 石头用 pieceId = -1 标记。
 */
public class Schematic {
    public static final int STONE_MARKER = -1;

    /** 导出：切分结果 → .schem 文件（含几何 + 稀疏 pieceId 数据） */
    public static void export(File file, Map<Integer, List<Voxelizer.Triangle>> geometry,
                              List<Voxelizer.Placement> placements,
                              List<int[]> interior, Voxelizer.Stats stats,
                              String texturePath) throws IOException {
        export(file, geometry, placements, interior, stats, texturePath, null);
    }

    public static void export(File file, Map<Integer, List<Voxelizer.Triangle>> geometry,
                              List<Voxelizer.Placement> placements,
                              List<int[]> interior, Voxelizer.Stats stats,
                              String texturePath,
                              java.util.function.IntConsumer progress) throws IOException {
        // 包围盒
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Voxelizer.Placement p : placements) {
            minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x());
            minY = Math.min(minY, p.y()); maxY = Math.max(maxY, p.y());
            minZ = Math.min(minZ, p.z()); maxZ = Math.max(maxZ, p.z());
        }
        int w = maxX - minX + 1;
        int h = maxY - minY + 1;
        int d = maxZ - minZ + 1;

        // 稀疏数据：pieceId 直接存（x,y,z,pieceId）
        ListTag sparseData = new ListTag();
        for (Voxelizer.Placement p : placements) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("x", p.x() - minX);
            entry.putInt("y", p.y() - minY);
            entry.putInt("z", p.z() - minZ);
            entry.putInt("p", p.pieceId());
            sparseData.add(entry);
        }
        for (int[] c : interior) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("x", c[0] - minX);
            entry.putInt("y", c[1] - minY);
            entry.putInt("z", c[2] - minZ);
            entry.putInt("p", STONE_MARKER);
            sparseData.add(entry);
        }

        CompoundTag root = new CompoundTag();
        root.putInt("Version", 2);
        root.putInt("DataVersion", 3953);
        root.putShort("Width", (short) w);
        root.putShort("Height", (short) h);
        root.putShort("Length", (short) d);
        root.put("SparseData", sparseData);
        root.putInt("SparseCount", sparseData.size());
        root.putByte("SparseFormat", (byte) 2); // v2: pieceId 直存

        // 几何 + 贴图
        CompoundTag metadata = new CompoundTag();
        metadata.putString("texturePath", texturePath == null ? "" : texturePath);
        try {
            if (progress != null) progress.accept(70);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (java.io.DataOutputStream out = new java.io.DataOutputStream(bos)) {
                out.writeInt(geometry.size());
                int geomIdx = 0, geomTotal = geometry.size();
                for (Map.Entry<Integer, List<Voxelizer.Triangle>> e : geometry.entrySet()) {
                    out.writeInt(e.getKey());
                    out.writeInt(e.getValue().size());
                    for (Voxelizer.Triangle t : e.getValue()) {
                        BakeFormat.writeVec3(out, t.p(0)); BakeFormat.writeVec3(out, t.p(1)); BakeFormat.writeVec3(out, t.p(2));
                        BakeFormat.writeVec3(out, t.n());
                        BakeFormat.writeVec3Opt(out, t.vn(0)); BakeFormat.writeVec3Opt(out, t.vn(1)); BakeFormat.writeVec3Opt(out, t.vn(2));
                        BakeFormat.writeVec2Opt(out, t.uv(0)); BakeFormat.writeVec2Opt(out, t.uv(1)); BakeFormat.writeVec2Opt(out, t.uv(2));
                    }
                    geomIdx++;
                    if (progress != null && (geomIdx % 50 == 0 || geomIdx == geomTotal)) {
                        progress.accept(70 + geomIdx * 25 / geomTotal);
                    }
                }
            }
            metadata.putByteArray("geometry", bos.toByteArray());
        } catch (IOException ex) {
            throw new IOException("几何序列化失败", ex);
        }
        root.put("Metadata", metadata);

        if (progress != null) progress.accept(95);
        NbtIo.writeCompressed(root, file.toPath());
        if (progress != null) progress.accept(100);
    }

    /** 导入结果：稀疏 pieceId 数据 + 几何 */
    public record SchematicData(int width, int height, int length,
                                int[] sparseX, int[] sparseY, int[] sparseZ, int[] sparsePieceId,
                                String texturePath,
                                Map<Integer, List<Voxelizer.Triangle>> geometry) {
    }

    public static SchematicData load(File file) throws IOException {
        CompoundTag root;
        try (InputStream in = new FileInputStream(file)) {
            root = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        }
        int w = root.getShort("Width");
        int h = root.getShort("Height");
        int d = root.getShort("Length");
        int count = root.getInt("SparseCount");
        int[] sx = new int[count], sy = new int[count], sz = new int[count], sp = new int[count];
        ListTag sparse = root.getList("SparseData", Tag.TAG_COMPOUND);
        for (int i = 0; i < sparse.size() && i < count; i++) {
            CompoundTag e = sparse.getCompound(i);
            sx[i] = e.getInt("x"); sy[i] = e.getInt("y"); sz[i] = e.getInt("z");
            sp[i] = e.contains("p") ? e.getInt("p") : e.getInt("s");
        }
        String texPath = "";
        Map<Integer, List<Voxelizer.Triangle>> geometry = null;
        if (root.contains("Metadata", Tag.TAG_COMPOUND)) {
            CompoundTag metadata = root.getCompound("Metadata");
            texPath = metadata.getString("texturePath");
            if (metadata.contains("geometry", Tag.TAG_BYTE_ARRAY)) {
                geometry = readGeometry(metadata.getByteArray("geometry"));
            }
        }
        return new SchematicData(w, h, d, sx, sy, sz, sp, texPath, geometry);
    }

    private static Map<Integer, List<Voxelizer.Triangle>> readGeometry(byte[] data) throws IOException {
        Map<Integer, List<Voxelizer.Triangle>> geometry = new java.util.HashMap<>();
        try (java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.ByteArrayInputStream(data))) {
            int geomCount = in.readInt();
            for (int i = 0; i < geomCount; i++) {
                int id = in.readInt();
                int triCount = in.readInt();
                List<Voxelizer.Triangle> tris = new java.util.ArrayList<>(triCount);
                for (int j = 0; j < triCount; j++) {
                    ObjMesh.Vec3 a = BakeFormat.readVec3(in), b = BakeFormat.readVec3(in), c = BakeFormat.readVec3(in);
                    ObjMesh.Vec3 n = BakeFormat.readVec3(in);
                    ObjMesh.Vec3 na = BakeFormat.readVec3Opt(in), nb = BakeFormat.readVec3Opt(in), nc = BakeFormat.readVec3Opt(in);
                    ObjMesh.Vec2 ua = BakeFormat.readVec2Opt(in), ub = BakeFormat.readVec2Opt(in), uc = BakeFormat.readVec2Opt(in);
                    tris.add(new Voxelizer.Triangle(a, b, c, n, na, nb, nc, ua, ub, uc));
                }
                geometry.put(id, tris);
            }
        }
        return geometry;
    }
}
