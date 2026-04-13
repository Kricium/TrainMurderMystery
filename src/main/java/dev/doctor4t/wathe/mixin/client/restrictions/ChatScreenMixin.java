package dev.doctor4t.wathe.mixin.client.restrictions;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {
    @Shadow
    protected TextFieldWidget chatField;

    @Inject(method = "init", at = @At("TAIL"))
    private void wathe$limitChatInputLength(CallbackInfo ci) {
        this.chatField.setMaxLength(256);
    }

    @Inject(method = "sendMessage", at = @At("HEAD"), cancellable = true)
    private void wathe$checkMessageLength(String chatText, boolean addToHistory, CallbackInfo ci) {
        if (chatText.isEmpty()) return;
        // Allow commands (starting with /) to pass through without length check
        if (chatText.startsWith("/")) return;
        // Block non-command messages exceeding 40 characters
        if (chatText.length() > 40) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendMessage(
                        Text.literal("消息过长，最多只能发送40个字符！").formatted(Formatting.RED),
                        true
                );
            }
            ci.cancel();
        }
    }
}
