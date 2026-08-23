package com.levodoubt.objuilder.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import com.levodoubt.objuilder.PolarisObjuilder;
import com.levodoubt.objuilder.block.PieceBlock;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Schematic 导出/导入（Sponge v2 格式，WorldEdit/结构方块兼容）。
 *
 * 导出：切分结果 → .schem 文件（NBT）。
 * 导入：读 .schem → 按 palette 还原方块状态 → 分块放置。
 *
 * 注：此工具类使用 MC 的 NBT 库（NbtIo）但**不依赖世界**，可离线生成（在游戏内命令触发，后台线程）。
 */
public class Schematic {
    /** 导出：切分结果 → .schem 文件 */
    public static void export(File file, List<Voxelizer.Placement> placements,
                              List<int[]> interior, String texturePath) throws IOException {
        // 计算包围盒
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

        // 构建 palette：方块状态 id → 状态字符串
        java.util.Map<String, Integer> palette = new java.util.LinkedHashMap<>();
        palette.put(Blocks.AIR.defaultBlockState().toString(), 0);
        String stone = Blocks.STONE.defaultBlockState().toString();
        palette.put(stone, 1);

        // 预登记所有子片状态
        List<String> pieceStates = new java.util.ArrayList<>();
        for (Voxelizer.Placement p : placements) {
            String stateStr = pieceStateString(p.pieceId());
            if (!palette.containsKey(stateStr)) {
                palette.put(stateStr, palette.size());
                pieceStates.add(stateStr);
            }
        }

        // blockData：XZY 顺序（Sponge 格式）
        byte[] blockData = new byte[w * h * d];
        java.util.Arrays.fill(blockData, (byte) 0);
        for (Voxelizer.Placement p : placements) {
            int idx = (p.x() - minX) + (p.z() - minZ) * w + (p.y() - minY) * w * d;
            blockData[idx] = (byte) (int) palette.get(pieceStateString(p.pieceId()));
        }
        for (int[] c : interior) {
            int idx = (c[0] - minX) + (c[2] - minZ) * w + (c[1] - minY) * w * d;
            blockData[idx] = 1; // 石头
        }

        // 写 NBT（Sponge v2）
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 2);
        root.putInt("DataVersion", 3953); // 1.21.1
        root.putShort("Width", (short) w);
        root.putShort("Height", (short) h);
        root.putShort("Length", (short) d);
        root.putInt("PaletteMax", palette.size());

        // Palette 列表（ListTag of CompoundTag: {Name, Properties}）
        ListTag paletteList = new ListTag();
        // 按 id 排序写入（id 0 = air, 1 = stone, 2+ = 子片）
        String[] sorted = new String[palette.size()];
        for (java.util.Map.Entry<String, Integer> e : palette.entrySet()) {
            sorted[e.getValue()] = e.getKey();
        }
        for (String s : sorted) {
            CompoundTag entry = new CompoundTag();
            String name = s;
            String props = "";
            int bracket = s.indexOf('[');
            if (bracket >= 0) {
                name = s.substring(0, bracket);
                props = s.substring(bracket + 1, s.length() - 1);
            }
            entry.putString("Name", name);
            if (!props.isEmpty()) {
                CompoundTag propsTag = new CompoundTag();
                for (String kv : props.split(",")) {
                    String[] pair = kv.split("=", 2);
                    if (pair.length == 2) {
                        propsTag.putString(pair[0].trim(), pair[1].trim());
                    }
                }
                entry.put("Properties", propsTag);
            }
            paletteList.add(entry);
        }
        root.put("Palette", paletteList);

        // blockData 压缩（gzip）
        byte[] compressed = compress(blockData);
        root.put("BlockData", new ByteArrayTag(compressed));
        root.put("BlockDataLen", IntTag.valueOf(blockData.length));

        // 元数据（含贴图路径）
        CompoundTag metadata = new CompoundTag();
        metadata.putString("texturePath", texturePath == null ? "" : texturePath);
        root.put("Metadata", metadata);

        NbtIo.writeCompressed(root, file.toPath());
    }

    private static byte[] compress(byte[] data) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bos)) {
            gz.write(data);
        }
        return bos.toByteArray();
    }

    private static byte[] decompress(byte[] data) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(
                new java.io.ByteArrayInputStream(data))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = gz.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
        }
        return bos.toByteArray();
    }

    /** 子片方块状态字符串（含属性） */
    private static String pieceStateString(int pieceId) {
        int a = pieceId / 256;
        int b = (pieceId / 16) % 16;
        int c = pieceId % 16;
        return "polarisobjuilder:obj_piece[piece_a=" + a + ",piece_b=" + b + ",piece_c=" + c + "]";
    }

    /** 导入结果：方块数据 + 包围盒 */
    public record SchematicData(int width, int height, int length,
                                byte[] blockData,
                                String[] paletteNames, CompoundTag[] paletteProps,
                                String texturePath) {
    }

    /** 读取 .schem → SchematicData */
    public static SchematicData load(File file) throws IOException {
        CompoundTag root;
        try (InputStream in = new FileInputStream(file)) {
            root = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        }
        int w = root.getShort("Width");
        int h = root.getShort("Height");
        int d = root.getShort("Length");
        byte[] blockData;
        if (root.contains("BlockData", Tag.TAG_BYTE_ARRAY)) {
            blockData = decompress(root.getByteArray("BlockData"));
        } else {
            blockData = root.getByteArray("BlockData"); // 可能已解压
        }
        // Palette
        ListTag paletteList = root.getList("Palette", Tag.TAG_COMPOUND);
        String[] names = new String[paletteList.size()];
        CompoundTag[] props = new CompoundTag[paletteList.size()];
        for (int i = 0; i < paletteList.size(); i++) {
            CompoundTag entry = paletteList.getCompound(i);
            names[i] = entry.getString("Name");
            props[i] = entry.contains("Properties") ? entry.getCompound("Properties") : null;
        }
        String texPath = "";
        if (root.contains("Metadata", Tag.TAG_COMPOUND)) {
            texPath = root.getCompound("Metadata").getString("texturePath");
        }
        return new SchematicData(w, h, d, blockData, names, props, texPath);
    }

    /** 从 palette 索引还原 BlockState */
    public static BlockState resolveBlockState(SchematicData data, int paletteIdx) {
        if (paletteIdx < 0 || paletteIdx >= data.paletteNames().length) {
            return Blocks.AIR.defaultBlockState();
        }
        String name = data.paletteNames()[paletteIdx];
        if (name.equals("minecraft:air")) return Blocks.AIR.defaultBlockState();
        if (name.equals("minecraft:stone")) return Blocks.STONE.defaultBlockState();
        if (name.equals("polarisobjuilder:obj_piece")) {
            BlockState state = PolarisObjuilder.OBJ_PIECE.get().defaultBlockState();
            CompoundTag props = data.paletteProps()[paletteIdx];
            if (props != null) {
                if (props.contains("piece_a")) state = state.setValue(PieceBlock.PIECE_A,
                        Integer.parseInt(props.getString("piece_a")));
                if (props.contains("piece_b")) state = state.setValue(PieceBlock.PIECE_B,
                        Integer.parseInt(props.getString("piece_b")));
                if (props.contains("piece_c")) state = state.setValue(PieceBlock.PIECE_C,
                        Integer.parseInt(props.getString("piece_c")));
            }
            return state;
        }
        return Blocks.AIR.defaultBlockState();
    }
}
