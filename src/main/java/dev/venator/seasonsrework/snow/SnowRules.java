package dev.venator.seasonsrework.snow;

import dev.venator.seasonsrework.climate.Climate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/**
 * Where snow lies, shared by the server (snow cover) and the client (Distant Horizons LODs, snowy textures),
 * so far and near snow follow the same rule.
 */
public final class SnowRules {
    /** The temperature now below 0.15, the same check vanilla's snowfall uses (seasons included). */
    public static boolean cold(Holder<Biome> biome, BlockPos pos) {
        return Climate.temperature(biome.value(), pos) < 0.15F;
    }

    /** Cold without the season (snowy biomes, high peaks): these don't wait for the first snowfall. */
    public static boolean alwaysCold(Holder<Biome> biome, BlockPos pos) {
        return Climate.baseTemperature(biome.value(), pos) < 0.15F;
    }

    /** After the first snowfall of the cold season (coverage 1), snow lies everywhere it's cold. */
    public static boolean snowAllowed(Holder<Biome> biome, BlockPos pos, float coverage) {
        return coverage >= 1F || alwaysCold(biome, pos);
    }

    private SnowRules() {}
}
