package dev.venator.seasonsrework.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client config: config/seasonsrework-client.toml. Visual only. */
public final class ClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue GRASS;
    public static final ModConfigSpec.BooleanValue FOLIAGE;
    public static final ModConfigSpec.BooleanValue SMOOTH;
    public static final ModConfigSpec.IntValue UPDATES_PER_STEP;
    public static final ModConfigSpec.BooleanValue SPRUCE;
    public static final ModConfigSpec.BooleanValue BIRCH;
    public static final ModConfigSpec.BooleanValue BIRCH_PATCHES;
    public static final ModConfigSpec.BooleanValue MANGROVE;
    public static final ModConfigSpec.BooleanValue SEASONAL_TEXTURES;
    public static final ModConfigSpec.BooleanValue DH_REFRESH;
    public static final ModConfigSpec.BooleanValue DH_SNOW;
    public static final ModConfigSpec.BooleanValue CROP_LOOKS;
    public static final ModConfigSpec.EnumValue<CropColors> CROP_COLORS;
    public static final ModConfigSpec.BooleanValue SNOW_ANIMATION;
    public static final ModConfigSpec.IntValue SNOW_ANIMATION_MS;
    public static final ModConfigSpec.IntValue SNOW_ANIMATION_DISTANCE;
    public static final ModConfigSpec.EnumValue<SnowLook> SNOW_LOOK;
    public static final ModConfigSpec.IntValue REDRAW_SECONDS;

    /** What makes snowy textures and LOD snow fade in (4 steps). */
    public enum SnowLook { GROUND, CALENDAR }

    /** Colors of crops that don't grow. ECLIPTIC: Ecliptic Seasons' winter beige and autumn dry orange. */
    public enum CropColors { DEFAULT, ECLIPTIC }

    static {
        BUILDER.comment(
                "Seasonal colors (Ecliptic Seasons' tables). The year is split into 24 color steps, 2 per season part.",
                "Biome color profiles can be forced with biome tags seasonsrework:color/<stable|slightly|monsoonal|seasonal|seasonal/hot|seasonal/cold>",
                "(Ecliptic's eclipticseasons:color/... tags work too). Biomes in seasonsrework:no_seasons keep their colors.")
                .push("colors");
        GRASS = BUILDER.comment("Seasonal grass colors.").define("grass", true);
        FOLIAGE = BUILDER.comment("Seasonal colors of leaves that take the biome's color (oak, jungle, acacia, dark oak...).").define("foliage", true);
        SMOOTH = BUILDER.comment("Blend each color step into the next over its days. false = colors jump once per step.")
                .define("smooth_transition", true);
        UPDATES_PER_STEP = BUILDER.comment("How many times per color step the blend is updated and nearby chunks re-colored.",
                        "A step is 1/24 of the year: 7 days with 42-day seasons, so 7 = once per in-game day.",
                        "Each update re-colors visible chunks (and Distant Horizons LODs), so higher = smoother but more rebuilds.")
                .defineInRange("updates_per_step", 7, 1, 48);
        BUILDER.pop().push("leaves");
        SPRUCE = BUILDER.comment("Seasonal spruce leaf colors.").define("spruce", true);
        BIRCH = BUILDER.comment("Seasonal birch leaf colors.").define("birch", true);
        BIRCH_PATCHES = BUILDER.comment("Autumn birches vary from yellow to orange depending on position.").define("birch_autumn_patches", true);
        MANGROVE = BUILDER.comment("Seasonal mangrove leaf colors.").define("mangrove", true);
        BUILDER.pop().push("textures");
        SEASONAL_TEXTURES = BUILDER.comment("Seasonal block textures from resource packs in Ecliptic Seasons' format",
                        "(assets/<namespace>/eclipticseasons/season_textures/*.json), e.g. Seasonal Plants and Seasonal Trees.")
                .define("seasonal_textures", true);
        BUILDER.pop().push("crops");
        CROP_LOOKS = BUILDER.comment("Crops that don't grow show it, in 4 steps: a hint while they grow slowly near a limit,",
                        "then stronger the further past it (dormant from cold, wilted from heat).")
                .define("crop_looks", true);
        CROP_COLORS = BUILDER.comment("DEFAULT: greyish blue-green (cold), dark yellow (heat).",
                        "ECLIPTIC: Ecliptic Seasons' colors, winter beige (cold) and dry orange (heat). Needs a resource reload (F3+T).")
                .defineEnum("crop_colors", CropColors.DEFAULT);
        BUILDER.pop().push("snow");
        SNOW_ANIMATION = BUILDER.comment("Snow that falls or melts near you slides up out of the ground / sinks into it instead of popping.")
                .define("animation", true);
        SNOW_ANIMATION_MS = BUILDER.comment("How long the slide takes (milliseconds).")
                .defineInRange("animation_ms", 1000, 50, 5000);
        SNOW_ANIMATION_DISTANCE = BUILDER.comment("Only snow this close to you (blocks) is animated; further away it just changes.")
                .defineInRange("animation_distance", 48, 4, 256);
        SNOW_LOOK = BUILDER.comment("Snowy textures (resource packs) and Distant Horizons LOD snow fade in over 4 steps; the steps between",
                        "draw the snowy texture over the normal one from the top down (top faces first). What sets the step:",
                        "GROUND: the snow that's there. Plants follow the snow in them, leaves the snow on them, crops their own",
                        "  snow (it comes with the snowfall), the rest where snow lies; deeper/colder = more steps.",
                        "CALENDAR: the temperature now (season, day, height), like the seasonal colors; not under a roof or near a heater.",
                        "Takes effect as chunks redraw (F3+A to see it at once).")
                .defineEnum("texture_mode", SnowLook.GROUND);
        REDRAW_SECONDS = BUILDER.comment("When snowy textures or colors change, nearby chunks are redrawn nearest first over this many seconds",
                        "instead of all at once (0 = all at once).")
                .defineInRange("redraw_seconds", 5, 0, 60);
        BUILDER.pop().push("distant_horizons");
        DH_REFRESH = BUILDER.comment("Re-color Distant Horizons LODs when the season colors change (nearest first).")
                .define("refresh_lods", true);
        DH_SNOW = BUILDER.comment("Seasonal snow and ice on LODs: snow where real snow would lie (same temperature and first-snow rules),",
                        "frozen open water, and snow/ice saved while it was cold removed once it's warm.")
                .define("snow_and_ice", true);
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ClientConfig() {}
}
