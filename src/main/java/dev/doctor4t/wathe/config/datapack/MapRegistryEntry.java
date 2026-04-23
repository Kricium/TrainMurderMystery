package dev.doctor4t.wathe.config.datapack;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.WatheGameModes;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 地图注册表条目
 * 每个条目代表一张可投票的地图，对应一个维度
 */
public record MapRegistryEntry(
    Identifier id,
    Identifier dimensionId,
    String displayName,
    Optional<String> description,
    MapEnhancementsConfiguration enhancements,
    int minPlayers,
    int maxPlayers,
    Set<Identifier> gameModes
) {
    private static final List<Identifier> DEFAULT_GAME_MODES = List.of(
        WatheGameModes.MURDER_ID,
        WatheGameModes.DISCOVERY_ID,
        WatheGameModes.LOOSE_ENDS_ID
    );

    public static final Codec<MapRegistryEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Identifier.CODEC.fieldOf("dimension").forGetter(MapRegistryEntry::dimensionId),
        Codec.STRING.fieldOf("display_name").forGetter(MapRegistryEntry::displayName),
        Codec.STRING.optionalFieldOf("description").forGetter(MapRegistryEntry::description),
        MapEnhancementsConfiguration.CODEC.optionalFieldOf("enhancements", new MapEnhancementsConfiguration(
            java.util.List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()
        )).forGetter(MapRegistryEntry::enhancements),
        Codec.INT.optionalFieldOf("min_players", 0).forGetter(MapRegistryEntry::minPlayers),
        Codec.INT.optionalFieldOf("max_players", 100).forGetter(MapRegistryEntry::maxPlayers),
        Identifier.CODEC.listOf().optionalFieldOf("game_modes", DEFAULT_GAME_MODES).forGetter(entry -> new ArrayList<>(entry.gameModes()))
    ).apply(instance, (dimensionId, displayName, description, enhancements, minPlayers, maxPlayers, gameModes) ->
        new MapRegistryEntry(
            dimensionId,
            dimensionId,
            displayName,
            description,
            enhancements,
            minPlayers,
            maxPlayers,
            new LinkedHashSet<>(gameModes)
        )));

    public MapRegistryEntry {
        if (gameModes == null || gameModes.isEmpty()) {
            gameModes = new LinkedHashSet<>(DEFAULT_GAME_MODES);
        } else {
            gameModes = new LinkedHashSet<>(gameModes);
        }
    }

    public MapRegistryEntry withId(Identifier id) {
        return new MapRegistryEntry(id, dimensionId, displayName, description, enhancements, minPlayers, maxPlayers, gameModes);
    }

    public boolean supportsGameMode(Identifier gameModeId) {
        return gameModes.contains(gameModeId);
    }

    /**
     * 检查给定人数是否满足此地图的人数限制
     */
    public boolean isEligible(int playerCount) {
        return playerCount >= minPlayers && playerCount <= maxPlayers;
    }

    public static Identifier defaultReferenceIdForDimension(Identifier dimensionId) {
        return Wathe.id("ref/" + dimensionId.getNamespace() + "/" + dimensionId.getPath());
    }
}
