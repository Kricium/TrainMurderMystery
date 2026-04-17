package dev.doctor4t.wathe.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 渐进式地图重置开关指令
 * <p>
 * 用法：{@code wathe:setGradualReset <true|false>}
 * <ul>
 *   <li>{@code true}  — 启用渐进式重置（分块逐 tick 复制，带进度提示，避免卡顿）</li>
 *   <li>{@code false} — 使用原始一次性重置（立即复制所有方块）</li>
 * </ul>
 * 需要权限等级 2 或 fabric-permissions {@code wathe.command.setgradualreset}。
 * </p>
 */
public class SetGradualResetCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("wathe:setGradualReset")
                        .requires(source -> source.hasPermissionLevel(2)
                                || Permissions.check(source, "wathe.command.setgradualreset"))
                        .then(
                                CommandManager.argument("enabled", BoolArgumentType.bool())
                                        .executes(context -> execute(
                                                context.getSource(),
                                                BoolArgumentType.getBool(context, "enabled")
                                        ))
                        )
                        // 无参数时查询当前状态
                        .executes(context -> query(context.getSource()))
        );
    }

    /**
     * 设置渐进式重置开关
     */
    private static int execute(ServerCommandSource source, boolean enabled) {
        GameWorldComponent game = GameWorldComponent.KEY.get(source.getWorld());
        game.setGradualResetEnabled(enabled);

        source.sendFeedback(
                () -> Text.translatable("commands.wathe.gradualreset.set", enabled)
                        .formatted(Formatting.GREEN),
                true  // 广播给所有 OP
        );
        return 1;
    }

    /**
     * 查询当前渐进式重置状态
     */
    private static int query(ServerCommandSource source) {
        GameWorldComponent game = GameWorldComponent.KEY.get(source.getWorld());
        boolean enabled = game.isGradualResetEnabled();

        source.sendFeedback(
                () -> Text.translatable("commands.wathe.gradualreset.query", enabled)
                        .formatted(Formatting.YELLOW),
                false
        );
        return 1;
    }
}
