package dev.doctor4t.wathe.command;

import com.mojang.brigadier.CommandDispatcher;
import dev.doctor4t.wathe.cca.MapVotingComponent;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

public class SkipVoteWaitCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            CommandManager.literal("wathe:skipVoteWait")
                .requires(source -> source.hasPermissionLevel(2) || Permissions.check(source, "wathe.command.skipvotewait"))
                .executes(context -> execute(context.getSource()))
        );
    }

    private static int execute(ServerCommandSource source) {
        MapVotingComponent voting = MapVotingComponent.KEY.get(source.getServer().getScoreboard());
        if (!voting.skipWaitingPhase()) {
            source.sendError(Text.translatable("commands.wathe.skipvotewait.no_active"));
            return 0;
        }

        source.sendFeedback(() -> Text.translatable("commands.wathe.skipvotewait.success"), true);
        return 1;
    }
}
