package dev.venator.seasonsrework.climate;

import dev.venator.seasonsrework.Config;
import dev.venator.seasonsrework.api.Season;
import dev.venator.seasonsrework.api.SeasonState;
import dev.venator.seasonsrework.api.Seasons;
import dev.venator.seasonsrework.snow.SnowConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

import java.util.List;

/**
 * The seasonal climate. Each biome has a climate zone (tags seasonsrework:climate/<zone>, Zone): seasonal zones change
 * the biome's temperature by the zone's value at the middle of each season, blended in between, so the snow line moves
 * a little every tick instead of jumping. On top, each season and each day get a random variation (warmer or colder
 * than usual, times the zone's factor), the same for a world on both sides. Both run some days behind the calendar (temperature lag); the tropical wet and
 * dry seasons don't. Tropical zones keep their temperature and get wet and dry seasons. The config can also give a
 * biome another temperature for the climate only (grass colors read the biome's own value), for biome mods that change
 * temperatures to tint grass (WWOO: beach 2.0, old growth pine taiga 2.0...).
 */
public final class Climate {
    /** Biomes in no climate tag: Serene Seasons' tropical biomes get the monsoon (the tag can add more). */
    public static final TagKey<Biome> TROPICAL = TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("seasonsrework", "tropical"));
    public static final TagKey<Biome> NO_SEASONS = TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("seasonsrework", "no_seasons"));
    /** Snow all year while SnowConfig.SNOW_LOCKS is on. */
    public static final TagKey<Biome> ALWAYS_SNOW = TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("seasonsrework", "always_snow"));
    private static final BiomeSnow ALL_YEAR = new BiomeSnow(365);
    /** Untagged biomes hotter than this have no seasons (Zone.HOT), as in Serene Seasons. */
    private static final float MAX_SEASONAL_TEMPERATURE = 0.8F;
    /** Zones are checked in this order (a biome in two tags gets the first). */
    private static final Zone[] TAGGED = {Zone.CAVE, Zone.POLAR_SEA, Zone.TEMPERATE, Zone.MILD, Zone.BOREAL, Zone.TUNDRA,
            Zone.ALPINE, Zone.HOT_DESERT, Zone.COLD_DESERT, Zone.RAINFOREST, Zone.MONSOON, Zone.SAVANNA,
            Zone.WARM_OCEAN, Zone.LUKEWARM_OCEAN, Zone.OCEAN, Zone.COLD_OCEAN};

    /** Where the (lagged) year is between two season middles, plus the season's and the day's variation. */
    record Phase(int from, int to, float blend, float season, float daily) {
        /** Between two season middles of a lagged state (0 = mid spring, wrapping winter to spring). */
        static Phase of(SeasonState lagged, float season, float daily) {
            double position = lagged.yearProgress() * Season.COUNT - 0.5;
            int index = (int) Math.floor(position);
            return new Phase(Math.floorMod(index, Season.COUNT), Math.floorMod(index + 1, Season.COUNT),
                    (float) (position - index), season, daily);
        }

        float blend(float[] values) {
            return values[from] + (values[to] - values[from]) * blend;
        }
    }

    static Phase phase() {
        return phase;
    }

    // Read by the Biome hooks on any thread; written once per tick.
    private static volatile Phase phase = new Phase(0, 0, 0, 0, 0);
    private static volatile boolean summer, winter, savannaDry;
    // The biomes of the last tag load on each side, to sort again when the config changes.
    private static volatile List<Holder<Biome>> serverBiomes = List.of(), clientBiomes = List.of();
    // The server's biomes with seasons (Snowpack).
    private static volatile List<Holder<Biome>> seasonal = List.of();

    /** The temperate zone's change now, variation included (the reference for the snowy season and /season). */
    public static float offset() {
        return change(Zone.TEMPERATE.changes, Zone.TEMPERATE.factor);
    }

    /** The temperate zone's change without the day's variation (the snowy season: a warm day doesn't end it). */
    public static float seasonalOffset() {
        Phase p = phase;
        float[] changes = Zone.TEMPERATE.changes;
        return changes[p.from] + (changes[p.to] - changes[p.from]) * p.blend + p.season * Zone.TEMPERATE.factor;
    }

    /** This season warmer (above 0) or colder than usual, before the zone's factor. */
    public static float seasonVariation() {
        return phase.season;
    }

    /** Today warmer (above 0) or colder than usual, before the zone's factor. */
    public static float dailyVariation() {
        return phase.daily;
    }

    /** The change now of a seasonal curve (mid spring, summer, autumn, winter), variation times factor included. */
    public static float change(float[] changes, float factor) {
        Phase p = phase;
        return changes[p.from] + (changes[p.to] - changes[p.from]) * p.blend + (p.season + p.daily) * factor;
    }

    /** A biome's seasonal change now (0 without seasons). */
    public static float change(Biome biome) {
        ClimateBiome climate = (ClimateBiome) (Object) biome;
        float[] changes = climate.seasonsrework$changes();
        return changes == null ? 0 : change(changes, climate.seasonsrework$zone().factor);
    }

    /**
     * Rain override for a zone, or null for the biome's own. Monsoon: wet summer, dry winter. Savanna: wet summer, dry
     * from late autumn to early spring. Cold desert: rare snow in winter (vanilla badlands never rain). Hot desert:
     * never (also deserts from other mods). On the calendar.
     */
    public static Boolean precipitation(Zone zone) {
        return switch (zone) {
            case MONSOON -> summer ? Boolean.TRUE : winter ? Boolean.FALSE : null;
            case SAVANNA -> summer ? Boolean.TRUE : savannaDry ? Boolean.FALSE : null;
            case COLD_DESERT -> winter ? Boolean.TRUE : null;
            case HOT_DESERT -> Boolean.FALSE;
            default -> null;
        };
    }

    /** Monsoon or savanna wet season now (for /season). */
    public static boolean wet(Zone zone) {
        return summer && (zone == Zone.MONSOON || zone == Zone.SAVANNA);
    }

    /** Monsoon or savanna dry season now (for /season). */
    public static boolean dry(Zone zone) {
        return zone == Zone.MONSOON ? winter : zone == Zone.SAVANNA && savannaDry;
    }

    /** Follows the season of a seasonal level (server and client tick, level load). */
    public static void update(Level level) {
        SeasonState state = Seasons.get(level);
        if (state == null) return;
        SeasonState lagged = state.lagged(Config.temperatureLag());
        phase = Phase.of(lagged, variationAt(lagged), dailyVariationAt(state));
        Season season = state.season();
        summer = season == Season.SUMMER;
        winter = season == Season.WINTER;
        savannaDry = winter || season == Season.AUTUMN && state.part() == SeasonState.PARTS - 1
                || season == Season.SPRING && state.part() == 0;
    }

    /**
     * Each season's variation peaks at its middle and blends into the next one's (smoothstep), so it never jumps.
     * Worked out from the variation seed and the season's number, so server and clients get the same without syncing.
     */
    static float variationAt(SeasonState state) {
        float amount = Config.seasonVariation();
        if (amount == 0) return 0;
        double position = (double) state.dayTime() / state.seasonTicks() - 0.5; // 0: middle of the first spring
        long index = (long) Math.floor(position);
        float blend = (float) (position - index);
        blend = blend * blend * (3 - 2 * blend);
        float from = random(index, SEASON_SALT), to = random(index + 1, SEASON_SALT);
        return amount * (from + (to - from) * blend);
    }

    /**
     * Each day's variation peaks at noon and blends into the next day's (smoothstep), so warm and cold spells last a
     * few days like a real front, with no jump at midnight. On the calendar (no lag). Same seed, other salt.
     */
    static float dailyVariationAt(SeasonState state) {
        float amount = Config.dailyVariation();
        if (amount == 0) return 0;
        double position = (state.dayTime() - 6000) / 24000.0; // 0: noon of the first day
        long index = (long) Math.floor(position);
        float blend = (float) (position - index);
        blend = blend * blend * (3 - 2 * blend);
        float from = random(index, DAY_SALT), to = random(index + 1, DAY_SALT);
        return amount * (from + (to - from) * blend);
    }

    private static final long SEASON_SALT = 0x9E3779B97F4A7C15L, DAY_SALT = 0xD1B54A32D192ED03L;

    /** -1 .. 1 for a season or day (counted from the start); two random halves added, so near 0 more often than the ends. */
    private static float random(long number, long salt) {
        long hash = mix(Config.variationSeed() ^ mix(number + salt));
        float a = (hash >>> 40) / (float) (1 << 24), b = ((hash >>> 16) & 0xFFFFFF) / (float) (1 << 24);
        return a + b - 1;
    }

    /** SplitMix64's mixer. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Sorts the biomes of the (server or client) registry when tags load. */
    public static void categorize(TagsUpdatedEvent event) {
        event.getRegistryAccess().registry(Registries.BIOME).ifPresent(registry -> {
            List<Holder<Biome>> biomes = List.copyOf(registry.holders().toList());
            if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED) clientBiomes = biomes;
            else serverBiomes = biomes;
            boolean client = event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED;
            for (Holder<Biome> holder : biomes) ((ClimateBiome) (Object) holder.value()).seasonsrework$setClientSide(client);
            biomes.forEach(Climate::categorize);
            if (event.getUpdateCause() != TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED) {
                seasonal = seasonalOf(biomes);
            }
            for (Holder<Biome> holder : biomes) ((ClimateBiome) (Object) holder.value()).seasonsrework$setColorProfile(ColorProfile.of(holder));
        });
    }

    /** The config changed (climate temperatures). */
    public static void recategorize() {
        for (Zone zone : Zone.values()) if (zone.seasonal()) zone.changes = Config.climateChanges(zone);
        serverBiomes.forEach(Climate::categorize);
        clientBiomes.forEach(Climate::categorize);
        seasonal = seasonalOf(serverBiomes);
        // A new phase object, so cached temperatures (ClimateBlend) are worked out again.
        phase = new Phase(phase.from(), phase.to(), phase.blend(), phase.season(), phase.daily());
    }

    /** The server's biomes with seasons. */
    public static List<Holder<Biome>> seasonalBiomes() {
        return seasonal;
    }

    private static List<Holder<Biome>> seasonalOf(List<Holder<Biome>> biomes) {
        return biomes.stream().filter(holder -> ((ClimateBiome) (Object) holder.value()).seasonsrework$changes() != null).toList();
    }

    private static void categorize(Holder<Biome> holder) {
        float own = holder.value().getBaseTemperature();
        Float configured = holder.unwrapKey().map(key -> Config.biomeTemperature(key.location())).orElse(null);
        float temperature = configured != null ? configured : own;
        Zone zone = zoneOf(holder, temperature);
        float[] changes = null;
        if (zone.seasonal()) {
            changes = zone.changes.clone();
            // Tundra and polar seas: summer at least THAW, so the snow and the sea ice melt.
            if (zone.thaws) changes[Season.SUMMER.ordinal()] = Math.max(changes[Season.SUMMER.ordinal()], Zone.THAW - temperature);
        }
        BiomeSnow snow = snowDays(holder);
        float snowShift = snowShift(temperature, changes, snow, zone.factor);
        ClimateBiome climate = (ClimateBiome) (Object) holder.value();
        climate.seasonsrework$setClimate(zone, temperature - own, snowShift, changes);
        // Locked (snow_locks): snowy biomes have snow all year, no-snow ones (0 days, or warm without seasons: deserts,
        // jungles, savannas) never; blending leaves both alone (ClimateBlend), only the biomes in between change.
        boolean locks = SnowConfig.SNOW_LOCKS.get();
        boolean always = locks && holder.is(ALWAYS_SNOW);
        boolean never = locks && !always && (snow != null ? snow.days() <= 0 : changes == null && temperature >= SNOW_LINE);
        climate.seasonsrework$setSnowLock(always ? ClimateBiome.SNOW_ALWAYS : never ? ClimateBiome.SNOW_NEVER : ClimateBiome.SNOW_FREE);
        climate.seasonsrework$setBlendKind(!holder.is(BiomeTags.IS_OVERWORLD) || zone == Zone.CAVE ? ClimateBiome.BLEND_NONE
                : holder.is(BiomeTags.IS_RIVER) || holder.is(BiomeTags.IS_BEACH) ? ClimateBiome.BLEND_FULL : ClimateBiome.BLEND_LAND);
    }

    /** The biome's snow days (data map), 365 for snowy biomes while snow_locks is on. Null without a value. */
    public static BiomeSnow snowDays(Holder<Biome> holder) {
        return SnowConfig.SNOW_LOCKS.get() && holder.is(ALWAYS_SNOW) ? ALL_YEAR : holder.getData(BiomeSnow.TYPE);
    }

    /** Vanilla snows and freezes below this temperature. */
    public static final float SNOW_LINE = 0.15F;

    /** True on a thread while it generates a chunk (WorldgenClimateMixin): biomes then give vanilla's temperature and rain. */
    public static final ThreadLocal<Boolean> WORLDGEN = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final int SAMPLES = 336;

    /**
     * Moves a seasonal curve up or down so it's below the snow line for the biome's share of snowy days (BiomeSnow),
     * in an average year at sea level. Never: the coldest day stays above it even in the coldest variation; all year:
     * the warmest day stays below it. 0 without a value or without seasons.
     */
    static float snowShift(float temperature, float[] changes, BiomeSnow snow, float factor) {
        if (snow == null || changes == null) return 0;
        float[] curve = new float[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            double position = (i + 0.5) / SAMPLES * Season.COUNT;
            int index = (int) position;
            float blend = (float) (position - index);
            curve[i] = temperature + changes[index] + (changes[(index + 1) % Season.COUNT] - changes[index]) * blend;
        }
        java.util.Arrays.sort(curve);
        float spread = (Config.seasonVariation() + Config.dailyVariation()) * factor + 0.02F;
        int cold = Math.round(snow.days() / 365F * SAMPLES);
        if (cold <= 0) return Math.max(0, SNOW_LINE + spread - curve[0]);
        if (cold >= SAMPLES) return Math.min(0, SNOW_LINE - spread - curve[SAMPLES - 1]);
        return SNOW_LINE - (curve[cold - 1] + curve[cold]) / 2;
    }

    /** The temperature now at sea level (no height), season and snow tuning included. */
    public static float surfaceTemperature(Biome biome) {
        return surfaceTemperature(biome, Float.NaN);
    }

    /** The same in a climate zone: cooling (0..1, NaN = unknown = 1) scales the cold part of the season (ClimateBlend). */
    public static float surfaceTemperature(Biome biome, float cooling) {
        ClimateBiome climate = (ClimateBiome) (Object) biome;
        return biome.getBaseTemperature() + climate.seasonsrework$shift() + zoned(climate.seasonsrework$snowShift() + change(biome), cooling);
    }

    /** A seasonal change (season + snow tuning) in a climate zone: its cold part times cooling (NaN: unchanged). */
    public static float zoned(float seasonal, float cooling) {
        return seasonal < 0 && cooling == cooling ? seasonal * cooling : seasonal;
    }

    private static Zone zoneOf(Holder<Biome> holder, float temperature) {
        for (Zone zone : TAGGED) if (holder.is(zone.tag)) return zone;
        if (holder.is(NO_SEASONS)) return Zone.NONE;
        if (holder.is(TROPICAL)) return Zone.MONSOON;
        return temperature > MAX_SEASONAL_TEMPERATURE ? Zone.HOT : Zone.TEMPERATE;
    }

    /** The temperature at pos now, season included (snow falls and water freezes below SNOW_LINE). */
    public static float temperature(Biome biome, net.minecraft.core.BlockPos pos) {
        return ((ClimateBiome) (Object) biome).seasonsrework$temperature(pos);
    }

    public static byte climateOf(Biome biome) {
        return ((ClimateBiome) (Object) biome).seasonsrework$climate();
    }

    public static Zone zoneOf(Biome biome) {
        return ((ClimateBiome) (Object) biome).seasonsrework$zone();
    }

    private Climate() {}
}
