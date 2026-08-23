package com.levodoubt.objuilder.debug;

import java.util.ArrayList;
import java.util.List;

import com.levodoubt.polarisdevtool.api.IDebugProvider;

/** 向 polarisdevtool 调试面板暴露 OBJ 导入诊断数据 */
public class ObjBuilderDebugProvider implements IDebugProvider {
    @Override
    public String section() {
        return "OBJ Builder";
    }

    @Override
    public List<String> lines() {
        List<String> list = new ArrayList<>();
        list.add("模型: " + LastImportStats.modelName);
        list.add("顶点: " + LastImportStats.vertices);
        list.add("三角形: " + LastImportStats.triangles);
        list.add("表面格: " + LastImportStats.gridCells);
        list.add("模板族: " + LastImportStats.pieceCount);
        list.add("渲染三角形: " + LastImportStats.renderTris);
        list.add("导入耗时: " + LastImportStats.importTimeMs + " ms");
        return list;
    }
}
