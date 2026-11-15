package dev.venatorvictor.equinox.climate;

import net.minecraft.core.BlockPos;

/** Added to Biome by BiomeMixin: the biome's climate, set when tags or the config load (Climate.categorize). */
public interface ClimateBiome {
    /** NONE: no seasons at all (oceans, caves, other dimensions); SEASONAL: every land biome (tropical ones too, Zone.tropical). */
    byte NONE = 0, SEASONAL = 1;
    /** Climate blending (ClimateBlend): not blended (other dimensions, caves), land (Config.blendStrength), full (rivers, beaches). */
    byte BLEND_NONE = 0, BLEND_LAND = 1, BLEND_FULL = 2;

    byte equinox$climate();

    Zone equinox$zone();

    /**
     * Added to the biome's temperature: seasonal zones, the °C climate's daily mean on Minecraft's scale minus the
     * biome's own temperature (Climate.fromCelsius); others, the config's climate temperature minus the biome's own.
     */
    float equinox$shift();

    /** The biome's change at mid spring, summer, autumn, winter, or null without seasons (Climate.change). */
    float[] equinox$changes();

    /** Added to the temperature (not to shift, so °C fallbacks don't see it): snow locks (Climate.lockShift). */
    float equinox$lockShift();

    void equinox$setClimate(Zone zone, float shift, float lockShift, float[] changes);

    /** The biome's °C change at midnight and noon at each season point, the start of its mid part (8 values): its own (BiomeCelsius.seasons) or its zone's; null for none. */
    float[] equinox$celsiusChanges();

    void equinox$setCelsiusChanges(float[] changes);

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

    /**
     * How strong its seasons are for colors, 0..1: its summer-to-winter drop in °C over a temperate one's (23 °C, Vienna).
     * Plains 1, swamp 0.7, monsoon 0.43, savanna 0.17, jungle 0.
     */
    float equinox$seasonStrength();

    void equinox$setSeasonStrength(float strength);

    /** The biome's color profile (seasonal colors, client side). */
    ColorProfile equinox$colorProfile();

    void equinox$setColorProfile(ColorProfile profile);

    /** Has geothermal patches (tag equinox:geothermal): warmer where world generation puts hot ground. */
    boolean equinox$geothermal();

    void equinox$setGeothermal(boolean geothermal);

    /** Biome.getTemperature (private): the temperature at a position, seasons included. */
    float equinox$temperature(BlockPos pos);
}
