package dev.venatorvictor.equinox.client;

import dev.venatorvictor.equinox.weather.ClimateGrid;
import dev.venatorvictor.equinox.weather.Weather;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Per-region weather on the client: the rain and thunder level follow the storm at the camera (sky darkening, fog,
 * rain sounds, lightning flashes, and rain particle mods reading the rain level), fading like vanilla's. Columns
 * without a storm stay dry (Weather.clientDry).
 */
public final class ClientWeather {
    /** Rain level per tick toward the storm's (vanilla fades 0.01 a tick). */
    private static final float FADE = 0.0125F;
    /** Full rain level from this storm strength on (the ragged edge is below). */
    private static final float FULL_RAIN = 0.25F;

    private static float rain, thunder;
    private static ClientLevel managed;

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || !Weather.on(level)) {
            Weather.clientLevel = null;
            managed = null;
            return;
        }
        Weather.clientThread = Thread.currentThread();
        Weather.clientLevel = level;
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        if (mc.player != null && camera.equals(Vec3.ZERO)) camera = mc.player.position();
        int x = Mth.floor(camera.x), z = Mth.floor(camera.z);
        float strength = Weather.strength(level, x, z);
        float rainTarget = Mth.clamp(strength / FULL_RAIN, 0, 1);
        float thunderTarget = Mth.clamp((strength - (1 - Weather.THUNDER_SHARE)) / 0.02F, 0, 1);
        if (managed != level) {
            // A new level (join, back from another dimension): start from the storm, no fade from vanilla's.
            managed = level;
            rain = rainTarget;
            thunder = thunderTarget;
        }
        rain += Mth.clamp(rainTarget - rain, -FADE, FADE);
        thunder += Mth.clamp(thunderTarget - thunder, -FADE, FADE);
        level.setRainLevel(rain);
        level.setThunderLevel(thunder);
        Weather.Side side = Weather.client();
        int keep = dev.venatorvictor.equinox.weather.ServerWeather.KEEP_RADIUS, span = 2 * keep + 1;
        if (side.grid.size() > span * span)
            side.grid.trim(Math.floorDiv(x, ClimateGrid.TILE_BLOCKS), Math.floorDiv(z, ClimateGrid.TILE_BLOCKS), keep);
    }

    /** Leaving a server: forget its storms. */
    public static void clear() {
        Weather.setClient(null);
        Weather.clientLevel = null;
        managed = null;
        rain = thunder = 0;
    }

    private ClientWeather() {
    }
}
