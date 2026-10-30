package dev.venator.seasonsrework.climate;

import dev.venator.seasonsrework.Config;
import dev.venator.seasonsrework.api.Season;
import dev.venator.seasonsrework.api.SeasonState;
import dev.venator.seasonsrework.api.Seasons;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

import java.util.List;

/**
 * The seasonal climate. Each biome has a climate zone (tags seasonsrework:climate/<zone>, Zone): seasonal zones change
 * the biome's temperature by the zone's value at the middle of each season, blended in between, so the snow line moves
 * a little every tick instead of jumping. On top, each season gets a random variation (warmer or colder than usual),
 * the same for a world on both sides. Both run some days behind the calendar (temperature lag); the tropical wet and
 * dry seasons don't. Tropical zones keep their temperature and get wet and dry seasons. The config can also give a
 * biome another temperature for the climate only (grass colors read the biome's own value), for biome mods that change
 * temperatures to tint grass (WWOO: beach 2.0, old growth pine taiga 2.0...).
 */
public final class Climate {
    /** Biomes in no climate tag: Serene Seasons' tropical biomes get the monsoon (the tag can add more). */
    public static final TagKey<Biome> TROPICAL = TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("seasonsrework", "tropical"));
    public static final TagKey<Biome> NO_SEASONS = TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("seasonsrework", "no_seasons"));
    /** Untagged biomes hotter than this have no seasons (Zone.HOT), as in Serene Seasons. */
    private static final float MAX_SEASONAL_TEMPERATURE = 0.8F;
    /** Zones are checked in this order (a biome in two tags gets the first). */
    private static final Zone[] TAGGED = {Zone.CAVE, Zone.POLAR_SEA, Zone.TEMPERATE, Zone.MILD, Zone.BOREAL, Zone.TUNDRA,
            Zone.ALPINE, Zone.HOT_DESERT, Zone.COLD_DESERT, Zone.RAINFOREST, Zone.MONSOON, Zone.SAVANNA};

    /** Where the (lagged) year is between two season middles, plus this season's variation. */
    private record Phase(int from, int to, float blend, float variation) {}

    // Read by the Biome hooks on any thread; written once per tick.
    private static volatile Phase phase = new Phase(0, 0, 0, 0);
    private static volatile boolean summer, winter, savannaDry;
    // The biomes of the last tag load on each side, to sort again when the config changes.
    private static volatile List<Holder<Biome>> serverBiomes = List.of(), clientBiomes = List.of();
    // The distinct seasonal curves of the server's biomes (for SnowCover's target).
    private static volatile List<float[]> curves = List.of();

    /** The temperate zone's change now, variation included (the reference for the snowy season and /season). */
    public static float offset() {
        return change(Zone.TEMPERATE.changes);
    }

    /** The random part of the change: this season warmer (above 0) or colder than usual. */
    public static float variation() {
        return phase.variation;
    }

    /** The change now of a seasonal curve (mid spring, summer, autumn, winter), variation included. */
    public static float change(float[] changes) {
        Phase p = phase;
        return changes[p.from] + (changes[p.to] - changes[p.from]) * p.blend + p.variation;
    }

    /** A biome's seasonal change now (0 without seasons). */
    public static float change(Biome biome) {
        float[] changes = ((ClimateBiome) (Object) biome).seasonsrework$changes();
        return changes == null ? 0 : change(changes);
    }

    /**
     * Rain override for a zone, or null for the biome's own. Monsoon: wet summer, dry winter. Savanna: wet summer, dry
     * from late autumn to early spring. Cold desert: rare snow in winter (vanilla badlands never rain). On the calendar.
     */
    public static Boolean precipitation(Zone zone) {
        return switch (zone) {
            case MONSOON -> summer ? Boolean.TRUE : winter ? Boolean.FALSE : null;
            case SAVANNA -> summer ? Boolean.TRUE : savannaDry ? Boolean.FALSE : null;
            case COLD_DESERT -> winter ? Boolean.TRUE : null;
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
        SeasonState lagged = state.lagged(Config.temperatureLagDays());
        // 0 at mid spring, 1 at mid summer... (season middles), wrapping winter to spring.
        double position = lagged.yearProgress() * Season.COUNT - 0.5;
        int index = (int) Math.floor(position);
        phase = new Phase(Math.floorMod(index, Season.COUNT), Math.floorMod(index + 1, Season.COUNT),
                (float) (position - index), variationAt(lagged));
        Season season = state.season();
        summer = season == Season.SUMMER;
        winter = season == Season.WINTER;
        savannaDry = winter || season == Season.AUTUMN && state.part() == SeasonState.PARTS - 1
                || season == Season.SPRING && state.part() == 0;
    }

    /** Changes with every STEP of any seasonal curve (SnowCover redoes the loaded chunks then). */
    public static int snowTarget(float step) {
        int target = 1;
        for (float[] curve : curves) target = 31 * target + Math.round(change(curve) / step);
        return target;
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
        float from = randomSeason(index), to = randomSeason(index + 1);
        return amount * (from + (to - from) * blend);
    }

    /** -1 .. 1 for a season (seasons since the start); two random halves added, so near 0 more often than the ends. */
    private static float randomSeason(long season) {
        long hash = mix(Config.variationSeed() ^ mix(season + 0x9E3779B97F4A7C15L));
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
            biomes.forEach(Climate::categorize);
            if (event.getUpdateCause() != TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED) curves = curvesOf(biomes);
            for (Holder<Biome> holder : biomes) ((ClimateBiome) (Object) holder.value()).seasonsrework$setColorProfile(ColorProfile.of(holder));
        });
    }

    /** The config changed (climate temperatures). */
    public static void recategorize() {
        for (Zone zone : Zone.values()) if (zone.seasonal()) zone.changes = Config.climateChanges(zone);
        serverBiomes.forEach(Climate::categorize);
        clientBiomes.forEach(Climate::categorize);
        curves = curvesOf(serverBiomes);
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
        ((ClimateBiome) (Object) holder.value()).seasonsrework$setClimate(zone, temperature - own, changes);
    }

    private static Zone zoneOf(Holder<Biome> holder, float temperature) {
        for (Zone zone : TAGGED) if (holder.is(zone.tag)) return zone;
        if (holder.is(NO_SEASONS)) return Zone.NONE;
        if (holder.is(TROPICAL)) return Zone.MONSOON;
        return temperature > MAX_SEASONAL_TEMPERATURE ? Zone.HOT : Zone.TEMPERATE;
    }

    /** The distinct curves of these biomes (same values = one curve). */
    private static List<float[]> curvesOf(List<Holder<Biome>> biomes) {
        List<float[]> list = new java.util.ArrayList<>();
        for (Holder<Biome> holder : biomes) {
            float[] changes = ((ClimateBiome) (Object) holder.value()).seasonsrework$changes();
            if (changes != null && list.stream().noneMatch(c -> java.util.Arrays.equals(c, changes))) list.add(changes);
        }
        return List.copyOf(list);
    }

    /** The temperature at pos without the season (snowy biomes and high peaks are cold all year). */
    public static float baseTemperature(Biome biome, net.minecraft.core.BlockPos pos) {
        ClimateBiome climate = (ClimateBiome) (Object) biome;
        return climate.seasonsrework$temperature(pos) - change(biome);
    }

    /** The temperature at pos now, season included (snow falls and water freezes below 0.15). */
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
