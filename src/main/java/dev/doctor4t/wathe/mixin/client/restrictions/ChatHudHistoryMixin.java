package dev.doctor4t.wathe.mixin.client.restrictions;

import dev.doctor4t.wathe.WatheConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import net.minecraft.client.gui.hud.ChatHud;

@Mixin(ChatHud.class)
public class ChatHudHistoryMixin {
    @ModifyConstant(method = "addMessage(Lnet/minecraft/client/gui/hud/ChatHudLine;)V", constant = @Constant(intValue = 100))
    private int wathe$increaseChatHistoryLimit(int original) {
        return Math.clamp(WatheConfig.chatHistoryLimit, WatheConfig.MIN_CHAT_HISTORY_LIMIT, WatheConfig.MAX_CHAT_HISTORY_LIMIT);
    }
}
