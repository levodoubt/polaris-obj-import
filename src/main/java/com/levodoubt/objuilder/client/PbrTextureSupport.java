package com.levodoubt.objuilder.client;

import com.levodoubt.objuilder.PolarisObjuilder;

/**
 * PBR 光影支持入口（无 Iris 依赖，安全加载）。
 *
 * <p>客户端启动时调用 {@link #init()}：
 * <ul>
 *   <li>检测 Iris/Oculus（net.irisshaders.iris 包）是否存在；</li>
 *   <li>存在 → 加载 {@link IrisPbrBridge} 注册 {@code PbrCapableTexture} 的 PBR loader；
 *       此后光影渲染时自动为 glb/OBJ 材质生成法线 / labPBR specular 贴图；</li>
 *   <li>不存在 → 静默跳过（无光影下 PBR 不生效，符合「PBR 仅光影下」决策）。</li>
 * </ul>
 */
public class PbrTextureSupport {
    /** Iris 是否可用（已检测） */
    public static boolean irisAvailable = false;

    private PbrTextureSupport() {
    }

    /** 客户端 Mod bus / Client 初始化时调用一次 */
    public static void init() {
        try {
            Class.forName("net.irisshaders.iris.pbr.loader.PBRTextureLoaderRegistry");
            irisAvailable = true;
            IrisPbrBridge.register();
            PolarisObjuilder.LOGGER.info("[Objuilder] Iris 检测到 → PBR 光影适配已启用（法线/labPBR specular）");
        } catch (Throwable t) {
            irisAvailable = false;
            PolarisObjuilder.LOGGER.info("[Objuilder] 未检测到 Iris → PBR 光影适配跳过（无光影保持现状）");
        }
    }
}
