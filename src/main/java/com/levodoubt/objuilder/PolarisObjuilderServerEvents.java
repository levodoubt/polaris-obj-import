package com.levodoubt.objuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.levodoubt.objuilder.animation.GlbAnimationController;
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

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
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
    }

    /** 服务端启动完成：按 SavedData 记录重新 spawn 摆放实体 + 碰撞体（持久化恢复，重进游戏模型自动出现） */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
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
        // 碰撞体补齐：父实体存在但碰撞体缺失（旧存档无记录 / 已删除）→ 重新生成
        int restoredColliders = 0;
        for (Placement p : data.all()) {
            Entity parent = p.entityUuid() != null ? overworld.getEntity(p.entityUuid()) : null;
            if (parent == null) continue; // 父实体都还没恢复（理论上不会发生）
            List<UUID> alive = new ArrayList<>();
            for (UUID cu : p.colliderUuids()) {
                if (cu != null && overworld.getEntity(cu) != null) alive.add(cu);
            }
            if (alive.size() == p.colliderUuids().size()) continue;
            List<UUID> spawned = spawnColliders(overworld, p);
            List<UUID> merged = new ArrayList<>(alive);
            merged.addAll(spawned);
            data.put(new Placement(p.id(), p.ref(), p.x(), p.y(), p.z(), p.yaw(), p.scale(),
                    p.entityUuid(), merged));
            restoredColliders += spawned.size();
        }
        if (restored > 0 || restoredColliders > 0) {
            PolarisObjuilder.LOGGER.info("[Objuilder] 已恢复 {} 个摆放模型、{} 个碰撞体（SavedData）",
                    restored, restoredColliders);
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
        // 生成碰撞体（每个 col: 盒一个 ColliderEntity；动态盒若已在"开"状态则不生成）
        List<UUID> colliderUuids = spawnColliders(level, new Placement(id, normalized,
                pos.x(), pos.y(), pos.z(), yaw, scale, e.getUUID(), List.of()));
        data.put(new Placement(id, normalized, pos.x(), pos.y(), pos.z(), yaw, scale,
                e.getUUID(), colliderUuids));
        source.sendSuccess(() -> Component.literal(String.format(
                "§a[Objuilder] 已摆放 #%d %s @ %.1f %.1f %.1f yaw=%.1f scale=%.2f（碰撞盒 %d 个）",
                id, normalized, pos.x(), pos.y(), pos.z(), yaw, scale, colliders.size())), true);
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

    /**
     * 引用归一化：
     * - 绝对路径且在 models/ 下 → 归一为 models/<相对> 引用
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
    private static AABB worldBox(GlbModel.ColliderDef c, Vec3 pos, float yaw, float scale) {
        double rad = Math.toRadians(yaw);
        double cos = Math.cos(rad), sin = Math.sin(rad);
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        double[] xs = {c.minX, c.maxX}, ys = {c.minY, c.maxY}, zs = {c.minZ, c.maxZ};
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
     * 为摆放生成碰撞体：每个 col: 盒 → 一个 ColliderEntity（世界 AABB）。
     * 动态盒（@Anim 绑定）若已在 OPEN_PARTS（该摆放该动画已开）→ 跳过（不阻挡，状态由 GlbInteraction 维护）。
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
            ce.setBox(worldBox(c, pos, p.yaw(), p.scale()), c.animBinding);
            level.addFreshEntity(ce);
            uuids.add(ce.getUUID());
        }
        return uuids;
    }

    /** config/polarisobjuilder 目录 */
    private static java.nio.file.Path modelsDir() {
        return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                .resolve("polarisobjuilder");
    }
}
