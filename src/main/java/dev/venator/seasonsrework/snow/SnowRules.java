package dev.venator.seasonsrework.snow;

import dev.venator.seasonsrework.Config;
import dev.venator.seasonsrework.climate.BiomeSnow;
import dev.venator.seasonsrework.climate.Climate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;

import java.util.Map;

/**
 * Where snow lies, shared by the server (snow cover, snowfall, crops) and the client (Distant Horizons LODs, snowy
 * textures), so far and near snow follow the same rule. Snow lies where the column has stayed below the snow line
 * since it last snowed there (Snowpack: the warmest since the last snowfall, per biome). How far below decides how
 * much: just under it, round patches (a smooth noise per column); RAMP under it, everywhere; colder, deeper.
 */
public final class SnowRules {
    /** Below the snow line by this much at the last snowfall: snow everywhere. Less: patches. */
    static final float RAMP = 0.05F;
    /** Each further step this far below the snow line adds a layer (with depth_by_climate). */
    private static final float LAYER = 0.08F;

    /** The temperature now below the snow line, the same check vanilla's snowfall and freezing use. */
    public static boolean cold(Holder<Biome> biome, BlockPos pos) {
        return Climate.temperature(biome.value(), pos) < Climate.SNOW_LINE;
    }

    /** Whether snow lies at pos. warmest: the server's (Snowpack.warmest()) or the client's (ClientSnow) values. */
    public static boolean lies(Holder<Biome> biome, BlockPos pos, Map<ResourceKey<Biome>, Float> warmest) {
        return margin(biome, pos, warmest) > patch(pos.getX(), pos.getZ()) * RAMP;
    }

    /**
     * Snow layers the cover builds at pos: 1, or with depth_by_climate one more per LAYER colder, up to the biome's
     * limit (more snowy days, deeper snow). Only where lies() is true.
     */
    public static int layers(Holder<Biome> biome, BlockPos pos, Map<ResourceKey<Biome>, Float> warmest) {
        if (!SnowConfig.SNOW_DEPTH.get()) return 1;
        float over = margin(biome, pos, warmest) - patch(pos.getX(), pos.getZ()) * RAMP;
        return Mth.clamp(1 + Mth.floor(over / LAYER), 1, maxLayers(biome));
    }

    /** Natural snow never fills a whole block (players can stack it to 8). */
    static int maxLayers(Holder<Biome> biome) {
        BiomeSnow snow = Climate.snowDays(biome);
        if (snow == null) return 2;
        float days = snow.days();
        return days < 60 ? 1 : days < 120 ? 2 : days < 200 ? 3 : days < 270 ? 4 : 6;
    }

    /**
     * How far below the snow line the column stayed since the last snowfall: the biome's warmest since then, plus
     * how much colder or warmer pos is than the biome at sea level (height). Biomes without seasons: now.
     */
    private static float margin(Holder<Biome> biome, BlockPos pos, Map<ResourceKey<Biome>, Float> warmest) {
        float here = Climate.temperature(biome.value(), pos);
        Float since = biome.unwrapKey().map(warmest::get).orElse(null);
        if (since == null) return Climate.SNOW_LINE - here;
        return Climate.SNOW_LINE - (since + here - Climate.surfaceTemperature(biome.value()));
    }

    /** 0 .. 1 per column, smooth: round blobs about 24 blocks wide with smaller ones on top. Same on both sides. */
    static float patch(int x, int z) {
        long seed = Config.variationSeed();
        return 0.7F * value(x, z, 24, seed) + 0.3F * value(x, z, 7, seed ^ 0x5DEECE66DL);
    }

    private static float value(int x, int z, int cell, long seed) {
        int cx = Math.floorDiv(x, cell), cz = Math.floorDiv(z, cell);
        float fx = (x - cx * cell + 0.5F) / cell, fz = (z - cz * cell + 0.5F) / cell;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        float a = corner(cx, cz, seed), b = corner(cx + 1, cz, seed), c = corner(cx, cz + 1, seed), d = corner(cx + 1, cz + 1, seed);
        return Mth.lerp(fz, Mth.lerp(fx, a, b), Mth.lerp(fx, c, d));
    }

    private static float corner(int x, int z, long seed) {
        long h = seed ^ x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return ((h ^ (h >>> 31)) >>> 40) / (float) (1 << 24);
    }

    private SnowRules() {}
}
