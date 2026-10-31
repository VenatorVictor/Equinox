package dev.venator.seasonsrework.climate;

import net.minecraft.core.BlockPos;

/** Added to Biome by BiomeMixin: the biome's climate, set when tags or the config load (Climate.categorize). */
public interface ClimateBiome {
    /** NONE: no seasons at all. HOT: too hot to change (untagged hot biomes); crops grow as in the tropics. */
    byte NONE = 0, SEASONAL = 1, TROPICAL = 2, HOT = 3;

    byte seasonsrework$climate();

    Zone seasonsrework$zone();

    /** Added to the biome's temperature: the config's climate temperature minus the biome's own one. */
    float seasonsrework$shift();

    /** The biome's change at mid spring, summer, autumn, winter, or null without seasons (Climate.change). */
    float[] seasonsrework$changes();

    /** Added to the temperature (not to shift, so °C fallbacks don't see it): tunes the biome's snow days (BiomeSnow). */
    float seasonsrework$snowShift();

    void seasonsrework$setClimate(Zone zone, float shift, float snowShift, float[] changes);

    /** The biome's color profile (seasonal colors, client side). */
    ColorProfile seasonsrework$colorProfile();

    void seasonsrework$setColorProfile(ColorProfile profile);

    /** Biome.getTemperature (private): the temperature at a position, seasons included. */
    float seasonsrework$temperature(BlockPos pos);
}
