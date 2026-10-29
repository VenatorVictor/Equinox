package dev.venator.seasonsrework.climate;

import net.minecraft.core.BlockPos;

/** Added to Biome by BiomeMixin: the biome's climate, set when tags or the config load (Climate.categorize). */
public interface ClimateBiome {
    /** NONE: no seasons at all. HOT: too hot to change (deserts); crops grow as in the tropics. */
    byte NONE = 0, SEASONAL = 1, TROPICAL = 2, HOT = 3;

    byte seasonsrework$climate();

    /** Added to the biome's temperature: the config's climate temperature minus the biome's own one. */
    float seasonsrework$shift();

    void seasonsrework$setClimate(byte climate, float shift);

    /** Biome.getTemperature (private): the temperature at a position, seasons included. */
    float seasonsrework$temperature(BlockPos pos);
}
