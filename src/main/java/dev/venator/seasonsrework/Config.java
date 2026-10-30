package dev.venator.seasonsrework;

import dev.venator.seasonsrework.climate.Climate;
import dev.venator.seasonsrework.climate.Zone;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Server config (per world; NeoForge sends it to clients, so both sides count the year the same way). */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    private static final List<String> DEFAULT_CLIMATE_CHANGES = Arrays.stream(Zone.values())
            .filter(Zone::seasonal).map(Zone::defaultEntry).toList();
    // Vanilla temperatures of the biomes WWOO changes (it uses temperature to tint grass and leaves).
    private static final List<String> DEFAULT_BIOME_TEMPERATURES = List.of(
            "minecraft:badlands=2.0", "minecraft:eroded_badlands=2.0", "minecraft:bamboo_jungle=0.95",
            "minecraft:jungle=0.95", "minecraft:beach=0.8", "minecraft:birch_forest=0.6",
            "minecraft:old_growth_birch_forest=0.6", "minecraft:forest=0.7", "minecraft:flower_forest=0.7",
            "minecraft:sunflower_plains=0.8", "minecraft:mangrove_swamp=0.8", "minecraft:meadow=0.5",
            "minecraft:old_growth_pine_taiga=0.3", "minecraft:old_growth_spruce_taiga=0.25", "minecraft:river=0.5",
            "minecraft:frozen_river=0.0", "minecraft:ice_spikes=0.0", "minecraft:grove=-0.2",
            "minecraft:snowy_slopes=-0.3", "minecraft:snowy_taiga=-0.5", "minecraft:snowy_beach=0.05",
            "minecraft:stony_peaks=1.0", "minecraft:windswept_hills=0.2", "minecraft:deep_frozen_ocean=0.05");

    public static final ModConfigSpec.IntValue DAYS_PER_SEASON = BUILDER
            .comment("Days in one season (a year is 4 seasons; 42 days: 168-day year). Each season has an early, mid",
                    "and late part. The season comes from the day count, so changing this moves the season.")
            .defineInRange("days_per_season", 42, 3, 1000);
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DIMENSIONS = BUILDER
            .comment("Dimensions with seasons.")
            .defineListAllowEmpty("dimensions", List.of("minecraft:overworld"), () -> "minecraft:overworld",
                    value -> value instanceof String s && ResourceLocation.tryParse(s) != null);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> CLIMATE_CHANGES = BUILDER
            .comment("Temperature change of each seasonal climate zone (biome tags seasonsrework:climate/<zone>) at the",
                    "middle of spring, summer, autumn and winter, as \"zone=spring,summer,autumn,winter\". Blended in",
                    "between. Snow falls and water freezes below 0.15 (plains 0.8, forest 0.7, taiga 0.25). Tundra and",
                    "polar seas reach at least 0.3 in summer, so they thaw. Missing zones keep their default.")
            .defineListAllowEmpty("climate_changes", DEFAULT_CLIMATE_CHANGES, () -> "temperate=0,0,0,0",
                    value -> value instanceof String s && parseClimateChange(s) != null);

    public static final ModConfigSpec.IntValue TEMPERATURE_LAG_DAYS = BUILDER
            .comment("The temperature, crop seasons and the grass, leaf and texture colors run this many days behind the",
                    "calendar, as in real life the warmest time comes about a month after the longest day (14: a third of a",
                    "42-day season). Day length, tropical wet and dry seasons and the season shown stay on the calendar.")
            .defineInRange("temperature_lag_days", 14, 0, 1000);
    public static final ModConfigSpec.DoubleValue SEASON_VARIATION = BUILDER
            .comment("Each season is warmer or colder than usual by a random amount up to this, so no two years are the",
                    "same (0.067: about 2 °C). Small changes are more likely than big ones. Blended from one season to",
                    "the next. Times the climate zone's factor (tropics 0.3, temperate and mild 1, deserts 0.8, boreal,",
                    "tundra, alpine and polar sea 1.5). Grass and leaf colors don't follow it. 0: every year the same.")
            .defineInRange("season_variation", 0.067, 0.0, 1.0);
    public static final ModConfigSpec.DoubleValue DAILY_VARIATION = BUILDER
            .comment("Each day is warmer or colder than usual by a random amount up to this, on top of the season's",
                    "(0.133: about 4 °C; in real life days swing more than seasons). Blended from one day's noon to the",
                    "next, so warm and cold spells last a few days. Same shape and climate factor as season_variation;",
                    "not lagged. Grass and leaf colors don't follow it. 0: off.")
            .defineInRange("daily_variation", 0.133, 0.0, 1.0);
    public static final ModConfigSpec.LongValue VARIATION_SEED = BUILDER
            .comment("Seed for the season and daily variation. 0: set from the world seed when the world loads.")
            .defineInRange("variation_seed", 0L, Long.MIN_VALUE, Long.MAX_VALUE);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> BIOME_TEMPERATURES = BUILDER
            .comment("Temperature a biome has for the climate (snow, rain, freezing, crops), as \"biome=temperature\".",
                    "Grass and leaf colors keep the biome's own value. For biome mods that change temperatures to tint",
                    "grass: the defaults put back vanilla's for the biomes WWOO changes (beach 2.0, old growth pine",
                    "taiga 2.0, windswept hills 2.0, badlands 0.25...). Without WWOO they change nothing.")
            .defineListAllowEmpty("biome_temperatures", DEFAULT_BIOME_TEMPERATURES, () -> "minecraft:plains=0.8",
                    value -> value instanceof String s && parseBiomeTemperature(s) != null);

    public static final ModConfigSpec.BooleanValue SEASONAL_CROPS = BUILDER
            .comment("Crops grow well only in their seasons (tags seasonsrework:<season>_crops, plus Serene Seasons'",
                    "sereneseasons:<season>_crops that Farmer's Delight and others ship). Unlisted plants always grow.")
            .define("seasonal_crops", true);
    public static final ModConfigSpec.IntValue OUT_OF_SEASON_GROWTH = BUILDER
            .comment("Out of season, a crop grows on 1 in this many growth chances (bone meal too). 1: normal, 0: never.",
                    "In season, under glass (up to 16 blocks above) or underground it grows normally.")
            .defineInRange("out_of_season_growth", 6, 0, 1000);
    public static final ModConfigSpec.IntValue UNDERGROUND_LEVEL = BUILDER
            .comment("Below this height, crops that can't see the sky grow all year.")
            .defineInRange("underground_level", 48, -2048, 2048);

    public static final ModConfigSpec.DoubleValue LATITUDE = BUILDER
            .comment("Latitude for the seasonal day length: the sun is up longer in summer and shorter in winter, as at",
                    "this latitude on Earth (equinox days stay vanilla's). 40: about 16.4 hours of sun at mid summer,",
                    "10.7 at mid winter (60: 17.8 and 8.2) (vanilla 13.6). 0: vanilla days all year. Mobs, beds, daylight sensors follow.")
            .defineInRange("latitude", 40.0, 0.0, 60.0);

    public static final ModConfigSpec SPEC = BUILDER.build();

    // Copies for hot paths and for before the config loads: set when it (re)loads.
    private static volatile int daysPerSeason = 42;
    private static volatile Set<ResourceKey<Level>> dimensions = Set.of(Level.OVERWORLD);
    private static volatile Map<Zone, float[]> climateChanges = Map.of();
    private static volatile int temperatureLagDays = 14;
    private static volatile float seasonVariation = 0.067F, dailyVariation = 0.133F;
    private static volatile long variationSeed;
    private static volatile Map<ResourceLocation, Float> biomeTemperatures = toMap(DEFAULT_BIOME_TEMPERATURES);
    private static volatile boolean seasonalCrops = true;
    private static volatile int outOfSeasonGrowth = 6, undergroundLevel = 48;
    private static volatile double latitude = 40;

    public static void refresh() {
        Set<ResourceKey<Level>> set = new HashSet<>();
        for (String id : DIMENSIONS.get()) set.add(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(id)));
        dimensions = Set.copyOf(set);
        daysPerSeason = DAYS_PER_SEASON.get();
        Map<Zone, float[]> changes = new HashMap<>();
        for (String entry : CLIMATE_CHANGES.get()) {
            Map.Entry<Zone, float[]> parsed = parseClimateChange(entry);
            if (parsed != null) changes.put(parsed.getKey(), parsed.getValue());
        }
        climateChanges = Map.copyOf(changes);
        temperatureLagDays = TEMPERATURE_LAG_DAYS.get();
        seasonVariation = SEASON_VARIATION.get().floatValue();
        dailyVariation = DAILY_VARIATION.get().floatValue();
        variationSeed = VARIATION_SEED.get();
        biomeTemperatures = toMap(BIOME_TEMPERATURES.get());
        seasonalCrops = SEASONAL_CROPS.get();
        outOfSeasonGrowth = OUT_OF_SEASON_GROWTH.get();
        undergroundLevel = UNDERGROUND_LEVEL.get();
        latitude = LATITUDE.get();
        Climate.recategorize();
    }

    /** A new world: takes the variation seed from the world seed (after the server config loads, before players join). */
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        if (VARIATION_SEED.get() != 0) return;
        long seed = event.getServer().getWorldData().worldGenOptions().seed();
        VARIATION_SEED.set(seed != 0 ? seed : 1);
        VARIATION_SEED.save();
        variationSeed = VARIATION_SEED.get();
    }

    private static Map<ResourceLocation, Float> toMap(List<? extends String> entries) {
        Map<ResourceLocation, Float> map = new HashMap<>();
        for (String entry : entries) {
            Map.Entry<ResourceLocation, Float> parsed = parseBiomeTemperature(entry);
            if (parsed != null) map.put(parsed.getKey(), parsed.getValue());
        }
        return Map.copyOf(map);
    }

    private static Map.Entry<ResourceLocation, Float> parseBiomeTemperature(String entry) {
        int split = entry.indexOf('=');
        if (split < 0) return null;
        ResourceLocation id = ResourceLocation.tryParse(entry.substring(0, split).trim());
        try {
            return id == null ? null : Map.entry(id, Float.parseFloat(entry.substring(split + 1).trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "temperate=-0.3,0.25,-0.3,-0.65": a seasonal zone and its 4 changes (each -5..5), or null. */
    private static Map.Entry<Zone, float[]> parseClimateChange(String entry) {
        int split = entry.indexOf('=');
        if (split < 0) return null;
        Zone zone = Zone.byId(entry.substring(0, split).trim());
        String[] values = entry.substring(split + 1).split(",");
        if (zone == null || !zone.seasonal() || values.length != 4) return null;
        float[] changes = new float[4];
        try {
            for (int i = 0; i < 4; i++) changes[i] = Float.parseFloat(values[i].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        for (float change : changes) if (!(change >= -5 && change <= 5)) return null;
        return Map.entry(zone, changes);
    }

    public static boolean hasSeasons(ResourceKey<Level> dimension) {
        return dimensions.contains(dimension);
    }

    public static int daysPerSeason() {
        return daysPerSeason;
    }

    /** A seasonal zone's change at mid spring, summer, autumn, winter (the config's, else the default). */
    public static float[] climateChanges(Zone zone) {
        float[] changes = climateChanges.get(zone);
        return changes != null ? changes : zone.defaultChanges();
    }

    /** Days the temperature and colors run behind the calendar. */
    public static int temperatureLagDays() {
        return temperatureLagDays;
    }

    /** Up to this much warmer or colder per season. */
    public static float seasonVariation() {
        return seasonVariation;
    }

    public static float dailyVariation() {
        return dailyVariation;
    }

    public static long variationSeed() {
        return variationSeed;
    }

    /** The configured climate temperature of a biome, or null for its own. */
    public static Float biomeTemperature(ResourceLocation biome) {
        return biomeTemperatures.get(biome);
    }

    public static boolean seasonalCrops() {
        return seasonalCrops;
    }

    /** 1 in this many growth chances out of season; 0: never. */
    public static int outOfSeasonGrowth() {
        return outOfSeasonGrowth;
    }

    public static int undergroundLevel() {
        return undergroundLevel;
    }

    public static double latitude() {
        return latitude;
    }

    private Config() {}
}
