package dev.doctor4t.wathe.config.datapack;

import net.minecraft.util.Identifier;

import java.util.*;

/**
 * 多地图注册表（单例）
 * 存储从数据包加载的所有地图配置
 */
public class MapRegistry {
    private static final MapRegistry INSTANCE = new MapRegistry();

    private final Map<Identifier, MapRegistryEntry> maps = new LinkedHashMap<>();

    private MapRegistry() {
    }

    public static MapRegistry getInstance() {
        return INSTANCE;
    }

    public void register(Identifier id, MapRegistryEntry entry) {
        maps.put(id, entry);
    }

    public void clear() {
        maps.clear();
    }

    public Map<Identifier, MapRegistryEntry> getMaps() {
        return Collections.unmodifiableMap(maps);
    }

    public MapRegistryEntry getMap(Identifier id) {
        return maps.get(id);
    }

    public int getMapCount() {
        return maps.size();
    }

    public List<MapRegistryEntry> getMapsForGameMode(Identifier gameModeId) {
        List<MapRegistryEntry> filtered = new ArrayList<>();
        for (MapRegistryEntry entry : maps.values()) {
            if (entry.supportsGameMode(gameModeId)) {
                filtered.add(entry);
            }
        }
        return filtered;
    }

    public List<MapRegistryEntry> getEligibleMapsForGameMode(Identifier gameModeId, int playerCount) {
        List<MapRegistryEntry> eligible = new ArrayList<>();
        for (MapRegistryEntry entry : maps.values()) {
            if (entry.supportsGameMode(gameModeId) && entry.isEligible(playerCount)) {
                eligible.add(entry);
            }
        }
        return eligible;
    }

    public Set<Identifier> getMapIds() {
        return Collections.unmodifiableSet(maps.keySet());
    }

    /**
     * 获取符合人数限制的地图列表
     */
    public List<MapRegistryEntry> getEligibleMaps(int playerCount) {
        List<MapRegistryEntry> eligible = new ArrayList<>();
        for (MapRegistryEntry entry : maps.values()) {
            if (entry.isEligible(playerCount)) {
                eligible.add(entry);
            }
        }
        return eligible;
    }

    /**
     * 获取所有地图条目列表（保持注册顺序）
     */
    public List<MapRegistryEntry> getAllMaps() {
        return new ArrayList<>(maps.values());
    }
}
