package com.levodoubt.objuilder;

import com.levodoubt.objuilder.client.PieceBakedModel;
import com.levodoubt.objuilder.command.ObjImportCommand;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * 客户端事件：注册 /objimport 命令 + 把 obj_piece 的 BakedModel 替换为 PieceBakedModel。
 * 纯客户端逻辑，专用服务器不加载。
 */
@EventBusSubscriber(modid = PolarisObjuilder.MODID, value = Dist.CLIENT)
public class PolarisObjuilderClient {
    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("objimport")
                .executes(ctx -> ObjImportCommand.run(ctx.getSource(), null))
                .then(Commands.argument("file", StringArgumentType.greedyString())
                        .executes(ctx -> ObjImportCommand.run(ctx.getSource(),
                                StringArgumentType.getString(ctx, "file")))));
    }

    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        // 替换 obj_piece 全部状态变体（256 个）为同一个 PieceBakedModel
        BakedModel replacement = null;
        for (java.util.Map.Entry<ModelResourceLocation, BakedModel> e : event.getModels().entrySet()) {
            if (e.getKey().toString().startsWith("polarisobjuilder:obj_piece")) {
                if (replacement == null) {
                    replacement = new PieceBakedModel(e.getValue().getParticleIcon());
                }
                e.setValue(replacement);
            }
        }
    }
}
