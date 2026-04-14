package dev.doctor4t.wathe.client.gui;

import dev.doctor4t.wathe.WatheConfig;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.jetbrains.annotations.NotNull;

public final class MatchPlayerCountRenderer {
    private static final int LABEL_COLOUR = 0xD0D0D0;
    private static final int NUMBER_COLOUR = 0x55CC55;

    private MatchPlayerCountRenderer() {
    }

    public static void renderHud(@NotNull TextRenderer renderer, @NotNull DrawContext context, @NotNull HudHeaderLayout layout) {
        if (!WatheConfig.showMatchPlayerCount || !layout.showMatchCount()) {
            return;
        }

        Text number = Text.literal(String.valueOf(layout.playerCount()))
            .styled(style -> style.withColor(NUMBER_COLOUR));
        Text text = Text.translatable("hud.match_player_count", number)
            .styled(style -> style.withColor(LABEL_COLOUR));

        int x = (context.getScaledWindowWidth() - renderer.getWidth(text)) / 2;
        context.drawTextWithShadow(renderer, text, x, layout.matchCountTopY(), 0xFFFFFFFF);
    }
}
