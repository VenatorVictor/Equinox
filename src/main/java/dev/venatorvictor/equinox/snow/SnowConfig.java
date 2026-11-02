package dev.venatorvictor.equinox.snow;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Snow cover settings, server side: config/equinox-common.toml (common: the leaf litter pack is picked at startup). */
public final class SnowConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue SNOW_LOCKS;
    public static final ModConfigSpec.BooleanValue FORCE_SNOWFALL;
    public static final ModConfigSpec.IntValue SNOWFALL_TICKS;
    public static final ModConfigSpec.BooleanValue SNOW_COVER;
    public static final ModConfigSpec.DoubleValue COLUMNS_PER_TICK;
    public static final ModConfigSpec.IntValue BUDGET_MICROS;
    public static final ModConfigSpec.IntValue SPARE_MICROS;
    public static final ModConfigSpec.IntValue BIOME_CELL;
    public static final ModConfigSpec.BooleanValue SNOWY_PLANTS;
    public static final ModConfigSpec.BooleanValue SNOW_DEPTH;
    public static final ModConfigSpec.EnumValue<Storage> STORAGE;

    /** How snow is kept in plants and fences (testing both). */
    public enum Storage { BLOCK_STATE, BLOCK_ENTITY }

    static {
        BUILDER.comment(
                "Snowfall: snow lies where it has stayed cold since it last snowed there (rain or a thaw takes it away),",
                "first in round patches, everywhere once it snows well below freezing, deeper when colder.",
                "How many days a year each biome is cold enough: data map equinox:snow (real days of 365).")
                .push("snowfall");
        SNOW_LOCKS = BUILDER.comment("TEMPORARY (testing): true = biomes in the tag equinox:always_snow (snowy plains, snowy taiga, ice spikes...)",
                        "have snow all year, biomes with 0 snow days or warm without seasons (desert, jungle, savanna, swamp) never",
                        "get any, and climate blending leaves both alone (only the biomes in between change near them).",
                        "false = every biome follows its snow days (data map) and blending changes all of them.")
                .define("snow_locks", true);
        FORCE_SNOWFALL = BUILDER.comment("Where a player is, it's cold enough to snow and no snow lies, a snowfall comes within a day",
                        "(at most one a day; weather is global, so it rains where it's warm).")
                .define("force_snowfall", true);
        SNOWFALL_TICKS = BUILDER.comment("How long that snowfall lasts, in ticks (6000 = a quarter of an in-game day, 5 real minutes).")
                .defineInRange("snowfall_ticks", 6000, 20, 1_000_000);
        BUILDER.pop().comment(
                "Snow cover: puts snow and ice on the ground following the snowfall rule above, and melts them, including",
                "a snow mod's snowy plants. Near players it changes bit by bit: snow builds while it snows and melts",
                "a layer at a time after; snow you break stays gone until the next snowfall. A chunk that loads catches up",
                "at once (covered only if it snowed there since the chunk was last saved).")
                .push("snow_cover");
        SNOW_COVER = BUILDER.comment("Turn the snow cover on.").define("enabled", true);
        COLUMNS_PER_TICK = BUILDER.comment("Near players: random columns per chunk each tick that snow builds on (while snowing) or melts on",
                        "(one layer each). 0.0625 (vanilla snowfall's own rate, which adds on top, so twice vanilla): a chunk is",
                        "about 45% covered after 1 minute of snowfall, 70% after 2, 95% after 5. Melting uses this rate alone. 0: off.")
                .defineInRange("columns_per_tick", 0.0625, 0.0, 16.0);
        BUDGET_MICROS = BUILDER.comment("Most time per server tick it may use, in microseconds (2000 = 2 ms of the 50 ms tick).",
                        "Lower = less lag, the snow just changes more slowly.")
                .defineInRange("budget_micros", 2000, 100, 20000);
        SPARE_MICROS = BUILDER.comment("When the server is ahead of schedule, it may use up to this much more of the time until the next tick,",
                        "in microseconds (10000 = 10 ms), always leaving 10 ms for the rest of the tick. Snow fills in faster",
                        "when the server isn't busy, without lag when it is. 0 = only budget_micros.")
                .defineInRange("spare_time_micros", 10000, 0, 40000);
        BIOME_CELL = BUILDER.comment("Biome edges of snow and ice: 1 = vanilla's irregular edges, checked per block (slowest);",
                        "2 or 4 = one check per 2x2 or 4x4 blocks (faster, edges show small squares).")
                .defineInRange("biome_cell", 1, 1, 4);
        BUILDER.pop().comment(
                "Snowy plants: snow lies in grass, ferns, flowers and other plants (a block state of the plant: it stays itself),",
                "and one layer around fences and walls. Breaking takes the snow first. Removing the mod just takes the snow away.",
                "Off while a snow mod that does the same is installed.")
                .push("snowy_plants");
        SNOWY_PLANTS = BUILDER.comment("Snow covers plants, fences and walls (snow cover and snowfall).").define("enabled", true);
        STORAGE = BUILDER.comment("TEMPORARY (testing): how new snow is kept. BLOCK_STATE = a state of the plant itself (it stays itself).",
                        "BLOCK_ENTITY = a snow block with the plant kept inside (a block entity that never ticks). Snow of the",
                        "other kind already in the world keeps working and melts as usual.")
                .defineEnum("storage", Storage.BLOCK_STATE);
        SNOW_DEPTH = BUILDER.comment("The snow cover builds deeper snow the colder the last snowfall was, up to a limit per biome",
                        "(more snowy days a year, deeper: 1 to 6 layers). false = always 1 layer (vanilla).")
                .define("depth_by_climate", false);
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private SnowConfig() {}
}
