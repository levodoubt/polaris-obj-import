package com.levodoubt.objuilder;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

// An example config class. This is not required, but it's a good idea to have one to keep your config organized.
// Demonstrates how to use Neo's config APIs
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue LOG_DIRT_BLOCK = BUILDER
            .comment("Whether to log the dirt block on common setup")
            .define("logDirtBlock", true);

    public static final ModConfigSpec.IntValue MAGIC_NUMBER = BUILDER
            .comment("A magic number")
            .defineInRange("magicNumber", 42, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.ConfigValue<String> MAGIC_NUMBER_INTRODUCTION = BUILDER
            .comment("What you want the introduction message to be for the magic number")
            .define("magicNumberIntroduction", "The magic number is... ");

    // a list of strings that are treated as resource locations for items
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ITEM_STRINGS = BUILDER
            .comment("A list of items to log on common setup.")
            .defineListAllowEmpty("items", List.of("minecraft:iron_ingot"), () -> "", Config::validateItemName);

    /** 调试输出总开关：false = 隐藏本模组的一切调试输出（控制台诊断日志 + 屏上诊断提示） */
    public static final ModConfigSpec.BooleanValue DEBUG = BUILDER
            .comment("调试输出总开关。false = 隐藏本模组的一切调试输出：",
                    "  · 控制台诊断日志（[Objuilder]/[Glb] 前缀行、阴影诊断、交互拾取、摆放碰撞盒世界盒…）",
                    "  · 屏上诊断提示（actionbar 的\"触发失败：已消耗/不可交互/准星偏离 N 格\"等）",
                    "不受影响：error 级报错（含异常栈）与指令反馈（/objplace 等 chat 回显）。",
                    "游戏内用 /objdebug true|false|on|off 切换（会立即写回本文件）。")
            .define("debug", true);

    /** 映射命令（commands.json 的 `$键`）原版反馈总开关：false = 被本模组执行的命令（give 等）不再回显 */
    public static final ModConfigSpec.BooleanValue COMMAND_FEEDBACK = BUILDER
            .comment("被本模组执行的映射命令（commands.json 的 $键，如 give）是否保留原版反馈。",
                    "true（默认）= 保留（\"已将 1 个…给予…\"）；false = 以 withSuppressedOutput() 执行。",
                    "命令的返回值不受影响（消耗判定 consume=result 照常工作）。",
                    "游戏内用 /objdebug feedback true|false 切换（会立即写回本文件）。")
            .define("commandFeedback", true);

    static final ModConfigSpec SPEC = BUILDER.build();

    /** 读配置里的调试开关（供 ObjDebug 同步；配置未加载时会抛，调用方需 try/catch） */
    public static boolean debugConfigured() {
        return DEBUG.get();
    }

    /** 写调试开关并落盘（/objdebug 调用；落盘失败抛异常，由指令侧捕获提示） */
    public static void saveDebug(boolean value) {
        DEBUG.set(value);
        SPEC.save();
    }

    /** 读配置里的命令反馈开关（供 ObjDebug 同步） */
    public static boolean commandFeedbackConfigured() {
        return COMMAND_FEEDBACK.get();
    }

    /** 写命令反馈开关并落盘（/objdebug feedback 调用） */
    public static void saveCommandFeedback(boolean value) {
        COMMAND_FEEDBACK.set(value);
        SPEC.save();
    }

    private static boolean validateItemName(final Object obj) {
        return obj instanceof String itemName && BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(itemName));
    }
}
