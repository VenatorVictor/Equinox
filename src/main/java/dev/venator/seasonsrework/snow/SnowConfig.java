package dev.venator.seasonsrework.snow;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Snow cover settings, server side: config/seasonsrework-common.toml (common: the leaf litter pack is picked at startup). */
public final class SnowConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue FORCE_SNOWFALL;
    public static final ModConfigSpec.IntValue SNOWFALL_TICKS;
    public static final ModConfigSpec.BooleanValue SNOW_COVER;
    public static final ModConfigSpec.IntValue BUDGET_MICROS;
    public static final ModConfigSpec.IntValue SPARE_MICROS;
    public static final ModConfigSpec.IntValue BIOME_CELL;
    public static final ModConfigSpec.BooleanValue SNOWY_LEAF_LITTER;
    public static final ModConfigSpec.BooleanValue SNOWY_PLANTS;
    public static final ModConfigSpec.BooleanValue SNOW_DEPTH;
    public static final ModConfigSpec.BooleanValue RESTORE_PLANTS;

    static {
        BUILDER.comment(
                "Snowfall: snow lies where it has stayed cold since it last snowed there (rain or a thaw takes it away),",
                "first in round patches, everywhere once it snows well below freezing, deeper when colder.",
                "How many days a year each biome is cold enough: data map seasonsrework:snow (real days of 365).")
                .push("snowfall");
        FORCE_SNOWFALL = BUILDER.comment("Where a player is, it's cold enough to snow and no snow lies, a snowfall comes within a day",
                        "(at most one a day; weather is global, so it rains where it's warm).")
                .define("force_snowfall", true);
        SNOWFALL_TICKS = BUILDER.comment("How long that snowfall lasts, in ticks (6000 = a quarter of an in-game day, 5 real minutes).")
                .defineInRange("snowfall_ticks", 6000, 20, 1_000_000);
        BUILDER.pop().comment(
                "Snow cover: puts snow and ice on loaded chunks following the snowfall rule above, and melts them, including",
                "Snow! Real Magic's snowy plants. Chunks are redone when the snow or the temperature changes, or when a",
                "chunk loads that was done for an older state.")
                .push("snow_cover");
        SNOW_COVER = BUILDER.comment("Turn the snow cover on.").define("enabled", true);
        BUDGET_MICROS = BUILDER.comment("Most time per server tick it may use, in microseconds (2000 = 2 ms of the 50 ms tick).",
                        "Lower = less lag, the snow just spreads over loaded chunks more slowly.")
                .defineInRange("budget_micros", 2000, 100, 20000);
        SPARE_MICROS = BUILDER.comment("When the server is ahead of schedule, it may use up to this much more of the time until the next tick,",
                        "in microseconds (10000 = 10 ms), always leaving 10 ms for the rest of the tick. Snow fills in faster",
                        "when the server isn't busy, without lag when it is. 0 = only budget_micros.")
                .defineInRange("spare_time_micros", 10000, 0, 40000);
        BIOME_CELL = BUILDER.comment("Biome edges of snow and ice: 1 = vanilla's irregular edges, checked per block (slowest);",
                        "2 or 4 = one check per 2x2 or 4x4 blocks (faster, edges show small squares).")
                .defineInRange("biome_cell", 1, 1, 4);
        SNOWY_LEAF_LITTER = BUILDER.comment("With Snow! Real Magic: snow covers leaf litter (Vanilla Backport / vanilla) and gives it back when it melts.",
                        "false = leaf litter stays bare inside the snow. Applies to Snow! Real Magic's own snowfall too. Needs a restart.")
                .worldRestart()
                .define("snowy_leaf_litter", true);
        BUILDER.pop().comment(
                "Snowy plants: snow lies on grass, ferns and flowers (the plant stays inside and comes back when the snow melts).",
                "Off while Snow! Real Magic is installed (it does the same).")
                .push("snowy_plants");
        SNOWY_PLANTS = BUILDER.comment("Snow covers plants (snow cover and snowfall).").define("enabled", true);
        SNOW_DEPTH = BUILDER.comment("The snow cover builds deeper snow the colder the last snowfall was, up to a limit per biome",
                        "(more snowy days a year, deeper: 1 to 6 layers). false = always 1 layer (vanilla).")
                .define("depth_by_climate", false);
        RESTORE_PLANTS = BUILDER.comment("Before removing this mod: true turns every snowy plant in chunks that load back into its plant",
                        "(and stops new ones), so no plants are lost. Load the places you care about, then remove the mod.")
                .define("restore_plants", false);
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private SnowConfig() {}
}
