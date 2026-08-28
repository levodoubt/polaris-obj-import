package com.levodoubt.objuilder.entity;

import com.levodoubt.objuilder.client.DomainModelCache;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.server.level.ServerEntity;

/**
 * 共面域实体（纯视觉模式）：一个实体承载一个"共面大平面"的几何。
 * - 位置 = 域原点（域几何为域局部坐标）
 * - 无碰撞、不可交互、不 tick（纯视觉地景/背景）
 * - 域 id 通过 entityData 同步，客户端渲染器从 DomainModelCache 取几何
 *
 * 全功能摆放（子工程 5）：新增 modelRef（模型引用，如 models/door.glb）与 modelScale。
 * - 旧流程（/glbdomain、/objdomain）：走 DATA_DOMAIN_ID，modelRef 为空，向后兼容。
 * - 新流程（/objplace）：走 modelRef，服务端只存引用字符串 + 坐标 + yaw + scale，
 *   客户端渲染器按引用懒加载几何（引用 → DomainModelCache 的 modelId）。
 * - yaw 用实体自带 yRot（setYRot），随实体同步 + 持久化。
 */
public class DomainEntity extends Entity {
    public static final EntityDataAccessor<Integer> DATA_DOMAIN_ID =
            SynchedEntityData.defineId(DomainEntity.class, EntityDataSerializers.INT);
    /** 模型引用（相对 config/polarisobjuilder/models/ 的路径，如 models/door.glb；空 = 旧流程走 domainId） */
    public static final EntityDataAccessor<String> DATA_MODEL_REF =
            SynchedEntityData.defineId(DomainEntity.class, EntityDataSerializers.STRING);
    /** 模型整体缩放（1.0 = 原尺寸） */
    public static final EntityDataAccessor<Float> DATA_MODEL_SCALE =
            SynchedEntityData.defineId(DomainEntity.class, EntityDataSerializers.FLOAT);
    /** 摆放 id（B1 收尾·事项 4：objplace 的 Placement.id，随包同步客户端 → 渲染器按摆放独立 modelId/动画；-1 = 旧流程/兼容） */
    public static final EntityDataAccessor<Integer> DATA_PLACEMENT_ID =
            SynchedEntityData.defineId(DomainEntity.class, EntityDataSerializers.INT);

    public DomainEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true; // 无碰撞（不可站立/不可推）
        this.noCulling = true; // 跳过视锥剔除（大模型跨多 chunk，视锥边缘易被误判剔除 → 消失）
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_DOMAIN_ID, -1);
        builder.define(DATA_MODEL_REF, "");
        builder.define(DATA_MODEL_SCALE, 1.0f);
        builder.define(DATA_PLACEMENT_ID, -1);
    }

    public void setDomainId(int id) {
        this.entityData.set(DATA_DOMAIN_ID, id);
    }

    public int getDomainId() {
        return this.entityData.get(DATA_DOMAIN_ID);
    }

    public void setModelRef(String ref) {
        this.entityData.set(DATA_MODEL_REF, ref != null ? ref : "");
    }

    public String getModelRef() {
        return this.entityData.get(DATA_MODEL_REF);
    }

    public void setModelScale(float scale) {
        this.entityData.set(DATA_MODEL_SCALE, scale);
    }

    public float getModelScale() {
        return this.entityData.get(DATA_MODEL_SCALE);
    }

    public void setPlacementId(int placementId) {
        this.entityData.set(DATA_PLACEMENT_ID, placementId);
    }

    public int getPlacementId() {
        return this.entityData.get(DATA_PLACEMENT_ID);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.entityData.set(DATA_DOMAIN_ID, tag.getInt("domainId"));
        this.entityData.set(DATA_MODEL_REF, tag.getString("modelRef"));
        this.entityData.set(DATA_MODEL_SCALE, tag.getFloat("modelScale"));
        this.entityData.set(DATA_PLACEMENT_ID, tag.getInt("placementId"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("domainId", getDomainId());
        tag.putString("modelRef", getModelRef());
        tag.putFloat("modelScale", getModelScale());
        tag.putInt("placementId", getPlacementId());
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket(ServerEntity serverEntity) {
        return new ClientboundAddEntityPacket(this, serverEntity);
    }

    @Override
    public boolean isPickable() {
        return false; // 不可交互（无准星高亮/无法攻击）
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return true; // 大平面始终渲染（距离剔除由 getBoundingBoxForCulling 控制）
    }

    @Override
    public boolean isNoGravity() {
        return true;
    }

    /** 视锥剔除用域几何范围（客户端渲染路径调用；服务端不调用此方法） */
    @Override
    public AABB getBoundingBoxForCulling() {
        AABB bounds = DomainModelCache.getBounds(resolveModelId());
        if (bounds == null) {
            // 缓存未就绪时返回保守的大包围盒，避免被视锥剔除误杀
            return getBoundingBox().inflate(64.0);
        }
        return getBoundingBox().minmax(bounds.move(position())).inflate(8.0);
    }

    /** 解析当前几何的 modelId：ref 路径优先（引用 → 缓存 id，按摆放独立），旧流程回退 domainId */
    private int resolveModelId() {
        String ref = getModelRef();
        if (ref != null && !ref.isEmpty()) {
            Integer id = com.levodoubt.objuilder.client.PlacementModelLoader.modelIdOf(ref, getPlacementId());
            if (id != null) return id;
            return -1;
        }
        return getDomainId();
    }
}
