package com.levodoubt.objuilder;

import com.levodoubt.objuilder.client.PieceBakedModel;
import com.levodoubt.objuilder.command.ObjImportCommand;
import com.mojang.brigadier.arguments.FloatArgumentType;
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
        // /objimport <mode> [scale] [file]
        var scaleOpt = Commands.argument("scale", FloatArgumentType.floatArg(0.01f, 100f))
                .executes(ctx -> ObjImportCommand.run(ctx.getSource(), null, false,
                        FloatArgumentType.getFloat(ctx, "scale")))
                .then(Commands.argument("file", StringArgumentType.greedyString())
                        .executes(ctx -> ObjImportCommand.run(ctx.getSource(),
                                StringArgumentType.getString(ctx, "file"), false,
                                FloatArgumentType.getFloat(ctx, "scale"))));
        var scaleBlock = Commands.argument("scale", FloatArgumentType.floatArg(0.01f, 100f))
                .executes(ctx -> ObjImportCommand.run(ctx.getSource(), null, true,
                        FloatArgumentType.getFloat(ctx, "scale")))
                .then(Commands.argument("file", StringArgumentType.greedyString())
                        .executes(ctx -> ObjImportCommand.run(ctx.getSource(),
                                StringArgumentType.getString(ctx, "file"), true,
                                FloatArgumentType.getFloat(ctx, "scale"))));
        dispatcher.register(Commands.literal("objimport")
                // /objimport slice [scale] [file] —— 子片空壳
                .then(Commands.literal("slice")
                        .executes(ctx -> ObjImportCommand.run(ctx.getSource(), null, false, 1f))
                        .then(scaleOpt)
                        .then(Commands.argument("file", StringArgumentType.greedyString())
                                .executes(ctx -> ObjImportCommand.run(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "file"), false, 1f))))
                // /objimport block [scale] [file] —— 子片 + 内部石头
                .then(Commands.literal("block")
                        .executes(ctx -> ObjImportCommand.run(ctx.getSource(), null, true, 1f))
                        .then(scaleBlock)
                        .then(Commands.argument("file", StringArgumentType.greedyString())
                                .executes(ctx -> ObjImportCommand.run(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "file"), true, 1f)))));
        // /objexport <out> <file> —— 离线烘焙导出（out 在前用 string，file 最后用 greedy 以支持含空格路径）
        dispatcher.register(Commands.literal("objexport")
                .then(Commands.argument("out", StringArgumentType.string())
                        .then(Commands.argument("file", StringArgumentType.greedyString())
                                .executes(ctx -> ObjImportCommand.export(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "file"),
                                        StringArgumentType.getString(ctx, "out"))))));
        // /objload <file> —— 快速摆放烘焙文件
        dispatcher.register(Commands.literal("objload")
                .then(Commands.argument("file", StringArgumentType.greedyString())
                        .executes(ctx -> ObjImportCommand.load(ctx.getSource(),
                                StringArgumentType.getString(ctx, "file")))));
        // /objschem <out> <file> —— 导出 schematic（Sponge v2）
        dispatcher.register(Commands.literal("objschem")
                .then(Commands.argument("out", StringArgumentType.string())
                        .then(Commands.argument("file", StringArgumentType.greedyString())
                                .executes(ctx -> ObjImportCommand.exportSchem(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "file"),
                                        StringArgumentType.getString(ctx, "out"))))));
        // /objschemload <file> —— 读取 schematic 放置
        dispatcher.register(Commands.literal("objschemload")
                .then(Commands.argument("file", StringArgumentType.greedyString())
                        .executes(ctx -> ObjImportCommand.loadSchem(ctx.getSource(),
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
