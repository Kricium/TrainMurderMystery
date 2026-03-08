package dev.doctor4t.wathe.index;

import dev.doctor4t.wathe.Wathe;
import net.minecraft.entity.attribute.ClampedEntityAttribute;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;

public interface WatheAttributes {
    // 默认值 200.0 = 平民体力上限 = GameConstants.getInTicks(0, 10) = 10秒 × 20 ticks
    RegistryEntry<EntityAttribute> MAX_SPRINT_TIME =
        Registry.registerReference(Registries.ATTRIBUTE, Wathe.id("max_sprint_time"),
            new ClampedEntityAttribute("attribute.name.wathe.max_sprint_time", 200.0, 0.0, 100000.0)
                .setTracked(true));

    static void initialize() {
        // 触发静态初始化，确保属性注册
    }
}
