package dev.venatorvictor.equinox.climate;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;

/**
 * Geothermal patches: the parts of some biomes (tag equinox:geothermal) where world generation places hot ground,
 * steaming pools and the woods around them. They are found with the same noise the generation uses for them
 * (vanilla's biome info noise at 1/1000 scale, above 0.45), so no data is stored and both sides agree. There the daily
 * mean never drops below MIN_CELSIUS (no snow, no ice) and is a little warmer all year; the warmth fades in over a
 * short band around each patch instead of stopping at a chunk edge.
 */
public final class Geothermal {
    public static final TagKey<Biome> TAG = TagKey.create(Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath("equinox", "geothermal"));

    private static final double SCALE = 1000;
    private static final double THRESHOLD = 0.45;
    /** Half the width of the fade, in noise units around THRESHOLD. */
    private static final double EDGE = 0.03;
    /** Lowest daily mean in a patch, °C: above freezing even on a winter night's low. */
    private static final float MIN_CELSIUS = 6;
    /** Extra warmth all year, °C. */
    private static final float WARMING = 1.5F;

    /** 0 outside a patch, 1 inside, smooth across the edge. */
    public static float strength(int x, int z) {
        double noise = Biome.BIOME_INFO_NOISE.getValue(x / SCALE, z / SCALE, false);
        double t = (noise - (THRESHOLD - EDGE)) / (2 * EDGE);
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        return (float) (t * t * (3 - 2 * t));
    }

    /** A Minecraft temperature at x, z with the patch's warmth applied. */
    public static float apply(float temperature, int x, int z) {
        float strength = strength(x, z);
        if (strength == 0) return temperature;
        float warm = Math.max(temperature + WARMING / Celsius.PER_UNIT, Climate.fromCelsius(MIN_CELSIUS));
        return Mth.lerp(strength, temperature, warm);
    }

    private Geothermal() {}
}
