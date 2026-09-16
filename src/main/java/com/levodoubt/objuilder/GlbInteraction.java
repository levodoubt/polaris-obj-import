package com.levodoubt.objuilder;

import java.util.HashSet;
import java.util.Set;

import com.levodoubt.objuilder.animation.GlbAnimationController;
import com.levodoubt.objuilder.entity.ColliderEntity;
import com.levodoubt.objuilder.network.GlbAnimPayload;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 部件级交互（子工程 7 + B1 收尾·事项 4）——「动画 + 碰撞状态」统一开关，
 * 供右键（ColliderEntity.interact）与命令（/glbanim play/reset）共用，避免两处逻辑漂移。
 *
 * 语义（延续子工程 6 M2）：
 * - OPEN_PARTS：服务端内存态，记录"处于打开"的 (摆放id:动画名) 集合（重进游戏重置 → 门默认关闭）
 * - 开门（play）：广播 GlbAnimPayload(PLAY, ONCE) + OPEN_PARTS.add + 碰撞体 active=false（门洞可穿过）
 * - 关门（reset）：广播 GlbAnimPayload(RESET) + OPEN_PARTS.remove + 碰撞体 active=true（恢复阻挡）
 *
 * 事项 4 升级：OPEN_PARTS 从「动画名全局粒度」升级为「摆放 id + 动画名」粒度（key = "pid:anim"）。
 * - 右键 toggle：精确到某个摆放（多同名门可独立开关）
 * - 命令 /glbanim play/reset：保持全局语义（所有绑定该动画名的动态盒一起开/关）
 *
 * 与子工程 6 的差异：碰撞体不再 discard/respawn，而是保留实体、仅切换 canBeCollidedWith（active）。
 * 门开后玩家仍可右键同一碰撞体关门（避免"discard 后无交互载体"的竞态）。
 */
public final class GlbInteraction {
    private GlbInteraction() {}

    /** 服务端「部件开状态」：处于"开"的 (摆放id:动画名) key 集合（内存态；重进游戏重置为关=门默认关闭） */
    private static final Set<String> OPEN_PARTS = new HashSet<>();

    /**
     * 服务端「已消耗部件」（子工程 8）：key = "摆放id:部件名"。
     * `$命令键` 盒执行"成功"后进此集合（不可点 + 不挡人），用于防止 `/glbanim reset` 之类
     * 全局动画操作把它重新"复活"成挡人实体。
     *
     * <p>⚠️ 会话级内存态（与 OPEN_PARTS 一致）：重启后清零 → 已消耗盒恢复可点，
     * 玩家已拿到的物品不会回收 → 可重复刷物品。解法是命令侧自带守卫（计分板/剧情 flag），见 `doc/2.1.13` §6.8。
     */
    private static final Set<String> CONSUMED = new HashSet<>();

    /** key 编码：摆放 id + 动画名（动画名一般不含冒号） */
    private static String key(int placementId, String animName) {
        return placementId + ":" + animName;
    }

    /** 精确查询：某摆放的某动画是否处于打开状态 */
    public static boolean isOpen(int placementId, String animName) {
        return OPEN_PARTS.contains(key(placementId, animName));
    }

    // ===================== 已消耗部件（子工程 8） =====================

    /** 标记某摆放的某部件已消耗（命令盒执行成功后调用） */
    public static void markConsumed(int placementId, String partName) {
        if (partName != null && !partName.isEmpty()) {
            CONSUMED.add(key(placementId, partName));
        }
    }

    /** 回滚消耗标记（consume="result" 且命令未生效时调用） */
    public static void unmarkConsumed(int placementId, String partName) {
        if (partName != null && !partName.isEmpty()) {
            CONSUMED.remove(key(placementId, partName));
        }
    }

    /** 某摆放的某部件是否已消耗 */
    public static boolean isConsumed(int placementId, String partName) {
        return partName != null && !partName.isEmpty() && CONSUMED.contains(key(placementId, partName));
    }

    /**
     * 右键翻转（事项 4：精确到摆放 id）：门关 → 开门（play once + 碰撞解除）；门开 → 关门（reset + 碰撞恢复）。
     * 供 {@link ColliderEntity#interact} 调用。
     *
     * @return 受影响（active 被切换）的碰撞体数量
     */
    public static int toggle(ServerLevel level, int placementId, String animName) {
        boolean currentlyOpen = OPEN_PARTS.contains(key(placementId, animName));
        long gameTime = level.getGameTime();
        GlbAnimPayload payload = currentlyOpen
                ? new GlbAnimPayload(GlbAnimPayload.ACT_RESET, animName, (byte) 0, 0L, 1.0f, placementId)
                : new GlbAnimPayload(GlbAnimPayload.ACT_PLAY, animName,
                        (byte) GlbAnimationController.PlayMode.ONCE.ordinal(), gameTime, 1.0f, placementId);
        return setOpen(level, placementId, animName, !currentlyOpen, payload);
    }

    /**
     * 命令 play/reset 用（全局粒度）：所有绑定该动画名的动态盒一起开/关。
     * 自定义广播包（保留子工程 3 命令的 mode/startTime 语义）。
     *
     * @return 绑定该动画名的碰撞体数量
     */
    public static int setOpen(ServerLevel level, String animName, boolean open, GlbAnimPayload payload) {
        if (open) {
            for (Entity e : level.getAllEntities()) {
                if (e instanceof ColliderEntity ce && animName.equals(ce.getAnimBinding())) {
                    OPEN_PARTS.add(key(ce.getPlacementId(), animName));
                    ce.setActive(false);
                }
            }
        } else {
            OPEN_PARTS.removeIf(k -> k.endsWith(":" + animName));
            for (Entity e : level.getAllEntities()) {
                if (e instanceof ColliderEntity ce && animName.equals(ce.getAnimBinding())) {
                    // 子工程 8：已消耗的部件不复活（否则 /glbanim reset 会把"已拿走"的盒重新变成挡人实体）
                    if (isConsumed(ce.getPlacementId(), ce.getPartName())) continue;
                    ce.setActive(true);
                }
            }
        }
        PacketDistributor.sendToAllPlayers(payload);
        return countBound(level, animName);
    }

    /** 精确 setOpen（右键内部）：仅操作该摆放绑定的动态盒（多同名门独立开关） */
    private static int setOpen(ServerLevel level, int placementId, String animName,
                               boolean open, GlbAnimPayload payload) {
        String k = key(placementId, animName);
        if (open) {
            OPEN_PARTS.add(k);
        } else {
            OPEN_PARTS.remove(k);
        }
        PacketDistributor.sendToAllPlayers(payload);
        int count = 0;
        for (Entity e : level.getAllEntities()) {
            if (e instanceof ColliderEntity ce && placementId == ce.getPlacementId()
                    && animName.equals(ce.getAnimBinding())) {
                // 关门 → 碰撞恢复（active=true 阻挡）；已消耗的部件不复活（子工程 8）
                if (!open && isConsumed(ce.getPlacementId(), ce.getPartName())) {
                    count++;
                    continue;
                }
                ce.setActive(!open); // 开门 → 碰撞解除（active=false 可穿过）
                count++;
            }
        }
        return count;
    }

    /** 绑定某动画名的碰撞体数量 */
    private static int countBound(ServerLevel level, String animName) {
        int count = 0;
        for (Entity e : level.getAllEntities()) {
            if (e instanceof ColliderEntity ce && animName.equals(ce.getAnimBinding())) count++;
        }
        return count;
    }
}
