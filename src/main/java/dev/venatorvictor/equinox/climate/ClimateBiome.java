package dev.venatorvictor.equinox.climate;

import net.minecraft.core.BlockPos;

/** Added to Biome by BiomeMixin: the biome's climate, set when tags or the config load (Climate.categorize). */
public interface ClimateBiome {
    /** NONE: no seasons at all. HOT: too hot to change (untagged hot biomes); crops grow as in the tropics. */
    byte NONE = 0, SEASONAL = 1, TROPICAL = 2, HOT = 3;
    /** Climate blending (ClimateBlend): not blended (other dimensions, caves), land (Config.blendStrength), full (rivers, beaches). */
    byte BLEND_NONE = 0, BLEND_LAND = 1, BLEND_FULL = 2;

    byte equinox$climate();

    Zone equinox$zone();

    /** Added to the biome's temperature: the config's climate temperature minus the biome's own one. */
    float equinox$shift();

    /** The biome's change at mid spring, summer, autumn, winter, or null without seasons (Climate.change). */
    float[] equinox$changes();

    /** Added to the temperature (not to shift, so °C fallbacks don't see it): tunes the biome's snow days (BiomeSnow). */
    float equinox$snowShift();

    void equinox$setClimate(Zone zone, float shift, float snowShift, float[] changes);

    /** How the biome takes part in climate blending (BLEND_*). */
    byte equinox$blendKind();

    void equinox$setBlendKind(byte kind);

    /** Snow the climate blending can't change: ALWAYS (snow all year, blending only colder), NEVER (no snow, only warmer). */
    byte SNOW_FREE = 0, SNOW_ALWAYS = 1, SNOW_NEVER = 2;

    byte equinox$snowLock();

    void equinox$setSnowLock(byte lock);

    /** True for the client's copy of the biome (single player has both): picks the side's climate blend mixes. */
    boolean equinox$clientSide();

    void equinox$setClientSide(boolean client);

    /** The biome's color profile (seasonal colors, client side). */
    ColorProfile equinox$colorProfile();

    void equinox$setColorProfile(ColorProfile profile);

    /** Biome.getTemperature (private): the temperature at a position, seasons included. */
    float equinox$temperature(BlockPos pos);
}
