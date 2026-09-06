package com.levodoubt.objuilder.core;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.levodoubt.objuilder.PolarisObjuilder;

/**
 * glb（glTF 2.0 二进制）轻量解析器（自写，不引入 jgltf 大依赖）。
 *
 * 只解析本子工程需要的静态字段：
 * GLB 容器（JSON chunk + BIN chunk）→ glTF JSON（scenes/nodes/meshes/primitives/
 * accessors/bufferViews/buffers/materials/textures/images）→ 解码 accessor 顶点数据。
 *
 * 成功标准 1（命令行打印 node 层级 / 顶点三角形数 / 材质列表）由命令层读取
 * {@link GlbModel} 打印日志完成，本类只负责解析。
 */
public class GlbParser {
    // GLB chunk 类型
    private static final int CHUNK_JSON = 0x4E4F534A; // "JSON"
    private static final int CHUNK_BIN = 0x004E4942;  // "BIN\0"
    // componentType
    private static final int CMP_BYTE = 5120;
    private static final int CMP_UBYTE = 5121;
    private static final int CMP_SHORT = 5122;
    private static final int CMP_USHORT = 5123;
    private static final int CMP_UINT = 5125;
    private static final int CMP_FLOAT = 5126;

    private GlbParser() {
    }

    /** 解析 .glb 文件 → GlbModel */
    public static GlbModel parse(File file) throws IOException {
        byte[] data = Files.readAllBytes(file.toPath());
        return parse(data, file.getName());
    }

    /** 解析 glb 字节 → GlbModel */
    public static GlbModel parse(byte[] data, String name) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int magic = bb.getInt();
        if (magic != 0x46546C67) { // "glTF"
            throw new IOException("不是有效的 glb 文件（magic 不匹配）: " + name);
        }
        int version = bb.getInt();
        if (version != 2) {
            throw new IOException("glb 版本 " + version + " 不支持（仅支持 2）");
        }
        bb.getInt(); // 文件总长（忽略）

        String json = null;
        byte[] bin = new byte[0];
        while (bb.remaining() >= 8) {
            int chunkLength = bb.getInt();
            int chunkType = bb.getInt();
            if (chunkLength < 0 || chunkLength > bb.remaining()) {
                throw new IOException("glb chunk 长度非法: " + chunkLength);
            }
            byte[] chunk = new byte[chunkLength];
            bb.get(chunk);
            if (chunkType == CHUNK_JSON) {
                json = new String(chunk, StandardCharsets.UTF_8);
            } else if (chunkType == CHUNK_BIN) {
                bin = chunk;
            }
        }
        if (json == null) {
            throw new IOException("glb 缺少 JSON chunk: " + name);
        }

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray nodesArr = optArr(root, "nodes");
        JsonArray meshesArr = optArr(root, "meshes");
        JsonArray materialsArr = optArr(root, "materials");
        JsonArray texturesArr = optArr(root, "textures");
        JsonArray imagesArr = optArr(root, "images");
        JsonArray accessorsArr = optArr(root, "accessors");
        JsonArray bufferViewsArr = optArr(root, "bufferViews");
        JsonArray scenesArr = optArr(root, "scenes");
        JsonArray animationsArr = optArr(root, "animations");

        GlbModel model = new GlbModel(name);

        // ---- nodes（先解析节点，保留层级） ----
        if (nodesArr != null) {
            for (JsonElement e : nodesArr) {
                JsonObject o = e.getAsJsonObject();
                String nname = optString(o, "name", "");
                float[] matrix = o.has("matrix") ? readVec(o.get("matrix"), 16) : null;
                float[] translation = o.has("translation") ? readVec(o.get("translation"), 3) : null;
                float[] rotation = o.has("rotation") ? readVec(o.get("rotation"), 4) : null;
                float[] scale = o.has("scale") ? readVec(o.get("scale"), 3) : null;
                int[] children = o.has("children") ? readIntArr(o.get("children")) : new int[0];
                int[] meshes;
                if (o.has("meshes")) { // 社区扩展：一节点多 mesh
                    meshes = readIntArr(o.get("meshes"));
                } else if (o.has("mesh")) {
                    meshes = new int[]{o.get("mesh").getAsInt()};
                } else {
                    meshes = new int[0];
                }
                GlbModel.Node node = new GlbModel.Node(nname, matrix, translation, rotation, scale, children, meshes);
                if (o.has("weights")) { // node 级 morph 权重（实例化，覆盖 mesh.weights）
                    node.weights = readFloatArr(o.get("weights"));
                }
                model.nodes.add(node);
            }
        }

        // ---- meshes / primitives ----
        if (meshesArr != null) {
            for (JsonElement e : meshesArr) {
                JsonObject o = e.getAsJsonObject();
                String mname = optString(o, "name", "");
                List<GlbModel.Primitive> prims = new ArrayList<>();
                JsonArray primArr = o.getAsJsonArray("primitives");
                for (JsonElement pe : primArr) {
                    JsonObject po = pe.getAsJsonObject();
                    JsonObject attrs = po.has("attributes") ? po.getAsJsonObject("attributes") : null;
                    float[] pos = null, nrm = null, uv = null;
                    if (attrs != null) {
                        if (attrs.has("POSITION")) {
                            pos = readAccessor(accessorsArr.get(attrs.get("POSITION").getAsInt()).getAsJsonObject(),
                                    bufferViewsArr, bin);
                        } else {
                            PolarisObjuilder.LOGGER.warn("[Glb] mesh '{}' primitive 无 POSITION，跳过", mname);
                            continue;
                        }
                        if (attrs.has("NORMAL")) {
                            nrm = readAccessor(accessorsArr.get(attrs.get("NORMAL").getAsInt()).getAsJsonObject(),
                                    bufferViewsArr, bin);
                        }
                        if (attrs.has("TEXCOORD_0")) {
                            uv = readAccessor(accessorsArr.get(attrs.get("TEXCOORD_0").getAsInt()).getAsJsonObject(),
                                    bufferViewsArr, bin);
                        }
                    }
                    int[] indices = null;
                    if (po.has("indices")) {
                        indices = readIndices(accessorsArr.get(po.get("indices").getAsInt()).getAsJsonObject(),
                                bufferViewsArr, bin);
                    }
                    int mat = po.has("material") ? po.get("material").getAsInt() : -1;
                    int mode = optInt(po, "mode", 4);
                    // morph targets（顶点动画，网格动画）：每 target 的 POSITION/NORMAL delta accessor
                    float[][] morphPos = null;
                    float[][] morphNrm = null;
                    if (po.has("targets")) {
                        JsonArray tArr = po.getAsJsonArray("targets");
                        int nT = tArr.size();
                        if (nT > 0) {
                            morphPos = new float[nT][];
                            morphNrm = new float[nT][];
                            for (int t = 0; t < nT; t++) {
                                JsonObject target = tArr.get(t).getAsJsonObject();
                                if (target.has("POSITION")) {
                                    morphPos[t] = readAccessor(
                                            accessorsArr.get(target.get("POSITION").getAsInt()).getAsJsonObject(),
                                            bufferViewsArr, bin);
                                }
                                if (target.has("NORMAL")) {
                                    morphNrm[t] = readAccessor(
                                            accessorsArr.get(target.get("NORMAL").getAsInt()).getAsJsonObject(),
                                            bufferViewsArr, bin);
                                }
                            }
                        }
                    }
                    prims.add(new GlbModel.Primitive(indices, pos, nrm, uv, mat, mode, morphPos, morphNrm));
                }
                GlbModel.Mesh mesh = new GlbModel.Mesh(mname, prims);
                if (o.has("weights")) { // mesh 级 morph 权重（静态默认；node.weights 可覆盖）
                    mesh.weights = readFloatArr(o.get("weights"));
                }
                model.meshes.add(mesh);
            }
        }

        // ---- materials（PBR 参数暂存） ----
        if (materialsArr != null) {
            for (JsonElement e : materialsArr) {
                JsonObject o = e.getAsJsonObject();
                JsonObject pbr = o.has("pbrMetallicRoughness")
                        ? o.getAsJsonObject("pbrMetallicRoughness") : null;
                float[] bcf = pbr != null && pbr.has("baseColorFactor")
                        ? readVec(pbr.get("baseColorFactor"), 4) : null;
                int bct = -1;
                float tOffU = 0f, tOffV = 0f, tScaleU = 1f, tScaleV = 1f, tRot = 0f;
                if (pbr != null && pbr.has("baseColorTexture")) {
                    JsonObject bcto = pbr.getAsJsonObject("baseColorTexture");
                    bct = bcto.get("index").getAsInt();
                    // KHR_texture_transform（Blender 材质 Mapping 节点导出）：offset/scale/rotation
                    if (bcto.has("extensions")) {
                        JsonObject bext = bcto.getAsJsonObject("extensions");
                        if (bext.has("KHR_texture_transform")) {
                            JsonObject t = bext.getAsJsonObject("KHR_texture_transform");
                            if (t.has("offset")) {
                                JsonArray off = t.getAsJsonArray("offset");
                                tOffU = off.get(0).getAsFloat();
                                tOffV = off.get(1).getAsFloat();
                            }
                            if (t.has("scale")) {
                                JsonArray sc = t.getAsJsonArray("scale");
                                tScaleU = sc.get(0).getAsFloat();
                                tScaleV = sc.get(1).getAsFloat();
                            }
                            if (t.has("rotation")) tRot = t.get("rotation").getAsFloat();
                        }
                    }
                }
                float metallic = pbr != null ? optFloat(pbr, "metallicFactor", 1f) : 1f;
                float rough = pbr != null ? optFloat(pbr, "roughnessFactor", 1f) : 1f;
                int mrt = pbr != null && pbr.has("metallicRoughnessTexture")
                        ? pbr.getAsJsonObject("metallicRoughnessTexture").get("index").getAsInt() : -1;
                int nt = o.has("normalTexture")
                        ? o.getAsJsonObject("normalTexture").get("index").getAsInt() : -1;
                float[] emf = o.has("emissiveFactor") ? readVec(o.get("emissiveFactor"), 3) : null;
                int et = o.has("emissiveTexture")
                        ? o.getAsJsonObject("emissiveTexture").get("index").getAsInt() : -1;
                // KHR_materials_emissive_strength：emissiveStrength（Blender 导出如 10）
                float ems = 1f;
                if (o.has("extensions")) {
                    JsonObject ext = o.getAsJsonObject("extensions");
                    if (ext.has("KHR_materials_emissive_strength")) {
                        JsonObject khr = ext.getAsJsonObject("KHR_materials_emissive_strength");
                        if (khr.has("emissiveStrength")) ems = khr.get("emissiveStrength").getAsFloat();
                    }
                }
                String alpha = optString(o, "alphaMode", "OPAQUE");
                // MASK 模式的 alpha 裁剪阈值（glTF 标准，默认 0.5）；Blender CLIP 混合模式会导出该字段
                float alphaCutoff = o.has("alphaCutoff") ? o.get("alphaCutoff").getAsFloat() : 0.5f;
                GlbModel.Material mat = new GlbModel.Material(bcf, bct, metallic, rough, mrt, nt, emf, et, ems, alpha, alphaCutoff);
                mat.doubleSided = o.has("doubleSided") && o.get("doubleSided").getAsBoolean();
                mat.texOffsetU = tOffU; mat.texOffsetV = tOffV;
                mat.texScaleU = tScaleU; mat.texScaleV = tScaleV;
                mat.texRotation = tRot;
                model.materials.add(mat);
            }
        }

        // ---- textures（source → image 索引） ----
        int[] texSource = new int[texturesArr != null ? texturesArr.size() : 0];
        if (texturesArr != null) {
            for (int i = 0; i < texturesArr.size(); i++) {
                JsonObject o = texturesArr.get(i).getAsJsonObject();
                texSource[i] = o.has("source") ? o.get("source").getAsInt() : -1;
                model.textures.add(texSource[i]);
            }
        }

        // ---- images（bufferView 内嵌贴图像素字节） ----
        if (imagesArr != null) {
            for (JsonElement e : imagesArr) {
                JsonObject o = e.getAsJsonObject();
                if (o.has("bufferView")) {
                    JsonObject bvo = bufferViewsArr.get(o.get("bufferView").getAsInt()).getAsJsonObject();
                    int buffer = optInt(bvo, "buffer", 0);
                    if (buffer == 0) {
                        int off = optInt(bvo, "byteOffset", 0);
                        int len = optInt(bvo, "byteLength", 0);
                        model.images.add(Arrays.copyOfRange(bin, off, off + len));
                    } else {
                        PolarisObjuilder.LOGGER.warn("[Glb] image buffer {} 非 BIN chunk，跳过", buffer);
                        model.images.add(null);
                    }
                } else {
                    // 外部 uri 贴图：本子工程不支持（静态显示可回退纯色）
                    PolarisObjuilder.LOGGER.warn("[Glb] 外部 uri 贴图不支持（{}）", optString(o, "uri", "?"));
                    model.images.add(null);
                }
            }
        }

        // ---- animations（子工程 2：node TRS 关键帧） ----
        if (animationsArr != null) {
            for (JsonElement e : animationsArr) {
                JsonObject o = e.getAsJsonObject();
                String aname = optString(o, "name", "");
                JsonArray samplersArr = o.has("samplers") ? o.getAsJsonArray("samplers") : null;
                JsonArray channelsArr = o.has("channels") ? o.getAsJsonArray("channels") : null;
                if (samplersArr == null || channelsArr == null) continue;
                List<GlbModel.Channel> chans = new ArrayList<>();
                for (JsonElement ce : channelsArr) {
                    JsonObject co = ce.getAsJsonObject();
                    JsonObject target = co.has("target") ? co.getAsJsonObject("target") : null;
                    if (target == null || !target.has("node") || !co.has("sampler")) {
                        PolarisObjuilder.LOGGER.warn("[Glb] 动画 '{}' 有 channel 缺 target.node/sampler，跳过", aname);
                        continue;
                    }
                    String path = optString(target, "path", "");
                    if (!path.equals("translation") && !path.equals("rotation")
                            && !path.equals("scale") && !path.equals("weights")) {
                        // 仅刚体 TRS + morph 权重动画（网格动画）：其它 path（如 pointer 扩展）跳过
                        PolarisObjuilder.LOGGER.warn("[Glb] 动画 '{}' channel path='{}' 跳过（不支持的 path）", aname, path);
                        continue;
                    }
                    int nodeIdx = target.get("node").getAsInt();
                    int samplerIdx = co.get("sampler").getAsInt();
                    if (samplerIdx < 0 || samplerIdx >= samplersArr.size()) continue;
                    JsonObject sampler = samplersArr.get(samplerIdx).getAsJsonObject();
                    if (!sampler.has("input") || !sampler.has("output")) continue;
                    String interpolation = optString(sampler, "interpolation", "LINEAR");
                    try {
                        float[] times = readAccessor(accessorsArr.get(sampler.get("input").getAsInt()).getAsJsonObject(),
                                bufferViewsArr, bin);
                        float[] values = readAccessor(accessorsArr.get(sampler.get("output").getAsInt()).getAsJsonObject(),
                                bufferViewsArr, bin);
                        chans.add(new GlbModel.Channel(nodeIdx, path, interpolation, times, values));
                    } catch (Exception ex) {
                        PolarisObjuilder.LOGGER.warn("[Glb] 动画 '{}' channel 数据解析失败，跳过", aname, ex);
                    }
                }
                model.animations.add(new GlbModel.Animation(aname, chans));
            }
        }

        // ---- scenes（根节点） ----
        if (scenesArr != null && !scenesArr.isEmpty()) {
            JsonObject scene0 = scenesArr.get(0).getAsJsonObject();
            if (scene0.has("nodes")) {
                for (JsonElement ne : scene0.getAsJsonArray("nodes")) {
                    model.sceneRoots.add(ne.getAsInt());
                }
            }
        } else if (!model.nodes.isEmpty()) {
            model.sceneRoots.add(0); // 无 scene 时回退第 0 节点
        }

        // ---- 碰撞盒（子工程 6：col: node → 模型空间 AABB，服务端 objplace/恢复生成碰撞体用） ----
        model.computeColliders();

        return model;
    }

    // ===================== accessor 解码 =====================

    /** 读取 accessor 顶点属性 → float 数组（count * componentCount） */
    private static float[] readAccessor(JsonObject acc, JsonArray bufferViews, byte[] bin) throws IOException {
        int componentType = acc.get("componentType").getAsInt();
        int count = acc.get("count").getAsInt();
        String type = acc.get("type").getAsString();
        int comps = componentsOf(type);
        boolean normalized = optBoolean(acc, "normalized", false);

        int byteOffset = optInt(acc, "byteOffset", 0);
        int byteLength = count * comps * bytesOf(componentType);
        if (acc.has("bufferView")) {
            JsonObject bvo = bufferViews.get(acc.get("bufferView").getAsInt()).getAsJsonObject();
            byteOffset += optInt(bvo, "byteOffset", 0);
            byteLength = optInt(bvo, "byteLength", byteLength);
        }
        checkRange(bin, byteOffset, byteLength, "accessor " + type);
        ByteBuffer buf = ByteBuffer.wrap(bin, byteOffset, byteLength).order(ByteOrder.LITTLE_ENDIAN);

        float[] out = new float[count * comps];
        for (int i = 0; i < out.length; i++) {
            out[i] = readComponent(buf, componentType);
        }
        if (normalized) {
            for (int i = 0; i < out.length; i++) {
                out[i] = normalize(componentType, out[i]);
            }
        }
        return out;
    }

    /** 读取索引 accessor → int[] */
    private static int[] readIndices(JsonObject acc, JsonArray bufferViews, byte[] bin) throws IOException {
        int componentType = acc.get("componentType").getAsInt();
        int count = acc.get("count").getAsInt();
        int byteOffset = optInt(acc, "byteOffset", 0);
        int byteLength = count * bytesOf(componentType);
        if (acc.has("bufferView")) {
            JsonObject bvo = bufferViews.get(acc.get("bufferView").getAsInt()).getAsJsonObject();
            byteOffset += optInt(bvo, "byteOffset", 0);
            byteLength = optInt(bvo, "byteLength", byteLength);
        }
        checkRange(bin, byteOffset, byteLength, "indices");
        ByteBuffer buf = ByteBuffer.wrap(bin, byteOffset, byteLength).order(ByteOrder.LITTLE_ENDIAN);
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            switch (componentType) {
                case CMP_BYTE -> out[i] = buf.get();
                case CMP_UBYTE -> out[i] = buf.get() & 0xFF;
                case CMP_SHORT -> out[i] = buf.getShort();
                case CMP_USHORT -> out[i] = buf.getShort() & 0xFFFF;
                case CMP_UINT -> out[i] = (int) buf.getInt(); // 仅支持 < 2^31
                default -> throw new IOException("索引 componentType " + componentType + " 不支持");
            }
        }
        return out;
    }

    private static float readComponent(ByteBuffer buf, int componentType) throws IOException {
        return switch (componentType) {
            case CMP_BYTE -> buf.get();
            case CMP_UBYTE -> buf.get() & 0xFF;
            case CMP_SHORT -> buf.getShort();
            case CMP_USHORT -> buf.getShort() & 0xFFFF;
            case CMP_UINT -> (float) (buf.getInt() & 0xFFFFFFFFL);
            case CMP_FLOAT -> buf.getFloat();
            default -> throw new IOException("componentType " + componentType + " 不支持");
        };
    }

    private static float normalize(int componentType, float v) {
        return switch (componentType) {
            case CMP_BYTE -> Math.max(v / 127f, -1f);
            case CMP_UBYTE -> v / 255f;
            case CMP_SHORT -> Math.max(v / 32767f, -1f);
            case CMP_USHORT -> v / 65535f;
            default -> v;
        };
    }

    private static int bytesOf(int componentType) throws IOException {
        return switch (componentType) {
            case CMP_BYTE, CMP_UBYTE -> 1;
            case CMP_SHORT, CMP_USHORT -> 2;
            case CMP_UINT, CMP_FLOAT -> 4;
            default -> throw new IOException("componentType " + componentType + " 不支持");
        };
    }

    private static int componentsOf(String type) throws IOException {
        return switch (type) {
            case "SCALAR" -> 1;
            case "VEC2" -> 2;
            case "VEC3" -> 3;
            case "VEC4" -> 4;
            case "MAT2" -> 4;
            case "MAT3" -> 9;
            case "MAT4" -> 16;
            default -> throw new IOException("accessor type " + type + " 不支持");
        };
    }

    private static void checkRange(byte[] bin, int offset, int length, String what) throws IOException {
        if (offset < 0 || length < 0 || offset + length > bin.length) {
            throw new IOException("accessor 数据越界（" + what + "）: offset=" + offset + " len=" + length
                    + " bin=" + bin.length);
        }
    }

    // ===================== JSON 小工具 =====================

    private static JsonArray optArr(JsonObject o, String key) {
        return o.has(key) ? o.getAsJsonArray(key) : null;
    }

    private static float[] readVec(JsonElement e, int n) {
        JsonArray arr = e.getAsJsonArray();
        float[] out = new float[n];
        for (int i = 0; i < n && i < arr.size(); i++) {
            out[i] = arr.get(i).getAsFloat();
        }
        return out;
    }

    /** 读取 JSON float 数组（morph weights 等） */
    private static float[] readFloatArr(JsonElement e) {
        JsonArray arr = e.getAsJsonArray();
        float[] out = new float[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            out[i] = arr.get(i).getAsFloat();
        }
        return out;
    }

    private static int[] readIntArr(JsonElement e) {
        JsonArray arr = e.getAsJsonArray();
        int[] out = new int[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            out[i] = arr.get(i).getAsInt();
        }
        return out;
    }

    private static int optInt(JsonObject o, String key, int def) {
        return o.has(key) ? o.get(key).getAsInt() : def;
    }

    private static float optFloat(JsonObject o, String key, float def) {
        return o.has(key) ? o.get(key).getAsFloat() : def;
    }

    private static boolean optBoolean(JsonObject o, String key, boolean def) {
        return o.has(key) ? o.get(key).getAsBoolean() : def;
    }

    private static String optString(JsonObject o, String key, String def) {
        return o.has(key) ? o.get(key).getAsString() : def;
    }
}
