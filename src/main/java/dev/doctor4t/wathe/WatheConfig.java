package dev.doctor4t.wathe;

import eu.midnightdust.lib.config.MidnightConfig;

public class WatheConfig extends MidnightConfig {
    public static final int MIN_CHAT_HISTORY_LIMIT = 100;
    public static final int MAX_CHAT_HISTORY_LIMIT = 5000;

    @Entry
    public static boolean disableScreenShake = false;

    @Entry
    public static SnowModeConfig snowOptLevel = SnowModeConfig.NO_OPTIMIZATION;

    @Entry
    public static InstinctModeConfig instinctMode = InstinctModeConfig.HOLD;

    @Entry
    public static boolean showMatchPlayerCount = true;

    @Entry(isSlider = true, min = 0, max = 100)
    public static int snowflakeChance = 100;

    @Entry(isSlider = true, min = MIN_CHAT_HISTORY_LIMIT, max = MAX_CHAT_HISTORY_LIMIT)
    public static int chatHistoryLimit = 500;

    public enum InstinctModeConfig {
        HOLD,
        TOGGLE,
    }

    public enum SnowModeConfig {
        NO_OPTIMIZATION,  // Standard behavior: checking if the particle hit the block.
        BOX_COLLIDER, // replaces the calculation against the terrain to the calculation against a box that approximates the train
        TURN_OFF, // Client side '/wathe:setVisual snow false'
    }
}
