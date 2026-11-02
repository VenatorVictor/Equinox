package dev.venatorvictor.equinox.snow;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;

import java.util.HashMap;
import java.util.Map;

/**
 * Client copy of the server's snowpack (Snowpack). Read from Distant Horizons' worker threads. Empty until the server
 * sends it (or forever, if the server doesn't have the mod): then snow follows the temperature now.
 */
public final class ClientSnow {
    /** Redraws at most this often (ms): during a snowfall the values move all the time. */
    private static final long REFRESH_MS = 5000;

    private static volatile Map<ResourceKey<Biome>, Float> warmest = Map.of();
    private static boolean changed;
    private static long lastRefresh;

    public static Map<ResourceKey<Biome>, Float> warmest() {
        return warmest;
    }

    static void receive(Map<ResourceLocation, Float> values) {
        Map<ResourceKey<Biome>, Float> map = new HashMap<>();
        values.forEach((id, value) -> map.put(ResourceKey.create(Registries.BIOME, id), value));
        warmest = Map.copyOf(map);
        changed = true;
    }

    /** Client tick: true when the snow changed and the last redraw was long enough ago. */
    public static boolean takeRefresh() {
        long now = System.currentTimeMillis();
        if (!changed || now - lastRefresh < REFRESH_MS) return false;
        changed = false;
        lastRefresh = now;
        return true;
    }

    public static void reset() {
        warmest = Map.of();
        changed = false;
    }

    private ClientSnow() {}
}
