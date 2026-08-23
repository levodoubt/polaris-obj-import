package com.levodoubt.objuilder.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/** 极简 OBJ 解析器：仅支持 v / f（三角化，忽略 vt/vn/mtllib） */
public class ObjParser {
    public static ObjMesh parse(File file) {
        ObjMesh mesh = new ObjMesh();
        List<ObjMesh.Vec3> verts = mesh.vertices;
        List<int[]> faces = mesh.faces;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] sp = line.split("\\s+");
                switch (sp[0]) {
                    case "v" -> verts.add(new ObjMesh.Vec3(
                            Float.parseFloat(sp[1]), Float.parseFloat(sp[2]), Float.parseFloat(sp[3])));
                    case "f" -> {
                        List<Integer> idx = new ArrayList<>(sp.length - 1);
                        for (int i = 1; i < sp.length; i++) {
                            String[] parts = sp[i].split("/");
                            idx.add(Integer.parseInt(parts[0]) - 1);
                        }
                        // 扇形三角化（n 边形 → n-2 三角形）
                        for (int i = 1; i + 1 < idx.size(); i++) {
                            faces.add(new int[]{idx.get(0), idx.get(i), idx.get(i + 1)});
                        }
                    }
                    default -> { /* 忽略其它行 */ }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("解析 OBJ 失败: " + file.getAbsolutePath(), e);
        }
        return mesh;
    }
}
