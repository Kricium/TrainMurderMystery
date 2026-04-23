package dev.doctor4t.wathe.client.gui.screen.ingame;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.cca.PlayerShopComponent;
import dev.doctor4t.wathe.client.gui.StoreRenderer;
import dev.doctor4t.wathe.util.ShopEntry;
import dev.doctor4t.wathe.util.ShopUtils;
import dev.doctor4t.wathe.util.StoreBuyPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class LimitedInventoryScreen extends LimitedHandledScreen<PlayerScreenHandler> {
    public static final Identifier BACKGROUND_TEXTURE = Wathe.id("textures/gui/container/limited_inventory.png");
    public static final @NotNull Identifier ID = Wathe.id("textures/gui/game.png");
    private static final int SHOP_COLUMN_SPACING = 38;
    private static final int SHOP_ROW_SPACING = 60;
    private static final int SHOP_SIDE_MARGIN = 20;
    private static final int SHOP_TOP_MARGIN = 10;
    private static final int SHOP_TOOLTIP_GAP = 10;
    private static final int SHOP_TOOLTIP_RESERVED_HEIGHT = 24;
    private static final int SHOP_BACKGROUND_SIZE = 30;
    private static final int SHOP_ITEM_OFFSET = 7;
    private static final int SHOP_ITEM_SIZE = 16;
    private static final int SHOP_PRICE_OVERFLOW = 26;
    private static final int SHOP_PRICE_LABEL_EXTRA_WIDTH = 6;
    private static final int SHOP_PRICE_LABEL_PADDING_X = 3;
    private static final int SHOP_PRICE_LABEL_PADDING_Y = 2;
    private static final int SHOP_PRICE_LABEL_GAP = 4;
    public final ClientPlayerEntity player;

    public LimitedInventoryScreen(@NotNull ClientPlayerEntity player) {
        super(player.playerScreenHandler, player.getInventory(), Text.empty());
        this.player = player;
    }

    @Override
    protected void init() {
        super.init();

        // Get shop entries for this player
        List<ShopEntry> entries = ShopUtils.getShopEntriesForPlayer(player);

        // Check shop access
        if (entries.isEmpty()) {
            return;
        }

        ShopLayout layout = this.computeShopLayout(entries.size());
        float contentHeight = (SHOP_PRICE_OVERFLOW + SHOP_BACKGROUND_SIZE + Math.max(0, layout.rows() - 1) * SHOP_ROW_SPACING) * layout.scale();
        float topEdge = Math.max(SHOP_TOP_MARGIN, layout.availableBottom() - contentHeight);

        for (int row = 0; row < layout.rows(); row++) {
            int rowStart = row * layout.columns();
            int rowSize = Math.min(layout.columns(), entries.size() - rowStart);
            float rowWidth = (SHOP_BACKGROUND_SIZE + Math.max(0, rowSize - 1) * SHOP_COLUMN_SPACING) * layout.scale();
            float rowX = this.width / 2f - rowWidth / 2f;
            float rowY = topEdge + SHOP_PRICE_OVERFLOW * layout.scale() + row * SHOP_ROW_SPACING * layout.scale();

            for (int column = 0; column < rowSize; column++) {
                int index = rowStart + column;
                int x = Math.round(rowX + column * SHOP_COLUMN_SPACING * layout.scale());
                int y = Math.round(rowY);
                this.addDrawableChild(new StoreItemWidget(this, x, y, layout.scale(), entries.get(index), index));
            }
        }
    }

    @Override
    protected void drawBackground(@NotNull DrawContext context, float delta, int mouseX, int mouseY) {
        context.drawTexture(BACKGROUND_TEXTURE, this.x, this.y, 0, 0, this.backgroundWidth, this.backgroundHeight);

        context.getMatrices().push();
        context.getMatrices().translate(context.getScaledWindowWidth() / 2f, context.getScaledWindowHeight(), 0);
        float scale = 0.28f;
        context.getMatrices().scale(scale, scale, 1f);
        int height = 254;
        int width = 497;
        context.getMatrices().translate(0, -230, 0);
        int xOffset = 0;
        int yOffset = 0;
        context.drawTexturedQuad(ID, (int) (xOffset - width / 2f), (int) (xOffset + width / 2f), (int) (yOffset - height / 2f), (int) (yOffset + height / 2f), 0, 0, 1f, 0, 1f, 1f, 1f, 1f, 1f);
        context.getMatrices().pop();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        this.drawMouseoverTooltip(context, mouseX, mouseY);
        StoreRenderer.renderHud(this.textRenderer, this.player, context, delta);
    }

    private ShopLayout computeShopLayout(int entryCount) {
        int availableWidth = Math.max(SHOP_BACKGROUND_SIZE, this.width - SHOP_SIDE_MARGIN * 2);
        int hotbarTop = this.y + 8;
        int availableBottom = hotbarTop - SHOP_TOOLTIP_GAP - SHOP_TOOLTIP_RESERVED_HEIGHT;
        int availableHeight = Math.max(1, availableBottom - SHOP_TOP_MARGIN);

        int bestColumns = 1;
        int bestRows = entryCount;
        float bestScale = 0f;

        for (int columns = 1; columns <= entryCount; columns++) {
            int rows = (entryCount + columns - 1) / columns;
            int width = SHOP_BACKGROUND_SIZE + Math.max(0, columns - 1) * SHOP_COLUMN_SPACING;
            int height = SHOP_PRICE_OVERFLOW + SHOP_BACKGROUND_SIZE + Math.max(0, rows - 1) * SHOP_ROW_SPACING;
            float scale = Math.min(1f, Math.min(availableWidth / (float) width, availableHeight / (float) height));

            if (scale > bestScale || (Math.abs(scale - bestScale) < 1.0E-4f && columns > bestColumns)) {
                bestColumns = columns;
                bestRows = rows;
                bestScale = scale;
            }
        }

        return new ShopLayout(bestColumns, bestRows, bestScale, availableBottom);
    }

    private record ShopLayout(int columns, int rows, float scale, int availableBottom) {
    }

    public static class StoreItemWidget extends ButtonWidget {
        public final LimitedInventoryScreen screen;
        public final ShopEntry entry;
        public final float scale;

        public StoreItemWidget(LimitedInventoryScreen screen, int x, int y, float scale, @NotNull ShopEntry entry, int index) {
            super(x, y, Math.max(1, Math.round(SHOP_BACKGROUND_SIZE * scale)), Math.max(1, Math.round(SHOP_BACKGROUND_SIZE * scale)), entry.stack().getName(), (a) -> ClientPlayNetworking.send(new StoreBuyPayload(index)), DEFAULT_NARRATION_SUPPLIER);
            this.screen = screen;
            this.entry = entry;
            this.scale = scale;
        }

        @Override
        protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            // Get shop component for cooldown/stock info
            PlayerShopComponent shopComponent = PlayerShopComponent.KEY.get(screen.player);
            boolean onCooldown = shopComponent.isOnCooldown(entry.id());
            boolean inStock = shopComponent.isInStock(entry.id());
            int remainingStock = shopComponent.getRemainingStock(entry.id());
            int maxStock = shopComponent.getMaxStock(entry.id());
            int remainingCooldown = shopComponent.getRemainingCooldown(entry.id());

            boolean unavailable = onCooldown || !inStock;

            context.getMatrices().push();
            context.getMatrices().translate(this.getX(), this.getY(), 0);
            context.getMatrices().scale(this.scale, this.scale, 1f);

            context.drawGuiTexture(entry.type().getTexture(), 0, 0, SHOP_BACKGROUND_SIZE, SHOP_BACKGROUND_SIZE);
            context.drawItem(this.entry.stack(), SHOP_ITEM_OFFSET, SHOP_ITEM_OFFSET);

            if (unavailable) {
                int darkColor = 0xAA000000;
                context.fillGradient(RenderLayer.getGuiOverlay(), SHOP_ITEM_OFFSET, SHOP_ITEM_OFFSET, SHOP_ITEM_OFFSET + SHOP_ITEM_SIZE, SHOP_ITEM_OFFSET + SHOP_ITEM_SIZE, darkColor, darkColor, 200);
            }

            context.getMatrices().push();
            context.getMatrices().translate(0, 0, 200);

            if (onCooldown) {
                int seconds = remainingCooldown / 20;
                if (seconds > 0) {
                    String cooldownText = seconds + "s";
                    int textX = SHOP_ITEM_OFFSET + SHOP_ITEM_SIZE / 2 - screen.textRenderer.getWidth(cooldownText) / 2;
                    int textY = SHOP_ITEM_OFFSET + 4;
                    context.drawText(screen.textRenderer, cooldownText, textX, textY, 0xFFFFFF, true);
                }
            }

            if (maxStock > 0) {
                String stockText = String.valueOf(remainingStock);
                int stockColor = remainingStock > 0 ? 0xFFFFFF : 0xFF4444;
                int textX = SHOP_ITEM_OFFSET + SHOP_ITEM_SIZE - screen.textRenderer.getWidth(stockText);
                int textY = SHOP_ITEM_OFFSET + SHOP_ITEM_SIZE - 8;
                context.drawText(screen.textRenderer, stockText, textX, textY, stockColor, true);
            }

            context.getMatrices().pop();

            context.getMatrices().pop();

            MutableText price = Text.literal(this.entry.price() + "\uE781");
            drawPriceLabel(context, price);

            if (this.isHovered()) {
                this.screen.renderLimitedInventoryTooltip(context, this.entry.stack());
                if (!unavailable) {
                    context.getMatrices().push();
                    context.getMatrices().translate(this.getX(), this.getY(), 0);
                    context.getMatrices().scale(this.scale, this.scale, 1f);
                    drawShopSlotHighlight(context, SHOP_ITEM_OFFSET, SHOP_ITEM_OFFSET, 0);
                    context.getMatrices().pop();
                }
            }
        }

        private void drawShopSlotHighlight(DrawContext context, int x, int y, int z) {
            int color = 0x90FFBF49;
            context.fillGradient(RenderLayer.getGuiOverlay(), x, y, x + 16, y + 14, color, color, z);
            context.fillGradient(RenderLayer.getGuiOverlay(), x, y + 14, x + 15, y + 15, color, color, z);
            context.fillGradient(RenderLayer.getGuiOverlay(), x, y + 15, x + 14, y + 16, color, color, z);
        }

        private void drawPriceLabel(DrawContext context, Text price) {
            int textWidth = this.screen.textRenderer.getWidth(price);
            int boxWidth = Math.max(this.getWidth() + SHOP_PRICE_LABEL_EXTRA_WIDTH, textWidth + SHOP_PRICE_LABEL_PADDING_X * 2);
            int boxHeight = this.screen.textRenderer.fontHeight + SHOP_PRICE_LABEL_PADDING_Y * 2;
            int boxX = this.getX() + this.getWidth() / 2 - boxWidth / 2;
            int boxY = this.getY() - boxHeight - Math.max(SHOP_PRICE_LABEL_GAP, Math.round(SHOP_PRICE_LABEL_GAP * this.scale));
            int left = boxX - 1;
            int top = boxY - 1;
            int right = boxX + boxWidth + 1;
            int bottom = boxY + boxHeight + 1;
            int backgroundColor = 0xFF160902;
            int borderTopColor = 0xFFC5A244;
            int borderBottomColor = 0xFF815A15;
            int textX = boxX + (boxWidth - textWidth) / 2;
            int textY = boxY + (boxHeight - this.screen.textRenderer.fontHeight) / 2;

            context.fill(left, top, right, bottom, backgroundColor);
            context.fill(left, top, right, top + 1, borderTopColor);
            context.fill(left, top, left + 1, bottom, borderTopColor);
            context.fill(left, bottom - 1, right, bottom, borderBottomColor);
            context.fill(right - 1, top, right, bottom, borderBottomColor);
            context.drawText(this.screen.textRenderer, price, textX, textY, 0xFFFFFF, true);
        }

        @Override
        public void drawMessage(DrawContext context, TextRenderer textRenderer, int color) {
        }
    }
}
