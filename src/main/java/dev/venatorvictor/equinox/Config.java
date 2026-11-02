package dev.venatorvictor.equinox;

import dev.venatorvictor.equinox.climate.Climate;
import dev.venatorvictor.equinox.climate.Zone;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Seasons config: config/equinox-server.toml, the same for every world; a server sends its values to players (ConfigSync). */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    private static final List<String> DEFAULT_CLIMATE_CHANGES = Arrays.stream(Zone.values())
            .filter(Zone::seasonal).map(Zone::defaultEntry).toList();
    // °C change per zone (dev/specs/seasons-rework-plan.md, "Seasonal change per climate"); temperate matches Vienna.
    private static final List<String> DEFAULT_CELSIUS_CHANGES = List.of(
            "temperate=0..1,9..11,0..-1,-12..-14", "mild=0,7,0,-9", "boreal=0,13,0,-10", "tundra=-5,16,5,-14",
            "alpine=-2,7,2,-6", "polar_sea=-3,13,3,-5", "hot_desert=-1,11,1,-10", "cold_desert=0,14,0,-14",
            "rainforest=0,0,0,0", "monsoon=1,0,-1,-10", "savanna=0,0,0,-4", "warm_ocean=0,1,0,-1",
            "lukewarm_ocean=-2,3,2,-3", "ocean=-2,4,2,-4", "cold_ocean=-2,4,2,-4");
    // Vanilla temperatures of biomes that a biome mod changes to tint grass and leaves.
    // Crop heaters: the vanilla blocks that melt snow (block light 12+), reach = light - 12. Real flames warm, cold lights only stop frost.
    private static final List<String> DEFAULT_CROP_HEATERS = List.of(
            "minecraft:lava=20,3", "minecraft:lava_cauldron=20,3", "minecraft:fire=16,3", "minecraft:campfire=16,3",
            "minecraft:furnace=12,1", "minecraft:smoker=12,1", "minecraft:blast_furnace=12,1",
            "minecraft:torch=5,2", "minecraft:wall_torch=5,2", "minecraft:lantern=5,3", "minecraft:jack_o_lantern=5,3",
            "minecraft:glowstone=2,3", "minecraft:sea_lantern=2,3", "minecraft:shroomlight=2,3", "minecraft:ochre_froglight=2,3",
            "minecraft:verdant_froglight=2,3", "minecraft:pearlescent_froglight=2,3", "minecraft:redstone_lamp=2,3",
            "minecraft:copper_bulb=2,3", "minecraft:waxed_copper_bulb=2,3", "minecraft:beacon=2,3", "minecraft:conduit=2,3",
            "minecraft:respawn_anchor=2,3", "minecraft:end_rod=2,2", "minecraft:cave_vines=2,2", "minecraft:cave_vines_plant=2,2",
            "minecraft:magma_block=10,2");
    private static final List<String> DEFAULT_CROP_COOLERS = List.of(
            "minecraft:snow_block=22,2", "minecraft:ice=20,3", "minecraft:packed_ice=18,4", "minecraft:blue_ice=15,5");
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
    public static final ModConfigSpec.ConfigValue<List<? extends String>> CLIMATE_CHANGES = BUILDER
            .comment("Temperature change of each seasonal climate zone (biome tags equinox:climate/<zone>) at the",
                    "middle of spring, summer, autumn and winter, as \"zone=spring,summer,autumn,winter\". Blended in",
                    "between. Snow falls and water freezes below 0.15 (plains 0.8, forest 0.7, taiga 0.25). Tundra and",
                    "polar seas reach at least 0.3 in summer, so they thaw. Missing zones keep their default.")
            .defineListAllowEmpty("climate_changes", DEFAULT_CLIMATE_CHANGES, () -> "temperate=0,0,0,0",
                    value -> value instanceof String s && parseClimateChange(s) != null);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> CELSIUS_CHANGES = BUILDER
            .comment("°C climate (for the temperature API: crops, temperature and survival mods): change of each climate zone",
                    "at the middle of spring, summer, autumn and winter, added to the biome's spring/autumn °C (data map",
                    "equinox:celsius). One value = same at midnight and noon; \"a..b\" = midnight..noon, so the",
                    "day-night gap can change with the season. Missing zones (and cave, hot, none) don't change.")
            .defineListAllowEmpty("celsius_changes", DEFAULT_CELSIUS_CHANGES, () -> "temperate=0,0,0,0",
                    value -> value instanceof String s && parseCelsiusChange(s) != null);

    public static final ModConfigSpec.DoubleValue TEMPERATURE_LAG = BUILDER
            .comment("The temperature, snow, crop seasons and the grass, leaf and texture colors run this share of a season",
                    "behind the calendar, as in real life the warmest time comes about a month after the longest day",
                    "(0.333: a third of a season, 14 days of a 42-day season). A share, so it scales with days_per_season.",
                    "Day length, tropical wet and dry seasons and the season shown stay on the calendar.")
            .defineInRange("temperature_lag", 1 / 3.0, 0.0, 4.0);
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
            .comment("Seed for the season and daily variation. 0 (default): each world uses its own world seed.",
                    "Any other number: every world gets the same random seasons and days from this seed.")
            .defineInRange("fixed_variation_seed", 0L, Long.MIN_VALUE, Long.MAX_VALUE);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> BIOME_TEMPERATURES = BUILDER
            .comment("Temperature a biome has for the climate (snow, rain, freezing, crops), as \"biome=temperature\".",
                    "Grass and leaf colors keep the biome's own value. For biome mods that change temperatures to tint",
                    "grass: the defaults put back vanilla's for biomes such mods change (beach 2.0, old growth pine",
                    "taiga 2.0, windswept hills 2.0, badlands 0.25...). Without such a mod they change nothing.")
            .defineListAllowEmpty("biome_temperatures", DEFAULT_BIOME_TEMPERATURES, () -> "minecraft:plains=0.8",
                    value -> value instanceof String s && parseBiomeTemperature(s) != null);

    public static final ModConfigSpec.IntValue BLEND_RADIUS = BUILDER
            .comment("Climate blending: the temperature at a place mixes its biome with the biomes around it, up to this",
                    "many blocks away (nearer ones count more), so a river in the jungle is tropical, one in the taiga cold,",
                    "and a forest next to a desert is warmer than one next to snowy plains. With snow_locks (snow config),",
                    "snowy and snowless biomes keep their own climate. World generation doesn't change. 0: off.")
            .defineInRange("blend_radius", 64, 0, 1024);
    public static final ModConfigSpec.IntValue BLEND_RADIUS_LARGE = BUILDER
            .comment("The same for Large Biomes worlds (biomes 4 times wider).")
            .defineInRange("blend_radius_large_biomes", 128, 0, 1024);
    public static final ModConfigSpec.DoubleValue BLEND_STRENGTH = BUILDER
            .comment("How much a biome takes from its surroundings: 0 = its own temperature, 1 = only the surroundings'.",
                    "Rivers and beaches always take 1 (they have no climate of their own).")
            .defineInRange("blend_strength", 0.5, 0.0, 1.0);
    public static final ModConfigSpec.IntValue DH_BLEND_DISTANCE = BUILDER
            .comment("Distant Horizons: players with DH get the blending out to their LOD distance, up to this many blocks,",
                    "so far snow and ice match the blending too (worked out a little each tick, nearest first). 0: off.")
            .defineInRange("dh_blend_distance", 4096, 0, 32768);
    public static final ModConfigSpec.BooleanValue CLIMATE_ZONES = BUILDER
            .comment("Climate zones: Minecraft places biomes by a temperature map (5 bands, frozen to hot, -1 to 1). Near the",
                    "warm bands (jungle, savanna, desert) the winter cold fades out, so there's no snow next to jungles.",
                    "Read from the world's own map (worlds with the vanilla biome placement only), it changes nothing in",
                    "world generation. Only the cold half of the seasons (and of the snow tuning) fades; summers stay.",
                    "Mountains (biomes in equinox:climate/alpine, e.g. stony peaks) keep their whole winter.")
            .define("climate_zones", true);
    public static final ModConfigSpec.DoubleValue ZONE_WARM_START = BUILDER
            .comment("Where on the temperature map the winter starts fading (full winter below). Vanilla's bands: frozen",
                    "below -0.45, cold to -0.15, temperate to 0.2, warm (jungle, savanna, forest) to 0.55, hot (desert) above.")
            .defineInRange("zone_warm_start", 0.0, -1.0, 1.0);
    public static final ModConfigSpec.DoubleValue ZONE_WARM_END = BUILDER
            .comment("Where the winter is gone (no seasonal cold above). 0.2 is where jungles and savannas start.")
            .defineInRange("zone_warm_end", 0.2, -1.0, 1.0);

    public static final ModConfigSpec.BooleanValue SEASONAL_CROPS = BUILDER
            .comment("Crops follow the temperature in °C: each has a range (data map equinox:crop_climate; crops only",
                    "in the season tags equinox:<season>_crops or the sereneseasons crop tags get one from their seasons).",
                    "Ideal: grows faster. Cool or warm: slow. Near the limits: stops. Past them (tonight's low, today's",
                    "high): farmland crops die. Heaters and coolers nearby (crop_heaters, crop_coolers below)",
                    "change the temperature. Unlisted plants, and plants outside the overworld, grow as in vanilla.")
            .define("seasonal_crops", true);
    public static final ModConfigSpec.IntValue SLOW_GROWTH = BUILDER
            .comment("Cool or warm (outside the ideal range): a crop grows on 1 in this many growth chances (bone meal",
                    "too). 1: normal, 0: never.")
            .defineInRange("slow_growth", 6, 0, 1000);
    public static final ModConfigSpec.DoubleValue IDEAL_BONUS = BUILDER
            .comment("In the ideal range, this share of growth chances grows for sure (about 1.4x vanilla speed at 0.15).")
            .defineInRange("ideal_bonus", 0.15, 0.0, 1.0);
    public static final ModConfigSpec.DoubleValue FARMLAND_HEAT = BUILDER
            .comment("When today's high is at least this (°C), farmland only stays wet with water within 2 blocks, not 4.")
            .defineInRange("farmland_heat", 30.0, -100.0, 100.0);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> CROP_HEATERS = BUILDER
            .comment("Blocks that keep crops near them at least this warm: \"block=°C,distance\". Distance counts like light",
                    "(steps along x, y and z). The defaults are the blocks that melt snow in vanilla, with the same reach (light",
                    "above 11: light - 12 blocks), so crops are safe from frost where snow can't lie. Light sources count only",
                    "while they shine that bright (lit, full). Flames warm enough to grow crops; cold lights only keep frost off",
                    "(2 °C: every vanilla crop survives it except cocoa). Magma isn't a light but is hot.")
            .defineListAllowEmpty("crop_heaters", DEFAULT_CROP_HEATERS, () -> "minecraft:torch=5,2",
                    value -> value instanceof String s && parseWarmth(s) != null);
    public static final ModConfigSpec.ConfigValue<List<? extends String>> CROP_COOLERS = BUILDER
            .comment("Blocks that keep crops near them at most this warm: \"block=°C,distance\" (same distance as heaters).")
            .defineListAllowEmpty("crop_coolers", DEFAULT_CROP_COOLERS, () -> "minecraft:ice=20,3",
                    value -> value instanceof String s && parseWarmth(s) != null);

    public static final ModConfigSpec.DoubleValue LATITUDE = BUILDER
            .comment("Latitude for the seasonal day length: the sun is up longer in summer and shorter in winter, as at",
                    "this latitude on Earth (equinox days stay vanilla's). 40: about 16.4 hours of sun at mid summer,",
                    "10.7 at mid winter (60: 17.8 and 8.2) (vanilla 13.6). 0: vanilla days all year. Mobs, beds, daylight sensors follow.")
            .defineInRange("latitude", 40.0, 0.0, 60.0);

    public static final ModConfigSpec SPEC = BUILDER.build();

    // Copies for hot paths and for before the config loads: set when it (re)loads.
    private static volatile int daysPerSeason = 42;
    private static volatile Map<Zone, float[]> climateChanges = Map.of();
    private static volatile Map<Zone, float[]> celsiusChanges = toCelsiusMap(DEFAULT_CELSIUS_CHANGES);
    private static volatile double temperatureLag = 1 / 3.0;
    private static volatile float seasonVariation = 0.067F, dailyVariation = 0.133F;
    private static volatile long variationSeed;
    private static volatile Map<ResourceLocation, Float> biomeTemperatures = toMap(DEFAULT_BIOME_TEMPERATURES);
    private static volatile Map<ResourceLocation, float[]> cropHeaters = Map.of(), cropCoolers = Map.of();
    private static volatile boolean seasonalCrops = true;
    private static volatile int slowGrowth = 6;
    private static volatile float idealBonus = 0.15F, farmlandHeat = 30;
    private static volatile double latitude = 40;
    private static volatile int blendRadius = 64, blendRadiusLarge = 128;
    private static volatile float blendStrength = 0.5F;
    private static volatile int dhBlendDistance = 4096;
    private static volatile boolean climateZones = true;
    private static volatile float zoneWarmStart = 0, zoneWarmEnd = 0.2F;

    public static void refresh() {
        daysPerSeason = val(DAYS_PER_SEASON);
        Map<Zone, float[]> changes = new HashMap<>();
        for (String entry : val(CLIMATE_CHANGES)) {
            Map.Entry<Zone, float[]> parsed = parseClimateChange(entry);
            if (parsed != null) changes.put(parsed.getKey(), parsed.getValue());
        }
        climateChanges = Map.copyOf(changes);
        celsiusChanges = toCelsiusMap(val(CELSIUS_CHANGES));
        temperatureLag = val(TEMPERATURE_LAG);
        seasonVariation = val(SEASON_VARIATION).floatValue();
        dailyVariation = val(DAILY_VARIATION).floatValue();
        variationSeed = val(VARIATION_SEED) != 0 ? val(VARIATION_SEED) : worldSeed;
        biomeTemperatures = toMap(val(BIOME_TEMPERATURES));
        cropHeaters = toWarmthMap(val(CROP_HEATERS));
        cropCoolers = toWarmthMap(val(CROP_COOLERS));
        dev.venatorvictor.equinox.crops.CropWarmth.reset();
        seasonalCrops = val(SEASONAL_CROPS);
        slowGrowth = val(SLOW_GROWTH);
        idealBonus = val(IDEAL_BONUS).floatValue();
        farmlandHeat = val(FARMLAND_HEAT).floatValue();
        latitude = val(LATITUDE);
        blendRadius = val(BLEND_RADIUS);
        blendRadiusLarge = val(BLEND_RADIUS_LARGE);
        blendStrength = val(BLEND_STRENGTH).floatValue();
        dhBlendDistance = val(DH_BLEND_DISTANCE);
        climateZones = val(CLIMATE_ZONES);
        zoneWarmStart = val(ZONE_WARM_START).floatValue();
        zoneWarmEnd = val(ZONE_WARM_END).floatValue();
        Climate.recategorize();
    }

    /** A world starts: with variation_seed 0 the variation follows this world's seed (kept in memory, not in the shared file). */
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        long seed = event.getServer().getWorldData().worldGenOptions().seed();
        worldSeed = seed != 0 ? seed : 1;
        refresh();
    }

    // ---- Sync: the file is global (config/), so NeoForge doesn't send it; a server sends its values to players.

    private static final List<ModConfigSpec.ConfigValue<?>> SYNCED = List.of(DAYS_PER_SEASON, CLIMATE_CHANGES, CELSIUS_CHANGES,
            TEMPERATURE_LAG, SEASON_VARIATION, DAILY_VARIATION, VARIATION_SEED, BIOME_TEMPERATURES, BLEND_RADIUS, BLEND_RADIUS_LARGE,
            BLEND_STRENGTH, DH_BLEND_DISTANCE, CLIMATE_ZONES, ZONE_WARM_START, ZONE_WARM_END, SEASONAL_CROPS, SLOW_GROWTH,
            IDEAL_BONUS, FARMLAND_HEAT, CROP_HEATERS, CROP_COOLERS, LATITUDE);
    /** The server's values while connected to one (path -> value); empty: this computer's file. */
    private static volatile Map<String, Object> remote = Map.of();
    private static volatile long worldSeed = 1;

    @SuppressWarnings("unchecked")
    private static <T> T val(ModConfigSpec.ConfigValue<T> value) {
        Object from = remote.get(String.join(".", value.getPath()));
        return from != null ? (T) from : value.get();
    }

    /** This server's values, the variation seed as worked out for the world. */
    public static CompoundTag syncTag() {
        CompoundTag tag = new CompoundTag();
        for (ModConfigSpec.ConfigValue<?> value : SYNCED) {
            String key = String.join(".", value.getPath());
            Object v = value == VARIATION_SEED ? (Object) variationSeed : value.get();
            if (v instanceof Boolean b) tag.putBoolean(key, b);
            else if (v instanceof Integer i) tag.putInt(key, i);
            else if (v instanceof Long l) tag.putLong(key, l);
            else if (v instanceof Double d) tag.putDouble(key, d);
            else if (v instanceof List<?> list) {
                ListTag entries = new ListTag();
                for (Object entry : list) entries.add(StringTag.valueOf(String.valueOf(entry)));
                tag.put(key, entries);
            }
        }
        return tag;
    }

    /** Client: use the server's values (null: back to this computer's file). */
    public static void receive(CompoundTag tag) {
        Map<String, Object> values = new HashMap<>();
        if (tag != null) for (ModConfigSpec.ConfigValue<?> value : SYNCED) {
            String key = String.join(".", value.getPath());
            if (!tag.contains(key)) continue;
            Object def = value.getDefault();
            if (def instanceof Boolean) values.put(key, tag.getBoolean(key));
            else if (def instanceof Integer) values.put(key, tag.getInt(key));
            else if (def instanceof Long) values.put(key, tag.getLong(key));
            else if (def instanceof Double) values.put(key, tag.getDouble(key));
            else if (def instanceof List) {
                List<String> list = new ArrayList<>();
                for (Tag entry : tag.getList(key, Tag.TAG_STRING)) list.add(entry.getAsString());
                values.put(key, List.copyOf(list));
            }
        }
        remote = Map.copyOf(values);
        refresh();
    }

    private static Map<ResourceLocation, Float> toMap(List<? extends String> entries) {
        Map<ResourceLocation, Float> map = new HashMap<>();
        for (String entry : entries) {
            Map.Entry<ResourceLocation, Float> parsed = parseBiomeTemperature(entry);
            if (parsed != null) map.put(parsed.getKey(), parsed.getValue());
        }
        return Map.copyOf(map);
    }

    private static Map<ResourceLocation, float[]> toWarmthMap(List<? extends String> entries) {
        Map<ResourceLocation, float[]> map = new HashMap<>();
        for (String entry : entries) {
            Map.Entry<ResourceLocation, float[]> parsed = parseWarmth(entry);
            if (parsed != null) map.put(parsed.getKey(), parsed.getValue());
        }
        return Map.copyOf(map);
    }

    /** "minecraft:torch=5,2": a block, °C and distance (0..16), or null. */
    private static Map.Entry<ResourceLocation, float[]> parseWarmth(String entry) {
        int split = entry.indexOf('=');
        if (split < 0) return null;
        ResourceLocation id = ResourceLocation.tryParse(entry.substring(0, split).trim());
        String[] parts = entry.substring(split + 1).split(",");
        if (id == null || parts.length != 2) return null;
        try {
            float celsius = Float.parseFloat(parts[0].trim());
            int distance = Integer.parseInt(parts[1].trim());
            return distance < 0 || distance > 16 || !(celsius >= -100 && celsius <= 100) ? null : Map.entry(id, new float[] {celsius, distance});
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Crop heaters: block id -> {°C at least, distance}. */
    public static Map<ResourceLocation, float[]> cropHeaters() {
        return cropHeaters;
    }

    /** Crop coolers: block id -> {°C at most, distance}. */
    public static Map<ResourceLocation, float[]> cropCoolers() {
        return cropCoolers;
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

    private static Map<Zone, float[]> toCelsiusMap(List<? extends String> entries) {
        Map<Zone, float[]> map = new HashMap<>();
        for (String entry : entries) {
            Map.Entry<Zone, float[]> parsed = parseCelsiusChange(entry);
            if (parsed != null) map.put(parsed.getKey(), parsed.getValue());
        }
        return Map.copyOf(map);
    }

    /** "temperate=0..1,9..11,0..-1,-12..-14": a zone and its 8 values (midnight, noon per season), or null. */
    private static Map.Entry<Zone, float[]> parseCelsiusChange(String entry) {
        int split = entry.indexOf('=');
        if (split < 0) return null;
        Zone zone = Zone.byId(entry.substring(0, split).trim());
        String[] values = entry.substring(split + 1).split(",");
        if (zone == null || values.length != 4) return null;
        float[] changes = new float[8];
        try {
            for (int i = 0; i < 4; i++) {
                String value = values[i].trim();
                int range = value.indexOf("..");
                changes[i * 2] = Float.parseFloat(range < 0 ? value : value.substring(0, range).trim());
                changes[i * 2 + 1] = Float.parseFloat(range < 0 ? value : value.substring(range + 2).trim());
            }
        } catch (NumberFormatException e) {
            return null;
        }
        for (float change : changes) if (!(change >= -100 && change <= 100)) return null;
        return Map.entry(zone, changes);
    }

    /** "temperate=-0.3,0.25,-0.3,-0.75": a seasonal zone and its 4 changes (each -5..5), or null. */
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

    /** Only the overworld has seasons. */
    public static boolean hasSeasons(ResourceKey<Level> dimension) {
        return dimension == Level.OVERWORLD;
    }

    public static int daysPerSeason() {
        return daysPerSeason;
    }

    /** A zone's °C change: midnight and noon at mid spring, summer, autumn, winter (8 values), or null for none. */
    public static float[] celsiusChanges(Zone zone) {
        return celsiusChanges.get(zone);
    }

    /** A seasonal zone's change at mid spring, summer, autumn, winter (the config's, else the default). */
    public static float[] climateChanges(Zone zone) {
        float[] changes = climateChanges.get(zone);
        return changes != null ? changes : zone.defaultChanges();
    }

    /** Climate blending radius in blocks (0: off). */
    public static int blendRadius() {
        return blendRadius;
    }

    /** Climate blending radius in Large Biomes worlds. */
    public static int blendRadiusLarge() {
        return blendRadiusLarge;
    }

    /** Distant Horizons blending distance in blocks (0: off). */
    public static int dhBlendDistance() {
        return dhBlendDistance;
    }

    /** Climate zones from the world's temperature map (ClimateBlend.cooling). */
    public static boolean climateZones() {
        return climateZones;
    }

    /** Temperature map value where the winter starts fading. */
    public static float zoneWarmStart() {
        return zoneWarmStart;
    }

    /** Temperature map value where the winter is gone. */
    public static float zoneWarmEnd() {
        return zoneWarmEnd;
    }

    /** How much a (land) biome's temperature takes from its surroundings. */
    public static float blendStrength() {
        return blendStrength;
    }

    /** Share of a season the temperature and colors run behind the calendar. */
    public static double temperatureLag() {
        return temperatureLag;
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

    /** Cool or warm: 1 in this many growth chances; 0: never. */
    public static int slowGrowth() {
        return slowGrowth;
    }

    public static float idealBonus() {
        return idealBonus;
    }

    public static float farmlandHeat() {
        return farmlandHeat;
    }

    public static double latitude() {
        return latitude;
    }

    private Config() {}
}
