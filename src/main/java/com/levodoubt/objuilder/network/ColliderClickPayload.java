package com.levodoubt.objuilder.network;

import com.levodoubt.objuilder.PolarisObjuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 点击辅助包（2.1.16，客户端 → 服务端）。
 *
 * <p>客户端用"宽容射线"（4.5 格 + 额外膨胀）自行命中交互盒后发来本包；
 * 服务端自行校验可达性并调用 {@code ColliderEntity.interact}，从而绕开原版
 * {@code ServerGamePacketListenerImpl.handleInteract} 的 3 格实体交互距离限制。
 */
public record ColliderClickPayload(int entityId, int hand) implements CustomPacketPayload {

    public static final Type<ColliderClickPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PolarisObjuilder.MODID, "collider_click"));

    public static final StreamCodec<ByteBuf, ColliderClickPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, ColliderClickPayload::entityId,
                    ByteBufCodecs.VAR_INT, ColliderClickPayload::hand,
                    ColliderClickPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
