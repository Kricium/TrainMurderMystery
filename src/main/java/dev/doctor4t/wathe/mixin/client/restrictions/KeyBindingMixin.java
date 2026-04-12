package dev.doctor4t.wathe.mixin.client.restrictions;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.doctor4t.wathe.cca.MapEnhancementsWorldComponent;
import dev.doctor4t.wathe.cca.PlayerStaminaComponent;
import dev.doctor4t.wathe.client.WatheClient;
import dev.doctor4t.wathe.config.datapack.MapEnhancementsConfiguration.JumpConfig;
import dev.doctor4t.wathe.game.GameFunctions;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(KeyBinding.class)
public abstract class KeyBindingMixin {
    @Shadow
    public abstract boolean equals(KeyBinding other);

    @Unique
    private boolean shouldSuppressKey() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        boolean creativeOrSpectator = GameFunctions.isPlayerSpectatingOrCreative(player);
        boolean result = false;

        // 游戏开始之后根据地图配置决定是否屏蔽跳跃键；创造/旁观模式不受限制
        if (!creativeOrSpectator
                && WatheClient.gameComponent != null
                && WatheClient.gameComponent.isRunning()
                && WatheClient.isPlayerPlayingAndAlive()) {
            KeyBinding jumpKey = client.options.jumpKey;
            if (WatheClient.mapEnhancementsWorldComponent != null) {
                JumpConfig jumpConfig = WatheClient.mapEnhancementsWorldComponent.getJumpConfig();
                if (!jumpConfig.allowed()) {
                    result = this.equals(jumpKey);
                } else if (jumpConfig.staminaCost() > 0 && this.equals(jumpKey) && player != null) {
                    PlayerStaminaComponent stamina = PlayerStaminaComponent.KEY.get(player);
                    if (!stamina.isInfiniteStamina()) {
                        result = stamina.getSprintingTicks() < jumpConfig.staminaCost();
                    }
                }
            } else {
                result = this.equals(jumpKey);
            }
        }
        // 其他键位始终不允许，防止出现bug
        if (!result && WatheClient.isPlayerPlayingAndAlive() && !WatheClient.isPlayerCreative() && WatheClient.trainComponent != null && WatheClient.trainComponent.hasHud()) {
            result = this.equals(client.options.swapHandsKey) ||
                    this.equals(client.options.togglePerspectiveKey) ||
                    this.equals(client.options.dropKey) ||
                    this.equals(client.options.advancementsKey) ||
                    this.equals(client.options.spectatorOutlinesKey);
        }
        return result;
    }

    @ModifyReturnValue(method = "wasPressed", at = @At("RETURN"))
    private boolean wathe$restrainWasPressedKeys(boolean original) {
        if (this.shouldSuppressKey()) return false;
        else return original;
    }

    @ModifyReturnValue(method = "isPressed", at = @At("RETURN"))
    private boolean wathe$restrainIsPressedKeys(boolean original) {
        if (this.shouldSuppressKey()) return false;
        else return original;
    }

    @ModifyReturnValue(method = "matchesKey", at = @At("RETURN"))
    private boolean wathe$restrainMatchesKey(boolean original) {
        if (this.shouldSuppressKey()) return false;
        else return original;
    }
}
