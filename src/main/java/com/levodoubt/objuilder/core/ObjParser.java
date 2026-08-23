package com.levodoubt.objuilder.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/** OBJ 解析器：支持 v / vt / f（含 v/vt/vn 索引，负索引）+ mtllib → map_Kd 贴图路径 */
public class ObjParser {
    public static ObjMesh parse(File file) {
        ObjMesh mesh = new ObjMesh();
        List<ObjMesh.Vec3> verts = mesh.vertices;
        List<ObjMesh.Vec2> uvs = mesh.uvs;
        List<ObjMesh.Vec3> vns = mesh.normals;
        List<ObjMesh.Face> faces = mesh.faces;
        String mtlName = null;
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
                        // 扇形三角化（n 边形 → n-2 三角形）
                        for (int i = 1; i + 1 < n; i++) {
                            faces.add(new ObjMesh.Face(
                                    vi[0], vi[i], vi[i + 1],
                                    ti[0], ti[i], ti[i + 1],
                                    ni[0], ni[i], ni[i + 1]));
                        }
                    }
                    default -> { /* 忽略其它行 */ }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("解析 OBJ 失败: " + file.getAbsolutePath(), e);
        }

        // 解析 MTL 的 map_Kd 贴图路径
        if (mtlName != null) {
            File mtlFile = new File(file.getParentFile(), mtlName);
            if (mtlFile.exists()) {
                mesh.texturePath = parseMapKd(mtlFile);
            }
        }
        return mesh;
    }

    /** OBJ 索引：负值 = 从末尾倒数，正值 = 1-based */
    private static int parseIndex(String s, int size) {
        int idx = Integer.parseInt(s);
        return idx < 0 ? size + idx : idx - 1;
    }

    /** 从 MTL 读取 map_Kd 贴图路径（保留原样：绝对路径或相对路径） */
    private static String parseMapKd(File mtlFile) {
        try (BufferedReader reader = new BufferedReader(new FileReader(mtlFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("map_Kd") || line.startsWith("map_Ka")) {
                    String[] sp = line.split("\\s+");
                    return sp[sp.length - 1]; // 最后一个 token 是贴图路径
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
