package dev.venatorvictor.equinox.climate;

import dev.venatorvictor.equinox.Config;
import dev.venatorvictor.equinox.api.SeasonState;
import dev.venatorvictor.equinox.api.Seasons;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The climate in °C, for the temperature API (dev.venatorvictor.equinox.api.Temperatures). Pure math, no block scans:
 * the biome's spring/autumn midnight..noon value (data map equinox:celsius), plus its zone's °C change for the
 * season (config celsius_changes, blended between season middles, lagged), plus the season and daily variation
 * times the zone's factor. In seasonal zones the same climate also sets the Minecraft temperature (snow, ice, rain),
 * and the daily mean here is read back from it, so the two always agree. Underground the season fades out over 20 blocks toward 15.6 °C, then it
 * warms toward ~32 °C near bedrock (the Nether is below; an almost linear curve); open sky keeps the weather. Cave biomes with
 * a value (lush caves, deep dark, sulfur caves) replace both.
 */
public final class Celsius {
    /** °C per Minecraft temperature unit, for the variation (0.067 season variation: about 2 °C). */
    public static final float PER_UNIT = 30F;
    /** Where the season is gone underground, and the temperature there. */
    private static final int FADE_DEPTH = 20;
    private static final float CAVE = 15.6F, BEDROCK = 32.2F; // 60 °F and 90 °F
    /** The sea warms and cools later than rivers and lakes: this much more of a season (7 days of 42; 1.5 months against 1). */
    private static final double SEA_EXTRA_LAG = 1 / 6.0;

    /** 0 at midnight, 1 at noon, smooth in between. */
    public static float dayWeight(Level level) {
        double time = Math.floorMod(level.getDayTime() - 18000, 24000) / 24000.0;
        return (float) ((1 - Math.cos(time * 2 * Math.PI)) / 2);
    }

    /** The air at pos, dayWeight 0 = tonight's low, 1 = today's high. */
    public static float air(Level level, BlockPos pos, float dayWeight) {
        Holder<Biome> here = level.getBiome(pos);
        BiomeCelsius cave = here.getData(BiomeCelsius.TYPE);
        if (cave != null && Climate.zoneOf(here.value()) == Zone.CAVE) return Mth.lerp(dayWeight, cave.night(), cave.day());
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
        int depth = surface - pos.getY();
        if (depth <= 0 || level.canSeeSky(pos)) return outside(level, here, pos, dayWeight);
        if (depth < FADE_DEPTH) {
            BlockPos top = new BlockPos(pos.getX(), surface, pos.getZ());
            float outside = outside(level, level.getBiome(top), top, dayWeight);
            return Mth.lerp((float) depth / FADE_DEPTH, outside, CAVE);
        }
        int span = surface - FADE_DEPTH - level.getMinBuildHeight();
        float deep = span <= 0 ? 1 : Mth.clamp((float) (depth - FADE_DEPTH) / span, 0, 1);
        return CAVE + (BEDROCK - CAVE) * (float) (Math.log1p(deep) / Math.log(2)); // "logarithmic" (k = 1)
    }

    /** The water at pos: the biome's water plus its season (smaller swing at sea, later), never below freezing. */
    public static float water(Level level, BlockPos pos) {
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
        if (pos.getY() < surface && !level.canSeeSky(pos)) return air(level, pos, 0.5F); // underground: the rock's
        Holder<Biome> holder = level.getBiome(pos);
        Zone zone = Climate.zoneOf(holder.value());
        BiomeCelsius entry = holder.getData(BiomeCelsius.TYPE);
        float night = entry != null ? entry.night() : fallback(holder.value()) - 4, day = entry != null ? entry.day() : night + 8;
        float water = entry != null && entry.water().isPresent() ? entry.water().get() : defaultWater((night + day) / 2);
        SeasonState state = Seasons.get(level);
        float[] changes = Config.celsiusChanges(zone);
        if (state != null && changes != null) {
            Climate.Phase phase = zone.sea()
                    ? Climate.Phase.of(state.lagged(Config.temperatureLag() + SEA_EXTRA_LAG), Climate.phase().season(), 0)
                    : Climate.phase();
            water += (change(changes, phase, 0) + change(changes, phase, 1)) / 2 + phase.season() * zone.factor * PER_UNIT;
        }
        return Math.max(water, zone.sea() ? -2 : 0);
    }

    /**
     * At the surface. Seasonal zones: the daily mean is the biome's Minecraft temperature at pos turned back into °C
     * (Climate.toCelsius), so it has everything the snow has (season, variation, climate zone, blending, vanilla's
     * cooling with height); the table gives the day-night swing around it. Others: the biome's value, its season and
     * the variation.
     */
    private static float outside(Level level, Holder<Biome> holder, BlockPos pos, float dayWeight) {
        BiomeCelsius entry = holder.getData(BiomeCelsius.TYPE);
        float night, day;
        if (entry != null) {
            night = entry.night();
            day = entry.day();
        } else {
            float mean = fallback(holder.value());
            night = mean - 4;
            day = mean + 4;
        }
        Zone zone = Climate.zoneOf(holder.value());
        float[] changes = Config.celsiusChanges(zone);
        boolean seasons = Seasons.get(level) != null;
        Climate.Phase phase = Climate.phase();
        float atNight = seasons && changes != null ? change(changes, phase, 0) : 0;
        float atNoon = seasons && changes != null ? change(changes, phase, 1) : 0;
        if (zone.seasonal()) {
            float mean = Climate.toCelsius(Climate.temperature(holder.value(), pos));
            float swing = (day - night + atNoon - atNight) / 2;
            return mean + Mth.lerp(dayWeight, -swing, swing);
        }
        float temperature = Mth.lerp(dayWeight, night + atNight, day + atNoon);
        if (seasons && changes != null) temperature += (phase.season() + phase.daily()) * zone.factor * PER_UNIT;
        return temperature;
    }

    /** The zone's change now at midnight (0) or noon (1). */
    private static float change(float[] changes, Climate.Phase phase, int noon) {
        float from = changes[phase.from() * 2 + noon], to = changes[phase.to() * 2 + noon];
        return from + (to - from) * phase.blend();
    }

    /** Biomes without a value (other mods): a rough mean from the climate temperature (plains 0.8: 14 °C). */
    private static float fallback(Biome biome) {
        float temperature = biome.getBaseTemperature() + ((ClimateBiome) (Object) biome).equinox$shift();
        return Mth.clamp(temperature * 25 - 6, -25, 35);
    }

    /** Default water temperature from the air (in units of 25 °C): a few °C colder. */
    private static float defaultWater(float air) {
        float t = air / 25;
        float water = t > 2 ? t - 0.25F : t < -0.5F ? t - 0.5F : t - (float) Math.pow(t - 2, 4) / 156 - 0.25F;
        return water * 25;
    }

    private Celsius() {}
}
