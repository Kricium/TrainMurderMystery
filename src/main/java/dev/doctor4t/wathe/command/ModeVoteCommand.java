package dev.doctor4t.wathe.command;

import com.mojang.brigadier.CommandDispatcher;
import dev.doctor4t.wathe.cca.MapVotingComponent;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import org.jetbrains.annotations.NotNull;

public class ModeVoteCommand {
    public static void register(@NotNull CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("wathe:modevote")
            .requires(source -> source.hasPermissionLevel(2) || Permissions.check(source, "wathe.command.modevote"))
            .executes(context -> {
                MapVotingComponent voting = MapVotingComponent.KEY.get(
                    context.getSource().getServer().getScoreboard());
                voting.startModeVoting();
                return 1;
            })
        );
    }
}
