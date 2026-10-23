package com.levodoubt.objuilder.command;

import com.levodoubt.objuilder.debug.ObjDebug;

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
 * 命令映射表（子工程 8）——把模型里的 `$命令键` 解耦成 {@code config/obj_importer/commands.json} 的键值对，
 * 改命令不用重导 glb、不用重开游戏（`/objcmd reload` 热重载）。
 *
 * <p>文件结构（见 `doc/2.1.13` §5）：
 * <pre>
 * {
 *   "commands": {
 *     "gun_take": { "cmds": ["give %player% minecraft:apple 1", "objremove %placement%"],
 *                   "perm": 2, "consume": "result", "feedback": false },
 *     "leaf_click": { "cmd": "playsound minecraft:block.lever.click master %player%", "consume": "never" }
 *   }
 * }
 * </pre>
 *
 * <table>
 *   <tr><th>字段</th><th>必需</th><th>默认</th><th>说明</th></tr>
 *   <tr><td>cmd / cmds</td><td>✅（二选一）</td><td>—</td><td><b>一键多命令</b>：{@code cmd} 单条字符串，或 {@code cmds} 字符串数组（顺序执行，任一条 success=false 即中止后续；两者同时出现时用 cmds 并记 warn）。命令串<b>不带前导 /</b>，支持 %player%/%uuid%/%part%/%placement%/%x%/%y%/%z% 占位符</td></tr>
 *   <tr><td>perm</td><td>❌</td><td>2</td><td>执行权限等级（/give 需要 2）</td></tr>
 *   <tr><td>consume</td><td>❌</td><td>"result"</td><td>result = 返回值 &gt; 0 才消耗；always = 执行即消耗；never = 永不消耗</td></tr>
 *   <tr><td>feedback</td><td>❌</td><td>false</td><td>true = 不抑制命令输出（失败提示可见）</td></tr>
 * </table>
 *
 * <p>要点：
 * <ul>
 *   <li>用 MC 自带 Gson 解析，不引入新依赖</li>
 *   <li>解析失败（JSON 语法/结构错误）→ <b>保留旧表</b>，避免一次手滑写坏 JSON 让所有交互失灵</li>
 *   <li>单条 entry 无效（缺 cmd/cmds、cmds 非数组等）→ 跳过该条 + warn，其余照常加载</li>
 *   <li>执行：以玩家身份 {@code createCommandSourceStack().withPermission(perm)}；默认 {@code withSuppressedOutput()}
 *       防刷屏；无论成败都写服务端日志</li>
 * </ul>
 */
public final class CommandTable {
    /** 消耗策略 */
    public enum Consume { RESULT, ALWAYS, NEVER }

    /**
     * 单条映射（cmds 非空、按顺序执行；perm/consume/feedback 已填默认值）。
     *
     * <p><b>一键多命令</b>（子工程 8 扩展）：按顺序执行，**任一条未生效（success=false）即中止后续命令**，
     * 整体返回 0 → {@code consume:"result"} 回滚消耗。守卫惯用法：
     * <pre>
     *   "cmds": ["execute if score %player% flag matches 1 run give %player% minecraft:apple 1",
     *            "objremove %placement%"]
     * </pre>
     * 第一条守卫不成立时第二条不执行 → 摆件不会被误删、物品也不会白给。
     */
    public record Entry(List<String> cmds, int perm, Consume consume, boolean feedback) {
        public Entry {
            cmds = List.copyOf(cmds);
        }
    }

    /** 加载结果（供 /objcmd reload 反馈；失败时 count = 旧表条数） */
    public record LoadResult(boolean ok, int count, int skipped, String message) {}

    /** 当前生效的表（不可变；加载失败时保持原值） */
    private static volatile Map<String, Entry> TABLE = Map.of();

    private CommandTable() {}

    /** commands.json 路径：config/obj_importer/commands.json（与模型目录 models/ 同级） */
    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("obj_importer").resolve("commands.json");
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

    /** 读取计划预览（/objcmd list 用；多命令用 " ; " 连接） */
    public static String describe(String key) {
        Entry e = get(key);
        if (e == null) return "<未配置>";
        return String.join(" ; ", e.cmds());
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
            ObjDebug.info("[Objuilder] 未找到命令映射表 {}（$ 命令键暂不可用）", path);
            return new LoadResult(true, 0, 0, "未找到 " + path + "（$ 命令键暂不可用）");
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            List<String> problems = new ArrayList<>();
            Map<String, Entry> parsed = parseTable(root, problems);
            TABLE = Collections.unmodifiableMap(parsed);
            for (String p : problems) {
                ObjDebug.warn("[Objuilder] commands.json: {}", p);
            }
            ObjDebug.info("[Objuilder] 已加载命令映射表 {}（{} 个键{}）",
                    path, parsed.size(), problems.isEmpty() ? "" : "，跳过 " + problems.size() + " 条无效项");
            String msg = "已加载 " + parsed.size() + " 个命令键"
                    + (problems.isEmpty() ? "" : "（跳过 " + problems.size() + " 条无效项，见日志）");
            return new LoadResult(true, parsed.size(), problems.size(), msg);
        } catch (Exception ex) {
            ObjDebug.warn("[Objuilder] 解析 {} 失败（保留旧表 {} 条）：{}",
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
            List<String> cmds = parseCommands(obj, key, problems);
            if (cmds.isEmpty()) {
                problems.add("键 '" + key + "' 缺少非空 \"cmd\"/\"cmds\" 字段，已跳过");
                continue;
            }
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
            map.put(key, new Entry(cmds, perm, consume, feedback));
        }
        return map;
    }

    /**
     * 解析 {@code cmd}（单条字符串）/ {@code cmds}（字符串数组 = 一键多命令）。
     * 两者同时出现时以 cmds 为准并记 warn；空串/非字符串项跳过；返回空列表 = 该 entry 无效。
     */
    private static List<String> parseCommands(JsonObject obj, String key, List<String> problems) {
        List<String> out = new ArrayList<>();
        JsonElement cmdsEl = obj.get("cmds");
        if (cmdsEl != null && cmdsEl.isJsonArray()) {
            if (obj.has("cmd")) {
                problems.add("键 '" + key + "' 同时含 cmd 与 cmds，已忽略 cmd（只用 cmds）");
            }
            for (JsonElement el : cmdsEl.getAsJsonArray()) {
                if (el != null && el.isJsonPrimitive()) {
                    String s = el.getAsString().trim();
                    if (!s.isEmpty()) out.add(s);
                } else {
                    problems.add("键 '" + key + "' 的 cmds 含非字符串项，已跳过该项");
                }
            }
        } else {
            if (cmdsEl != null) {
                problems.add("键 '" + key + "' 的 cmds 不是数组，已忽略（单条可用 cmd）");
            }
            JsonElement cmdEl = obj.get("cmd");
            if (cmdEl != null && cmdEl.isJsonPrimitive()) {
                String s = cmdEl.getAsString().trim();
                if (!s.isEmpty()) out.add(s);
            }
        }
        return out;
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
     * @param partialOut  可选出参：把"中止前已经生效的返回值合计"写回 {@code partialOut[0]}
     *                    （调用方据此判断"是否已产生副作用"，避免回滚导致重复发放，见 v0.4.5）
     * @return 各条命令返回值之和（&gt;0 = 整体生效；任一条未生效 → 立即中止后续命令并返回 0；-1 = 异常）；
     *         供 {@code consume="result"} 判定
     */
    public static int run(ServerPlayer player, String key, String partName, int placementId, Vec3 center) {
        return run(player, key, partName, placementId, center, null);
    }

    /** 带"已生效合计"出参的重载（见 {@link #run(ServerPlayer, String, String, int, Vec3)}） */
    public static int run(ServerPlayer player, String key, String partName, int placementId, Vec3 center,
                          int[] partialOut) {
        Entry entry = get(key);
        if (entry == null) {
            ObjDebug.warn("[Objuilder] 命令键 '{}' 不存在，未执行", key);
            return -1;
        }
        MinecraftServer server = player.getServer();
        if (server == null) return -1;
        List<String> cmds = entry.cmds();
        int total = 0;
        for (int i = 0; i < cmds.size(); i++) {
            String command = applyPlaceholders(cmds.get(i), player, partName, placementId, center);
            final boolean[] capturedSuccess = {false};
            final int[] capturedResult = {0};
            CommandSourceStack source = player.createCommandSourceStack()
                    .withPermission(entry.perm())
                    .withCallback((success, result) -> {
                        capturedSuccess[0] = success;
                        capturedResult[0] = result;
                    }, CommandResultCallback::chain);
            if (!entry.feedback() || !ObjDebug.commandFeedback()) {
                // 静默执行：① 该键在 commands.json 里写了 feedback=false；② 全局开关 /objdebug feedback false
                // 开（用户要求"屏蔽 give 之类的命令反馈"）。silent=true 抑制回显；命令返回值/回调不受影响
                source = source.withSuppressedOutput();
            }
            try {
                server.getCommands().performPrefixedCommand(source, command);
            } catch (Exception ex) {
                // performCommand 内部已吞掉大部分异常，这里是最后兜底（防模组自身崩服）
                ObjDebug.warn("[Objuilder] 命令键 '{}' 第 {}/{} 条执行异常：{}（命令：'{}'）",
                        key, i + 1, cmds.size(), ex.toString(), command);
                if (partialOut != null) partialOut[0] = total;
                return -1;
            }
            if (!capturedSuccess[0]) {
                // 未 success（解析失败 / execute if 条件不成立被判失败）→ 中止后续命令，整体视为未生效
                // → 返回 0 → consume="result" 回滚消耗。守卫惯用法（cmds 第一条做条件、第二条删摆件）依赖此中止语义。
                ObjDebug.warn(
                        "[Objuilder] 命令键 '{}' 第 {}/{} 条未生效 → 中止后续 {} 条（命令：'{}'；部件 '{}'，摆放 #{}）",
                        key, i + 1, cmds.size(), cmds.size() - i - 1, command, partName, placementId);
                if (partialOut != null) partialOut[0] = total;
                return 0;
            }
            total += capturedResult[0];
        }
        if (cmds.size() == 1) {
            ObjDebug.info("[Objuilder] 命令键 '{}' → '{}' 返回 {}（部件 '{}'，摆放 #{}）",
                    key, cmds.get(0), total, partName, placementId);
        } else {
            ObjDebug.info("[Objuilder] 命令键 '{}' → {} 条命令全部生效，返回 {}（部件 '{}'，摆放 #{}）",
                    key, cmds.size(), total, partName, placementId);
        }
        return total;
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
