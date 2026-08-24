package com.levodoubt.objuilder.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OBJ 解析器：支持 v / vt / vn / f（含 v/vt/vn 索引，负索引）+ mtllib → 多材质（usemtl + 多 map_Kd） */
public class ObjParser {
    public static ObjMesh parse(File file) {
        ObjMesh mesh = new ObjMesh();
        List<ObjMesh.Vec3> verts = mesh.vertices;
        List<ObjMesh.Vec2> uvs = mesh.uvs;
        List<ObjMesh.Vec3> vns = mesh.normals;
        List<ObjMesh.Face> faces = mesh.faces;
        String mtlName = null;
        String currentMaterial = null;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] sp = line.split("\\s+");
                switch (sp[0]) {
                    case "v" -> verts.add(new ObjMesh.Vec3(
                            Float.parseFloat(sp[1]), Float.parseFloat(sp[2]), Float.parseFloat(sp[3])));
                    case "vt" -> uvs.add(new ObjMesh.Vec2(
                            Float.parseFloat(sp[1]), Float.parseFloat(sp[2])));
                    case "vn" -> vns.add(new ObjMesh.Vec3(
                            Float.parseFloat(sp[1]), Float.parseFloat(sp[2]), Float.parseFloat(sp[3])));
                    case "mtllib" -> mtlName = sp[1];
                    case "usemtl" -> currentMaterial = sp.length > 1 ? sp[1] : null;
                    case "f" -> {
                        int n = sp.length - 1;
                        int[] vi = new int[n];
                        int[] ti = new int[n];
                        int[] ni = new int[n];
                        for (int i = 0; i < n; i++) {
                            String[] parts = sp[i + 1].split("/");
                            vi[i] = parseIndex(parts[0], verts.size());
                            ti[i] = (parts.length > 1 && !parts[1].isEmpty())
                                    ? parseIndex(parts[1], uvs.size()) : -1;
                            ni[i] = (parts.length > 2 && !parts[2].isEmpty())
                                    ? parseIndex(parts[2], vns.size()) : -1;
                        }
                        // 当前材质 id（无 usemtl → -1）
                        int matId = currentMaterial != null
                                ? materialId(mesh, currentMaterial) : -1;
                        // 扇形三角化（n 边形 → n-2 三角形）
                        for (int i = 1; i + 1 < n; i++) {
                            ObjMesh.Face face = new ObjMesh.Face(
                                    vi[0], vi[i], vi[i + 1],
                                    ti[0], ti[i], ti[i + 1],
                                    ni[0], ni[i], ni[i + 1]);
                            face.materialId = matId;
                            faces.add(face);
                        }
                    }
                    default -> { /* 忽略其它行 */ }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("解析 OBJ 失败: " + file.getAbsolutePath(), e);
        }

        // 解析 MTL：所有 newmtl → map_Kd 映射
        if (mtlName != null) {
            File mtlFile = new File(file.getParentFile(), mtlName);
            if (mtlFile.exists()) {
                parseMtls(mesh, mtlFile);
            }
        }
        // 兼容旧单材质：第一张 map_Kd 作为 texturePath
        if (!mesh.materials.isEmpty() && mesh.materials.get(0).mapKd != null) {
            mesh.texturePath = mesh.materials.get(0).mapKd;
        }
        return mesh;
    }

    /** 取材质 id（按出现顺序分配），不存在则创建占位（待 MTL 填充 map_Kd） */
    private static int materialId(ObjMesh mesh, String name) {
        for (int i = 0; i < mesh.materials.size(); i++) {
            if (mesh.materials.get(i).name.equals(name)) return i;
        }
        mesh.materials.add(new ObjMesh.Material(name, null));
        return mesh.materials.size() - 1;
    }

    /** 解析 MTL：所有 newmtl 及其 map_Kd 贴图路径 + Kd 漫反射颜色 */
    private static void parseMtls(ObjMesh mesh, File mtlFile) {
        Map<String, String> mapKd = new LinkedHashMap<>();
        Map<String, float[]> kdColor = new LinkedHashMap<>();
        Map<String, float[]> keColor = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(mtlFile))) {
            String line;
            String cur = null;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] sp = line.split("\\s+");
                if (sp[0].equals("newmtl") && sp.length > 1) {
                    cur = sp[1];
                    mapKd.putIfAbsent(cur, null);
                    kdColor.putIfAbsent(cur, new float[]{1f, 1f, 1f});
                    keColor.putIfAbsent(cur, new float[]{0f, 0f, 0f});
                } else if ((sp[0].equals("map_Kd") || sp[0].equals("map_Ka")) && cur != null) {
                    // map_Kd 可能带 -options，路径是最后一个 token
                    String path = sp[sp.length - 1];
                    // 去掉可能的空格分段（简单处理：以最后一个非选项 token 为准）
                    if (path.startsWith("-")) {
                        // 罕见：选项在最后，找第一个非 - 开头的
                        for (String s : sp) {
                            if (!s.startsWith("-") && !s.equals("map_Kd") && !s.equals("map_Ka")) {
                                path = s;
                                break;
                            }
                        }
                    }
                    mapKd.put(cur, path);
                } else if (sp[0].equals("Kd") && cur != null && sp.length >= 4) {
                    try {
                        kdColor.put(cur, new float[]{
                                Float.parseFloat(sp[1]), Float.parseFloat(sp[2]), Float.parseFloat(sp[3])});
                    } catch (NumberFormatException ignored) {
                    }
                } else if (sp[0].equals("Ke") && cur != null && sp.length >= 4) {
                    try {
                        keColor.put(cur, new float[]{
                                Float.parseFloat(sp[1]), Float.parseFloat(sp[2]), Float.parseFloat(sp[3])});
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // 回填材质列表的 map_Kd 与 Kd/Ke 颜色
        for (int i = 0; i < mesh.materials.size(); i++) {
            String name = mesh.materials.get(i).name;
            String path = mapKd.get(name);
            float[] kd = kdColor.get(name);
            float[] ke = keColor.get(name);
            ObjMesh.Material m = mesh.materials.get(i);
            boolean needMapKd = path != null && m.mapKd == null;
            boolean needKd = kd != null;
            boolean needKe = ke != null;
            if (needMapKd || needKd || needKe) {
                mesh.materials.set(i, new ObjMesh.Material(name,
                        needMapKd ? path : m.mapKd,
                        needKd ? kd[0] : m.kdR,
                        needKd ? kd[1] : m.kdG,
                        needKd ? kd[2] : m.kdB,
                        needKe ? ke[0] : m.keR,
                        needKe ? ke[1] : m.keG,
                        needKe ? ke[2] : m.keB));
            }
        }
    }

    /** OBJ 索引：负值 = 从末尾倒数，正值 = 1-based */
    private static int parseIndex(String s, int size) {
        int idx = Integer.parseInt(s);
        return idx < 0 ? size + idx : idx - 1;
    }
}
