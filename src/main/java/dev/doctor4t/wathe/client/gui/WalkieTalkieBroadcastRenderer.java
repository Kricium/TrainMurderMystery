package dev.doctor4t.wathe.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 对讲机广播消息的客户端渲染管理器
 * 负责管理消息队列并在屏幕上方渲染文字框
 *
 * 参考 StarRailExpress 中 BroadcasterHudMixin 的实现方式：
 * - 消息以带半透明黑色背景的文字框形式显示在屏幕上方居中位置
 * - 支持多条消息堆叠显示，超过上限时显示"还有 N 条消息"提示
 * - 每条消息有存活时间，过期后自动移除
 */
public class WalkieTalkieBroadcastRenderer {

    /**
     * 广播消息信息记录，包含消息文本和销毁时间
     */
    private record BroadcastMessageInfo(Text message, long destroyTime) {}

    /** 当前待渲染的消息队列 */
    private static final List<BroadcastMessageInfo> messageQueue = new ArrayList<>();

    /** 每条消息的显示持续时间（tick），约 5 秒 */
    private static final int MESSAGE_DURATION_TICKS = 100;

    /** 屏幕上最多同时显示的消息数量 */
    private static final int MAX_VISIBLE_MESSAGES = 4;

    /** 消息起始 Y 坐标（距屏幕顶部的像素距离） */
    private static final int START_Y = 20;

    /** 每条消息之间的垂直间距（像素） */
    private static final int MESSAGE_SPACING = 20;

    /** 文字框内边距（像素） */
    private static final int PADDING = 4;

    /** 半透明黑色背景颜色 (ARGB: 50% 不透明度的黑色) */
    private static final int BG_COLOR = 0x80000000;

    /** 文字颜色（白色） */
    private static final int TEXT_COLOR = 0xFFFFFF;

    /**
     * 添加一条新的广播消息到渲染队列
     * 由 WalkieTalkieBroadcastPayload.Receiver 在客户端收到数据包时调用
     *
     * @param message 要显示的消息内容
     */
    public static void addMessage(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null) return;

        // 计算消息销毁时间 = 当前游戏时间 + 持续时间
        long destroyTime = client.world.getTime() + MESSAGE_DURATION_TICKS;
        messageQueue.add(new BroadcastMessageInfo(Text.literal(message), destroyTime));
    }

    /**
     * 在 HUD 上渲染所有未过期的广播消息
     * 由 InGameHudMixin 在每帧渲染时调用
     *
     * 渲染逻辑：
     * 1. 先移除所有已过期的消息
     * 2. 从屏幕上方 Y=20 开始，逐条向下绘制消息
     * 3. 每条消息绘制一个半透明黑色背景矩形 + 白色文字
     * 4. 如果消息数量超过上限或超出屏幕中间位置，显示"还有 N 条消息"
     *
     * @param renderer 文字渲染器
     * @param player   当前客户端玩家
     * @param context  绘制上下文
     */
    public static void renderHud(TextRenderer renderer, ClientPlayerEntity player, DrawContext context) {
        if (player == null || messageQueue.isEmpty()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;

        // 移除已过期的消息
        long currentTime = client.world.getTime();
        messageQueue.removeIf(info -> currentTime >= info.destroyTime());

        if (messageQueue.isEmpty()) return;

        int screenWidth = context.getScaledWindowWidth();
        int screenHeight = context.getScaledWindowHeight();
        int maxTextWidth = screenWidth - PADDING * 4;
        int y = START_Y;
        int count = messageQueue.size();

        for (int i = 0; i < count; i++) {
            // 当消息过多或超出屏幕中间时，显示省略提示并跳到最后一条
            if (i >= 1 && (y >= (screenHeight / 2 - 40) || i >= MAX_VISIBLE_MESSAGES) && i < count - 1) {
                // 显示"还有 N 条消息"的提示
                Text moreText = Text.translatable("hud.wathe.walkie_talkie.more_messages", (count - i - 1));
                int textWidth = renderer.getWidth(moreText);
                int x = (screenWidth - textWidth) / 2;

                context.fill(x - PADDING, y - PADDING, x + textWidth + PADDING,
                        y + renderer.fontHeight + PADDING, BG_COLOR);
                context.drawText(renderer, moreText, x, y, 0xAAAAAA, false);
                y += MESSAGE_SPACING;

                // 跳到最后一条消息
                i = count - 1;
            }

            // 绘制单条消息：半透明背景 + 居中白色文字
            BroadcastMessageInfo info = messageQueue.get(i);
            Text message = info.message();
            // 截断过长文本以防溢出屏幕
            if (renderer.getWidth(message) > maxTextWidth) {
                message = Text.literal(renderer.trimToWidth(message.getString(), maxTextWidth - renderer.getWidth("...")) + "...");
            }
            int textWidth = renderer.getWidth(message);
            int x = (screenWidth - textWidth) / 2;

            // 绘制半透明黑色背景矩形
            context.fill(x - PADDING, y - PADDING, x + textWidth + PADDING,
                    y + renderer.fontHeight + PADDING, BG_COLOR);
            // 绘制白色文字
            context.drawText(renderer, message, x, y, TEXT_COLOR, false);
            y += MESSAGE_SPACING;
        }
    }

    /**
     * 清空所有待渲染的广播消息
     * 在游戏重置或断开连接时调用
     */
    public static void clear() {
        messageQueue.clear();
    }
}
