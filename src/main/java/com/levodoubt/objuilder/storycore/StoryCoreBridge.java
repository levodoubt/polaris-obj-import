package com.levodoubt.objuilder.storycore;

import java.util.Map;

import com.levodoubt.objuilder.GlbInteraction;
import com.levodoubt.objuilder.PolarisObjuilder;
import com.levodoubt.objuilder.animation.GlbAnimationController;
import com.levodoubt.objuilder.network.GlbAnimPayload;
import com.levodoubt.polarisstorycore.engine.StoryActionRegistry;

import net.minecraft.server.level.ServerLevel;

/**
 * StoryCore 桥接（B1 收尾·事项 6）——唯一引用 StoryCore API 的类，惰性加载（仅 StoryCore 存在时）。
 *
 * 注册剧情动作 'polarisobjuilder:anim'：剧本 action 节点触发 glb 动画播放 + 动态碰撞解除。
 * actionParams：
 *   name（必填）动画名；mode（可选）once / loop（默认 once）。
 * 处理逻辑与右键 / /glbanim 完全一致（走 {@link GlbInteraction#setOpen}），单出口无漂移。
 */
public final class StoryCoreBridge {
    private StoryCoreBridge() {
    }

    /** 注册剧情动作（仅被 {@link StoryCoreSupport} 在确认 StoryCore 存在后调用） */
    public static void register() {
        StoryActionRegistry.register("polarisobjuilder:anim", ctx -> {
            Map<String, String> params = ctx.params();
            String name = params == null ? null : params.get("name");
            if (name == null || name.isBlank()) {
                PolarisObjuilder.LOGGER.warn(
                        "[Objuilder] 剧情动作 'polarisobjuilder:anim' 缺少 name 参数 @ node '{}'",
                        ctx.node().id());
                return;
            }
            String mode = params.getOrDefault("mode", "once");
            GlbAnimationController.PlayMode pm =
                    "loop".equalsIgnoreCase(mode) ? GlbAnimationController.PlayMode.LOOP
                            : GlbAnimationController.PlayMode.ONCE;
            ServerLevel level = ctx.player().serverLevel();
            long gameTime = level.getGameTime();
            GlbAnimPayload payload = new GlbAnimPayload(GlbAnimPayload.ACT_PLAY, name,
                    (byte) pm.ordinal(), gameTime, 1.0f, -1); // 剧情 action 按动画名全局触发
            int affected = GlbInteraction.setOpen(level, name, true, payload);
            PolarisObjuilder.LOGGER.info(
                    "[Objuilder] 剧情动作 'polarisobjuilder:anim' → 动画 '{}' ({}), 碰撞盒 {} 个",
                    name, mode, affected);
        });
    }
}
