package dev.doctor4t.wathe.cca;

import dev.doctor4t.wathe.Wathe;
import dev.doctor4t.wathe.api.GameMode;
import dev.doctor4t.wathe.api.WatheGameModes;
import dev.doctor4t.wathe.config.datapack.MapRegistry;
import dev.doctor4t.wathe.config.datapack.MapRegistryEntry;
import dev.doctor4t.wathe.game.GameFunctions;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

import java.util.*;

/**
 * 地图投票 ScoreboardComponent
 * 绑定到 Scoreboard，全局唯一
 * 管理游戏结束后的地图投票流程：投票 → 加权随机 → 轮盘动画 → 传送
 */
public class MapVotingComponent implements AutoSyncedComponent, ServerTickingComponent {
    public static final ComponentKey<MapVotingComponent> KEY =
        ComponentRegistry.getOrCreate(Wathe.id("map_voting"), MapVotingComponent.class);

    private final Scoreboard scoreboard;

    public enum VotingStage {
        MODE,
        MAP
    }

    // === Synced state ===
    private boolean votingActive = false;
    private VotingStage votingStage = VotingStage.MAP;
    private int votingTicksRemaining = 0;
    private final List<VotingModeEntry> availableModes = new ArrayList<>();
    private final List<UnavailableModeEntry> unavailableModes = new ArrayList<>();
    private final List<VotingMapEntry> availableMaps = new ArrayList<>();
    private final List<UnavailableMapEntry> unavailableMaps = new ArrayList<>();
    private int[] voteCounts = new int[0];
    private final Map<UUID, Integer> playerVotes = new HashMap<>();
    private int selectedModeIndex = -1;
    private int selectedMapIndex = -1;
    private boolean roulettePhase = false;
    private int rouletteTicksRemaining = 0;

    // === Persisted ===
    @Nullable
    private Identifier lastSelectedGameMode = null;
    @Nullable
    private Identifier lastSelectedDimension = null;

    // === Server-only (not synced) ===
    @Nullable
    private MinecraftServer server = null;

    private static final int VOTING_DURATION_TICKS = 30 * 20; // 30 seconds
    private static final int ROULETTE_DURATION_TICKS = 8 * 20; // 8 seconds (5s scroll + 3s stop)
    private static final int ALL_VOTED_REMAINING_TICKS = 5 * 20; // 5 seconds after all voted
    private static final int MIN_PLAYERS_FOR_VOTING = 2;
    private static final Identifier RANDOM_MODE_OPTION_ID = Wathe.id("random_mode");
    private static final Identifier RANDOM_MAP_OPTION_ID = Wathe.id("random_map");
    private static final Identifier MURDER_DEFAULT_MAP_ID = Wathe.id("default");

    public record VotingModeEntry(
        Identifier gameModeId,
        String displayName,
        String description,
        int minPlayers,
        boolean showPlayerLimit
    ) {}

    public record UnavailableModeEntry(
        Identifier gameModeId,
        String displayName,
        String reason
    ) {}

    /**
     * 可用地图条目（同步到客户端）
     */
    public record VotingMapEntry(
        Identifier mapId,
        Identifier dimensionId,
        Identifier gameModeId,
        String displayName,
        String description,
        int minPlayers,
        int maxPlayers
    ) {}

    /**
     * 不可用地图条目（同步到客户端，展示不可用原因）
     */
    public record UnavailableMapEntry(
        Identifier dimensionId,
        String displayName,
        String reason
    ) {}

    public MapVotingComponent(Scoreboard scoreboard, @Nullable MinecraftServer server) {
        this.scoreboard = scoreboard;
        this.server = server;
    }

    public void sync() {
        KEY.sync(this.scoreboard);
    }

    // === Getters ===

    public boolean isVotingActive() {
        return votingActive;
    }

    public int getVotingTicksRemaining() {
        return votingTicksRemaining;
    }

    public VotingStage getVotingStage() {
        return votingStage;
    }

    public List<VotingModeEntry> getAvailableModes() {
        return availableModes;
    }

    public List<UnavailableModeEntry> getUnavailableModes() {
        return unavailableModes;
    }

    public List<VotingMapEntry> getAvailableMaps() {
        return availableMaps;
    }

    public List<UnavailableMapEntry> getUnavailableMaps() {
        return unavailableMaps;
    }

    public int[] getVoteCounts() {
        return voteCounts;
    }

    public int getVotedMapIndex(UUID playerId) {
        return playerVotes.getOrDefault(playerId, -1);
    }

    public int getSelectedModeIndex() {
        return selectedModeIndex;
    }

    public int getPlayerVoteCount() {
        return playerVotes.size();
    }

    public int getSelectedMapIndex() {
        return selectedMapIndex;
    }

    public boolean isRoulettePhase() {
        return roulettePhase;
    }

    public int getRouletteTicksRemaining() {
        return rouletteTicksRemaining;
    }

    @Nullable
    public Identifier getLastSelectedGameMode() {
        return lastSelectedGameMode;
    }

    @Nullable
    public Identifier getLastSelectedDimension() {
        return lastSelectedDimension;
    }

    /**
     * Directly set lastSelectedDimension (for single-map skip voting case)
     */
    public void setLastSelectedDimensionDirect(@Nullable Identifier dimensionId) {
        this.lastSelectedDimension = dimensionId;
        this.sync();
    }

    public boolean isModeVoting() {
        return votingStage == VotingStage.MODE;
    }

    // === Voting Logic (server-side) ===

    /**
     * 开始投票（无参数，内部获取人数并过滤地图）
     */
    public void startVoting() {
        if (server == null) {
            Wathe.LOGGER.warn("Cannot start voting: server reference not set");
            return;
        }

        Identifier currentGameModeId = resolveCurrentGameModeId();
        if (currentGameModeId == null) {
            Wathe.LOGGER.warn("Cannot start map voting: current game mode is unknown");
            return;
        }

        int playerCount = 0;
        for (ServerWorld world : server.getWorlds()) {
            playerCount += world.getPlayers().size();
        }

        if (MapRegistry.getInstance().getMapCount() == 0) {
            Wathe.LOGGER.info("No maps registered, skipping voting");
            return;
        }

        resetStateForNewVoting();
        startMapVotingForMode(currentGameModeId, playerCount);
    }

    public void startModeVoting() {
        if (server == null) {
            Wathe.LOGGER.warn("Cannot start mode voting: server reference not set");
            return;
        }

        if (MapRegistry.getInstance().getMapCount() == 0) {
            Wathe.LOGGER.info("No maps registered, skipping mode voting");
            return;
        }

        int playerCount = 0;
        for (ServerWorld world : server.getWorlds()) {
            playerCount += world.getPlayers().size();
        }

        resetStateForNewVoting();
        this.votingActive = true;
        this.votingStage = VotingStage.MODE;
        this.votingTicksRemaining = VOTING_DURATION_TICKS;

        buildModeChoices(playerCount);
        this.voteCounts = new int[this.availableModes.size()];

        if (this.availableModes.isEmpty()) {
            Wathe.LOGGER.info("No eligible game modes for {} players, voting cancelled", playerCount);
            reset();
            return;
        }

        if (this.availableModes.size() == 1) {
            startMapVotingForMode(this.availableModes.get(0).gameModeId(), playerCount);
            return;
        }

        Wathe.LOGGER.info("Mode voting started with {} eligible modes, {} unavailable, for {} players",
            availableModes.size(), unavailableModes.size(), playerCount);
        this.sync();
    }

    private void resetStateForNewVoting() {
        this.votingActive = true;
        this.votingStage = VotingStage.MODE;
        this.votingTicksRemaining = VOTING_DURATION_TICKS;
        this.availableModes.clear();
        this.unavailableModes.clear();
        this.selectedModeIndex = -1;
        this.selectedMapIndex = -1;
        this.roulettePhase = false;
        this.rouletteTicksRemaining = 0;
        this.playerVotes.clear();
        this.availableMaps.clear();
        this.unavailableMaps.clear();
        this.voteCounts = new int[0];
    }

    private void buildModeChoices(int playerCount) {
        for (GameMode mode : WatheGameModes.GAME_MODES.values()) {
            if (mode.identifier.equals(WatheGameModes.DISCOVERY_ID)) {
                continue;
            }

            List<MapRegistryEntry> mapsForMode = MapRegistry.getInstance().getMapsForGameMode(mode.identifier);
            if (mapsForMode.isEmpty()) {
                continue;
            }

            List<MapRegistryEntry> eligibleMapsForMode = MapRegistry.getInstance().getEligibleMapsForGameMode(mode.identifier, playerCount);
            if (playerCount < mode.minPlayerCount || eligibleMapsForMode.isEmpty()) {
                this.unavailableModes.add(new UnavailableModeEntry(
                    mode.identifier,
                    net.minecraft.text.Text.translatable("gamemode." + mode.identifier.getNamespace() + "." + mode.identifier.getPath()).getString(),
                    playerCount < mode.minPlayerCount ? "min_players:" + mode.minPlayerCount : "no_maps"
                ));
                continue;
            }

            this.availableModes.add(new VotingModeEntry(
                mode.identifier,
                net.minecraft.text.Text.translatable("gamemode." + mode.identifier.getNamespace() + "." + mode.identifier.getPath()).getString(),
                net.minecraft.text.Text.translatable(
                    "gui.wathe.mode_voting.description." + mode.identifier.getNamespace() + "." + mode.identifier.getPath()
                ).getString(),
                mode.minPlayerCount,
                mode.hasPlayerLimitDisplay
            ));
        }

        if (this.availableModes.size() > 1) {
            this.availableModes.add(0, new VotingModeEntry(
                RANDOM_MODE_OPTION_ID,
                net.minecraft.text.Text.translatable("gui.wathe.mode_voting.random_mode").getString(),
                net.minecraft.text.Text.translatable("gui.wathe.mode_voting.random_mode.description").getString(),
                0,
                false
            ));
        }
    }

    private void startMapVotingForMode(Identifier gameModeId, int playerCount) {
        this.votingActive = true;
        this.votingStage = VotingStage.MAP;
        this.votingTicksRemaining = VOTING_DURATION_TICKS;
        this.roulettePhase = false;
        this.rouletteTicksRemaining = 0;
        this.playerVotes.clear();
        this.availableMaps.clear();
        this.unavailableMaps.clear();

        List<MapRegistryEntry> allModeMaps = MapRegistry.getInstance().getMapsForGameMode(gameModeId);
        for (MapRegistryEntry mapEntry : allModeMaps) {
            if (mapEntry.isEligible(playerCount)) {
                this.availableMaps.add(new VotingMapEntry(
                    mapEntry.id(),
                    mapEntry.dimensionId(),
                    gameModeId,
                    mapEntry.displayName(),
                    mapEntry.description().orElse(""),
                    mapEntry.minPlayers(),
                    mapEntry.maxPlayers()
                ));
            } else {
                this.unavailableMaps.add(new UnavailableMapEntry(
                    mapEntry.dimensionId(),
                    mapEntry.displayName(),
                    getMapUnavailableReason(mapEntry, playerCount)
                ));
            }
        }

        if (getRandomizableMapIndices(gameModeId).size() > 1) {
            this.availableMaps.add(0, new VotingMapEntry(
                RANDOM_MAP_OPTION_ID,
                RANDOM_MAP_OPTION_ID,
                gameModeId,
                net.minecraft.text.Text.translatable("gui.wathe.map_voting.random_map").getString(),
                net.minecraft.text.Text.translatable("gui.wathe.map_voting.random_map.description").getString(),
                0,
                0
            ));
        }

        this.voteCounts = new int[this.availableMaps.size()];
        this.selectedModeIndex = indexOfMode(gameModeId);
        this.lastSelectedGameMode = gameModeId;

        if (this.availableMaps.isEmpty()) {
            Wathe.LOGGER.info("No eligible maps for mode {} and {} players, voting cancelled", gameModeId, playerCount);
            reset();
            return;
        }

        if (this.availableMaps.size() == 1) {
            VotingMapEntry onlyMap = this.availableMaps.get(0);
            this.lastSelectedDimension = onlyMap.dimensionId();
            this.votingActive = false;
            this.sync();
            ServerWorld overworld = server.getOverworld();
            GameFunctions.finalizeVoting(overworld, gameModeId, onlyMap.dimensionId());
            return;
        }

        Wathe.LOGGER.info("Map voting started for mode {} with {} eligible maps, {} unavailable, for {} players",
            gameModeId, availableMaps.size(), unavailableMaps.size(), playerCount);
        this.sync();
    }

    private String getMapUnavailableReason(MapRegistryEntry mapEntry, int playerCount) {
        if (playerCount < mapEntry.minPlayers()) {
            return "min_players:" + mapEntry.minPlayers();
        }
        if (playerCount > mapEntry.maxPlayers()) {
            return "max_players:" + mapEntry.maxPlayers();
        }
        return "unavailable";
    }

    private int indexOfMode(Identifier gameModeId) {
        for (int i = 0; i < availableModes.size(); i++) {
            if (availableModes.get(i).gameModeId().equals(gameModeId)) {
                return i;
            }
        }
        return -1;
    }

    @Nullable
    private Identifier resolveCurrentGameModeId() {
        if (server == null) {
            return null;
        }

        ServerWorld overworld = server.getOverworld();
        ServerWorld preferredWorld = overworld;
        if (lastSelectedDimension != null) {
            ServerWorld selectedWorld = server.getWorld(net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD, lastSelectedDimension));
            if (selectedWorld != null) {
                preferredWorld = selectedWorld;
            }
        }
        if (preferredWorld != null) {
            GameMode currentMode = GameWorldComponent.KEY.get(preferredWorld).getGameMode();
            if (currentMode != null) {
                return currentMode.identifier;
            }
        }

        return lastSelectedGameMode;
    }

    /**
     * 投票（服务端验证）
     */
    public void castVote(UUID playerId, int optionIndex) {
        if (!votingActive || roulettePhase) return;
        int optionCount = votingStage == VotingStage.MODE ? availableModes.size() : availableMaps.size();
        if (optionIndex < 0 || optionIndex >= optionCount) return;

        // Remove old vote
        Integer oldVote = playerVotes.get(playerId);
        if (oldVote != null && oldVote >= 0 && oldVote < voteCounts.length) {
            voteCounts[oldVote] = Math.max(0, voteCounts[oldVote] - 1);
        }

        // Record new vote
        playerVotes.put(playerId, optionIndex);
        voteCounts[optionIndex]++;

        // 所有在线玩家都投票完成时，缩短倒计时到5秒
        if (server != null && !roulettePhase) {
            int onlinePlayers = 0;
            for (ServerWorld world : server.getWorlds()) {
                onlinePlayers += world.getPlayers().size();
            }
            if (playerVotes.size() >= onlinePlayers && votingTicksRemaining > ALL_VOTED_REMAINING_TICKS) {
                votingTicksRemaining = ALL_VOTED_REMAINING_TICKS;
            }
        }

        this.sync();
    }

    public boolean skipWaitingPhase() {
        if (!votingActive || server == null) {
            return false;
        }

        if (roulettePhase) {
            finishSelection();
            return true;
        }

        endVoting(true);
        return true;
    }

    /**
     * 投票结束，执行加权随机选择
     */
    private void endVoting() {
        endVoting(false);
    }

    private void endVoting(boolean ignoreMinPlayers) {
        if (server == null) return;

        int onlinePlayers = 0;
        for (ServerWorld world : server.getWorlds()) {
            onlinePlayers += world.getPlayers().size();
        }

        if (!ignoreMinPlayers && onlinePlayers < MIN_PLAYERS_FOR_VOTING) {
            // Not enough players to complete a vote yet, reset timer and wait
            this.votingTicksRemaining = VOTING_DURATION_TICKS;
            Wathe.LOGGER.info("Not enough players ({}/{}) for voting result, resetting timer",
                onlinePlayers, MIN_PLAYERS_FOR_VOTING);
            this.sync();
            return;
        }

        int optionCount = votingStage == VotingStage.MODE ? availableModes.size() : availableMaps.size();
        int selectedIndex = selectOptionWeighted(optionCount);
        if (votingStage == VotingStage.MODE) {
            if (selectedIndex < 0 || selectedIndex >= availableModes.size()) {
                reset();
                return;
            }
            Identifier selectedGameModeId = availableModes.get(selectedIndex).gameModeId();
            if (RANDOM_MODE_OPTION_ID.equals(selectedGameModeId)) {
                selectedGameModeId = selectRandomModeFromCurrentChoices();
                if (selectedGameModeId == null) {
                    reset();
                    return;
                }
            }
            this.selectedModeIndex = indexOfMode(selectedGameModeId);
            int playerCount = 0;
            for (ServerWorld world : server.getWorlds()) {
                playerCount += world.getPlayers().size();
            }
            startMapVotingForMode(selectedGameModeId, playerCount);
            return;
        }

        if (selectedIndex < 0 || selectedIndex >= availableMaps.size()) {
            reset();
            return;
        }
        VotingMapEntry selectedEntry = availableMaps.get(selectedIndex);
        if (RANDOM_MAP_OPTION_ID.equals(selectedEntry.mapId())) {
            selectedIndex = selectRandomMapIndexForCurrentChoices(selectedEntry.gameModeId());
            if (selectedIndex < 0 || selectedIndex >= availableMaps.size()) {
                reset();
                return;
            }
        }
        this.selectedMapIndex = selectedIndex;
        this.roulettePhase = true;
        this.rouletteTicksRemaining = ROULETTE_DURATION_TICKS;

        Wathe.LOGGER.info("Voting ended, selected map index {} ({})",
            selectedMapIndex,
            selectedMapIndex >= 0 && selectedMapIndex < availableMaps.size()
                ? availableMaps.get(selectedMapIndex).displayName()
                : "unknown");
        this.sync();
    }

    /**
     * 加权随机选择：0票=权重1，有票按票数
     */
    private int selectOptionWeighted(int optionCount) {
        if (optionCount <= 0) return -1;

        Random random = new Random();
        int totalWeight = 0;
        int[] weights = new int[optionCount];

        // 检查是否有任何人投票
        boolean hasAnyVotes = false;
        for (int c : voteCounts) {
            if (c > 0) { hasAnyVotes = true; break; }
        }

        if (!hasAnyVotes) {
            int randomOptionIndex = getRandomOptionIndexForCurrentStage();
            if (randomOptionIndex >= 0) {
                return randomOptionIndex;
            }
        }

        for (int i = 0; i < optionCount; i++) {
            // 无人投票：所有选项等概率；有人投票：只有获得票数的选项有概率
            weights[i] = hasAnyVotes ? voteCounts[i] : 1;
            totalWeight += weights[i];
        }

        if (totalWeight <= 0) {
            return 0;
        }

        int roll = random.nextInt(totalWeight);
        int cumulative = 0;
        for (int i = 0; i < weights.length; i++) {
            cumulative += weights[i];
            if (roll < cumulative) {
                return i;
            }
        }

        return 0; // fallback
    }

    private int getRandomOptionIndexForCurrentStage() {
        if (votingStage == VotingStage.MODE) {
            return indexOfMode(RANDOM_MODE_OPTION_ID);
        }

        for (int i = 0; i < availableMaps.size(); i++) {
            if (RANDOM_MAP_OPTION_ID.equals(availableMaps.get(i).mapId())) {
                return i;
            }
        }
        return -1;
    }

    @Nullable
    private Identifier selectRandomModeFromCurrentChoices() {
        List<Identifier> candidates = new ArrayList<>();
        for (VotingModeEntry entry : availableModes) {
            if (!RANDOM_MODE_OPTION_ID.equals(entry.gameModeId())) {
                candidates.add(entry.gameModeId());
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(new Random().nextInt(candidates.size()));
    }

    private int selectRandomMapIndexForCurrentChoices(Identifier gameModeId) {
        List<Integer> candidates = getRandomizableMapIndices(gameModeId);
        if (candidates.isEmpty()) {
            return -1;
        }
        return candidates.get(new Random().nextInt(candidates.size()));
    }

    private List<Integer> getRandomizableMapIndices(Identifier gameModeId) {
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < availableMaps.size(); i++) {
            VotingMapEntry entry = availableMaps.get(i);
            if (RANDOM_MAP_OPTION_ID.equals(entry.mapId())) {
                continue;
            }
            if (!entry.gameModeId().equals(gameModeId)) {
                continue;
            }
            if (WatheGameModes.MURDER_ID.equals(gameModeId) && MURDER_DEFAULT_MAP_ID.equals(entry.mapId())) {
                continue;
            }
            candidates.add(i);
        }
        return candidates;
    }

    /**
     * 轮盘结束，执行传送
     */
    private void finishSelection() {
        if (server == null) return;

        if (selectedMapIndex < 0 || selectedMapIndex >= availableMaps.size()) {
            Wathe.LOGGER.warn("Invalid selected map index {}, aborting", selectedMapIndex);
            reset();
            return;
        }

        Identifier targetDimensionId = availableMaps.get(selectedMapIndex).dimensionId();
        Identifier targetGameModeId = availableMaps.get(selectedMapIndex).gameModeId();
        this.lastSelectedGameMode = targetGameModeId;
        this.lastSelectedDimension = targetDimensionId;

        // Reset voting state before teleport
        this.votingActive = false;
        this.sync();

        // Execute teleport from overworld (or any world with players)
        ServerWorld overworld = server.getOverworld();
        GameFunctions.finalizeVoting(overworld, targetGameModeId, targetDimensionId);
    }

    /**
     * 清空所有状态
     */
    public void reset() {
        this.votingActive = false;
        this.votingStage = VotingStage.MODE;
        this.votingTicksRemaining = 0;
        this.availableModes.clear();
        this.unavailableModes.clear();
        this.availableMaps.clear();
        this.unavailableMaps.clear();
        this.voteCounts = new int[0];
        this.playerVotes.clear();
        this.selectedModeIndex = -1;
        this.selectedMapIndex = -1;
        this.roulettePhase = false;
        this.rouletteTicksRemaining = 0;
        this.sync();
    }

    /**
     * ServerTickingComponent: called every tick
     */
    @Override
    public void serverTick() {
        if (!votingActive) return;

        if (roulettePhase) {
            if (--rouletteTicksRemaining <= 0) {
                finishSelection();
            }
            return;
        }

        if (--votingTicksRemaining <= 0) {
            endVoting();
        }

        // Sync every second for countdown
        if (votingTicksRemaining % 20 == 0) {
            this.sync();
        }
    }

    /**
     * 新玩家加入时检查：如果投票活跃且人数达标，重新启动倒计时
     */
    public void onPlayerJoin() {
        if (server == null) return;
        if (votingActive && !roulettePhase) {
            int onlinePlayers = 0;
            for (ServerWorld world : server.getWorlds()) {
                onlinePlayers += world.getPlayers().size();
            }
            if (onlinePlayers >= MIN_PLAYERS_FOR_VOTING && votingTicksRemaining <= 0) {
                votingTicksRemaining = VOTING_DURATION_TICKS;
                this.sync();
            }
        }
    }



    // === NBT ===

    @Override
    public void readFromNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        this.votingActive = tag.getBoolean("VotingActive");
        if (tag.contains("VotingStage")) {
            this.votingStage = VotingStage.valueOf(tag.getString("VotingStage"));
        } else {
            this.votingStage = VotingStage.MAP;
        }
        this.votingTicksRemaining = tag.getInt("VotingTicksRemaining");
        this.selectedModeIndex = tag.getInt("SelectedModeIndex");
        this.selectedMapIndex = tag.getInt("SelectedMapIndex");
        this.roulettePhase = tag.getBoolean("RoulettePhase");
        this.rouletteTicksRemaining = tag.getInt("RouletteTicksRemaining");

        if (tag.contains("LastSelectedGameMode")) {
            this.lastSelectedGameMode = Identifier.tryParse(tag.getString("LastSelectedGameMode"));
        } else {
            this.lastSelectedGameMode = null;
        }

        // Last selected dimension (persisted)
        if (tag.contains("LastSelectedDimension")) {
            this.lastSelectedDimension = Identifier.tryParse(tag.getString("LastSelectedDimension"));
        } else {
            this.lastSelectedDimension = null;
        }

        this.availableModes.clear();
        if (tag.contains("AvailableModes")) {
            NbtList modeList = tag.getList("AvailableModes", NbtElement.COMPOUND_TYPE);
            for (NbtElement e : modeList) {
                NbtCompound modeNbt = (NbtCompound) e;
                this.availableModes.add(new VotingModeEntry(
                    Identifier.tryParse(modeNbt.getString("GameModeId")),
                    modeNbt.getString("DisplayName"),
                    modeNbt.getString("Description"),
                    modeNbt.getInt("MinPlayers"),
                    modeNbt.getBoolean("ShowPlayerLimit")
                ));
            }
        }

        this.unavailableModes.clear();
        if (tag.contains("UnavailableModes")) {
            NbtList modeList = tag.getList("UnavailableModes", NbtElement.COMPOUND_TYPE);
            for (NbtElement e : modeList) {
                NbtCompound modeNbt = (NbtCompound) e;
                this.unavailableModes.add(new UnavailableModeEntry(
                    Identifier.tryParse(modeNbt.getString("GameModeId")),
                    modeNbt.getString("DisplayName"),
                    modeNbt.getString("Reason")
                ));
            }
        }

        // Available maps
        this.availableMaps.clear();
        if (tag.contains("AvailableMaps")) {
            NbtList mapsList = tag.getList("AvailableMaps", NbtElement.COMPOUND_TYPE);
            for (NbtElement e : mapsList) {
                NbtCompound mapNbt = (NbtCompound) e;
                Identifier dimensionId = Identifier.tryParse(mapNbt.getString("DimensionId"));
                Identifier mapId = mapNbt.contains("MapId")
                    ? Identifier.tryParse(mapNbt.getString("MapId"))
                    : MapRegistryEntry.defaultReferenceIdForDimension(dimensionId);
                Identifier gameModeId = mapNbt.contains("GameModeId")
                    ? Identifier.tryParse(mapNbt.getString("GameModeId"))
                    : (this.lastSelectedGameMode != null ? this.lastSelectedGameMode : WatheGameModes.MURDER_ID);
                this.availableMaps.add(new VotingMapEntry(
                    mapId,
                    dimensionId,
                    gameModeId,
                    mapNbt.getString("DisplayName"),
                    mapNbt.getString("Description"),
                    mapNbt.getInt("MinPlayers"),
                    mapNbt.getInt("MaxPlayers")
                ));
            }
        }

        // Unavailable maps
        this.unavailableMaps.clear();
        if (tag.contains("UnavailableMaps")) {
            NbtList unavList = tag.getList("UnavailableMaps", NbtElement.COMPOUND_TYPE);
            for (NbtElement e : unavList) {
                NbtCompound mapNbt = (NbtCompound) e;
                this.unavailableMaps.add(new UnavailableMapEntry(
                    Identifier.tryParse(mapNbt.getString("DimensionId")),
                    mapNbt.getString("DisplayName"),
                    mapNbt.getString("Reason")
                ));
            }
        }

        // Vote counts
        if (tag.contains("VoteCounts")) {
            this.voteCounts = tag.getIntArray("VoteCounts");
        } else {
            this.voteCounts = new int[this.availableMaps.size()];
        }

        // Player votes
        this.playerVotes.clear();
        if (tag.contains("PlayerVotes")) {
            NbtList votesList = tag.getList("PlayerVotes", NbtElement.COMPOUND_TYPE);
            for (NbtElement e : votesList) {
                NbtCompound voteNbt = (NbtCompound) e;
                UUID playerId = voteNbt.getUuid("PlayerId");
                int mapIndex = voteNbt.getInt("MapIndex");
                this.playerVotes.put(playerId, mapIndex);
            }
        }
    }

    @Override
    public void writeToNbt(@NotNull NbtCompound tag, RegistryWrapper.WrapperLookup registryLookup) {
        tag.putBoolean("VotingActive", votingActive);
        tag.putString("VotingStage", votingStage.name());
        tag.putInt("VotingTicksRemaining", votingTicksRemaining);
        tag.putInt("SelectedModeIndex", selectedModeIndex);
        tag.putInt("SelectedMapIndex", selectedMapIndex);
        tag.putBoolean("RoulettePhase", roulettePhase);
        tag.putInt("RouletteTicksRemaining", rouletteTicksRemaining);

        if (lastSelectedGameMode != null) {
            tag.putString("LastSelectedGameMode", lastSelectedGameMode.toString());
        }

        // Last selected dimension
        if (lastSelectedDimension != null) {
            tag.putString("LastSelectedDimension", lastSelectedDimension.toString());
        }

        NbtList modeList = new NbtList();
        for (VotingModeEntry entry : availableModes) {
            NbtCompound modeNbt = new NbtCompound();
            modeNbt.putString("GameModeId", entry.gameModeId().toString());
            modeNbt.putString("DisplayName", entry.displayName());
            modeNbt.putString("Description", entry.description());
            modeNbt.putInt("MinPlayers", entry.minPlayers());
            modeNbt.putBoolean("ShowPlayerLimit", entry.showPlayerLimit());
            modeList.add(modeNbt);
        }
        tag.put("AvailableModes", modeList);

        NbtList unavailableModeList = new NbtList();
        for (UnavailableModeEntry entry : unavailableModes) {
            NbtCompound modeNbt = new NbtCompound();
            modeNbt.putString("GameModeId", entry.gameModeId().toString());
            modeNbt.putString("DisplayName", entry.displayName());
            modeNbt.putString("Reason", entry.reason());
            unavailableModeList.add(modeNbt);
        }
        tag.put("UnavailableModes", unavailableModeList);

        // Available maps
        NbtList mapsList = new NbtList();
        for (VotingMapEntry entry : availableMaps) {
            NbtCompound mapNbt = new NbtCompound();
            mapNbt.putString("MapId", entry.mapId().toString());
            mapNbt.putString("DimensionId", entry.dimensionId().toString());
            mapNbt.putString("GameModeId", entry.gameModeId().toString());
            mapNbt.putString("DisplayName", entry.displayName());
            mapNbt.putString("Description", entry.description());
            mapNbt.putInt("MinPlayers", entry.minPlayers());
            mapNbt.putInt("MaxPlayers", entry.maxPlayers());
            mapsList.add(mapNbt);
        }
        tag.put("AvailableMaps", mapsList);

        // Unavailable maps
        NbtList unavList = new NbtList();
        for (UnavailableMapEntry entry : unavailableMaps) {
            NbtCompound mapNbt = new NbtCompound();
            mapNbt.putString("DimensionId", entry.dimensionId().toString());
            mapNbt.putString("DisplayName", entry.displayName());
            mapNbt.putString("Reason", entry.reason());
            unavList.add(mapNbt);
        }
        tag.put("UnavailableMaps", unavList);

        // Vote counts
        tag.putIntArray("VoteCounts", voteCounts);

        // Player votes
        NbtList votesList = new NbtList();
        for (Map.Entry<UUID, Integer> entry : playerVotes.entrySet()) {
            NbtCompound voteNbt = new NbtCompound();
            voteNbt.putUuid("PlayerId", entry.getKey());
            voteNbt.putInt("MapIndex", entry.getValue());
            votesList.add(voteNbt);
        }
        tag.put("PlayerVotes", votesList);
    }
}
