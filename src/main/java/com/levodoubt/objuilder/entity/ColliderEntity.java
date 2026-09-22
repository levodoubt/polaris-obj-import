package com.levodoubt.objuilder.entity;

import com.levodoubt.objuilder.GlbInteraction;
import com.levodoubt.objuilder.PolarisObjuilder;
import com.levodoubt.objuilder.command.CommandTable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 隐形碰撞体实体（子工程 6 + 7 + 8）——用 MC 引擎原生单 AABB 碰撞拼出多 AABB 碰撞：
 * - 每个 `col:` / `use:` 盒 → 一个 ColliderEntity，AABB = 该盒经 yaw/scale/平移后的世界盒
 * - 无渲染、无重力、不可动、不 tick（纯静态碰撞体）
 * - isPushable = true：1.21.1 的 {@code Level.getEntityCollisions} 用 Entity::isPushable
 *   过滤，只有 isPushable 的实体才参与玩家/生物碰撞（这就是挡人的前提）；
 *   push(Vec3)/push(Entity) 空实现屏蔽被推动 → 玩家撞上来碰撞体纹丝不动
 * - canBeCollidedWith = collides && active（同步字段）：
 *   `col:` 恒参与碰撞（静态 true / 动态盒 @Anim 随服务端 OPEN_PARTS 翻转）；
 *   `use:`（子工程 8）恒 false → 玩家可穿过（物件类交互盒不挡人）
 * - isPickable = pickEnabled && (animBinding != null || cmdKey != null)（子工程 7 + 8）：
 *   配置了动画或命令键的才可被准星选中并右键；纯碰撞盒不可选中（无"可交互"高亮）
 * - interact（子工程 7 + 8）：服务端按「动画 → 状态 → 命令」顺序执行——
 *   动画 toggle（复用 GlbInteraction）+ `$命令键` 查 commands.json 执行 + 一次性消耗（可回滚）
 * - AABB 通过 SynchedEntityData 同步 6 个 double（LONG 承载位），客户端实体也有正确碰撞箱
 *   （玩家客户端移动预测用 getBoundingBox()）
 * - 持久化：依赖父摆放（PlacementSavedData）恢复重建；实体本身兜底写 AABB + 部件名/动画/命令/是否碰撞
 *   （active / pickEnabled 不持久化：重启强制恢复"阻挡 + 可点"，与 OPEN_PARTS / CONSUMED 内存态清零一致）
 */
public class ColliderEntity extends Entity {
    // 碰撞盒坐标用 double 精度同步（MC 原生只有 FLOAT，float 精度 ~1e-7 临界于 collideX 的 1e-7 容差，
    // 会导致客户端预测"贴墙卡住"）。用原生 LONG 序列化器承载 double 的 64 位（doubleToLongBits），
    // 精度完整且无需注册自定义序列化器（NeoForge 禁止 mod 直接 registerSerializer）。
    private static final EntityDataAccessor<Long> DATA_MIN_X =
            SynchedEntityData.defineId(ColliderEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Long> DATA_MIN_Y =
            SynchedEntityData.defineId(ColliderEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Long> DATA_MIN_Z =
            SynchedEntityData.defineId(ColliderEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Long> DATA_MAX_X =
            SynchedEntityData.defineId(ColliderEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Long> DATA_MAX_Y =
            SynchedEntityData.defineId(ColliderEntity.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Long> DATA_MAX_Z =
            SynchedEntityData.defineId(ColliderEntity.class, EntityDataSerializers.LONG);

    /** 绑定的动画名（`col:xxx@AnimName`，子工程 7 交互判定 + 服务端 M2 状态开关用）；空串 = 静态盒（纯碰撞，不可交互） */
    private static final EntityDataAccessor<String> DATA_ANIM_BINDING =
            SynchedEntityData.defineId(ColliderEntity.class, EntityDataSerializers.STRING);

    /** 是否阻挡（子工程 7）：门关=true 挡人；门开=false 可穿过（保留实体供再次右键关门）。随 entityData 同步客户端移动预测 */
    private static final EntityDataAccessor<Boolean> DATA_ACTIVE =
            SynchedEntityData.defineId(ColliderEntity.class, EntityDataSerializers.BOOLEAN);

    /** 部件名（子工程 8：`col:`/`use:` 之后、`@`/`$` 之前的名字）；空串 = 未知。用于 %part% 占位符 + 消耗状态 key + 日志 */
    private static final EntityDataAccessor<String> DATA_PART_NAME =
            SynchedEntityData.defineId(ColliderEntity.class, EntityDataSerializers.STRING);

    /** 命令映射键（子工程 8：`$Key` 后缀，commands.json 的键）；空串 = 无命令 */
    private static final EntityDataAccessor<String> DATA_CMD_KEY =
            SynchedEntityData.defineId(ColliderEntity.class, EntityDataSerializers.STRING);

    /** 是否参与实体碰撞（子工程 8）：`col:` → true（挡人）；`use:` → false（玩家可穿过） */
    private static final EntityDataAccessor<Boolean> DATA_COLLIDES =
            SynchedEntityData.defineId(ColliderEntity.class, EntityDataSerializers.BOOLEAN);

    /** 是否仍可被右键（子工程 8）：一次性命令盒执行"成功"后置 false（不可点 + 不挡人）；不持久化，重启恢复 true */
    private static final EntityDataAccessor<Boolean> DATA_PICK_ENABLED =
            SynchedEntityData.defineId(ColliderEntity.class, EntityDataSerializers.BOOLEAN);

    /**
     * 所属摆放 id（B1 收尾·事项 4：精确 toggle 用，多同名门独立开关）；-1 = 未知。
     * B1 收尾修复：改为 entityData **同步双端**——此前只在服务端有值，客户端交互日志/提示恒显示
     * `摆放 #-1`（排查时无法把命中盒对应到具体摆放）。
     */
    private static final EntityDataAccessor<Integer> DATA_PLACEMENT_ID =
            SynchedEntityData.defineId(ColliderEntity.class, EntityDataSerializers.INT);

    /** 当前世界 AABB（从 SynchedEntityData 重建；makeBoundingBox override 恒返回它，可表达任意矩形盒） */
    private AABB box = new AABB(Vec3.ZERO, Vec3.ZERO);

    public ColliderEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noCulling = true; // 大碰撞盒跨多 chunk，避免视锥剔除误判（渲染器为空，影响小但保险）
        rebuildBox();
    }

    /** 所属摆放 id（服务端 spawn/恢复时设置；随 entityData 同步客户端，供日志/提示显示"摆放 #N"） */
    public void setPlacementId(int placementId) {
        this.entityData.set(DATA_PLACEMENT_ID, placementId);
    }

    /** 所属摆放 id；-1 = 未知（服务端按它精确匹配右键 toggle 目标） */
    public int getPlacementId() {
        return this.entityData.get(DATA_PLACEMENT_ID);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_MIN_X, 0L);
        builder.define(DATA_MIN_Y, 0L);
        builder.define(DATA_MIN_Z, 0L);
        builder.define(DATA_MAX_X, 0L);
        builder.define(DATA_MAX_Y, 0L);
        builder.define(DATA_MAX_Z, 0L);
        builder.define(DATA_ANIM_BINDING, "");
        builder.define(DATA_ACTIVE, true);
        builder.define(DATA_PART_NAME, "");
        builder.define(DATA_CMD_KEY, "");
        // 默认 true：旧存档/旧实体没有该字段时行为与子工程 6/7 完全一致（阻挡）
        builder.define(DATA_COLLIDES, true);
        builder.define(DATA_PICK_ENABLED, true);
        builder.define(DATA_PLACEMENT_ID, -1);
    }

    /**
     * 服务端：设置世界 AABB（碰撞体位置 = 盒中心）+ 部件名/动画绑定/命令键/是否碰撞，
     * 随 addEntity 包自动同步客户端（double 经 long bits 承载）。
     */
    public void setBox(AABB worldBox, String partName, String animBinding, String cmdKey, boolean collides) {
        this.box = worldBox;
        this.entityData.set(DATA_ANIM_BINDING, animBinding == null ? "" : animBinding);
        this.entityData.set(DATA_PART_NAME, partName == null ? "" : partName);
        this.entityData.set(DATA_CMD_KEY, cmdKey == null ? "" : cmdKey);
        this.entityData.set(DATA_COLLIDES, collides);
        this.entityData.set(DATA_PICK_ENABLED, true);   // 新建/恢复的盒默认可点
        this.entityData.set(DATA_MIN_X, Double.doubleToLongBits(worldBox.minX));
        this.entityData.set(DATA_MIN_Y, Double.doubleToLongBits(worldBox.minY));
        this.entityData.set(DATA_MIN_Z, Double.doubleToLongBits(worldBox.minZ));
        this.entityData.set(DATA_MAX_X, Double.doubleToLongBits(worldBox.maxX));
        this.entityData.set(DATA_MAX_Y, Double.doubleToLongBits(worldBox.maxY));
        this.entityData.set(DATA_MAX_Z, Double.doubleToLongBits(worldBox.maxZ));
        this.setBoundingBox(worldBox);            // 同步引擎字段（部分内部逻辑直接读字段）
        this.setPos(worldBox.getCenter());        // 位置 = 中心（追踪/剔除基准）
    }

    public AABB getColliderBox() {
        return box;
    }

    /** 绑定的动画名；null = 无动画绑定。双端一致（entityData 同步） */
    public String getAnimBinding() {
        String s = this.entityData.get(DATA_ANIM_BINDING);
        return (s == null || s.isEmpty()) ? null : s;
    }

    /** 部件名（`col:`/`use:` 之后的原始名）；null = 未知 */
    public String getPartName() {
        String s = this.entityData.get(DATA_PART_NAME);
        return (s == null || s.isEmpty()) ? null : s;
    }

    /** 命令映射键（`$Key`）；null = 无命令。双端一致（entityData 同步，客户端 isPickable 判据之一） */
    public String getCmdKey() {
        String s = this.entityData.get(DATA_CMD_KEY);
        return (s == null || s.isEmpty()) ? null : s;
    }

    /** 是否参与实体碰撞（`col:` → true / `use:` → false） */
    public boolean collides() {
        return this.entityData.get(DATA_COLLIDES);
    }

    /** 是否仍可被右键（命令盒消耗后 false） */
    public boolean isPickEnabled() {
        return this.entityData.get(DATA_PICK_ENABLED);
    }

    /** 一次性命令盒消耗/回滚 */
    public void setPickEnabled(boolean enabled) {
        this.entityData.set(DATA_PICK_ENABLED, enabled);
    }

    /**
     * 门开/关状态切换（服务端调用，随 entityData 同步客户端移动预测）：
     * false = 门开不阻挡（可穿过，仍可右键关门）；true = 门关阻挡
     */
    public void setActive(boolean active) {
        this.entityData.set(DATA_ACTIVE, active);
    }

    /** 从 SynchedEntityData 重建 AABB（客户端构造/spawn 后调用；double 由 long bits 还原） */
    private void rebuildBox() {
        double minX = Double.longBitsToDouble(this.entityData.get(DATA_MIN_X));
        double minY = Double.longBitsToDouble(this.entityData.get(DATA_MIN_Y));
        double minZ = Double.longBitsToDouble(this.entityData.get(DATA_MIN_Z));
        double maxX = Double.longBitsToDouble(this.entityData.get(DATA_MAX_X));
        double maxY = Double.longBitsToDouble(this.entityData.get(DATA_MAX_Y));
        double maxZ = Double.longBitsToDouble(this.entityData.get(DATA_MAX_Z));
        if (minX < maxX && minY < maxY && minZ < maxZ) {
            this.box = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        } else {
            this.box = new AABB(position(), position());
        }
    }

    /**
     * 碰撞箱：引擎任何 setPos/makeBoundingBox 重建都会走这里，恒返回实际矩形世界盒
     * （Entity.getBoundingBox() 是 final，返回 boundingBox 字段；字段由此方法保证为矩形）
     */
    @Override
    protected AABB makeBoundingBox() {
        return this.box;
    }

    /** 客户端 spawn：entityData 同步完成后重建碰撞箱（addEntity 包的 setPosRaw 不重建 boundingBox） */
    @Override
    public void recreateFromPacket(net.minecraft.network.protocol.game.ClientboundAddEntityPacket packet) {
        super.recreateFromPacket(packet);
        rebuildBox();
        this.setBoundingBox(this.box);
    }

    /**
     * 同步数据变化回调（子工程 7 修复的关键）：
     * 客户端 entityData 赋值（ClientPacketListener.handleAddEntity → assignValues）发生在
     * recreateFromPacket 之后，故 recreateFromPacket 里的 rebuildBox 读到的是默认值（box 零体积）。
     * 必须在 box 数据真正到达时重建，否则客户端 getBoundingBox 是"盒中心一个点"，
     * 准星拾取区域（GameRenderer.pick 用 getBoundingBox().inflate(getPickRadius)）严重偏移 →
     * 右键必须精确对准盒中心才能触发。服务端 setBox 也会走到这里（幂等，box 相同）。
     */
    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_MIN_X.equals(key) || DATA_MIN_Y.equals(key) || DATA_MIN_Z.equals(key)
                || DATA_MAX_X.equals(key) || DATA_MAX_Y.equals(key) || DATA_MAX_Z.equals(key)) {
            rebuildBox();
            this.setBoundingBox(this.box);
        }
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        return this.box.inflate(1.0);
    }

    /** 参与碰撞但永不被推动（isPushable=true 是 getEntityCollisions 的过滤条件，push 空实现防位移） */
    @Override
    public boolean isPushable() {
        return true;
    }

    /**
     * 可被碰撞（核心）：1.21.1 的 {@code Level.getEntityCollisions} 用
     * {@code 移动实体.canCollideWith(本实体)} 过滤，而 {@code Entity.canCollideWith(e)} 内部调用
     * {@code e.canBeCollidedWith()}（默认 false）→ 不 override 则碰撞体永远不会进入玩家/生物的碰撞判定。
     * - `col:` 前缀：随 active 翻转（门开=false 可穿过，门关=true 阻挡）
     * - `use:` 前缀（子工程 8）：collides=false → 恒不挡人（玩家可穿过，但仍可被右键）
     */
    @Override
    public boolean canBeCollidedWith() {
        return collides() && this.entityData.get(DATA_ACTIVE);
    }

    @Override
    public void push(Vec3 speed) {
        // 静止：忽略一切外部速度（玩家/生物/爆炸推不动）
    }

    @Override
    public void push(Entity entity) {
        // 静止：忽略其它实体推动
    }

    @Override
    public void tick() {
        super.tick();
        // 静态碰撞体：每 tick 速度清零（防外部代码 set 速度导致位移）
        this.setDeltaMovement(Vec3.ZERO);
    }

    @Override
    public boolean isNoGravity() {
        return true;
    }

    /**
     * 可被准星选中（子工程 7 + 8）：配置了动画绑定（`@Anim`）或命令键（`$Key`）且未被消耗时返回 true，
     * 准星命中后客户端发 interact 包 → 服务端 interact 执行右键逻辑。
     * 纯碰撞盒（`col:xxx` 无 @ 无 $）保持 false：玩家瞄墙不会出现"可交互"高亮（交接文档 §9 踩坑 2）。
     */
    @Override
    public boolean isPickable() {
        return isPickEnabled() && (getAnimBinding() != null || getCmdKey() != null);
    }

    /**
     * 拾取半径（子工程 7；子工程 9 收窄）：只做**小幅**膨胀。
     *
     * <p>原实现 `min(4, size*0.5+0.25)` 对大盒（如 1.83×0.26×0.32 的枪盒）会产生 ~1.2 格的膨胀体积，
     * 足以把旁边 2cm 的子弹盒整个吞掉 → 点子弹却命中枪盒（表现为"点子弹触发枪的动画"）。
     * 现改为上限 0.3 且随尺寸缓增：薄门板仍好点，大盒不再跨部件误命中。
     * 需要更大点击范围时应在模型里把标记盒画大（不可见标记盒不影响观感）。
     */
    @Override
    public float getPickRadius() {
        return (float) Math.min(0.3, 0.1 + this.box.getSize() * 0.05);
    }

    /**
     * 右键交互（子工程 7 + 8）：服务端按「动画 → 状态 → 命令」固定顺序执行。
     *
     * <pre>
     * 1) 无 @ 无 $            → PASS（纯碰撞，不可交互）
     * 2) 客户端               → SUCCESS（只负责准星拾取，逻辑全在服务端）
     * 3) 已消耗 / 命令键不存在 → 不消耗（键不存在额外 warn + actionbar 提示）
     * 4) 动画绑定             → GlbInteraction.toggle（开门/关门，子工程 7 语义不变）
     * 5) 消耗占位             → pickEnabled=false + active=false + CONSUMED.add（先占位，命令后回滚）
     * 6) 命令                 → CommandTable.run；consume="result" 且返回值 &lt;= 0 → 回滚消耗
     * </pre>
     *
     * <p>先占位再回滚的原因：命令串里可能含 `/objremove %placement%`（执行后本实体已被移除），
     * 放在命令之后设状态不安全；而 `consume:"result"` 又要求按返回值回滚。
     */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        String anim = getAnimBinding();
        String cmd = getCmdKey();
        if (anim == null && cmd == null) {
            return InteractionResult.PASS; // 纯碰撞（col:xxx / use:xxx 无后缀）：无交互
        }
        if (this.level().isClientSide) {
            return InteractionResult.SUCCESS; // 客户端只做准星拾取
        }
        if (!isPickEnabled()) {
            // 服务端权威：已消耗（客户端可能时序性重发 interact 包）—— 必须给可见原因，否则现场只会看到"点了没反应"
            player.displayClientMessage(Component.literal("§c[Objuilder] 触发失败：部件已消耗/禁用（"
                    + getPartName() + " · 摆放 #" + getPlacementId() + "）"), true);
            PolarisObjuilder.LOGGER.info("[Objuilder][交互] 拒绝：部件 '{}' 摆放 #{} 已消耗/禁用（pickEnabled=false）",
                    getPartName(), getPlacementId());
            return InteractionResult.PASS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        ServerLevel serverLevel = (ServerLevel) this.level();
        if (cmd != null && !CommandTable.has(cmd)) {
            // 键不存在：不消耗、不静默（否则现场极难排查"点了没反应"）；此处早于动画/状态，故无任何副作用
            PolarisObjuilder.LOGGER.warn("[Objuilder] 命令键 '{}' 不存在（部件 '{}'，摆放 #{}）→ 未消耗、未改状态",
                    cmd, getPartName(), getPlacementId());
            String part = getPartName();
            player.displayClientMessage(Component.literal(
                    "§c[Objuilder] 命令键未配置: " + cmd + (part == null ? "" : "（部件 " + part + "）")), true);
            return InteractionResult.SUCCESS;
        }
        // 1) 动画（复用子工程 7 机制）；先记录点击前状态，供命令未生效时回滚（标准 6 / 2.1.14 加固 A）
        boolean animWasOpen = anim != null && GlbInteraction.isOpen(getPlacementId(), anim);
        if (anim != null) {
            GlbInteraction.toggle(serverLevel, getPlacementId(), anim);
        }
        // 2) 消耗占位（仅命令盒；默认 consume="result"）
        boolean consume = cmd != null && CommandTable.shouldConsume(cmd);
        if (consume) {
            setPickEnabled(false);
            setActive(false);
            GlbInteraction.markConsumed(getPlacementId(), getPartName());
        }
        // 3) 命令（服务端、以玩家身份、带占位符）
        if (cmd != null) {
            int result = CommandTable.run(serverPlayer, cmd, getPartName(), getPlacementId(), this.box.getCenter());
            // "未生效"判据：consume="result" 且返回值 <= 0（含 cmds 多命令"任一条失败即中止"返回 0）
            boolean ineffective = CommandTable.mode(cmd) == CommandTable.Consume.RESULT && result <= 0;
            PolarisObjuilder.LOGGER.info("[Objuilder][交互] 部件 '{}' 摆放 #{} 命令 {} → 返回 {}（{}）",
                    getPartName(), getPlacementId(), cmd, result, ineffective ? "未生效" : "已生效");
            if (ineffective) {
                // 加固 B：失败必须给玩家可见反馈（否则现场只能翻日志）
                String why = getPartName() == null ? cmd : cmd + "（部件 " + getPartName() + "）";
                player.displayClientMessage(Component.literal(
                        "§c[Objuilder] 命令未生效，本次交互已忽略：" + why), true);
                if (consume) {
                    // 加固 A：连动画 toggle 一起回滚到点击前状态（否则"动画已播/状态=开 但盒还活着"，
                    // 玩家再点一下会变成 toggle RESET —— 实测表现为"枪掉下来又飞回去"）
                    if (anim != null) {
                        GlbInteraction.restoreOpen(serverLevel, getPlacementId(), anim, animWasOpen);
                    }
                    // active 跟随"点击前的动画状态"：静态盒（anim==null）恢复阻挡；动态盒按 animWasOpen 定
                    setActive(!(anim != null && animWasOpen));
                    setPickEnabled(true);
                    GlbInteraction.unmarkConsumed(getPlacementId(), getPartName());
                }
            }
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * 是否进入客户端渲染/拾取列表：无渲染（renderer 为空实现），但必须返回 true——
     * 客户端准星拾取（GameRenderer.pick）遍历 {@code level.entitiesForRendering()}，
     * 返回 false 会被排除出该列表 → isPickable 判定永不执行 → 右键完全无效。
     * （子工程 6 纯碰撞不依赖拾取，返回 false 无碍；子工程 7 交互是拾取前提，必须 true）
     */
    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return true;
    }

    // ===================== 持久化（兜底；正常走父摆放恢复） =====================

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.entityData.set(DATA_MIN_X, Double.doubleToLongBits(tag.getDouble("minX")));
        this.entityData.set(DATA_MIN_Y, Double.doubleToLongBits(tag.getDouble("minY")));
        this.entityData.set(DATA_MIN_Z, Double.doubleToLongBits(tag.getDouble("minZ")));
        this.entityData.set(DATA_MAX_X, Double.doubleToLongBits(tag.getDouble("maxX")));
        this.entityData.set(DATA_MAX_Y, Double.doubleToLongBits(tag.getDouble("maxY")));
        this.entityData.set(DATA_MAX_Z, Double.doubleToLongBits(tag.getDouble("maxZ")));
        this.entityData.set(DATA_ANIM_BINDING, tag.getString("animBinding"));
        this.entityData.set(DATA_PART_NAME, tag.getString("partName"));
        this.entityData.set(DATA_CMD_KEY, tag.getString("cmdKey"));
        // 旧存档（子工程 6/7）没有 collides → 默认 true（保持原"col: 挡人"行为）
        this.entityData.set(DATA_COLLIDES, !tag.contains("collides") || tag.getBoolean("collides"));
        // 摆放 id（子工程 8 复检修复）：原先不持久化 → 重进存档后为 -1，命令里 %placement% 取到 -1、
        // /objremove %placement% 失效。此处持久化；老存档无该字段时由 onServerStarted 按 SavedData 回填。
        this.entityData.set(DATA_PLACEMENT_ID, tag.contains("placementId") ? tag.getInt("placementId") : -1);
        // active / pickEnabled 不持久化：重启强制恢复"阻挡 + 可点"——与 OPEN_PARTS / CONSUMED 内存态清零
        // （门默认关闭、命令盒恢复可点）一致，保证重启后碰撞状态与动画初始姿势（门关）对齐
        this.entityData.set(DATA_ACTIVE, true);
        this.entityData.set(DATA_PICK_ENABLED, true);
        rebuildBox();
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putDouble("minX", box.minX);
        tag.putDouble("minY", box.minY);
        tag.putDouble("minZ", box.minZ);
        tag.putDouble("maxX", box.maxX);
        tag.putDouble("maxY", box.maxY);
        tag.putDouble("maxZ", box.maxZ);
        tag.putString("animBinding", getAnimBinding() == null ? "" : getAnimBinding());
        tag.putString("partName", getPartName() == null ? "" : getPartName());
        tag.putString("cmdKey", getCmdKey() == null ? "" : getCmdKey());
        tag.putBoolean("collides", collides());
        tag.putInt("placementId", getPlacementId());
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket(ServerEntity serverEntity) {
        return new ClientboundAddEntityPacket(this, serverEntity);
    }
}
