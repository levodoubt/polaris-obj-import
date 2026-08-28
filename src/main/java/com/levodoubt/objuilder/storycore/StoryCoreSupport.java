package com.levodoubt.objuilder.storycore;

import com.levodoubt.objuilder.PolarisObjuilder;

/**
 * StoryCore（polarisstorycore）软依赖检测与桥接加载（B1 收尾·事项 6）。
 *
 * 用 Class.forName 检测剧情核心是否存在：
 * - 存在 → 加载 {@link StoryCoreBridge}（唯一引用 StoryCore API 的类）注册剧情动作 'polarisobjuilder:anim'
 * - 不存在 → 静默跳过，B1 独立可用（无 NoClassDefFoundError）
 *
 * 重要：本类不得直接引用任何 StoryCore 类型；对 StoryCore 的引用全部收敛在 StoryCoreBridge，
 * 且仅在确认 StoryCore 存在后才加载该类（其方法体引用的 storycore 类此时可正常解析）。
 */
public final class StoryCoreSupport {
    private static boolean checked = false;

    private StoryCoreSupport() {
    }

    /** 检测 StoryCore 并注册联动动作（FMLCommonSetupEvent 调用，此时所有 mod 构造器已完成） */
    public static synchronized void init() {
        if (checked) return;
        checked = true;
        try {
            Class.forName("com.levodoubt.polarisstorycore.engine.StoryActionRegistry");
            StoryCoreBridge.register(); // 仅 StoryCore 存在时加载该类
            PolarisObjuilder.LOGGER.info(
                    "[Objuilder] 检测到 StoryCore → 已注册剧情动作 'polarisobjuilder:anim'");
        } catch (ClassNotFoundException e) {
            PolarisObjuilder.LOGGER.info(
                    "[Objuilder] 未检测到 StoryCore → 跳过剧情联动（B1 独立可用）");
        } catch (Throwable t) {
            PolarisObjuilder.LOGGER.warn(
                    "[Objuilder] StoryCore 联动初始化失败（不影响 B1 本体）", t);
        }
    }
}
