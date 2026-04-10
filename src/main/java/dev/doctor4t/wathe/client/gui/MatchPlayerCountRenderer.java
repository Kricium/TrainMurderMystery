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

    private MatchPlayerCountRenderer() {
    }

    public static void renderHud(TextRenderer renderer, @NotNull ClientPlayerEntity player, @NotNull DrawContext context) {
        GameWorldComponent gameWorldComponent = GameWorldComponent.KEY.get(player.getWorld());
        if (!gameWorldComponent.isRunning()) {
            return;
        }

        int playerCount = gameWorldComponent.getAllPlayers().size();
        if (playerCount <= 0) {
            return;
        }

        MutableText text = Text.empty()
            .append(Text.literal(String.valueOf(playerCount)).styled(style -> style.withColor(NUMBER_COLOUR)))
            .append(Text.literal(" 人对局").styled(style -> style.withColor(LABEL_COLOUR)));
        int y = TimeRenderer.shouldRender(player) ? 18 : 6;

        context.getMatrices().push();
        context.getMatrices().translate(context.getScaledWindowWidth() / 2f, y, 0);
        context.drawTextWithShadow(renderer, text, -renderer.getWidth(text) / 2, 0, 0xFFFFFFFF);
        context.getMatrices().pop();
    }
}
