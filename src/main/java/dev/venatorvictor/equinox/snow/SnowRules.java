package dev.venatorvictor.equinox.snow;

import dev.venatorvictor.equinox.climate.BiomeSnow;
import dev.venatorvictor.equinox.climate.Climate;
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
 * much: anywhere under it, everywhere (no patches for now); colder, deeper.
 */
public final class SnowRules {
    /** Each further step this far below the snow line adds a layer (with depth_by_climate). */
    private static final float LAYER = 0.08F;

    /** The temperature now below the snow line, the same check vanilla's snowfall and freezing use. */
    public static boolean cold(Holder<Biome> biome, BlockPos pos) {
        return Climate.temperature(biome.value(), pos) < Climate.SNOW_LINE;
    }

    /** Whether snow lies at pos. warmest: the server's (Snowpack.warmest()) or the client's (ClientSnow) values. */
    public static boolean lies(Holder<Biome> biome, BlockPos pos, Map<ResourceKey<Biome>, Float> warmest) {
        return lies(biome, pos, warmest, Climate.temperature(biome.value(), pos));
    }

    /** lies() with the temperature at pos already known (here). */
    public static boolean lies(Holder<Biome> biome, BlockPos pos, Map<ResourceKey<Biome>, Float> warmest, float here) {
        return margin(biome, warmest, here) > 0;
    }

    /**
     * Snow layers the cover builds at pos: 1, or with depth_by_climate one more per LAYER colder, up to the biome's
     * limit (more snowy days, deeper snow). Only where lies() is true.
     */
    public static int layers(Holder<Biome> biome, BlockPos pos, Map<ResourceKey<Biome>, Float> warmest, float here) {
        if (!dev.venatorvictor.equinox.Config.snowDepth()) return 1;
        float over = margin(biome, warmest, here);
        return Mth.clamp(1 + Mth.floor(over / LAYER), 1, maxLayers(biome));
    }

    /** How far below the snow line one snow look step is (snow textures and LOD snow fade in 4 steps). */
    private static final float LOOK_STEP = 0.03F;

    /**
     * GROUND look: how snowy things look where snow lies, 1..4 (4 = fully snowy), by how far the column is past the
     * point where snow starts to lie (the same margin the snow cover uses): the first snow looks light, a long cold
     * spell full. 0 where snow doesn't lie.
     */
    public static int groundStep(Holder<Biome> biome, BlockPos pos, Map<ResourceKey<Biome>, Float> warmest, float here) {
        float over = margin(biome, warmest, here);
        if (over <= 0) return 0;
        return Mth.clamp(1 + Mth.floor(over / LOOK_STEP), 1, 4);
    }

    /**
     * CALENDAR look: how snowy things look from the temperature now alone (season, day and height), 0..4, like the
     * seasonal colors: no snowfall needed, snow or not on the ground.
     */
    public static int calendarStep(float here) {
        float below = Climate.SNOW_LINE - here;
        if (below <= 0) return 0;
        return Mth.clamp(1 + Mth.floor(below / LOOK_STEP), 1, 4);
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
    private static float margin(Holder<Biome> biome, Map<ResourceKey<Biome>, Float> warmest, float here) {
        Float since = biome.unwrapKey().map(warmest::get).orElse(null);
        if (since == null) return Climate.SNOW_LINE - here;
        return Climate.SNOW_LINE - (since + here - Climate.surfaceTemperature(biome.value()));
    }

    private SnowRules() {}
}
