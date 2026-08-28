package com.levodoubt.objuilder.network;

import com.levodoubt.objuilder.PolarisObjuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 摆放缓存释放包（B1 收尾·事项 1/4，服务端 → 客户端，play 方向）。
 *
 * 服务端 objremove/objclear 删除某摆放 → 广播本包（带摆放 id）→
 * 客户端释放该摆放独立的几何缓存（DomainModelCache）与动态纹理（PlacementModelLoader.release），
 * 避免长时间导入/删除内存只增不减。
 */
public record CacheReleasePayload(int placementId) implements CustomPacketPayload {

    public static final Type<CacheReleasePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PolarisObjuilder.MODID, "cache_release"));

    public static final StreamCodec<ByteBuf, CacheReleasePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, CacheReleasePayload::placementId,
                    CacheReleasePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
