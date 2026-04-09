package dev.doctor4t.wathe.util;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.client.gui.WalkieTalkieBroadcastRenderer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import org.jetbrains.annotations.NotNull;

/**
 * 对讲机广播消息 S2C 数据包
 * 服务端发送给客户端，用于在屏幕上方显示对讲机频道中的文字消息
 */
public record WalkieTalkieBroadcastPayload(String message) implements CustomPayload {
    public static final Id<WalkieTalkieBroadcastPayload> ID = new Id<>(Wathe.id("walkie_talkie_broadcast"));
    public static final PacketCodec<PacketByteBuf, WalkieTalkieBroadcastPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, WalkieTalkieBroadcastPayload::message,
            WalkieTalkieBroadcastPayload::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    /**
     * 客户端接收处理器：将收到的广播消息添加到渲染队列中
     */
    public static class Receiver implements ClientPlayNetworking.PlayPayloadHandler<WalkieTalkieBroadcastPayload> {
        @Override
        public void receive(@NotNull WalkieTalkieBroadcastPayload payload, ClientPlayNetworking.@NotNull Context context) {
            // 将消息添加到客户端广播渲染队列
            context.client().execute(() -> {
                WalkieTalkieBroadcastRenderer.addMessage(payload.message());
            });
        }
    }
}
