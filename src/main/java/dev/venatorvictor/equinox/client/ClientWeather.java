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
    private static final float FULL_RAIN = 0.3F;
    /** The rain level comes from the storm around you: the middle and 8 points this far out (blocks). */
    private static final int AREA = 24;
    /** Rain level (sky and cloud darkening) when the clouds have gathered but no rain falls yet. */
    private static final float GATHER_DARK = 0.5F;

    private static float rain, thunder;
    private static ClientLevel managed;

    /** 1 under the cloud layer at the camera, 0 above it, fading through it. */
    private static float underClouds(ClientLevel level, Vec3 camera) {
        float height = level.effects().getCloudHeight();
        dev.venatorvictor.equinox.client.clouds.CloudSky.Sky s = CloudRenderer.sky();
        Weather.Side side = Weather.client();
        if (Float.isNaN(height) || s == null || side == null || !CloudRenderer.active(level)) return 1;
        double[] off = side.field.offset(level.getGameTime());
        float x = (float) (camera.x - off[0] - s.centerX()), z = (float) (camera.z - off[1] - s.centerZ());
        float base = height + s.map(s.map0(), 1, x, z), top = height + s.map(s.map0(), 2, x, z);
        return 1 - Mth.clamp((float) (camera.y - base) / Math.max(4, top + 8 - base), 0, 1);
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || !Weather.on(level)) {
            Weather.clientLevel = null;
            managed = null;
            dev.venatorvictor.equinox.client.dh.DhClouds.clear();
            return;
        }
        Weather.clientThread = Thread.currentThread();
        Weather.clientLevel = level;
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        if (mc.player != null && camera.equals(Vec3.ZERO)) camera = mc.player.position();
        int x = Mth.floor(camera.x), z = Mth.floor(camera.z);
        // The storm around you, not one column: at a storm's ragged edge the level stays steady while you walk.
        float strength = 2 * Weather.strength(level, x, z), peak = 0;
        for (int k = 0; k < 8; k++) {
            float a = k * Mth.TWO_PI / 8;
            float s = Weather.strength(level, x + Math.round(Mth.cos(a) * AREA), z + Math.round(Mth.sin(a) * AREA));
            strength += s;
            peak = Math.max(peak, s);
        }
        strength /= 10;
        // A storm coming or gathering overhead: the sky and the clouds darken first (vanilla darkens them by the rain
        // level; no rain falls where the columns stay dry), up to GATHER_DARK just before it rains; then the rain level
        // rises from there (no jump between the two).
        Weather.Side sky = Weather.client();
        long time = level.getGameTime();
        float gather = GATHER_DARK * Math.min(1, sky.cloudiness(camera.x, camera.z, time, sky.field.offset(time)));
        float rainTarget = Math.max(gather, Mth.clamp(strength / FULL_RAIN, 0, 1));
        float thunderTarget = Mth.clamp((Math.max(strength, peak * 0.8F) - (1 - Weather.THUNDER_SHARE)) / 0.02F, 0, 1);
        if (managed != level) {
            // A new level (join, back from another dimension): start from the storm, no fade from vanilla's.
            managed = level;
            rain = rainTarget;
            thunder = thunderTarget;
        }
        rain += Mth.clamp(rainTarget - rain, -FADE, FADE);
        thunder += Mth.clamp(thunderTarget - thunder, -FADE, FADE);
        // rain and snow fall from under the clouds: none above them, fading out through the cloud
        level.setRainLevel(rain * underClouds(level, camera));
        level.setThunderLevel(thunder);
        Weather.Side side = Weather.client();
        if (side != null) dev.venatorvictor.equinox.client.dh.DhClouds.tick(level);
        CloudRenderer.tick(level);
        int keep = dev.venatorvictor.equinox.weather.ServerWeather.KEEP_RADIUS, span = 2 * keep + 1;
        if (side.grid.size() > span * span)
            side.grid.trim(Math.floorDiv(x, ClimateGrid.TILE_BLOCKS), Math.floorDiv(z, ClimateGrid.TILE_BLOCKS), keep);
    }

    /** Leaving a server: forget its storms. */
    public static void clear() {
        Weather.setClient(null);
        CloudRenderer.clear();
        Weather.clientLevel = null;
        managed = null;
        rain = thunder = 0;
    }

    private ClientWeather() {
    }
}
