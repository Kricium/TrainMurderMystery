package dev.doctor4t.wathe.mixin;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.event.AllowPlayerPunching;
import dev.doctor4t.wathe.cca.GameWorldComponent;
import dev.doctor4t.wathe.cca.MapEnhancementsWorldComponent;
import dev.doctor4t.wathe.cca.PlayerStaminaComponent;
import dev.doctor4t.wathe.config.datapack.MapEnhancementsConfiguration.GravityConfig;
import dev.doctor4t.wathe.config.datapack.MapEnhancementsConfiguration.JumpConfig;
import dev.doctor4t.wathe.game.GameFunctions;
import dev.doctor4t.wathe.index.WatheItems;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundEvent;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin extends EntityMixin {
    @Unique
    private static final EntityAttributeModifier WEAPON_KNOCKBACK_MODIFIER = new EntityAttributeModifier(Wathe.id("weapon_knockback_modifier"), .5f, EntityAttributeModifier.Operation.ADD_VALUE);

    @Unique
    private float wathe$lastGravityMultiplier = Float.NaN;

    @Shadow
    protected boolean jumping;

    @Shadow
    public abstract void playSound(@Nullable SoundEvent sound);

    @Shadow
    public abstract @Nullable EntityAttributeInstance getAttributeInstance(RegistryEntry<EntityAttribute> attribute);

    @Inject(method = "tick", at = @At("HEAD"))
    public void wathe$addKnockbackWithKnife(CallbackInfo ci) {
        if ((Object) this instanceof PlayerEntity player) {
            boolean shouldKnockback = player.getMainHandStack().isOf(WatheItems.KNIFE)
                    || AllowPlayerPunching.EVENT.invoker().allowPunching(player, player);
            updateAttribute(player.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_KNOCKBACK), WEAPON_KNOCKBACK_MODIFIER, shouldKnockback);
        }
    }

    // 服务端应用重力配置 - 根据地图配置修改玩家重力
    @Inject(method = "tick", at = @At("HEAD"))
    public void wathe$applyGravityMultiplier(CallbackInfo ci) {
        if ((Object) this instanceof PlayerEntity player) {
            EntityAttributeInstance gravityAttr = player.getAttributeInstance(EntityAttributes.GENERIC_GRAVITY);
            if (gravityAttr == null) return;

            GameWorldComponent gameComponent = GameWorldComponent.KEY.get(player.getWorld());
            float targetMultiplier;
            if (gameComponent != null && gameComponent.isRunning() && GameFunctions.isPlayerAliveAndSurvival(player)) {
                GravityConfig gravityConfig = MapEnhancementsWorldComponent.KEY.get(player.getWorld()).getGravityConfig();
                targetMultiplier = gravityConfig.gravityMultiplier();
            } else {
                targetMultiplier = 1.0f;
            }

            // 仅在乘数发生变化时更新属性修改器
            if (targetMultiplier != wathe$lastGravityMultiplier) {
                // 移除旧的修改器
                if (gravityAttr.hasModifier(Wathe.id("map_gravity_modifier"))) {
                    gravityAttr.removeModifier(Wathe.id("map_gravity_modifier"));
                }
                // 仅在乘数不为 1.0 时添加修改器
                if (targetMultiplier != 1.0f) {
                    gravityAttr.addTemporaryModifier(new EntityAttributeModifier(
                        Wathe.id("map_gravity_modifier"),
                        targetMultiplier - 1.0f,
                        EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL
                    ));
                }
                wathe$lastGravityMultiplier = targetMultiplier;
            }
        }
    }

    // 服务端限制跳跃 - 根据地图配置决定是否允许跳跃
    @Inject(method = "jump", at = @At("HEAD"), cancellable = true)
    public void wathe$restrictJump(CallbackInfo ci) {
        if ((Object) this instanceof PlayerEntity player) {
            // 仅在服务端检查
            if (!player.getWorld().isClient) {
                GameWorldComponent gameComponent = GameWorldComponent.KEY.get(player.getWorld());
                // 游戏进行中且玩家是存活的生存模式玩家时检查跳跃配置
                if (gameComponent != null && gameComponent.isRunning() && GameFunctions.isPlayerAliveAndSurvival(player)) {
                    JumpConfig jumpConfig = MapEnhancementsWorldComponent.KEY.get(player.getWorld()).getJumpConfig();
                    if (!jumpConfig.allowed()) {
                        // 不允许跳跃
                        ci.cancel();
                    } else if (jumpConfig.staminaCost() > 0) {
                        // 允许跳跃但消耗体力
                        PlayerStaminaComponent stamina = PlayerStaminaComponent.KEY.get(player);
                        if (stamina.isInfiniteStamina()) {
                            // 无限体力，允许跳跃且不消耗体力
                        } else if (stamina.getSprintingTicks() < jumpConfig.staminaCost()) {
                            // 体力不足，取消跳跃
                            ci.cancel();
                        } else {
                            // 扣除体力
                            stamina.setSprintingTicks(stamina.getSprintingTicks() - jumpConfig.staminaCost());
                        }
                    }
                    // allowed=true 且 staminaCost=0 → 自由跳跃，不取消
                }
            }
        }
    }

    @Unique
    private static void updateAttribute(EntityAttributeInstance attribute, EntityAttributeModifier modifier, boolean addOrKeep) {
        if (attribute != null) {
            boolean alreadyHasModifier = attribute.hasModifier(modifier.id());
            if (addOrKeep && !alreadyHasModifier) {
                attribute.addPersistentModifier(modifier);
            } else if (!addOrKeep && alreadyHasModifier) {
                attribute.removeModifier(modifier);
            }
        }
    }
}
