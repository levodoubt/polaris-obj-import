package com.levodoubt.objuilder.debug;

import com.levodoubt.objuilder.Config;
import com.levodoubt.objuilder.PolarisObjuilder;

import java.util.function.Supplier;

import net.minecraft.commands.CommandSourceStack;
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
 * 持久化在 `config/polarisobjuilder-common.toml` 的 `debug`（指令内改后立即落盘，重启仍生效）。
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

    /**
     * 命令反馈开关（`/objdebug feedback false`）：是否保留**被本模组执行的映射命令**（commands.json 的 `$键`，
     * 如 `give`）自身产生的原版反馈（"已将 1 个…给予…"）。
     * 默认 true = 有反馈；false = 以 {@code withSuppressedOutput()} 执行（静默，但返回值仍可取 → 消耗判定不受影响）。
     */
    private static volatile boolean commandFeedback = true;

    /** 调试输出是否开启（true = 打印） */
    public static boolean on() {
        return enabled;
    }

    /** 是否保留映射命令的原版反馈（false = 屏蔽 give 之类的回显） */
    public static boolean commandFeedback() {
        return commandFeedback;
    }

    /** 仅设置运行期开关（幂等，不落盘）；落盘走 {@link Config#saveDebug(boolean)} */
    public static void set(boolean value) {
        enabled = value;
    }

    /** 仅设置命令反馈开关（幂等，不落盘）；落盘走 {@link Config#saveCommandFeedback(boolean)} */
    public static void setCommandFeedback(boolean value) {
        commandFeedback = value;
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
        try {
            setCommandFeedback(Config.commandFeedbackConfigured());
        } catch (Throwable ignored) {
            // 同上
        }
    }

    // ===================== 聊天框输出（命令反馈） =====================

    /**
     * 命令反馈（chat）：开关关闭时静默。
     * 只用于 `[Objuilder]` / `[Glb]` 前缀的本模组消息；**指令失败提示（无前缀）不在此列**。
     */
    public static void chat(CommandSourceStack source, Supplier<Component> message, boolean broadcastToOps) {
        if (enabled) {
            source.sendSuccess(message, broadcastToOps);
        }
    }

    /** 命令反馈（失败分支，同上） */
    public static void chatFail(CommandSourceStack source, Component message) {
        if (enabled) {
            source.sendFailure(message);
        }
    }

    /** 控制通道：`/objdebug` 自身的回执**不受开关影响**（否则关掉后无法确认开关状态） */
    public static void control(CommandSourceStack source, Supplier<Component> message, boolean broadcastToOps) {
        source.sendSuccess(message, broadcastToOps);
    }

    /** 控制通道（失败分支） */
    public static void controlFail(CommandSourceStack source, Component message) {
        source.sendFailure(message);
    }

    /** 本模组聊天前缀（隐藏目标）：`[Objuilder]` 与 `[Glb]` */
    private static final String[] CHAT_PREFIXES = {"[Objuilder]", "[Glb]"};

    /** 匹配 `§x` 颜色/格式码（消息可能是 `Component.literal("§c[Objuilder] …")`，此时 § 是正文一部分） */
    private static final java.util.regex.Pattern FORMAT_CODE = java.util.regex.Pattern.compile("\u00a7.");

    /**
     * 该聊天消息是否为本模组前缀（`[Objuilder]` / `[Glb]`，大小写不敏感）。
     * 先剥掉 `§x` 格式码再判断，保证 `Component.literal("§c[Objuilder] …")` 与
     * `Component.literal("[Objuilder] …").withStyle(RED)` 两种写法都能命中。
     */
    public static boolean isModChat(Component message) {
        if (message == null) {
            return false;
        }
        String plain = FORMAT_CODE.matcher(message.getString()).replaceAll("").trim();
        for (String prefix : CHAT_PREFIXES) {
            if (plain.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return true;
            }
        }
        return false;
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
