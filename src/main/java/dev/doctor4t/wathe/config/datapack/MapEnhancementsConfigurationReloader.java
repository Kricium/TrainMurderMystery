package dev.doctor4t.wathe.config.datapack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.WatheGameModes;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 从 datapack 加载地图配置的资源重载监听器
 * 配置路径:
 *   - data/wathe/maps/*.json (多地图注册表配置)
 *   - data/wathe/areas/*.json (legacy 单地图配置，自动注册为 overworld)
 */
public class MapEnhancementsConfigurationReloader implements SimpleSynchronousResourceReloadListener {

    private static final Gson GSON = new GsonBuilder().setLenient().create();
    private static final String LEGACY_DATA_PATH = "areas";
    private static final String MAPS_DATA_PATH = "maps";
    private static final Set<Identifier> KNOWN_GAME_MODES = Set.of(
        WatheGameModes.MURDER_ID,
        WatheGameModes.DISCOVERY_ID,
        WatheGameModes.LOOSE_ENDS_ID
    );

    public static void register() {
        ResourceManagerHelper.get(ResourceType.SERVER_DATA)
            .registerReloadListener(new MapEnhancementsConfigurationReloader());
        Wathe.LOGGER.info("Registered map configuration reloader");
    }

    @Override
    public Identifier getFabricId() {
        return Wathe.id("area_configuration");
    }

    @Override
    public void reload(ResourceManager manager) {
        Wathe.LOGGER.info("Reloading map configurations...");

        // Clear registry
        MapRegistry.getInstance().clear();

        // === Load multi-map configs from data/wathe/maps/*.json ===
        Map<Identifier, Resource> mapResources = manager.findResources(
            MAPS_DATA_PATH,
            id -> id.getPath().endsWith(".json")
        );
        List<PendingMapConfig> pendingRefConfigs = new ArrayList<>();

        for (Map.Entry<Identifier, Resource> entry : mapResources.entrySet()) {
            Identifier resourceId = entry.getKey();

            // Only load wathe namespace
            if (!resourceId.getNamespace().equals(Wathe.MOD_ID)) {
                continue;
            }

            try (InputStreamReader reader = new InputStreamReader(
                    entry.getValue().getInputStream(),
                    StandardCharsets.UTF_8)) {

                JsonElement json = GSON.fromJson(reader, JsonElement.class);

                String path = resourceId.getPath();
                String name = path.substring(MAPS_DATA_PATH.length() + 1, path.length() - 5); // strip prefix and .json
                Identifier mapId = Identifier.of(resourceId.getNamespace(), name);

                if (json.isJsonObject() && json.getAsJsonObject().has("ref")) {
                    pendingRefConfigs.add(new PendingMapConfig(resourceId, mapId, json.getAsJsonObject()));
                    continue;
                }

                Optional<MapRegistryEntry> result = parseMapConfig(resourceId, mapId, json);

                if (result.isPresent()) {
                    MapRegistry.getInstance().register(mapId, result.get());
                    Wathe.LOGGER.info("Registered map '{}' (dimension: {}) from {}",
                        mapId, result.get().dimensionId(), resourceId);
                }

            } catch (Exception e) {
                Wathe.LOGGER.error("Error loading map config from {}", resourceId, e);
            }
        }
        resolvePendingRefConfigs(pendingRefConfigs);

        // === Legacy compatibility: load from data/wathe/areas/*.json as overworld map ===
        // Only if no maps were loaded from the new path
        if (MapRegistry.getInstance().getMapCount() == 0) {
            Map<Identifier, Resource> legacyResources = manager.findResources(
                LEGACY_DATA_PATH,
                id -> id.getPath().endsWith(".json")
            );

            for (Map.Entry<Identifier, Resource> entry : legacyResources.entrySet()) {
                Identifier resourceId = entry.getKey();

                if (!resourceId.getNamespace().equals(Wathe.MOD_ID)) {
                    continue;
                }

                try (InputStreamReader reader = new InputStreamReader(
                        entry.getValue().getInputStream(),
                        StandardCharsets.UTF_8)) {

                    JsonElement json = GSON.fromJson(reader, JsonElement.class);

                    Optional<MapEnhancementsConfiguration> result = MapEnhancementsConfiguration.CODEC
                        .parse(JsonOps.INSTANCE, json)
                        .resultOrPartial(error ->
                            Wathe.LOGGER.error("Failed to parse legacy area config {}: {}", resourceId, error));

                    if (result.isPresent()) {
                        // Register as overworld map entry
                        Identifier overworldDimension = Identifier.ofVanilla("overworld");
                        MapRegistryEntry legacyEntry = new MapRegistryEntry(
                            Identifier.of(Wathe.MOD_ID, "legacy_overworld"),
                            overworldDimension,
                            "Overworld",
                            Optional.empty(),
                            result.get(),
                            0,
                            100,
                            new LinkedHashSet<>(KNOWN_GAME_MODES)
                        );
                        MapRegistry.getInstance().register(
                            Identifier.of(Wathe.MOD_ID, "legacy_overworld"),
                            legacyEntry
                        );
                        Wathe.LOGGER.info("Loaded legacy area config from {} as overworld map", resourceId);
                        break; // Only load first valid legacy config
                    }

                } catch (Exception e) {
                    Wathe.LOGGER.error("Error loading legacy area config from {}", resourceId, e);
                }
            }
        }

        Wathe.LOGGER.info("Map registry loaded: {} maps registered", MapRegistry.getInstance().getMapCount());
    }

    private void resolvePendingRefConfigs(List<PendingMapConfig> pendingRefConfigs) {
        boolean madeProgress = true;
        while (!pendingRefConfigs.isEmpty() && madeProgress) {
            madeProgress = false;
            for (int i = pendingRefConfigs.size() - 1; i >= 0; i--) {
                PendingMapConfig pending = pendingRefConfigs.get(i);
                Optional<MapRegistryEntry> result = parseMapConfig(pending.resourceId(), pending.mapId(), pending.json());
                if (result.isPresent()) {
                    MapRegistry.getInstance().register(pending.mapId(), result.get());
                    Wathe.LOGGER.info("Registered map '{}' (dimension: {}) from {}",
                        pending.mapId(), result.get().dimensionId(), pending.resourceId());
                    pendingRefConfigs.remove(i);
                    madeProgress = true;
                }
            }
        }

        for (PendingMapConfig pending : pendingRefConfigs) {
            Wathe.LOGGER.error("Unable to resolve referenced map config {} after loading all base maps", pending.resourceId());
        }
    }

    private Optional<MapRegistryEntry> parseMapConfig(Identifier resourceId, Identifier mapId, JsonElement json) {
        if (!json.isJsonObject()) {
            Wathe.LOGGER.error("Map config {} is not a JSON object", resourceId);
            return Optional.empty();
        }

        JsonObject object = json.getAsJsonObject();

        if (object.has("ref")) {
            Identifier referenceId = Identifier.tryParse(object.get("ref").getAsString());
            if (referenceId == null) {
                Wathe.LOGGER.error("Invalid ref id in {}: {}", resourceId, object.get("ref"));
                return Optional.empty();
            }
            MapRegistryEntry reference = MapRegistry.getInstance().getMap(referenceId);
            if (reference == null) {
                Wathe.LOGGER.error("Referenced map {} not loaded before {}", referenceId, resourceId);
                return Optional.empty();
            }

            Identifier dimensionId = object.has("dimension")
                ? Identifier.tryParse(object.get("dimension").getAsString())
                : reference.dimensionId();
            if (dimensionId == null) {
                Wathe.LOGGER.error("Invalid dimension id in {}: {}", resourceId, object.get("dimension"));
                return Optional.empty();
            }
            Set<Identifier> gameModes = extractGameModes(object, reference.gameModes());
            Optional<String> overrideDisplay = object.has("display_name")
                ? Optional.of(object.get("display_name").getAsString())
                : Optional.empty();
            Optional<String> overrideDescription = object.has("description")
                ? Optional.of(object.get("description").getAsString())
                : Optional.empty();
            int minPlayers = object.has("min_players") ? object.get("min_players").getAsInt() : reference.minPlayers();
            int maxPlayers = object.has("max_players") ? object.get("max_players").getAsInt() : reference.maxPlayers();

            return Optional.of(new MapRegistryEntry(
                mapId,
                dimensionId,
                overrideDisplay.orElse(reference.displayName()),
                overrideDescription.isPresent() ? overrideDescription : reference.description(),
                reference.enhancements(),
                minPlayers,
                maxPlayers,
                gameModes
            ));
        }

        Optional<MapRegistryEntry> parsed = MapRegistryEntry.CODEC
            .parse(JsonOps.INSTANCE, json)
            .resultOrPartial(error ->
                Wathe.LOGGER.error("Failed to parse map config {}: {}", resourceId, error));
        return parsed.map(entry -> entry.withId(mapId));
    }

    private Set<Identifier> extractGameModes(JsonObject object, Set<Identifier> fallback) {
        if (!object.has("game_modes")) {
            return new LinkedHashSet<>(fallback);
        }
        if (!object.get("game_modes").isJsonArray()) {
            return new LinkedHashSet<>(fallback);
        }

        LinkedHashSet<Identifier> result = new LinkedHashSet<>();
        object.getAsJsonArray("game_modes").forEach(element -> {
            Identifier id = Identifier.tryParse(element.getAsString());
            if (id != null) {
                result.add(id);
            }
        });
        return result;
    }

    private record PendingMapConfig(Identifier resourceId, Identifier mapId, JsonObject json) {
    }
}
