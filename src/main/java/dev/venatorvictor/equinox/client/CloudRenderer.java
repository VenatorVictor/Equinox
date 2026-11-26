package dev.venatorvictor.equinox.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.venatorvictor.equinox.Equinox;
import dev.venatorvictor.equinox.climate.Climate;
import dev.venatorvictor.equinox.client.clouds.CloudMesher;
import dev.venatorvictor.equinox.client.clouds.CloudModel;
import dev.venatorvictor.equinox.client.dh.DhClouds;
import dev.venatorvictor.equinox.weather.Weather;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Our clouds (weather per region): the sky from CloudModel, drawn like vanilla's clouds, in vanilla's place and
 * render types, so shader packs treat them as clouds. Greys are in the texture (a strip from black to white, sampled
 * smoothly), because some shader packs keep the texture but drop the vertex colour; the vertex colour carries only
 * vanilla's face shading. The overall colour is vanilla's cloud colour for the time of day (the storm greys are in the
 * clouds themselves).
 * <p>
 * Cost: the sky is built on our own low-priority thread every REBUILD_TICKS or when the camera moves on. Far clouds are
 * bigger cells (levels of detail), equal neighbours are joined, and the sky is cut into chunks that keep their buffers
 * while unchanged, so only changed chunks go to the graphics card. The heap cubes (a few hundred faces) are remade
 * every CUBE_TICKS, blended between this build and the next, so they grow smoothly.
 * With Distant Horizons the sky goes to its box renderer instead (DhClouds).
 */
public final class CloudRenderer {
    private static final int CELL = CloudModel.CELL;
    private static final ResourceLocation GREYS = ResourceLocation.fromNamespaceAndPath(Equinox.MOD_ID, "clouds_grey");
    private static final ResourceLocation VANILLA = ResourceLocation.withDefaultNamespace("textures/environment/clouds.png");
    /** Ticks between sky builds, base cells the camera may move before one, ticks between heap cube remakes. */
    public static final int REBUILD_TICKS = 40;
    private static final int REBUILD_CELLS = 8, CUBE_TICKS = 2;

    private static final ExecutorService BUILDER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Equinox clouds");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    /** A chunk's buffer on the graphics card. */
    private record Shown(VertexBuffer buffer, double originX, double originZ, long outline, long hash) {
    }

    private static final Long2ObjectOpenHashMap<Shown> SHOWN = new Long2ObjectOpenHashMap<>();
    /** Hashes of the shown chunks, read by the build thread to skip unchanged chunks. */
    private static final Map<Long, CloudMesher.Shown> SHOWN_HASH = new ConcurrentHashMap<>();
    private static VertexBuffer cubeBuffer;
    private static boolean cubesEmpty = true, greysReady;
    private static long cubeTime = Long.MIN_VALUE;
    private static double cubeAnchorX, cubeAnchorZ;
    private static CompletableFuture<Built> pending;
    private static long builtTime = Long.MIN_VALUE;
    private static int builtVersion = -1, builtRadius = -1;
    private static boolean builtMesh;
    private static CloudModel model;
    /** The sky shown now. */
    private static volatile CloudModel.Sky sky;

    private record Built(CloudModel.Sky sky, List<CloudMesher.Chunk> chunks) {
    }

    /** Our clouds take over from vanilla's here (weather per region on, setting on, storms known). */
    public static boolean active(ClientLevel level) {
        return level != null && ClientConfig.STORM_CLOUDS.get() && Weather.on(level) && Weather.client() != null;
    }

    /** Reach in base cells. */
    public static int radius() {
        return Math.max(CloudModel.LEVEL_REACH, ClientConfig.CLOUD_DISTANCE.get() / CELL);
    }

    /** The model for the current storms (render thread: reads the vanilla cloud texture, resource packs too). */
    private static CloudModel model(Weather.Side side) {
        if (model == null || model.seed() != side.seed) {
            boolean[] cells = null;
            int size = 0;
            try (InputStream in = Minecraft.getInstance().getResourceManager().open(VANILLA); NativeImage image = NativeImage.read(in)) {
                size = Math.min(image.getWidth(), image.getHeight());
                cells = new boolean[size * size];
                for (int x = 0; x < size; x++)
                    for (int z = 0; z < size; z++) cells[x * size + z] = ((image.getPixelRGBA(x, z) >>> 24) & 0xFF) > 0;
            } catch (Exception e) {
                Equinox.LOGGER.warn("Equinox: couldn't read the vanilla cloud texture, starting from a clear sky", e);
            }
            model = new CloudModel(side.seed, cells, size);
        }
        return model;
    }

    /** Starts a background build when one is due. mesh: also make the chunk faces (the vanilla path). */
    public static void schedule(ClientLevel level, double camX, double camZ, boolean mesh) {
        Weather.Side side = Weather.client();
        long time = level.getGameTime();
        double[] now = side.field.offset(time);
        int camCellX = Mth.floor((camX - now[0]) / CELL), camCellZ = Mth.floor((camZ - now[1]) / CELL);
        int radius = radius();
        CloudModel.Sky shown = sky;
        boolean moved = shown == null || Math.max(Math.abs(camCellX - shown.centerX()), Math.abs(camCellZ - shown.centerZ())) > REBUILD_CELLS;
        if (pending != null || !(moved || time - builtTime >= REBUILD_TICKS || time < builtTime
                || builtVersion != side.grid.version() || builtRadius != radius || builtMesh != mesh)) return;
        builtTime = time;
        builtVersion = side.grid.version();
        builtRadius = radius;
        builtMesh = mesh;
        CloudModel m = model(side);
        float summer = Mth.clamp(Climate.change(new float[] {0.5F, 1F, 0.5F, 0F}, 0), 0, 1);
        long dayTime = Math.floorMod(level.getDayTime(), 24000L);
        float day = dayTime < 12500 ? Mth.clamp(Math.min(dayTime + 500, 12500 - dayTime) / 1500F, 0, 1) : 0;
        pending = CompletableFuture.supplyAsync(() -> {
            CloudModel.Sky built = m.build(side, time, now, camCellX, camCellZ, radius, REBUILD_TICKS, summer, day);
            return new Built(built, mesh ? CloudMesher.chunks(built, SHOWN_HASH) : null);
        }, BUILDER);
    }

    /** A finished build, if one is ready. */
    private static Built collect() {
        if (pending == null || !pending.isDone()) return null;
        Built built = pending.getNow(null);
        pending = null;
        if (built != null) sky = built.sky;
        return built;
    }

    /** For the box renderer: a newly finished sky, or null. */
    public static CloudModel.Sky pollSky() {
        Built built = collect();
        if (built == null) return null;
        if (built.chunks != null) for (CloudMesher.Chunk chunk : built.chunks) if (chunk.bytes() != null) chunk.bytes().close();
        return built.sky;
    }

    /** Vanilla's cloud colour for the time of day only (no rain: the storm greys are in the clouds). */
    public static float[] dayColor(ClientLevel level, float partialTick) {
        float f = level.getTimeOfDay(partialTick);
        float light = Mth.clamp(Mth.cos(f * Mth.TWO_PI) * 2 + 0.5F, 0, 1);
        return new float[] {light * 0.9F + 0.1F, light * 0.9F + 0.1F, light * 0.85F + 0.15F};
    }

    /** Draws the clouds; called instead of vanilla's (same place, same render state). */
    public static void render(ClientLevel level, PoseStack poseStack, Matrix4f frustumMatrix, Matrix4f projectionMatrix,
                              float partialTick, double camX, double camY, double camZ) {
        if (DhClouds.drawing()) return; // Distant Horizons draws them
        float height = level.effects().getCloudHeight();
        if (Float.isNaN(height)) return;
        Minecraft mc = Minecraft.getInstance();
        Weather.Side side = Weather.client();
        long time = level.getGameTime();
        double[] now = side.field.offset(time), next = side.field.offset(time + 1);
        double offX = Mth.lerp(partialTick, now[0], next[0]), offZ = Mth.lerp(partialTick, now[1], next[1]);
        CloudStatus status = mc.options.getCloudsType();

        Built built = collect();
        if (built != null && built.chunks != null) upload(built.chunks);
        schedule(level, camX, camZ, true);
        CloudModel.Sky s = sky;
        if (s == null) return;
        ensureGreys();
        if (time - cubeTime >= CUBE_TICKS || time < cubeTime || built != null) remakeCubes(s, time, partialTick);

        float[] color = dayColor(level, partialTick);
        FogRenderer.levelFogColor();
        float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
        FogShape fogShape = RenderSystem.getShaderFogShape();
        float reach = radius() * CELL;
        RenderSystem.setShaderFogStart(reach * 0.55F);
        RenderSystem.setShaderFogEnd(reach);
        RenderSystem.setShaderFogShape(FogShape.CYLINDER);
        RenderSystem.setShaderColor(color[0], color[1], color[2], 1);

        for (int pass = status == CloudStatus.FANCY ? 0 : 1; pass < 2; pass++) {
            RenderType type = pass == 0 ? RenderType.cloudsDepthOnly() : RenderType.clouds();
            type.setupRenderState();
            RenderSystem.setShaderTexture(0, GREYS);
            ShaderInstance shader = RenderSystem.getShader();
            for (Shown shown : SHOWN.values()) {
                poseStack.pushPose();
                poseStack.mulPose(frustumMatrix);
                poseStack.translate(shown.originX + offX - camX, height - camY, shown.originZ + offZ - camZ);
                shown.buffer.bind();
                shown.buffer.drawWithShader(poseStack.last().pose(), projectionMatrix, shader);
                poseStack.popPose();
            }
            if (cubeBuffer != null && !cubesEmpty) {
                poseStack.pushPose();
                poseStack.mulPose(frustumMatrix);
                poseStack.translate(cubeAnchorX + offX - camX, height - camY, cubeAnchorZ + offZ - camZ);
                cubeBuffer.bind();
                cubeBuffer.drawWithShader(poseStack.last().pose(), projectionMatrix, shader);
                poseStack.popPose();
            }
            type.clearRenderState();
        }
        VertexBuffer.unbind();

        RenderSystem.setShaderColor(1, 1, 1, 1);
        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);
        RenderSystem.setShaderFogShape(fogShape);
    }

    /** Heap cubes blended between this build and the next (they grow a block at a time). */
    private static void remakeCubes(CloudModel.Sky s, long time, float partialTick) {
        cubeTime = time;
        float f = Mth.clamp((time + partialTick - s.time()) / (float) s.next(), 0, 1);
        cubeAnchorX = s.centerX() * (double) CELL;
        cubeAnchorZ = s.centerZ() * (double) CELL;
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(Math.max(4096, s.cubes().size() * 48 * 28))) {
            BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
            CloudMesher.cubes(s, f, cubeAnchorX, cubeAnchorZ, builder);
            MeshData mesh = builder.build();
            cubesEmpty = mesh == null;
            if (mesh != null) {
                if (cubeBuffer == null) cubeBuffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
                cubeBuffer.bind();
                cubeBuffer.upload(mesh);
                VertexBuffer.unbind();
            }
        }
    }

    /** New chunks uploaded, unchanged ones kept, gone ones dropped. */
    private static void upload(List<CloudMesher.Chunk> chunks) {
        Long2ObjectOpenHashMap<Shown> keep = new Long2ObjectOpenHashMap<>();
        for (CloudMesher.Chunk chunk : chunks) {
            Shown old = SHOWN.remove(chunk.key());
            if (chunk.mesh() == null) {
                if (old != null) keep.put(chunk.key(), new Shown(old.buffer, old.originX, old.originZ, chunk.outline(), old.hash));
                continue;
            }
            VertexBuffer buffer = old != null ? old.buffer : new VertexBuffer(VertexBuffer.Usage.STATIC);
            buffer.bind();
            buffer.upload(chunk.mesh());
            VertexBuffer.unbind();
            chunk.bytes().close();
            keep.put(chunk.key(), new Shown(buffer, chunk.originX(), chunk.originZ(), chunk.outline(), chunk.hash()));
        }
        for (Shown gone : SHOWN.values()) gone.buffer.close();
        SHOWN.clear();
        SHOWN.putAll(keep);
        SHOWN_HASH.clear();
        for (Long2ObjectOpenHashMap.Entry<Shown> e : SHOWN.long2ObjectEntrySet())
            SHOWN_HASH.put(e.getLongKey(), new CloudMesher.Shown(e.getValue().outline, e.getValue().hash));
    }

    /** The grey strip: black to white, sampled smoothly. */
    private static void ensureGreys() {
        if (greysReady) return;
        NativeImage image = new NativeImage(CloudModel.GREYS_TEXELS, 1, false);
        for (int i = 0; i < CloudModel.GREYS_TEXELS; i++) {
            int g = Math.round(255F * i / (CloudModel.GREYS_TEXELS - 1));
            image.setPixelRGBA(i, 0, 0xFF000000 | g << 16 | g << 8 | g);
        }
        DynamicTexture texture = new DynamicTexture(image);
        Minecraft.getInstance().getTextureManager().register(GREYS, texture);
        texture.setFilter(true, false);
        greysReady = true;
    }

    /** Forget everything (leaving a world). */
    public static void clear() {
        for (Shown shown : SHOWN.values()) shown.buffer.close();
        SHOWN.clear();
        SHOWN_HASH.clear();
        if (cubeBuffer != null) cubeBuffer.close();
        cubeBuffer = null;
        cubesEmpty = true;
        pending = null;
        builtTime = Long.MIN_VALUE;
        cubeTime = Long.MIN_VALUE;
        model = null;
        sky = null;
        DhClouds.clear();
    }

    private CloudRenderer() {
    }
}
