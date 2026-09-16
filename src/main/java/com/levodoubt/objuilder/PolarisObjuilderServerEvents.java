package com.levodoubt.objuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.levodoubt.objuilder.animation.GlbAnimationController;
import com.levodoubt.objuilder.command.CommandTable;
import com.levodoubt.objuilder.core.GlbModel;
import com.levodoubt.objuilder.core.GlbParser;
import com.levodoubt.objuilder.entity.ColliderEntity;
import com.levodoubt.objuilder.entity.DomainEntity;
import com.levodoubt.objuilder.network.CacheReleasePayload;
import com.levodoubt.objuilder.network.GlbAnimPayload;
import com.levodoubt.objuilder.server.PlacementSavedData;
import com.levodoubt.objuilder.server.PlacementSavedData.Placement;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 服务端命令（GAME bus，双端加载，命令在服务端执行）。
 *
 * 动画控制（子工程 3）：
 *   /glbanim play <name> once|loop / stop <name> / reset <name>
 *   触发链路：剧情引擎（A1 COMMAND 触发器）→ 服务端执行 /glbanim → 广播控制包 → 客户端播放。
 *
 * 全功能摆放 + 管理（子工程 5）：
 *   /objplace <ref> <x y z> <yaw> <scale>  —— 服务端权威摆放（坐标支持 ~/^ 相对）
 *   /objlist                                —— 列出所有摆放
 *   /objremove <id>                         —— 移除指定摆放（删实体 + 删 SavedData 记录）
 *   /objclear                               —— 清空全部摆放
 *   摆放清单存服务端 SavedData（overworld），重进游戏自动恢复。
 *
 * 命令映射表（子工程 8）：
 *   /objcmd reload | list | test &lt;键&gt;      —— commands.json 热重载 / 列出键 / 调试执行
 *   文件：config/polarisobjuilder/commands.json（模型里的 `$键` 在这里映射成真实命令）
 */
@EventBusSubscriber(modid = PolarisObjuilder.MODID, bus = EventBusSubscriber.Bus.GAME)
public class PolarisObjuilderServerEvents {
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        // ===== 动画控制（子工程 3）=====
        dispatcher.register(Commands.literal("glbanim")
                .then(Commands.literal("play")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .then(Commands.literal("once")
                                        .executes(ctx -> play(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"),
                                                GlbAnimationController.PlayMode.ONCE)))
                                .then(Commands.literal("loop")
                                        .executes(ctx -> play(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"),
                                                GlbAnimationController.PlayMode.LOOP)))))
                .then(Commands.literal("stop")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> control(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name"),
                                        GlbAnimPayload.ACT_STOP, "已停止"))))
                .then(Commands.literal("reset")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> control(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name"),
                                        GlbAnimPayload.ACT_RESET, "已重置")))));

        // ===== 全功能摆放 + 管理（子工程 5）=====
        // /objplace <x y z> <yaw> <scale> <ref>
        // ref 放最后用 greedyString（内建、有序列化器、支持中文/任意路径），避免自定义 ArgumentType 无法序列化
        var objPlace = Commands.literal("objplace")
                .then(Commands.argument("pos", Vec3Argument.vec3())
                        .then(Commands.argument("yaw", FloatArgumentType.floatArg())
                                .then(Commands.argument("scale", FloatArgumentType.floatArg(0.01f, 100f))
                                        .then(Commands.argument("ref", StringArgumentType.greedyString())
                                                .executes(ctx -> objplace(ctx.getSource(),
                                                        Vec3Argument.getVec3(ctx, "pos"),
                                                        FloatArgumentType.getFloat(ctx, "yaw"),
                                                        FloatArgumentType.getFloat(ctx, "scale"),
                                                        StringArgumentType.getString(ctx, "ref")))))));
        dispatcher.register(objPlace);
        // /objlist
        dispatcher.register(Commands.literal("objlist")
                .executes(ctx -> objlist(ctx.getSource())));
        // /objremove <id>
        dispatcher.register(Commands.literal("objremove")
                .then(Commands.argument("id", IntegerArgumentType.integer(1))
                        .executes(ctx -> objremove(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "id")))));
        // /objclear
        dispatcher.register(Commands.literal("objclear")
                .executes(ctx -> objclear(ctx.getSource())));
        // /objcmd（子工程 8）：命令映射表热重载 / 列表 / 调试执行
        dispatcher.register(Commands.literal("objcmd")
                .then(Commands.literal("reload")
                        .executes(ctx -> objcmdReload(ctx.getSource())))
                .then(Commands.literal("list")
                        .executes(ctx -> objcmdList(ctx.getSource())))
                .then(Commands.literal("test")
                        .then(Commands.argument("key", StringArgumentType.word())
                                .executes(ctx -> objcmdTest(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "key"))))));
    }

    /** 服务端启动完成：加载命令映射表 + 按 SavedData 记录重新 spawn 摆放实体 + 碰撞体（持久化恢复，重进游戏模型自动出现） */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        // 子工程 8：命令映射表（commands.json）——$ 命令键的键值对在这里进内存
        CommandTable.load(server);
        ServerLevel overworld = server.overworld();
        PlacementSavedData data = PlacementSavedData.get(overworld);
        int restored = 0;
        for (Placement p : data.all()) {
            // 已有对应实体（uuid 仍在）→ 补齐 placementId（旧存档/升级前摆放）后跳过；否则重建
            Entity existing = p.entityUuid() != null ? overworld.getEntity(p.entityUuid()) : null;
            if (existing instanceof DomainEntity de) {
                de.setPlacementId(p.id());
                continue;
            }
            DomainEntity e = new DomainEntity(PolarisObjuilder.DOMAIN_ENTITY.get(), overworld);
            e.setPos(p.x(), p.y(), p.z());
            e.setYRot(p.yaw());
            e.setModelRef(p.ref());
            e.setModelScale(p.scale());
            e.setPlacementId(p.id());
            overworld.addFreshEntity(e);
            data.put(new Placement(p.id(), p.ref(), p.x(), p.y(), p.z(), p.yaw(), p.scale(),
                    e.getUUID(), p.colliderUuids())); // 回填新实体 UUID
            restored++;
        }
        // 碰撞体补齐 + 摆放 id 回填：父实体存在但碰撞体缺失（旧存档无记录 / 已删除）→ 重新生成；
        // 已存在的碰撞体也按 SavedData 回填 placementId（子工程 8 复检修复：老存档的碰撞体没有该字段，
        // 不补会让命令里的 %placement% 取到 -1、/objremove %placement% 失效）
        int restoredColliders = 0;
        int backfilled = 0;
        for (Placement p : data.all()) {
            Entity parent = p.entityUuid() != null ? overworld.getEntity(p.entityUuid()) : null;
            if (parent == null) continue; // 父实体都还没恢复（理论上不会发生）
            List<UUID> alive = new ArrayList<>();
            for (UUID cu : p.colliderUuids()) {
                Entity ce = cu != null ? overworld.getEntity(cu) : null;
                if (ce == null) continue;
                alive.add(cu);
                if (ce instanceof ColliderEntity collider && collider.getPlacementId() != p.id()) {
                    collider.setPlacementId(p.id());
                    backfilled++;
                }
            }
            if (alive.size() == p.colliderUuids().size()) continue;
            List<UUID> spawned = spawnColliders(overworld, p);
            List<UUID> merged = new ArrayList<>(alive);
            merged.addAll(spawned);
            data.put(new Placement(p.id(), p.ref(), p.x(), p.y(), p.z(), p.yaw(), p.scale(),
                    p.entityUuid(), merged));
            restoredColliders += spawned.size();
        }
        if (restored > 0 || restoredColliders > 0 || backfilled > 0) {
            PolarisObjuilder.LOGGER.info("[Objuilder] 已恢复 {} 个摆放模型、{} 个碰撞体（SavedData）；回填 {} 个碰撞体摆放 id",
                    restored, restoredColliders, backfilled);
        }
    }

    // ===================== glbanim（子工程 3）+ 碰撞状态联动（子工程 6 M2） =====================

    /**
     * play：广播播放包（服务端 gameTime tick 作时间基准）+ 服务端「部件开状态」翻转（动态碰撞盒不阻挡）。
     * 与右键共用 {@link GlbInteraction#setOpen}，保证两处逻辑一致（不漂移）。
     */
    private static int play(CommandSourceStack source, String name, GlbAnimationController.PlayMode mode) {
        ServerLevel level = source.getLevel();
        long gameTime = level.getGameTime();
        int disabled = GlbInteraction.setOpen(level, name, true,
                new GlbAnimPayload(GlbAnimPayload.ACT_PLAY, name, (byte) mode.ordinal(), gameTime, 1.0f, -1));
        source.sendSuccess(() -> Component.literal(String.format(
                "§a[Glb] 触发动画 '%s' → %s（碰撞盒 %s）", name,
                mode == GlbAnimationController.PlayMode.ONCE ? "once" : "loop",
                disabled > 0 ? "关闭 " + disabled + " 个" : "无")), false);
        return 1;
    }

    /** stop / reset：广播对应控制包；reset 同时把绑定动画的碰撞盒置回"关"（阻挡，走 GlbInteraction） */
    private static int control(CommandSourceStack source, String name, byte action, String verb) {
        ServerLevel level = source.getLevel();
        String extra = "";
        if (action == GlbAnimPayload.ACT_RESET) {
            int enabled = GlbInteraction.setOpen(level, name, false,
                    new GlbAnimPayload(GlbAnimPayload.ACT_RESET, name, (byte) 0, 0L, 1.0f, -1));
            extra = enabled > 0 ? "（碰撞盒恢复 " + enabled + " 个）" : "（无动态碰撞盒）";
        } else {
            // stop：只广播，不碰碰撞状态
            PacketDistributor.sendToAllPlayers(new GlbAnimPayload(action, name, (byte) 0, 0L, 1.0f, -1));
        }
        String msg = extra;
        source.sendSuccess(() -> Component.literal(String.format(
                "§a[Glb] 动画 '%s' %s%s", name, verb, msg)), false);
        return 1;
    }

    // ===================== objplace / objlist / objremove / objclear（子工程 5） =====================

    /** 权威摆放：解析碰撞盒 → yaw 校验 → 写 SavedData → spawn 实体 + 碰撞体（自动同步客户端） */
    private static int objplace(CommandSourceStack source, Vec3 pos, float yaw, float scale, String ref) {
        ServerLevel level = source.getLevel();
        String normalized = normalizeRef(ref);
        if (normalized == null) {
            source.sendFailure(Component.literal(
                    "引用无效：绝对路径必须在 config/polarisobjuilder/models/ 下；相对路径用 models/<file>"));
            return 0;
        }
        File modelFile = modelsDir().resolve(normalized).toFile();
        if (!modelFile.exists()) {
            source.sendFailure(Component.literal("模型文件不存在: " + modelFile.getAbsolutePath()));
            return 0;
        }
        // 子工程 6：解析模型碰撞盒（col: node）；OBJ 无碰撞盒概念 → 空列表（纯视觉，yaw 不限）
        List<GlbModel.ColliderDef> colliders = parseColliders(normalized);
        if (!colliders.isEmpty() && !isAxisAlignedYaw(yaw)) {
            source.sendFailure(Component.literal(
                    "模型含碰撞盒，yaw 必须为 90° 的倍数（0/90/180/270）以保证碰撞盒与视觉精确对齐"));
            return 0;
        }
        PlacementSavedData data = PlacementSavedData.get(source.getServer().overworld());
        int id = data.allocateId();
        DomainEntity e = new DomainEntity(PolarisObjuilder.DOMAIN_ENTITY.get(), level);
        e.setPos(pos.x(), pos.y(), pos.z());
        e.setYRot(yaw);
        e.setModelRef(normalized);
        e.setModelScale(scale);
        e.setPlacementId(id); // 摆放 id：客户端按它独立加载几何/动画（多同名门独立开关）
        level.addFreshEntity(e);
        // 生成碰撞体（每个 col:/use: 盒一个 ColliderEntity；动态盒若已在"开"状态则不生成）
        Placement placeRec = new Placement(id, normalized,
                pos.x(), pos.y(), pos.z(), yaw, scale, e.getUUID(), List.of());
        List<UUID> colliderUuids = spawnColliders(level, placeRec);
        data.put(new Placement(id, normalized, pos.x(), pos.y(), pos.z(), yaw, scale,
                e.getUUID(), colliderUuids));
        // 子工程 9：光源盒（light:）→ 在区域内填充光源方块
        int lightCount = placeLights(level, placeRec);
        // 子工程 8：统计（挡人 col: / 仅交互 use: / 带命令 $），便于现场验证命名是否符合预期
        long blocking = colliders.stream().filter(c -> c.collides).count();
        long useOnly = colliders.size() - blocking;
        long withCmd = colliders.stream().filter(GlbModel.ColliderDef::hasCommand).count();
        source.sendSuccess(() -> Component.literal(String.format(
                "§a[Objuilder] 已摆放 #%d %s @ %.1f %.1f %.1f yaw=%.1f scale=%.2f"
                        + "（碰撞盒 %d 个：挡人 %d / 仅交互 %d / 带命令 %d；光源方块 %d 个）",
                id, normalized, pos.x(), pos.y(), pos.z(), yaw, scale,
                colliders.size(), blocking, useOnly, withCmd, lightCount)), true);
        return 1;
    }

    /** 列出所有摆放 */
    private static int objlist(CommandSourceStack source) {
        PlacementSavedData data = PlacementSavedData.get(source.getServer().overworld());
        List<Placement> all = data.all();
        if (all.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7[Objuilder] 无摆放模型"), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal(
                String.format("§7[Objuilder] 已摆放 %d 个模型：", all.size())), false);
        for (Placement p : all) {
            source.sendSuccess(() -> Component.literal(String.format(
                    "  §f#%d §a%s §7@ %.1f/%.1f/%.1f yaw=%.1f scale=%.2f",
                    p.id(), p.ref(), p.x(), p.y(), p.z(), p.yaw(), p.scale())), false);
        }
        return 1;
    }

    /** 移除指定摆放：删父实体 + 连带删碰撞体（跨维度按 UUID 定位）+ 删 SavedData 记录；广播客户端释放该摆放缓存 */
    private static int objremove(CommandSourceStack source, int id) {
        PlacementSavedData data = PlacementSavedData.get(source.getServer().overworld());
        Placement p = data.get(id);
        if (p == null) {
            source.sendFailure(Component.literal("未找到摆放 #" + id));
            return 0;
        }
        discardEntity(source.getServer(), p.entityUuid());
        for (UUID cu : p.colliderUuids()) {
            discardEntity(source.getServer(), cu);
        }
        data.remove(id);
        // 事项 1/4：广播客户端释放该摆放独立的几何/纹理缓存
        PacketDistributor.sendToAllPlayers(new CacheReleasePayload(p.id()));
        source.sendSuccess(() -> Component.literal("§a[Objuilder] 已移除摆放 #" + id), true);
        return 1;
    }

    /** 清空全部摆放（连带删碰撞体）；逐摆放广播客户端释放缓存 */
    private static int objclear(CommandSourceStack source) {
        PlacementSavedData data = PlacementSavedData.get(source.getServer().overworld());
        List<Placement> all = data.all();
        int removed = 0;
        for (Placement p : all) {
            if (discardEntity(source.getServer(), p.entityUuid())) removed++;
            for (UUID cu : p.colliderUuids()) {
                discardEntity(source.getServer(), cu);
            }
        }
        data.clear();
        // 事项 1/4：清空后逐摆放广播释放客户端缓存
        for (Placement p : all) {
            PacketDistributor.sendToAllPlayers(new CacheReleasePayload(p.id()));
        }
        if (!all.isEmpty()) {
            PolarisObjuilder.LOGGER.info("[Objuilder] 已清空全部摆放 → 广播释放 {} 个摆放缓存", all.size());
        }
        int cleared = removed;
        source.sendSuccess(() -> Component.literal(
                String.format("§a[Objuilder] 已清空 %d 个摆放模型", cleared)), true);
        return 1;
    }

    // ===================== objcmd（子工程 8）：命令映射表 =====================

    /** /objcmd reload：重新读取 commands.json（失败保留旧表并提示） */
    private static int objcmdReload(CommandSourceStack source) {
        CommandTable.LoadResult result = CommandTable.load(source.getServer());
        if (result.ok()) {
            source.sendSuccess(() -> Component.literal("§a[Objuilder] " + result.message()
                    + "\n§7文件：" + CommandTable.file()), true);
            return 1;
        }
        source.sendFailure(Component.literal("§c[Objuilder] " + result.message()));
        return 0;
    }

    /** /objcmd list：列出所有命令键 */
    private static int objcmdList(CommandSourceStack source) {
        List<String> keys = CommandTable.keys();
        if (keys.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7[Objuilder] 命令表为空（" + CommandTable.file() + "）"), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal(
                String.format("§7[Objuilder] 命令表 %d 个键（%s）：", keys.size(), CommandTable.file())), false);
        for (String key : keys) {
            CommandTable.Entry e = CommandTable.get(key);
            String cmd = "";
            String more = "";
            if (e != null) {
                List<String> cmds = e.cmds();
                cmd = cmds.isEmpty() ? "" : cmds.get(0);
                if (cmds.size() > 1) more = " §8(+" + (cmds.size() - 1) + " 条)";
            }
            if (cmd.length() > 70) cmd = cmd.substring(0, 70) + "…";
            String flags = e == null ? "" : String.format(" §8[perm=%d consume=%s%s]",
                    e.perm(), e.consume().name().toLowerCase(java.util.Locale.ROOT),
                    e.feedback() ? " feedback" : "");
            String line = "  §f" + key + " §7→ §a" + cmd + more + flags;
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    /** /objcmd test &lt;键&gt;：以自己为对象执行一次（调试用，不消耗、不改任何部件状态） */
    private static int objcmdTest(CommandSourceStack source, String key) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!CommandTable.has(key)) {
            source.sendFailure(Component.literal("§c[Objuilder] 命令键不存在: " + key
                    + "（可用 /objcmd list 查看）"));
            return 0;
        }
        int result = CommandTable.run(player, key, "test", -1, player.position());
        source.sendSuccess(() -> Component.literal(String.format(
                "§a[Objuilder] 测试命令键 '%s' → 返回值 %d（未消耗任何部件；多命令为各条返回值之和）", key, result)), false);
        return result > 0 ? 1 : 0;
    }

    /**
     * 引用归一化：
     * - 绝对路径且在 models/ 下 → 归一为 models/&lt;相对&gt; 引用
     * - 相对路径 → 补 models/ 前缀（已是则原样）
     * - 绝对路径不在 models/ 下 → null（warn 拒绝，要求先拷贝进 models/）
     */
    private static String normalizeRef(String ref) {
        if (ref == null || ref.isBlank()) return null;
        File f = new File(ref);
        if (f.isAbsolute()) {
            try {
                java.nio.file.Path p = f.toPath().toAbsolutePath().normalize();
                java.nio.file.Path base = modelsDir().toAbsolutePath().normalize();
                if (p.startsWith(base)) {
                    return "models/" + base.relativize(p).toString().replace('\\', '/');
                }
            } catch (Exception ignored) {
            }
            return null;
        }
        return ref.startsWith("models/") ? ref : "models/" + ref;
    }

    /** 跨所有维度按 UUID 定位并移除实体（父摆放 DomainEntity / 碰撞体 ColliderEntity） */
    private static boolean discardEntity(MinecraftServer server, UUID uuid) {
        if (uuid == null) return false;
        for (ServerLevel lvl : server.getAllLevels()) {
            Entity e = lvl.getEntity(uuid);
            if (e instanceof DomainEntity || e instanceof ColliderEntity) {
                e.discard();
                return true;
            }
        }
        return false;
    }

    // ===================== 子工程 6 · 碰撞盒辅助 =====================

    /**
     * 解析模型引用中的碰撞盒（col: node → 模型空间 AABB）。
     * 仅 glb 支持碰撞盒；OBJ 无 node 概念 → 返回空列表。解析失败返回空（不影响视觉摆放）。
     */
    private static List<GlbModel.ColliderDef> parseColliders(String ref) {
        File f = modelsDir().resolve(ref).toFile();
        if (!f.exists() || !f.getName().toLowerCase().endsWith(".glb")) return List.of();
        try {
            return GlbParser.parse(f).colliders;
        } catch (Exception e) {
            PolarisObjuilder.LOGGER.warn("[Objuilder] 解析碰撞盒失败: {} ({})", ref, e.toString());
            return List.of();
        }
    }

    /** yaw 是否为 90° 倍数（0/90/180/270；负数同样判定） */
    private static boolean isAxisAlignedYaw(float yaw) {
        float m = Math.abs(yaw % 90f);
        return m < 1e-3f || Math.abs(m - 90f) < 1e-3f;
    }

    /**
     * 碰撞盒世界 AABB：模型空间 8 角点 → 按 yaw 旋转 → ×scale → +pos。
     * 旋转变换与渲染一致（DomainEntityRenderer 用 Axis.YP.rotationDegrees(yaw)，逆时针：
     * x' = x·cosθ + z·sinθ, z' = -x·sinθ + z·cosθ），保证碰撞与视觉对齐。
     * yaw 已限 90° 倍数，变换后仍是轴对齐盒。
     */
    private static AABB worldBox(float minXIn, float minYIn, float minZIn,
                                 float maxXIn, float maxYIn, float maxZIn,
                                 Vec3 pos, float yaw, float scale) {
        double rad = Math.toRadians(yaw);
        double cos = Math.cos(rad), sin = Math.sin(rad);
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        double[] xs = {minXIn, maxXIn}, ys = {minYIn, maxYIn}, zs = {minZIn, maxZIn};
        for (double x : xs) {
            for (double y : ys) {
                for (double z : zs) {
                    double wx = x * cos + z * sin;      // 绕 Y 旋转（yaw）
                    double wz = -x * sin + z * cos;
                    double px = pos.x + wx * scale;
                    double py = pos.y + y * scale;
                    double pz = pos.z + wz * scale;
                    minX = Math.min(minX, px); maxX = Math.max(maxX, px);
                    minY = Math.min(minY, py); maxY = Math.max(maxY, py);
                    minZ = Math.min(minZ, pz); maxZ = Math.max(maxZ, pz);
                }
            }
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * 为摆放生成碰撞体：每个碰撞盒（`col:` / `use:`）→ 一个 ColliderEntity（世界 AABB）。
     * 动态盒（@Anim 绑定）若已在 OPEN_PARTS（该摆放该动画已开）→ 跳过（不阻挡，状态由 GlbInteraction 维护）。
     * 子工程 8：`use:` 盒照常生成（要能右键），但 collides=false → 恒不挡人。
     * 返回生成的碰撞体 UUID 列表。
     */
    private static List<UUID> spawnColliders(ServerLevel level, Placement p) {
        List<UUID> uuids = new ArrayList<>();
        List<GlbModel.ColliderDef> colliders = parseColliders(p.ref());
        if (colliders.isEmpty()) return uuids;
        Vec3 pos = new Vec3(p.x(), p.y(), p.z());
        for (GlbModel.ColliderDef c : colliders) {
            // 事项 4：按「摆放 id + 动画名」精确判断该动态盒是否已开（多同名门独立）
            if (c.isDynamic() && GlbInteraction.isOpen(p.id(), c.animBinding)) continue;
            ColliderEntity ce = new ColliderEntity(PolarisObjuilder.COLLIDER_ENTITY.get(), level);
            ce.setPlacementId(p.id());
            ce.setBox(worldBox(c.minX, c.minY, c.minZ, c.maxX, c.maxY, c.maxZ, pos, p.yaw(), p.scale()),
                    c.name, c.animBinding, c.cmdKey, c.collides);
            level.addFreshEntity(ce);
            uuids.add(ce.getUUID());
        }
        return uuids;
    }

    // ===================== 光源盒（子工程 9）：`light:` 前缀 → 世界内填充光源方块 =====================

    /** 光源方块等级（`light:` 盒填充用）：15 = 满亮 */
    private static final int LIGHT_BLOCK_LEVEL = 15;

    /** 解析 `light:` 光源盒（与碰撞盒同一份模型解析；失败返回空表） */
    private static List<GlbModel.LightDef> parseLights(String ref) {
        File f = modelsDir().resolve(ref).toFile();
        if (!f.exists() || !f.getName().toLowerCase().endsWith(".glb")) return List.of();
        try {
            return GlbParser.parse(f).lights;
        } catch (Exception e) {
            PolarisObjuilder.LOGGER.warn("[Objuilder] 解析光源盒失败: {} ({})", ref, e.toString());
            return List.of();
        }
    }

    /** 摆放/恢复时：在 light: 盒区域内填充光源方块（只填空气，不覆盖已有方块）；返回放置数量 */
    private static int placeLights(ServerLevel level, Placement p) {
        List<GlbModel.LightDef> lights = parseLights(p.ref());
        if (lights.isEmpty()) return 0;
        Vec3 pos = new Vec3(p.x(), p.y(), p.z());
        int placed = 0;
        for (GlbModel.LightDef l : lights) {
            AABB box = worldBox(l.minX, l.minY, l.minZ, l.maxX, l.maxY, l.maxZ, pos, p.yaw(), p.scale());
            placed += fillLightBox(level, box);
        }
        return placed;
    }

    /** 移除摆放时：清理该摆放 light: 盒区域内的光源方块（只清 minecraft:light，不动玩家建筑） */
    private static int clearLights(ServerLevel level, Placement p) {
        List<GlbModel.LightDef> lights = parseLights(p.ref());
        if (lights.isEmpty()) return 0;
        Vec3 pos = new Vec3(p.x(), p.y(), p.z());
        int cleared = 0;
        for (GlbModel.LightDef l : lights) {
            AABB box = worldBox(l.minX, l.minY, l.minZ, l.maxX, l.maxY, l.maxZ, pos, p.yaw(), p.scale());
            cleared += clearLightBox(level, box);
        }
        return cleared;
    }

    /** 摆放记录不带维度；恢复/清理约定用主世界，另加命令所在维度（两者相同则去重） */
    private static List<ServerLevel> lightLevels(CommandSourceStack source) {
        ServerLevel lv = source.getLevel();
        ServerLevel ow = source.getServer().overworld();
        return lv == ow ? List.of(lv) : List.of(lv, ow);
    }

    /** 填充一个世界 AABB 内的光源方块（上限 512 格防呆；只填空气） */
    private static int fillLightBox(ServerLevel level, AABB box) {
        int placed = 0;
        for (BlockPos bp : BlockPos.betweenClosed(
                BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX - 1.0E-4, box.maxY - 1.0E-4, box.maxZ - 1.0E-4))) {
            if (placed >= 512) {
                PolarisObjuilder.LOGGER.warn("[Objuilder] light: 盒超过 512 格，已截断");
                break;
            }
            BlockState st = level.getBlockState(bp);
            if (st.is(Blocks.LIGHT) || !st.isAir()) continue;
            level.setBlock(bp, Blocks.LIGHT.defaultBlockState()
                    .setValue(LightBlock.LEVEL, LIGHT_BLOCK_LEVEL), 3);
            placed++;
        }
        return placed;
    }

    /** 清理一个世界 AABB 内的光源方块（只清 minecraft:light） */
    private static int clearLightBox(ServerLevel level, AABB box) {
        int cleared = 0;
        for (BlockPos bp : BlockPos.betweenClosed(
                BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX - 1.0E-4, box.maxY - 1.0E-4, box.maxZ - 1.0E-4))) {
            if (level.getBlockState(bp).is(Blocks.LIGHT)) {
                level.setBlock(bp, Blocks.AIR.defaultBlockState(), 3);
                cleared++;
            }
        }
        return cleared;
    }

    /** config/polarisobjuilder 目录 */
    private static java.nio.file.Path modelsDir() {
        return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                .resolve("polarisobjuilder");
    }
}
