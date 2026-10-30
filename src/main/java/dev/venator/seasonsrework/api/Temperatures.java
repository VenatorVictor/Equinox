package dev.venator.seasonsrework.api;

import dev.venator.seasonsrework.climate.Celsius;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Entry point for other code (crops, the Cold Sweat compat, survival mods): the climate in °C at a position, on either
 * side. Air: the biome, the season, the season and daily variation, the time of day, depth and cave biomes. Pure math
 * and a biome lookup, cheap enough for every crop tick. Heaters and coolers (crops) are not included.
 */
public final class Temperatures {
    /** The air at pos now, in °C. */
    public static float air(Level level, BlockPos pos) {
        return Celsius.air(level, pos, Celsius.dayWeight(level));
    }

    /** Today's air at pos, in °C: tonight's low and today's high (the season and variation as now). */
    public static DayRange airToday(Level level, BlockPos pos) {
        return new DayRange(Celsius.air(level, pos, 0), Celsius.air(level, pos, 1));
    }

    /** The water at pos, in °C (no daily swing; never below freezing: 0, or −2 at sea). */
    public static float water(Level level, BlockPos pos) {
        return Celsius.water(level, pos);
    }

    /** A day's low and high in °C. */
    public record DayRange(float low, float high) {
        public float mean() {
            return (low + high) / 2;
        }
    }

    private Temperatures() {}
}
