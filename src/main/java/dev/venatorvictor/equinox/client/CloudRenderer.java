package dev.venatorvictor.equinox.client;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.venatorvictor.equinox.Equinox;
import dev.venatorvictor.equinox.climate.Climate;
import dev.venatorvictor.equinox.client.clouds.CloudExtras;
import dev.venatorvictor.equinox.client.clouds.CloudField;
import dev.venatorvictor.equinox.client.clouds.CloudGpu;
import dev.venatorvictor.equinox.client.clouds.CloudSky;
import dev.venatorvictor.equinox.client.dh.DhClouds;
import dev.venatorvictor.equinox.weather.Weather;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Our clouds (weather per region), drawn in vanilla's place and render types, so shader packs treat them as clouds.
 * The sky (CloudSky: heaps, towers, anvils, the layer map) is worked out on our own low-priority thread every
 * REBUILD_TICKS or when the camera moves on; the graphics card turns it into cubes (CloudGpu), a few chunks a frame.
 * Colours are in the palette texture (some shader packs keep the texture but drop the vertex colour); the vertex
 * colour carries vanilla's face shading. Cloud shadows and distant rain are drawn here too (CloudExtras).
 * <p>
 * Without OpenGL 4.3 (compute shaders) the game keeps vanilla's clouds. With Distant Horizons the far clouds go to its
 * box renderer (DhClouds), so they sit behind far terrain; the near ones are drawn here.
 */
public final class CloudRenderer {
    /** Ticks between sky builds, blocks the camera may move before one. */
    public static final int REBUILD_TICKS = 40;
    private static final int REBUILD_BLOCKS = 96;
    /** How dark the cloud shadows are at most. */
    private static final float SHADOW_STRENGTH = 0.42F;

    private static final ExecutorService BUILDER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Equinox clouds");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    private record Built(CloudSky.Sky sky, CloudField field) {
    }

    private static CompletableFuture<Built> pending;
    /** The density on the main processor for the latest sky (is the camera inside a cloud?). */
    private static volatile CloudField field;
    /** How far inside a cloud the camera is (0..1, eased per tick), and that cloud's storm level. */
    private static float inside, insideStorm;
    private static long builtTime = Long.MIN_VALUE / 2, paletteTime = Long.MIN_VALUE / 2;
    private static int builtVersion = -1, builtReach = -1;
    private static double builtX, builtZ;
    private static String shownSettings = "";
    private static CloudSky model;
    /** The latest sky (for the far clouds too). */
    private static volatile CloudSky.Sky sky;
    /** The game's cloud shader plus cubes growing and shrinking (not used under a shader pack). */
    private static ShaderInstance growing;
    private static Boolean shaderApi;
    private static java.lang.invoke.MethodHandle packInUse;

    /** Mod bus: our cloud shader. */
    public static void registerShaders(net.neoforged.neoforge.client.event.RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Equinox.MOD_ID, "clouds_growing"),
                    com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL), shader -> growing = shader);
        } catch (java.io.IOException e) {
            Equinox.LOGGER.warn("Equinox: the growing-cloud shader didn't load, cubes will just appear", e);
        }
    }

    /** Our clouds take over from vanilla's here (weather per region on, setting on, storms known, compute shaders). */
    public static boolean active(ClientLevel level) {
        return level != null && ClientConfig.STORM_CLOUDS.get() && Weather.on(level) && Weather.client() != null && CloudGpu.supported();
    }

    /** How far the clouds reach (blocks). */
    public static int reach() {
        return ClientConfig.CLOUD_DISTANCE.get();
    }

    /** How far the clouds drawn here reach: all of them, or with Distant Horizons drawing the far ones, the game's view. */
    public static int nearReach() {
        if (!DhClouds.drawing()) return reach();
        int view = (Minecraft.getInstance().options.getEffectiveRenderDistance() - 1) * 16;
        return Mth.clamp(view, 192, reach());
    }

    public static CloudSky.Sky sky() {
        return sky;
    }

    /** Near cubes, rings of detail (blocks the near cubes reach), from the settings. */
    private static int ring() {
        return switch (ClientConfig.CLOUD_DETAIL.get()) {
            case LOW -> 192;
            case MEDIUM -> 384;
            case HIGH -> 576;
        };
    }

    /** Starts a background sky build when one is due. */
    public static void schedule(ClientLevel level, double camX, double camZ) {
        Weather.Side side = Weather.client();
        long time = level.getGameTime();
        double[] now = side.field.offset(time);
        double wx = camX - now[0], wz = camZ - now[1];
        int reach = reach();
        boolean moved = Math.max(Math.abs(wx - builtX), Math.abs(wz - builtZ)) > REBUILD_BLOCKS;
        if (pending != null || !(moved || time - builtTime >= REBUILD_TICKS || time < builtTime
                || builtVersion != side.grid.version() || builtReach != reach)) return;
        builtTime = time;
        builtVersion = side.grid.version();
        builtReach = reach;
        builtX = wx;
        builtZ = wz;
        if (model == null || model.seed() != side.seed) model = new CloudSky(side.seed);
        CloudSky m = model;
        float summer = Mth.clamp(Climate.change(new float[] {0.5F, 1F, 0.5F, 0F}, 0), 0, 1);
        long dayTime = level.getDayTime();
        float amount = ClientConfig.CLOUD_AMOUNT.get().floatValue();
        int cube = ClientConfig.CLOUD_CUBE.get();
        pending = CompletableFuture.supplyAsync(() -> {
            CloudSky.Sky built = m.build(side, time, now, wx, wz, reach, summer, dayTime, amount);
            return new Built(built, new CloudField(built, cube, reach));
        }, BUILDER);
    }

    /** A finished build, if one is ready. */
    private static CloudSky.Sky collect() {
        if (pending == null || !pending.isDone()) return null;
        Built built = null;
        try {
            built = pending.getNow(null);
        } catch (Exception e) {
            Equinox.LOGGER.warn("Equinox: a cloud build failed", e);
        }
        pending = null;
        if (built == null) return null;
        sky = built.sky;
        field = built.field;
        return built.sky;
    }

    // ---- inside a cloud: fog

    /** Client tick: how far inside a cloud the camera is (eased, so the fog comes and goes over about half a second). */
    public static void tick(ClientLevel level) {
        float target = 0;
        CloudField f = field;
        Weather.Side side = Weather.client();
        float height = level.effects().getCloudHeight();
        if (f != null && side != null && !Float.isNaN(height) && active(level) && ClientConfig.CLOUD_FOG.get() > 0) {
            net.minecraft.world.phys.Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            double[] now = side.field.offset(level.getGameTime());
            CloudSky.Sky s = f.sky();
            float[] d = f.density((float) (camera.x - now[0] - s.centerX()), (float) (camera.y - height),
                    (float) (camera.z - now[1] - s.centerZ()), new float[3]);
            // from the cloud's edge to a cube or two deep
            target = smooth(-1, 6, d[0]);
            if (d[0] > -1) insideStorm = d[1];
        }
        inside += (target - inside) * 0.25F;
        if (inside < 0.001F) inside = 0;
    }

    /** Game bus: fog closes in inside a cloud (not under water, lava or powder snow). */
    public static void onFog(net.neoforged.neoforge.client.event.ViewportEvent.RenderFog event) {
        if (inside <= 0 || event.getType() != net.minecraft.world.level.material.FogType.NONE) return;
        float deep = ClientConfig.CLOUD_FOG.get();
        float far = Math.min(event.getFarPlaneDistance(), Mth.lerp(inside, event.getFarPlaneDistance(), deep));
        float near = Math.min(event.getNearPlaneDistance(), Mth.lerp(inside, event.getNearPlaneDistance(), -deep * 0.25F));
        event.setFarPlaneDistance(far);
        event.setNearPlaneDistance(near);
        event.setCanceled(true);
    }

    /** Game bus: inside a cloud the fog takes the cloud's colour. */
    public static void onFogColor(net.neoforged.neoforge.client.event.ViewportEvent.ComputeFogColor event) {
        if (inside <= 0) return;
        float[] c = CloudExtras.colour(0.7F, insideStorm);
        event.setRed(Mth.lerp(inside, event.getRed(), c[0]));
        event.setGreen(Mth.lerp(inside, event.getGreen(), c[1]));
        event.setBlue(Mth.lerp(inside, event.getBlue(), c[2]));
    }

    /** Toward the sun (or the moon, at night), from the time of day: vanilla's sun goes over from east to west. */
    public static float[] sun(float timeOfDay) {
        float a = timeOfDay * Mth.TWO_PI;
        float x = -Mth.sin(a), y = Mth.cos(a);
        return y >= 0 ? new float[] {x, y, 0} : new float[] {-x, -y, 0};
    }

    /** A shader pack is in use (then the pack draws its own shadows and light). */
    private static boolean shaderPack() {
        if (shaderApi == null) {
            shaderApi = false;
            try {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                Object instance = api.getMethod("getInstance").invoke(null);
                packInUse = java.lang.invoke.MethodHandles.publicLookup()
                        .findVirtual(api, "isShaderPackInUse", java.lang.invoke.MethodType.methodType(boolean.class)).bindTo(instance);
                shaderApi = true;
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // no shader loader
            }
        }
        if (!shaderApi) return false;
        try {
            return (boolean) packInUse.invoke();
        } catch (Throwable e) {
            return false;
        }
    }

    /** Draws the clouds; called instead of vanilla's (same place, same render state). */
    public static void render(ClientLevel level, PoseStack poseStack, Matrix4f frustumMatrix, Matrix4f projectionMatrix,
                              float partialTick, double camX, double camY, double camZ) {
        float height = level.effects().getCloudHeight();
        if (Float.isNaN(height)) return;
        Minecraft mc = Minecraft.getInstance();
        Weather.Side side = Weather.client();
        long time = level.getGameTime();
        double[] now = side.field.offset(time), next = side.field.offset(time + 1);
        double offX = Mth.lerp(partialTick, now[0], next[0]), offZ = Mth.lerp(partialTick, now[1], next[1]);

        String settings = ClientConfig.CLOUD_CUBE.get() + "/" + ClientConfig.CLOUD_DETAIL.get() + "/" + ClientConfig.CLOUD_LIGHTING.get();
        if (!settings.equals(shownSettings)) {
            CloudGpu.clear();
            shownSettings = settings;
            if (sky != null) CloudGpu.setSky(sky);
        }
        CloudSky.Sky built = collect();
        if (built != null) CloudGpu.setSky(built);
        schedule(level, camX, camZ);
        CloudSky.Sky s = CloudGpu.sky();
        if (s == null) return;

        float timeOfDay = level.getTimeOfDay(partialTick);
        float[] sun = sun(timeOfDay);
        int reach = nearReach();
        boolean pack = shaderPack();
        // cubes grow and shrink with our shader; under a shader pack the pack's own cloud shader draws them (no growing)
        float animSeconds = pack || growing == null ? 0 : ClientConfig.CLOUD_ANIMATION.get().floatValue();
        CloudGpu.update(camX - offX, camZ - offZ, reach, ClientConfig.CLOUD_CUBE.get(), ring(), ClientConfig.CLOUD_UPDATES.get(),
                sun, ClientConfig.CLOUD_LIGHTING.get() == ClientConfig.CloudLighting.FULL, (int) (time / 2 & 1023), animSeconds);
        float animTime = (float) ((time / 2 & 1023) + ((time & 1) + partialTick) / 2);
        if (time - paletteTime >= 10 || time < paletteTime) {
            paletteTime = time;
            CloudExtras.palette(timeOfDay);
        }

        // ground shadows first (they darken the picture under the clouds), only without a shader pack
        if (ClientConfig.CLOUD_SHADOWS.get() && !Minecraft.useShaderTransparency() && !pack) {
            float[] toward = {-Mth.sin(timeOfDay * Mth.TWO_PI), Mth.cos(timeOfDay * Mth.TWO_PI), 0};
            float rainy = 1 - 0.6F * level.getRainLevel(partialTick);
            // underground (no sky where the camera is) the view isn't under the clouds
            int skyLight = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, net.minecraft.core.BlockPos.containing(camX, camY, camZ));
            CloudExtras.shadows(s, frustumMatrix, projectionMatrix, toward, (float) (height - camY), offX, offZ, camX, camZ,
                    SHADOW_STRENGTH * rainy * smooth(0.05F, 0.3F, toward[1]) * smooth(6, 14, skyLight));
        }

        FogRenderer.levelFogColor();
        float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
        FogShape fogShape = RenderSystem.getShaderFogShape();
        // inside a cloud the cloud's own cubes fade into the same fog as the world
        RenderSystem.setShaderFogStart(Mth.lerp(inside, reach * 0.55F, Math.min(fogStart, reach * 0.55F)));
        RenderSystem.setShaderFogEnd(Mth.lerp(inside, reach, Math.min(fogEnd, reach)));
        RenderSystem.setShaderFogShape(FogShape.CYLINDER);
        float glowUp = smooth(-0.05F, 0.2F, Mth.cos(timeOfDay * Mth.TWO_PI));
        float[] toSun = {-Mth.sin(timeOfDay * Mth.TWO_PI), Mth.cos(timeOfDay * Mth.TWO_PI), 0};
        ShaderInstance[] used = new ShaderInstance[1];
        CloudGpu.Tint tint = (x, y, z, cube) -> {
            if (used[0] == growing && growing != null) {
                growing.safeGetUniform("AnimTime").set(animTime);
                growing.safeGetUniform("AnimLength").set(Math.max(0.5F, animSeconds * 10));
                growing.safeGetUniform("Cube").set((float) cube);
            }
            // light through the clouds toward the sun: brighter looking toward it
            float len = Mth.sqrt(x * x + y * y + z * z);
            float facing = len < 1 ? 0 : Math.max(0, (x * toSun[0] + y * toSun[1] + z * toSun[2]) / len);
            float glow = 1 + 0.18F * glowUp * (float) Math.pow(facing, 6);
            RenderSystem.setShaderColor(glow, glow, glow * 0.97F, 1);
        };

        CloudStatus status = mc.options.getCloudsType();
        for (int pass = status == CloudStatus.FANCY ? 0 : 1; pass < 2; pass++) {
            RenderType type = pass == 0 ? RenderType.cloudsDepthOnly() : RenderType.clouds();
            type.setupRenderState();
            if (animSeconds > 0) RenderSystem.setShader(() -> growing);
            RenderSystem.setShaderTexture(0, CloudExtras.PALETTE);
            ShaderInstance shader = RenderSystem.getShader();
            used[0] = shader;
            if (shader != null) CloudGpu.draw(frustumMatrix, projectionMatrix, camX, camY, camZ, offX, offZ, height, shader, tint);
            type.clearRenderState();
        }
        RenderSystem.setShaderColor(1, 1, 1, 1);

        if (ClientConfig.DISTANT_RAIN.get())
            CloudExtras.rain(s, frustumMatrix, offX, offZ, camX, camY, camZ, height, level.getSeaLevel() - 2, 96, reach, timeOfDay);

        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);
        RenderSystem.setShaderFogShape(fogShape);
    }

    private static float smooth(float a, float b, float x) {
        float t = Mth.clamp((x - a) / (b - a), 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** Forget everything (leaving a world). */
    public static void clear() {
        CloudGpu.clear();
        CloudExtras.clear();
        pending = null;
        builtTime = Long.MIN_VALUE / 2;
        paletteTime = Long.MIN_VALUE / 2;
        builtReach = -1;
        model = null;
        sky = null;
        field = null;
        inside = 0;
        DhClouds.clear();
    }

    private CloudRenderer() {
    }
}
