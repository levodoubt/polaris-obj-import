package com.levodoubt.objuilder.command;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.levodoubt.objuilder.PolarisObjuilder;

import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;

/**
 * 命令映射表（子工程 8）——把模型里的 `$命令键` 解耦成 {@code config/polarisobjuilder/commands.json} 的键值对，
 * 改命令不用重导 glb、不用重开游戏（`/objcmd reload` 热重载）。
 *
 * <p>文件结构（见 `doc/2.1.13` §5）：
 * <pre>
 * {
 *   "commands": {
 *     "gun_take": { "cmd": "give %player% minecraft:apple 1", "perm": 2, "consume": "result", "feedback": false }
 *   }
 * }
 * </pre>
 *
 * <table>
 *   <tr><th>字段</th><th>必需</th><th>默认</th><th>说明</th></tr>
 *   <tr><td>cmd</td><td>✅</td><td>—</td><td>命令串（<b>不带前导 /</b>），支持 %player%/%uuid%/%part%/%placement%/%x%/%y%/%z% 占位符</td></tr>
 *   <tr><td>perm</td><td>❌</td><td>2</td><td>执行权限等级（/give 需要 2）</td></tr>
 *   <tr><td>consume</td><td>❌</td><td>"result"</td><td>result = 返回值 &gt; 0 才消耗；always = 执行即消耗；never = 永不消耗</td></tr>
 *   <tr><td>feedback</td><td>❌</td><td>false</td><td>true = 不抑制命令输出（失败提示可见）</td></tr>
 * </table>
 *
 * <p>要点：
 * <ul>
 *   <li>用 MC 自带 Gson 解析，不引入新依赖</li>
 *   <li>解析失败（JSON 语法/结构错误）→ <b>保留旧表</b>，避免一次手滑写坏 JSON 让所有交互失灵</li>
 *   <li>单条 entry 无效（缺 cmd 等）→ 跳过该条 + warn，其余照常加载</li>
 *   <li>执行：以玩家身份 {@code createCommandSourceStack().withPermission(perm)}；默认 {@code withSuppressedOutput()}
 *       防刷屏；无论成败都写服务端日志</li>
 * </ul>
 */
public final class CommandTable {
    /** 消耗策略 */
    public enum Consume { RESULT, ALWAYS, NEVER }

    /** 单条映射（cmd 非空；perm/consume/feedback 已填默认值） */
    public record Entry(String cmd, int perm, Consume consume, boolean feedback) {}

    /** 加载结果（供 /objcmd reload 反馈；失败时 count = 旧表条数） */
    public record LoadResult(boolean ok, int count, int skipped, String message) {}

    /** 当前生效的表（不可变；加载失败时保持原值） */
    private static volatile Map<String, Entry> TABLE = Map.of();

    private CommandTable() {}

    /** commands.json 路径：config/polarisobjuilder/commands.json（与模型目录 models/ 同级） */
    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("polarisobjuilder").resolve("commands.json");
    }

    public static boolean has(String key) {
        return key != null && TABLE.containsKey(key);
    }

    /** 消耗策略（键不存在时按 RESULT，调用方应先 has 判定） */
    public static Consume mode(String key) {
        Entry e = key == null ? null : TABLE.get(key);
        return e == null ? Consume.RESULT : e.consume();
    }

    /** 是否需要一次性消耗（NEVER → false） */
    public static boolean shouldConsume(String key) {
        return mode(key) != Consume.NEVER;
    }

    public static int size() {
        return TABLE.size();
    }

    /** 全部键（字典序，供 /objcmd list） */
    public static List<String> keys() {
        List<String> keys = new ArrayList<>(TABLE.keySet());
        Collections.sort(keys);
        return keys;
    }

    /** 取条目（可能为 null） */
    public static Entry get(String key) {
        return key == null ? null : TABLE.get(key);
    }

    /** 读取计划预览（/objcmd list 用） */
    public static String describe(String key) {
        Entry e = get(key);
        if (e == null) return "<未配置>";
        return e.cmd();
    }

    // ===================== 加载 =====================

    /**
     * 重新加载 commands.json（服务端启动 + /objcmd reload 调用）。
     * 文件不存在 → 空表（$ 命令键不可用）；解析失败 → 保留旧表。
     */
    public static synchronized LoadResult load(MinecraftServer server) {
        Path path = file();
        if (!Files.exists(path)) {
            TABLE = Map.of();
            PolarisObjuilder.LOGGER.info("[Objuilder] 未找到命令映射表 {}（$ 命令键暂不可用）", path);
            return new LoadResult(true, 0, 0, "未找到 " + path + "（$ 命令键暂不可用）");
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            List<String> problems = new ArrayList<>();
            Map<String, Entry> parsed = parseTable(root, problems);
            TABLE = Collections.unmodifiableMap(parsed);
            for (String p : problems) {
                PolarisObjuilder.LOGGER.warn("[Objuilder] commands.json: {}", p);
            }
            PolarisObjuilder.LOGGER.info("[Objuilder] 已加载命令映射表 {}（{} 个键{}）",
                    path, parsed.size(), problems.isEmpty() ? "" : "，跳过 " + problems.size() + " 条无效项");
            String msg = "已加载 " + parsed.size() + " 个命令键"
                    + (problems.isEmpty() ? "" : "（跳过 " + problems.size() + " 条无效项，见日志）");
            return new LoadResult(true, parsed.size(), problems.size(), msg);
        } catch (Exception ex) {
            PolarisObjuilder.LOGGER.warn("[Objuilder] 解析 {} 失败（保留旧表 {} 条）：{}",
                    path, TABLE.size(), ex.toString());
            return new LoadResult(false, TABLE.size(), 0,
                    "解析失败：" + ex.getMessage() + "（已保留旧表 " + TABLE.size() + " 条）");
        }
    }

    /** 解析 commands 对象；无效条目收集到 problems（不中断整体加载） */
    private static Map<String, Entry> parseTable(JsonElement root, List<String> problems) {
        Map<String, Entry> map = new LinkedHashMap<>();
        JsonObject rootObj = asObject(root, problems, "根节点必须是 JSON 对象");
        if (rootObj == null) return map;
        JsonElement commands = rootObj.get("commands");
        JsonObject commandsObj = asObject(commands, problems, "缺少 \"commands\" 对象");
        if (commandsObj == null) return map;
        for (Map.Entry<String, JsonElement> member : commandsObj.entrySet()) {
            String key = member.getKey();
            if (key == null || key.isBlank()) {
                problems.add("存在空键名，已跳过");
                continue;
            }
            JsonObject obj = asObject(member.getValue(), problems, "键 '" + key + "' 的值必须是对象");
            if (obj == null) continue;
            JsonElement cmdEl = obj.get("cmd");
            if (cmdEl == null || !cmdEl.isJsonPrimitive() || cmdEl.getAsString().isBlank()) {
                problems.add("键 '" + key + "' 缺少非空 \"cmd\" 字段，已跳过");
                continue;
            }
            String cmd = cmdEl.getAsString().trim();
            int perm = 2;
            if (obj.has("perm") && obj.get("perm").isJsonPrimitive()) {
                try {
                    perm = obj.get("perm").getAsInt();
                } catch (Exception ex) {
                    problems.add("键 '" + key + "' 的 perm 不是整数，按默认 2 处理");
                }
            }
            Consume consume = Consume.RESULT;
            if (obj.has("consume") && obj.get("consume").isJsonPrimitive()) {
                String raw = obj.get("consume").getAsString().trim().toLowerCase(Locale.ROOT);
                switch (raw) {
                    case "result" -> consume = Consume.RESULT;
                    case "always" -> consume = Consume.ALWAYS;
                    case "never" -> consume = Consume.NEVER;
                    default -> problems.add("键 '" + key + "' 的 consume 值 '" + raw
                            + "' 未知（应为 result/always/never），按 result 处理");
                }
            }
            boolean feedback = obj.has("feedback") && obj.get("feedback").isJsonPrimitive()
                    && obj.get("feedback").getAsBoolean();
            map.put(key, new Entry(cmd, perm, consume, feedback));
        }
        return map;
    }

    private static JsonObject asObject(JsonElement el, List<String> problems, String message) {
        if (el == null || !el.isJsonObject()) {
            problems.add(message);
            return null;
        }
        return el.getAsJsonObject();
    }

    // ===================== 执行 =====================

    /**
     * 执行映射命令（服务端、以玩家身份）。
     *
     * <p>⚠️ 1.21.1 的 {@code Commands.performPrefixedCommand} 返回 <b>void</b>（返回值为命令自身的
     * {@code Command.run} 结果，被 ExecutionContext 框架消费）。取结果的正确做法与 {@code /execute store result}
     * 同源：在 source 上挂 {@link CommandResultCallback}（{@code withCallback} + {@code CommandResultCallback::chain}
     * 与既有回调链式合并），执行完毕后读回调捕获的 (success, result)。
     *
     * @param key         映射键（调用方应先 {@link #has}）
     * @param partName    部件名（%part%），可 null
     * @param placementId 摆放 id（%placement%），-1 = 未知
     * @param center      碰撞盒世界中心（%x%/%y%/%z%），可 null
     * @return 命令返回值（&gt;0 = 生效，0 = 未生效/执行失败，-1 = 异常；供 consume="result" 判定）
     */
    public static int run(ServerPlayer player, String key, String partName, int placementId, Vec3 center) {
        Entry entry = get(key);
        if (entry == null) {
            PolarisObjuilder.LOGGER.warn("[Objuilder] 命令键 '{}' 不存在，未执行", key);
            return -1;
        }
        MinecraftServer server = player.getServer();
        if (server == null) return -1;
        String command = applyPlaceholders(entry.cmd(), player, partName, placementId, center);
        final boolean[] capturedSuccess = {false};
        final int[] capturedResult = {0};
        CommandSourceStack source = player.createCommandSourceStack()
                .withPermission(entry.perm())
                .withCallback((success, result) -> {
                    capturedSuccess[0] = success;
                    capturedResult[0] = result;
                }, CommandResultCallback::chain);
        if (!entry.feedback()) {
            source = source.withSuppressedOutput();   // silent=true 抑制反馈；回调仍有效
        }
        try {
            server.getCommands().performPrefixedCommand(source, command);
        } catch (Exception ex) {
            // performCommand 内部已吞掉大部分异常，这里是最后兜底（防模组自身崩服）
            PolarisObjuilder.LOGGER.warn("[Objuilder] 命令键 '{}' 执行异常：{}（命令：'{}'）",
                    key, ex.toString(), command);
            return -1;
        }
        // 未 success（解析失败/条件不成立被判定为失败）→ 视为未生效（返回 0 → consume="result" 回滚）
        int result = capturedSuccess[0] ? capturedResult[0] : 0;
        PolarisObjuilder.LOGGER.info("[Objuilder] 命令键 '{}' → '{}' 返回 {}（success={}，部件 '{}'，摆放 #{}）",
                key, command, result, capturedSuccess[0], partName, placementId);
        return result;
    }

    /** 占位符替换：%player% %uuid% %part% %placement% %x% %y% %z% */
    private static String applyPlaceholders(String cmd, ServerPlayer player,
                                            String partName, int placementId, Vec3 center) {
        String out = cmd;
        out = out.replace("%player%", player.getGameProfile().getName());
        out = out.replace("%uuid%", player.getUUID().toString());
        out = out.replace("%part%", partName == null ? "" : partName);
        out = out.replace("%placement%", Integer.toString(placementId));
        if (center != null) {
            out = out.replace("%x%", fmt(center.x))
                    .replace("%y%", fmt(center.y))
                    .replace("%z%", fmt(center.z));
        }
        return out;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
