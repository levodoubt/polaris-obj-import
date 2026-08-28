package com.levodoubt.objuilder.network;

import com.levodoubt.objuilder.PolarisObjuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 动画控制同步包（子工程 3 + B1 收尾·事项 4，服务端 → 客户端，play 方向）。
 *
 * 服务端触发动画 → 广播本包 → 所有客户端同步播放。
 * 时间基准 = 服务端 gameTime（tick，long），客户端换算秒与渲染时间对齐（误差仅网络延迟）。
 *
 * B1 收尾·事项 4：新增 placementId（摆放 id）。
 * - placementId >= 0 → 精确触发该摆放的动画（多同名门独立开关，右键路径）
 * - placementId < 0  → 按动画名全局触发（/glbanim 命令、剧情 action 路径，向后兼容）
 *
 * action: 0=play（mode/startTime/speed 生效） 1=stop（保持姿势） 2=reset（回初始姿势）
 */
public record GlbAnimPayload(byte action, String name, byte mode, long serverGameTime, float speed,
                             int placementId) implements CustomPacketPayload {

    public static final byte ACT_PLAY = 0;
    public static final byte ACT_STOP = 1;
    public static final byte ACT_RESET = 2;

    public static final Type<GlbAnimPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PolarisObjuilder.MODID, "glb_anim"));

    public static final StreamCodec<ByteBuf, GlbAnimPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BYTE, GlbAnimPayload::action,
                    ByteBufCodecs.STRING_UTF8, GlbAnimPayload::name,
                    ByteBufCodecs.BYTE, GlbAnimPayload::mode,
                    ByteBufCodecs.VAR_LONG, GlbAnimPayload::serverGameTime,
                    ByteBufCodecs.FLOAT, GlbAnimPayload::speed,
                    ByteBufCodecs.VAR_INT, GlbAnimPayload::placementId,
                    GlbAnimPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
