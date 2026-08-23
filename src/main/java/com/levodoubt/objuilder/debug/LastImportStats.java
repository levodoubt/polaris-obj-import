package com.levodoubt.objuilder.debug;

/** 最近一次导入的统计（供调试面板读取） */
public final class LastImportStats {
    private LastImportStats() {
    }

    public static volatile String modelName = "—";
    public static volatile int vertices;
    public static volatile int triangles;
    public static volatile int gridCells;
    public static volatile int pieceCount;
    public static volatile long renderTris;
    public static volatile long importTimeMs;

    public static void reset(String name) {
        modelName = name;
        vertices = 0;
        triangles = 0;
        gridCells = 0;
        pieceCount = 0;
        renderTris = 0;
        importTimeMs = 0;
    }
}
