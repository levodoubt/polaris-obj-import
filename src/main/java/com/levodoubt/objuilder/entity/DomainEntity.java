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
 */
public class DomainEntity extends Entity {
    public static final EntityDataAccessor<Integer> DATA_DOMAIN_ID =
            SynchedEntityData.defineId(DomainEntity.class, EntityDataSerializers.INT);

    public DomainEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true; // 无碰撞（不可站立/不可推）
        this.noCulling = true; // 跳过视锥剔除（大模型跨多 chunk，视锥边缘易被误判剔除 → 消失）
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_DOMAIN_ID, -1);
    }

    public void setDomainId(int id) {
        this.entityData.set(DATA_DOMAIN_ID, id);
    }

    public int getDomainId() {
        return this.entityData.get(DATA_DOMAIN_ID);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.entityData.set(DATA_DOMAIN_ID, tag.getInt("domainId"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("domainId", getDomainId());
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
        AABB bounds = DomainModelCache.getBounds(getDomainId());
        if (bounds == null) {
            // 缓存未就绪时返回保守的大包围盒，避免被视锥剔除误杀
            return getBoundingBox().inflate(64.0);
        }
        return getBoundingBox().minmax(bounds.move(position())).inflate(8.0);
    }
}
