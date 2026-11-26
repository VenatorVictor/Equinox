package dev.venatorvictor.equinox.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client config: config/equinox-client.toml. Visual only. */
public final class ClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue GRASS;
    public static final ModConfigSpec.BooleanValue FOLIAGE;
    public static final ModConfigSpec.BooleanValue SMOOTH;
    public static final ModConfigSpec.IntValue UPDATES_PER_STEP;
    public static final ModConfigSpec.DoubleValue CLIMATE_COLOR_BLEND;
    public static final ModConfigSpec.BooleanValue CLIMATE_COLOR_STRENGTH;
    public static final ModConfigSpec.BooleanValue SPRUCE;
    public static final ModConfigSpec.BooleanValue BIRCH;
    public static final ModConfigSpec.BooleanValue BIRCH_PATCHES;
    public static final ModConfigSpec.BooleanValue MANGROVE;
    public static final ModConfigSpec.EnumValue<TreeColors> TREE_COLORS;
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
    public static final ModConfigSpec.EnumValue<HudPosition> HUD_POSITION;
    public static final ModConfigSpec.BooleanValue STORM_CLOUDS;
    public static final ModConfigSpec.IntValue CLOUD_DISTANCE;
    public static final ModConfigSpec.IntValue CLOUD_CUBE;
    public static final ModConfigSpec.EnumValue<CloudDetail> CLOUD_DETAIL;
    public static final ModConfigSpec.EnumValue<CloudLighting> CLOUD_LIGHTING;
    public static final ModConfigSpec.BooleanValue CLOUD_SHADOWS;
    public static final ModConfigSpec.BooleanValue DISTANT_RAIN;
    public static final ModConfigSpec.DoubleValue CLOUD_AMOUNT;
    public static final ModConfigSpec.IntValue CLOUD_UPDATES;
    public static final ModConfigSpec.DoubleValue CLOUD_ANIMATION;
    public static final ModConfigSpec.IntValue CLOUD_FOG;
    public static final ModConfigSpec.BooleanValue DH_CLOUDS;

    /**
     * Tree colors: REPLACE = each tree's own colors and timing, mixed over the original; FILTER = the same, keeping
     * the original's brightness; OFF = the earlier colors (biome tables).
     */
    public enum TreeColors { REPLACE, FILTER, OFF }

    /** What makes snowy textures and LOD snow fade in (4 steps). */
    public enum SnowLook { GROUND, CALENDAR }

    /** Where the held calendar's date box sits on the screen. */
    public enum HudPosition { TOP_LEFT, TOP_CENTER, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, ABOVE_HOTBAR }

    /** How far the small near cubes reach: LOW 128 blocks, MEDIUM 256, HIGH 384 (then cubes twice as big to 3 times that). */
    public enum CloudDetail { LOW, MEDIUM, HIGH }

    /** FULL: sunlight through the cloud (thick parts shade the rest); SIMPLE: lighter from above only. */
    public enum CloudLighting { FULL, SIMPLE }

    /** Colors of crops that don't grow. BEIGE_ORANGE: winter beige and autumn dry orange. */
    public enum CropColors { DEFAULT, BEIGE_ORANGE }

    static {
        BUILDER.comment(
                "Seasonal colors, by color week (48 a year, by date).",
                "Biome color profiles can be forced with biome tags equinox:color/<stable|slightly|monsoonal|seasonal|seasonal/hot|seasonal/cold>",
                "Biomes in equinox:no_seasons keep their colors.")
                .push("colors");
        GRASS = BUILDER.comment("Seasonal grass colors.").define("grass", true);
        FOLIAGE = BUILDER.comment("Seasonal colors of leaves that take the biome's color (oak, jungle, acacia, dark oak...).").define("foliage", true);
        CLIMATE_COLOR_BLEND = BUILDER.comment("Grass and leaf colors blended like the climate: each place takes the colors of the biomes around",
                        "it (the server's blend_radius, nearer ones count more), so biome borders fade over many blocks instead of",
                        "Minecraft's Biome Blend (a few blocks). Worked out per chunk, so it costs the same at any radius. 1 = fully,",
                        "0 = only the block's own biome (as before; Minecraft's Biome Blend still applies on top).")
                .defineInRange("climate_color_blend", 1.0, 0.0, 1.0);
        CLIMATE_COLOR_STRENGTH = BUILDER.comment("Seasonal colors as strong as the seasons are cold: a place with a mild winter (the climate zone, warm",
                        "neighbours) changes less, savannas a little, jungles not at all. false = every biome's table at full strength.")
                .define("climate_color_strength", true);
        SMOOTH = BUILDER.comment("Blend each week's color into the next over its days. false = colors jump once per week.")
                .define("smooth_transition", true);
        UPDATES_PER_STEP = BUILDER.comment("How many times per color week the color blend is updated and nearby chunks re-colored.",
                        "A color week is 1/48 of the year: 4 days with 48-day seasons, so 4 = once per in-game day.",
                        "Each update re-colors visible chunks (and Distant Horizons LODs), so higher = smoother but more rebuilds.")
                .defineInRange("updates_per_week", 4, 1, 48);
        BUILDER.pop().push("leaves");
        TREE_COLORS = BUILDER.comment("Tree colors through the year. Each tree has its own timing and autumn color (oak brown-gold, dark oak",
                        "copper, birch yellow); biomes whose own foliage color is an autumn one use it in autumn and are green before.",
                        "REPLACE: mixed over the original color. FILTER: the same colors, keeping the original's brightness.",
                        "OFF: the earlier colors (oak and other biome-colored leaves follow the biome tables).")
                .defineEnum("tree_colors", TreeColors.FILTER);
        SPRUCE = BUILDER.comment("Seasonal spruce leaf colors.").define("spruce", true);
        BIRCH = BUILDER.comment("Seasonal birch leaf colors.").define("birch", true);
        BIRCH_PATCHES = BUILDER.comment("Autumn birches vary from yellow to orange depending on position.").define("birch_autumn_patches", true);
        MANGROVE = BUILDER.comment("Seasonal mangrove leaf colors.").define("mangrove", true);
        BUILDER.pop().push("textures");
        SEASONAL_TEXTURES = BUILDER.comment("Seasonal block textures from resource packs in the seasonal texture format",
                        "(assets/<namespace>/season_textures/*.json), e.g. the built-in Seasonal Plants pack.")
                .define("seasonal_textures", true);
        BUILDER.pop().push("crops");
        CROP_LOOKS = BUILDER.comment("Crops that don't grow show it, in 4 steps: a hint while they grow slowly near a limit,",
                        "then stronger the further past it (dormant from cold, wilted from heat).")
                .define("crop_looks", true);
        CROP_COLORS = BUILDER.comment("DEFAULT: greyish blue-green (cold), dark yellow (heat).",
                        "BEIGE_ORANGE: winter beige (cold) and dry orange (heat). Needs a resource reload (F3+T).")
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
        BUILDER.pop().push("clouds");
        STORM_CLOUDS = BUILDER.comment("Equinox clouds. With weather per region: 3D block clouds drawn from the storms, so clouds are where the rain is. Fair",
                        "days have small and big heaps that form and dissolve; a coming front brings high streaks, then middle bands,",
                        "then a closing grey deck; thunderstorms gather heaps that climb into a tower (it reaches its top as the rain",
                        "starts) and spread an anvil. Lit by the sun through the cloud. Made on the graphics card (needs OpenGL 4.3;",
                        "without it vanilla clouds stay). Follows the game's Clouds option (off: none, fast: one pass). false: vanilla clouds.")
                .define("storm_clouds", true);
        CLOUD_DISTANCE = BUILDER.comment("How far the clouds reach, in blocks (they fade out toward it).")
                .defineInRange("cloud_distance", 768, 384, 2304);
        CLOUD_CUBE = BUILDER.comment("Size of the cubes near you, in blocks: 4 (finer) or 8 (lighter). Farther away they are twice and four",
                        "times as big (at most 16).")
                .defineInRange("cube_size", 4, 4, 8);
        CLOUD_DETAIL = BUILDER.comment("How far the near cubes reach: LOW 128 blocks, MEDIUM 256, HIGH 384; the next size reaches three times as far.")
                .defineEnum("detail", CloudDetail.MEDIUM);
        CLOUD_LIGHTING = BUILDER.comment("FULL: sunlight goes through the cloud, so thick parts shade the rest and storm bases are dark.",
                        "SIMPLE: lighter from above only (cheaper).")
                .defineEnum("lighting", CloudLighting.FULL);
        CLOUD_SHADOWS = BUILDER.comment("Cloud shadows on the ground. Not with a shader pack (it does its own) or Fabulous graphics.")
                .define("shadows", true);
        DISTANT_RAIN = BUILDER.comment("Grey veils of rain under far raining clouds (near you the game's own rain falls).")
                .define("distant_rain", true);
        CLOUD_AMOUNT = BUILDER.comment("How many fair-weather clouds (heaps and high streaks): 1 = normal, 0 = none, 2 = twice as many.",
                        "Storm clouds always come with their storms.")
                .defineInRange("fair_amount", 1.0, 0.0, 2.0);
        CLOUD_UPDATES = BUILDER.comment("Cloud pieces remade per frame as the sky changes. Higher = clouds follow the weather sooner, more work",
                        "for the graphics card.")
                .defineInRange("updates_per_frame", 2, 1, 8);
        CLOUD_ANIMATION = BUILDER.comment("Cloud cubes grow out of their middle when they appear and shrink away when they go, over this many",
                        "seconds (0 = they just appear). Not with a shader pack (its own cloud shader draws them).")
                .defineInRange("growing_seconds", 5.0, 0.0, 8.0);
        CLOUD_FOG = BUILDER.comment("Inside a cloud, fog closes in to the cloud's colour: how far you still see deep inside, in blocks",
                        "(0 = no fog, the cloud is hollow inside).")
                .defineInRange("inside_fog_blocks", 16, 0, 128);
        DH_CLOUDS = BUILDER.comment("With Distant Horizons: the far clouds are drawn by it, so they sit behind far terrain (the near ones",
                        "are drawn the normal way). false: all clouds the normal way (far ones may show over far mountains).")
                .define("distant_horizons_far_clouds", true);
        BUILDER.pop().push("calendar");
        HUD_POSITION = BUILDER.comment("Where the date box shows while you hold a calendar: TOP_LEFT, TOP_CENTER, TOP_RIGHT, BOTTOM_LEFT,",
                        "BOTTOM_RIGHT, or ABOVE_HOTBAR (centered just above the health and food bars).")
                .defineEnum("hud_position", HudPosition.TOP_LEFT);
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ClientConfig() {}
}
