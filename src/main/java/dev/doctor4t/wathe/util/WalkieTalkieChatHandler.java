package dev.doctor4t.wathe.util;

import dev.doctor4t.wathe.compat.TrainVoicePlugin;
import dev.doctor4t.wathe.index.WatheDataComponentTypes;
import dev.doctor4t.wathe.item.WalkieTalkieItem;
import dev.doctor4t.wathe.item.component.WalkieTalkieComponent;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;

/**
 * 对讲机聊天消息处理器（服务端）
 *
 * 当玩家手持对讲机在聊天框发送消息时：
 * 1. 不拦截原始聊天消息（聊天正常发送）
 * 2. 获取发送者手持对讲机的频道号
 * 3. 遍历所有在线玩家，找到物品栏中有相同频道对讲机的玩家
 * 4. 通过 S2C 数据包将消息副本发送给这些玩家，在屏幕上方显示
 * 5. 在发送者位置播放经验球音效，让周围玩家听到
 *
 * 复用 TrainVoicePlugin 中的 isReceivingChannel 方法来检查接收者频道
 */
public class WalkieTalkieChatHandler {

    /**
     * 注册服务端聊天消息事件
     * 在 Wathe.onInitialize() 中调用
     */
    public static void register() {
        // 使用 CHAT_MESSAGE 事件（消息发送后触发），不拦截原始聊天
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            // 检查发送者是否手持对讲机（主手）
            ItemStack mainHandStack = sender.getMainHandStack();
            if (!(mainHandStack.getItem() instanceof WalkieTalkieItem)) {
                return;
            }

            // 获取发送者对讲机的频道号
            WalkieTalkieComponent component = mainHandStack.getOrDefault(
                    WatheDataComponentTypes.WALKIE_TALKIE, WalkieTalkieComponent.DEFAULT);
            int senderChannel = component.channel();

            // 获取聊天消息内容
            String chatMessage = message.getContent().getString();

            // 在发送者位置播放经验球拾取音效，让周围玩家可以听到
            sender.getServerWorld().playSound(
                    null,                          // 所有玩家都能听到（包括发送者自己）
                    sender.getX(), sender.getY(), sender.getZ(),
                    SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
                    SoundCategory.PLAYERS,
                    1.0f,                          // 音量
                    1.0f                           // 音调
            );

            // 遍历所有在线玩家，向同频道的玩家发送广播
            for (ServerPlayerEntity receiver : sender.getServer().getPlayerManager().getPlayerList()) {
                // 检查接收者物品栏中是否有相同频道的对讲机
                if (!TrainVoicePlugin.isReceivingChannel(receiver, senderChannel)) {
                    continue;
                }

                // 发送 S2C 广播数据包，在接收者屏幕上方显示消息副本
                ServerPlayNetworking.send(receiver, new WalkieTalkieBroadcastPayload(
                        sender.getName().getString(), senderChannel, chatMessage));
            }
        });
    }
}
