package dev.doctor4t.wathe.mixin.client.restrictions;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.client.WatheClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 处理聊天界面显示
 * 当玩家处于游戏中的时候屏蔽聊天界面
 * 以使用talk bubbles模组
 */

@Mixin(ChatHud.class)
public class ChatHudMixin {
    @WrapMethod(method = "render")
    public void wathe$disableChatRender(DrawContext context, int currentTick, int mouseX, int mouseY, boolean focused, Operation<Void> original) {
        if (MinecraftClient.getInstance().player != null) {
            GameWorldComponent gameWorldComponent = GameWorldComponent.KEY.get(MinecraftClient.getInstance().player.getWorld());
            if (!WatheClient.isPlayerAliveAndInSurvival() || !gameWorldComponent.isRunning()) {
                original.call(context, currentTick, mouseX, mouseY, focused);
            }
        }
    }
}
