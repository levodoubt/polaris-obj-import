package com.levodoubt.objuilder.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 服务端权威摆放清单（SavedData 持久化，子工程 5）。
 *
 * - folder = polarisobjuilder_placements，重进游戏自动恢复摆放。
 * - 每条记录：id(int 自增) · ref(模型引用) · x/y/z · yaw · scale · 实体 UUID（供 objremove/objclear 定位实体）。
 * - 改动必须调 setDirty()，否则不落盘。
 *
 * 1.21.1 用旧 API：{@link SavedData.Factory} + NBT（不是更高版本 SavedDataType + Codec）。
 */
public class PlacementSavedData extends SavedData {
    public static final String DATA_NAME = "polarisobjuilder_placements";

    /** 单条摆放记录（子工程 6：colliderUuids = 该摆放的碰撞体实体 UUID 列表，供 remove/clear/恢复联动） */
    public record Placement(int id, String ref, double x, double y, double z,
                            float yaw, float scale, UUID entityUuid, List<UUID> colliderUuids) {
        /** 便捷：为记录设置碰撞体 UUID 列表（不修改原记录） */
        public Placement withColliders(List<UUID> colliders) {
            return new Placement(id, ref, x, y, z, yaw, scale, entityUuid, colliders);
        }
    }

    private final Map<Integer, Placement> placements = new LinkedHashMap<>();
    private int nextId = 1;

    /** 取服务端权威数据（每个维度世界一个；overworld 即可） */
    public static PlacementSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<PlacementSavedData>(PlacementSavedData::new, PlacementSavedData::load),
                DATA_NAME);
    }

    public static PlacementSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        PlacementSavedData data = new PlacementSavedData();
        data.nextId = Math.max(1, tag.getInt("nextId"));
        ListTag list = tag.getList("placements", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            int id = t.getInt("id");
            UUID uuid = t.hasUUID("uuid") ? t.getUUID("uuid") : null;
            List<UUID> colliders = new ArrayList<>();
            ListTag clist = t.getList("colliders", Tag.TAG_COMPOUND);
            for (int j = 0; j < clist.size(); j++) {
                CompoundTag ct = clist.getCompound(j);
                if (ct.hasUUID("uuid")) colliders.add(ct.getUUID("uuid"));
            }
            data.placements.put(id, new Placement(id,
                    t.getString("ref"),
                    t.getDouble("x"), t.getDouble("y"), t.getDouble("z"),
                    t.getFloat("yaw"), t.getFloat("scale"), uuid, colliders));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("nextId", nextId);
        ListTag list = new ListTag();
        for (Placement p : placements.values()) {
            CompoundTag t = new CompoundTag();
            t.putInt("id", p.id());
            t.putString("ref", p.ref());
            t.putDouble("x", p.x());
            t.putDouble("y", p.y());
            t.putDouble("z", p.z());
            t.putFloat("yaw", p.yaw());
            t.putFloat("scale", p.scale());
            if (p.entityUuid() != null) t.putUUID("uuid", p.entityUuid());
            ListTag clist = new ListTag();
            for (UUID cu : p.colliderUuids()) {
                if (cu == null) continue;
                CompoundTag ct = new CompoundTag();
                ct.putUUID("uuid", cu);
                clist.add(ct);
            }
            t.put("colliders", clist);
            list.add(t);
        }
        tag.put("placements", list);
        return tag;
    }

    /** 分配新的自增 id（读取恢复后 nextId 已推进，避免覆盖旧记录） */
    public int allocateId() {
        return nextId++;
    }

    /** 新增/更新一条记录（实体 spawn 后回填 UUID 时也用） */
    public void put(Placement p) {
        placements.put(p.id(), p);
        setDirty();
    }

    public Placement get(int id) {
        return placements.get(id);
    }

    public boolean remove(int id) {
        boolean removed = placements.remove(id) != null;
        if (removed) setDirty();
        return removed;
    }

    public void clear() {
        if (placements.isEmpty()) return;
        placements.clear();
        setDirty();
    }

    public List<Placement> all() {
        return new ArrayList<>(placements.values());
    }

    public int count() {
        return placements.size();
    }
}
