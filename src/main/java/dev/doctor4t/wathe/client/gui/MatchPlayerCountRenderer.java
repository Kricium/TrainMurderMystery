package dev.doctor4t.wathe.client.gui;

import dev.doctor4t.wathe.cca.GameWorldComponent;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import org.jetbrains.annotations.NotNull;

public final class MatchPlayerCountRenderer {
    private static final int LABEL_COLOUR = 0xFFD0D0D0;
    private static final int NUMBER_COLOUR = 0xFF55CC55;
    private static final int TOP_Y = 6;
    private static final int BELOW_TIME_Y = 18;

    private MatchPlayerCountRenderer() {
    }

    public static void renderHud(TextRenderer renderer, @NotNull ClientPlayerEntity player, @NotNull DrawContext context) {
        if (!shouldRender(player)) {
            return;
        }

        GameWorldComponent gameWorldComponent = GameWorldComponent.KEY.get(player.getWorld());
        int playerCount = gameWorldComponent.getAllPlayers().size();
        if (playerCount <= 0) {
            return;
        }

        MutableText text = Text.empty()
            .append(Text.literal(String.valueOf(playerCount)).styled(style -> style.withColor(NUMBER_COLOUR)))
            .append(Text.literal(" 人对局").styled(style -> style.withColor(LABEL_COLOUR)));
        int y = getHudY(player);

        context.getMatrices().push();
        context.getMatrices().translate(context.getScaledWindowWidth() / 2f, y, 0);
        context.drawTextWithShadow(renderer, text, -renderer.getWidth(text) / 2, 0, 0xFFFFFFFF);
        context.getMatrices().pop();
    }

    public static boolean shouldRender(@NotNull ClientPlayerEntity player) {
        GameWorldComponent gameWorldComponent = GameWorldComponent.KEY.get(player.getWorld());
        return gameWorldComponent.isRunning();
    }

    public static int getHudY(@NotNull ClientPlayerEntity player) {
        return TimeRenderer.shouldRender(player) ? BELOW_TIME_Y : TOP_Y;
    }

    public static int getBottomY(@NotNull TextRenderer renderer, @NotNull ClientPlayerEntity player) {
        return getHudY(player) + renderer.fontHeight;
    }
}
