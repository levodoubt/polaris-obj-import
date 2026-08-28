package com.levodoubt.objuilder.entity;

import com.levodoubt.objuilder.GlbInteraction;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 隐形碰撞体实体（子工程 6 + 7）——用 MC 引擎原生单 AABB 碰撞拼出多 AABB 碰撞：
 * - 每个 `col:` 盒 → 一个 ColliderEntity，AABB = 该盒经 yaw/scale/平移后的世界盒
 * - 无渲染、无重力、不可动、不 tick（纯静态碰撞体）
 * - isPushable = true：1.21.1 的 {@code Level.getEntityCollisions} 用 Entity::isPushable
 *   过滤，只有 isPushable 的实体才参与玩家/生物碰撞（这就是挡人的前提）；
 *   push(Vec3)/push(Entity) 空实现屏蔽被推动 → 玩家撞上来碰撞体纹丝不动
 * - canBeCollidedWith = active（同步字段）：静态盒恒 true；动态盒（@Anim）随服务端
 *   OPEN_PARTS 翻转——门开 active=false 可穿过、门关 active=true 阻挡
 * - isPickable = animBinding != null（子工程 7）：仅交互碰撞体（`col:xxx@Anim`）可被
 *   准星选中并右键 toggle；纯碰撞盒（`col:xxx`）不可选中（无"可交互"高亮）
 * - interact（子工程 7）：服务端执行 {@code GlbInteraction.toggle}，右键开关门
 * - AABB 通过 SynchedEntityData 同步 6 个 double（LONG 承载位），客户端实体也有正确碰撞箱
 *   （玩家客户端移动预测用 getBoundingBox()）
 * - 持久化：依赖父摆放（PlacementSavedData）恢复重建；实体本身兜底写 AABB + animBinding
 *   （active 不持久化：重启强制恢复阻挡，与 OPEN_PARTS 内存态清零、门默认关闭一致）
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

    /** 当前世界 AABB（从 SynchedEntityData 重建；makeBoundingBox override 恒返回它，可表达任意矩形盒） */
    private AABB box = new AABB(Vec3.ZERO, Vec3.ZERO);

    /** 所属摆放 id（B1 收尾·事项 4：精确 toggle 用，多同名门独立开关）；-1 = 未知 */
    private int placementId = -1;

    public ColliderEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noCulling = true; // 大碰撞盒跨多 chunk，避免视锥剔除误判（渲染器为空，影响小但保险）
        rebuildBox();
    }

    /** 所属摆放 id（服务端 spawn/恢复时设置；不随包同步，客户端交互只需 animBinding） */
    public void setPlacementId(int placementId) {
        this.placementId = placementId;
    }

    /** 所属摆放 id；-1 = 未知（服务端按它精确匹配右键 toggle 目标） */
    public int getPlacementId() {
        return placementId;
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
    }

    /** 服务端：设置世界 AABB（碰撞体位置 = 盒中心）+ 动画绑定，随 addEntity 包自动同步客户端（double 经 long bits 承载） */
    public void setBox(AABB worldBox, String binding) {
        this.box = worldBox;
        this.entityData.set(DATA_ANIM_BINDING, binding == null ? "" : binding);
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

    /** 绑定的动画名；null = 纯碰撞盒（无交互）。双端一致（entityData 同步） */
    public String getAnimBinding() {
        String s = this.entityData.get(DATA_ANIM_BINDING);
        return (s == null || s.isEmpty()) ? null : s;
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
     * 动态盒（@Anim）随 active 翻转：门开=false 可穿过，门关=true 阻挡。
     */
    @Override
    public boolean canBeCollidedWith() {
        return this.entityData.get(DATA_ACTIVE);
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
     * 可被准星选中（子工程 7）：仅交互碰撞体（`col:xxx@Anim`，animBinding != null）返回 true，
     * 准星命中后客户端发 interact 包 → 服务端 interact 执行右键 toggle。
     * 纯碰撞盒（`col:xxx` 无 @）保持 false：玩家瞄墙不会出现"可交互"高亮（交接文档 §9 踩坑 2）。
     */
    @Override
    public boolean isPickable() {
        return getAnimBinding() != null;
    }

    /**
     * 拾取半径（子工程 7）：门板等宽盒随 AABB 尺寸自适应（上限 4 格），
     * 准星指门洞附近也能命中，便于右键开关门（getPickRadius 默认 0 = 仅盒面命中）。
     */
    @Override
    public float getPickRadius() {
        return (float) Math.min(4.0, this.box.getSize() * 0.5 + 0.25);
    }

    /**
     * 右键交互（子工程 7 + 事项 4）：服务端执行 toggle（开门/关门翻转，动画 + 碰撞状态），
     * 按「摆放 id + 动画名」精确匹配（多同名门独立开关）。
     * 纯碰撞盒返回 PASS；交互盒返回 SUCCESS。客户端只负责准星命中（isPickable + getBoundingBox），
     * 不在此执行逻辑（多端同步由服务端广播保证）。
     */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (getAnimBinding() == null) {
            return InteractionResult.PASS; // 纯碰撞（col:xxx）：无交互
        }
        if (!this.level().isClientSide) {
            GlbInteraction.toggle((ServerLevel) this.level(), getPlacementId(), getAnimBinding());
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
        // active 不持久化：重启强制恢复"阻挡"——与 OPEN_PARTS 内存态清零（门默认关闭）一致，
        // 保证重启后碰撞状态与动画初始姿势（门关）对齐
        this.entityData.set(DATA_ACTIVE, true);
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
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket(ServerEntity serverEntity) {
        return new ClientboundAddEntityPacket(this, serverEntity);
    }
}
