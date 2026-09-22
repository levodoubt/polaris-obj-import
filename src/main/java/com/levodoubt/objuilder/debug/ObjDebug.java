package com.levodoubt.objuilder.debug;

import com.levodoubt.objuilder.Config;
import com.levodoubt.objuilder.PolarisObjuilder;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * 调试输出总开关（v0.4.3）——游戏内 `/objdebug false` 一键静默本模组的一切调试输出。
 *
 * <p>为什么需要它：本模组为排查模型/碰撞/光影/交互问题加了大量诊断输出（阴影诊断、交互拾取、
 * 摆放碰撞体世界盒、glb 材质解析、命令执行结果… 共 90 余处）。这些是"开发期工具"，不是运行时日志；
 * 正常游玩时既刷屏又干扰现场判断（真问题时混在几百行诊断里看不见）。
 *
 * <p>开关语义（`false` = 隐藏一切调试输出）：
 * <ul>
 *   <li><b>控制台日志</b>：本模组的 info / warn / debug 全部静默（含 `[Objuilder]`、`[Glb]` 前缀行）</li>
 *   <li><b>屏上诊断提示</b>：actionbar 的"触发失败：部件已消耗 / 不可交互 / 准星偏离 N 格 / 已拒绝"等
 *       —— 这些是排查用提示，随开关一并静默（见 {@link #hint}）</li>
 *   <li><b>不受影响</b>：① error 级（异常栈、处理失败）；② 指令反馈（`/objplace`、`/objclear`、`/objdebug`
 *       自身的 chat 回显）—— 关掉调试不等于关掉报错与命令回执</li>
 * </ul>
 *
 * <p>作用域与持久化：运行期是**静态开关**（单机下客户端与集成服务端同 JVM → 一次切换两端同时静默）；
 * 持久化在 `config/polarisobjbuilder-common.toml` 的 `debug`（指令内改后立即落盘，重启仍生效）。
 *
 * <p>⚠️ 新增诊断输出请一律走 {@link #info}/{@link #warn}/{@link #hint}，不要直接用
 * {@code PolarisObjuilder.LOGGER} —— 否则 `/objdebug false` 关不掉它（与 §7-13 同一类教训：
 * 加了诊断却忘了挂开关，等于给现场留了个"关不掉的话痨"）。
 */
public final class ObjDebug {
    private ObjDebug() {
    }

    /** 默认 true = 保持开发/测试期行为（能看到全部诊断）；发布前可在配置里改默认值 */
    private static volatile boolean enabled = true;

    /** 调试输出是否开启（true = 打印） */
    public static boolean on() {
        return enabled;
    }

    /** 仅设置运行期开关（幂等，不落盘）；落盘走 {@link Config#saveDebug(boolean)} */
    public static void set(boolean value) {
        enabled = value;
    }

    /**
     * 从配置同步开关（mod 初始化 common setup、服务端启动时调用）。
     * 用 try/catch 包住：配置尚未加载/已卸载时 {@code get()} 会抛，此时保持当前值而不是崩。
     */
    public static void syncFromConfig() {
        try {
            set(Config.debugConfigured());
        } catch (Throwable ignored) {
            // 配置还没加载好：保持默认值（true），等下一次同步
        }
    }

    // ===================== 日志出口（error 除外：报错永不被开关关掉） =====================

    public static void info(String format, Object... args) {
        if (enabled) {
            PolarisObjuilder.LOGGER.info(format, args);
        }
    }

    public static void warn(String format, Object... args) {
        if (enabled) {
            PolarisObjuilder.LOGGER.warn(format, args);
        }
    }

    public static void debug(String format, Object... args) {
        if (enabled) {
            PolarisObjuilder.LOGGER.debug(format, args);
        }
    }

    /** 错误/异常：**始终输出**，不受开关影响（关掉调试不等于关掉报错） */
    public static void error(String format, Object... args) {
        PolarisObjuilder.LOGGER.error(format, args);
    }

    /**
     * 玩家可见的调试提示（actionbar）。开关关闭时静默 —— 这类提示（"触发失败：…"、"准星偏离 N 格"）
     * 是排查工具，正常游玩时属于噪音。
     */
    public static void hint(Player player, Component message) {
        if (enabled && player != null && message != null) {
            player.displayClientMessage(message, true);
        }
    }
}
