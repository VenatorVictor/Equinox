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
import dev.venatorvictor.equinox.client.clouds.CloudModel;
import dev.venatorvictor.equinox.client.dh.DhClouds;
import dev.venatorvictor.equinox.weather.Weather;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Our clouds (weather per region): vanilla's cloud layer that grows and darkens where storms are, with heap groups,
 * towers and the other cloud kinds (CloudModel). Drawn like vanilla's clouds, in vanilla's place and render types, so
 * shader packs treat them as clouds. The darkening is in the texture (a strip of greys, each cloud's UV picks one),
 * because some shader packs keep the texture but drop the vertex colour; the vertex colour only carries vanilla's
 * face shading.
 * <p>
 * Performance: the sky is built on our own low-priority thread every REBUILD_TICKS (storms grow slowly) or when the
 * camera moves on, as one static mesh moved with the wind each frame; storms are sampled on a coarse lattice. With
 * Distant Horizons the same sky goes to its box renderer instead (DhClouds), so clouds sit right behind LOD terrain.
 */
public final class CloudRenderer {
    private static final int CELL = CloudModel.CELL;
    private static final ResourceLocation GREYS = ResourceLocation.fromNamespaceAndPath(Equinox.MOD_ID, "clouds_grey");
    private static final ResourceLocation VANILLA = ResourceLocation.withDefaultNamespace("textures/environment/clouds.png");
    /** Ticks between rebuilds, and cells the camera may move before one. */
    private static final int REBUILD_TICKS = 40, REBUILD_CELLS = 8;

    private static final ExecutorService BUILDER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Equinox clouds");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    private static VertexBuffer buffer;
    private static boolean empty = true, greysReady;
    private static int shownX, shownZ;
    private static CompletableFuture<Built> pending;
    private static long builtTime = Long.MIN_VALUE;
    private static int builtVersion = -1, builtRadius = -1;
    private static CloudStatus builtStatus;
    private static boolean builtMesh;
    private static CloudModel model;
    /** The last built sky. */
    private static volatile CloudModel.Sky lastSky;

    private record Built(CloudModel.Sky sky, ByteBufferBuilder bytes, MeshData mesh) {
    }

    /** Our clouds take over from vanilla's here (weather per region on, setting on, storms known). */
    public static boolean active(ClientLevel level) {
        return level != null && ClientConfig.STORM_CLOUDS.get() && Weather.on(level) && Weather.client() != null;
    }

    public static int radius() {
        return Math.max(16, ClientConfig.CLOUD_DISTANCE.get() / CELL);
    }

    /** The model for the current storms (made on the render thread: reads the vanilla cloud texture, resource packs too). */
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

    /** Starts a background build when one is due (render or client thread). mesh: also make the vanilla-path mesh. */
    public static void schedule(ClientLevel level, double camX, double camZ, int radius, boolean mesh, CloudStatus status) {
        Weather.Side side = Weather.client();
        long time = level.getGameTime();
        double[] now = side.field.offset(time);
        int camCellX = Mth.floor((camX - now[0]) / CELL), camCellZ = Mth.floor((camZ - now[1]) / CELL);
        CloudModel.Sky shown = lastSky;
        boolean moved = shown == null || Math.max(Math.abs(camCellX - shown.originX()), Math.abs(camCellZ - shown.originZ())) > REBUILD_CELLS;
        if (pending != null || !(moved || time - builtTime >= REBUILD_TICKS || time < builtTime || builtVersion != side.grid.version()
                || builtRadius != radius || builtStatus != status || builtMesh != mesh)) return;
        builtTime = time;
        builtVersion = side.grid.version();
        builtRadius = radius;
        builtStatus = status;
        builtMesh = mesh;
        CloudModel m = model(side);
        float summer = Mth.clamp(Climate.change(new float[] {0.5F, 1F, 0.5F, 0F}, 0), 0, 1);
        long dayTime = Math.floorMod(level.getDayTime(), 24000L);
        float day = dayTime < 12500 ? Mth.clamp(Math.min(dayTime + 500, 12500 - dayTime) / 1500F, 0, 1) : 0;
        float camGrey = CloudModel.cameraGrey(level.getRainLevel(1), level.getThunderLevel(1));
        pending = CompletableFuture.supplyAsync(() -> {
            CloudModel.Sky sky = m.build(side, time, now, camCellX, camCellZ, radius, summer, day, camGrey);
            return mesh ? mesh(sky) : new Built(sky, null, null);
        }, BUILDER);
    }

    /** A finished build, if one is ready (its sky becomes the last sky). */
    private static Built collect() {
        if (pending == null || !pending.isDone()) return null;
        Built built = pending.getNow(null);
        pending = null;
        if (built != null) lastSky = built.sky;
        return built;
    }

    /** For the box renderer: a newly finished sky, or null. */
    public static CloudModel.Sky pollSky() {
        Built built = collect();
        if (built == null) return null;
        if (built.bytes != null) built.bytes.close();
        return built.sky;
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
        int radius = radius();
        CloudStatus status = mc.options.getCloudsType();

        Built built = collect();
        if (built != null) upload(built);
        schedule(level, camX, camZ, radius, true, status);
        if (buffer == null || empty) return;
        ensureGreys();

        Vec3 color = level.getCloudColor(partialTick);
        FogRenderer.levelFogColor();
        float fogStart = RenderSystem.getShaderFogStart(), fogEnd = RenderSystem.getShaderFogEnd();
        FogShape fogShape = RenderSystem.getShaderFogShape();
        float reach = radius * CELL;
        RenderSystem.setShaderFogStart(reach * 0.55F);
        RenderSystem.setShaderFogEnd(reach);
        RenderSystem.setShaderFogShape(FogShape.CYLINDER);
        RenderSystem.setShaderColor((float) color.x, (float) color.y, (float) color.z, 1);

        poseStack.pushPose();
        poseStack.mulPose(frustumMatrix);
        poseStack.translate(shownX * (double) CELL + offX - camX, height - camY, shownZ * (double) CELL + offZ - camZ);
        buffer.bind();
        for (int pass = status == CloudStatus.FANCY ? 0 : 1; pass < 2; pass++) {
            RenderType type = pass == 0 ? RenderType.cloudsDepthOnly() : RenderType.clouds();
            type.setupRenderState();
            RenderSystem.setShaderTexture(0, GREYS);
            ShaderInstance shader = RenderSystem.getShader();
            buffer.drawWithShader(poseStack.last().pose(), projectionMatrix, shader);
            type.clearRenderState();
        }
        VertexBuffer.unbind();
        poseStack.popPose();

        RenderSystem.setShaderColor(1, 1, 1, 1);
        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);
        RenderSystem.setShaderFogShape(fogShape);
    }

    /** The grey strip: GREYS steps from black to white. */
    private static void ensureGreys() {
        if (greysReady) return;
        NativeImage image = new NativeImage(CloudModel.GREYS, 1, false);
        for (int i = 0; i < CloudModel.GREYS; i++) {
            int g = Math.round(255F * i / (CloudModel.GREYS - 1));
            image.setPixelRGBA(i, 0, 0xFF000000 | g << 16 | g << 8 | g);
        }
        Minecraft.getInstance().getTextureManager().register(GREYS, new DynamicTexture(image));
        greysReady = true;
    }

    private static void upload(Built built) {
        if (built.mesh == null && built.bytes == null) return; // a box-renderer build
        if (buffer == null) buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        shownX = built.sky.originX();
        shownZ = built.sky.originZ();
        empty = built.mesh == null;
        if (built.mesh != null) {
            buffer.bind();
            buffer.upload(built.mesh);
            VertexBuffer.unbind();
        }
        built.bytes.close();
    }

    /** Forget everything (leaving a world). */
    public static void clear() {
        if (buffer != null) buffer.close();
        buffer = null;
        empty = true;
        pending = null;
        builtTime = Long.MIN_VALUE;
        model = null;
        lastSky = null;
        DhClouds.clear();
    }

    /** Background: the faces of every layer. Heights relative to the cloud height, origin = the sky's origin cell. */
    private static Built mesh(CloudModel.Sky sky) {
        int n = sky.n(), radius = sky.radius();
        ByteBufferBuilder bytes = new ByteBufferBuilder(1 << 20);
        BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
        for (CloudModel.Layer layer : sky.layers()) {
            float[] bottom = layer.bottom, top = layer.top;
            float a = layer.alpha;
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    int index = i * n + j;
                    float b = bottom[index], t = top[index];
                    if (t <= b) continue;
                    float u = ((layer.grey[index] & 0xFF) + 0.5F) / CloudModel.GREYS;
                    float x0 = (i - radius) * CELL, x1 = x0 + CELL, z0 = (j - radius) * CELL, z1 = z0 + CELL;
                    quad(builder, x0, t, z0, x0, t, z1, x1, t, z1, x1, t, z0, u, 1F, a, 0, 1, 0);
                    quad(builder, x0, b, z0, x1, b, z0, x1, b, z1, x0, b, z1, u, 0.7F, a, 0, -1, 0);
                    // Sides only where this cloud reaches past its neighbour (vanilla's shading per side).
                    side(builder, bottom, top, n, i - 1, j, b, t, u, 0.9F, a, x0, z0, x0, z1, -1, 0);
                    side(builder, bottom, top, n, i + 1, j, b, t, u, 0.9F, a, x1, z1, x1, z0, 1, 0);
                    side(builder, bottom, top, n, i, j - 1, b, t, u, 0.8F, a, x1, z0, x0, z0, 0, -1);
                    side(builder, bottom, top, n, i, j + 1, b, t, u, 0.8F, a, x0, z1, x1, z1, 0, 1);
                }
            }
        }
        MeshData mesh = builder.build();
        return new Built(sky, bytes, mesh);
    }

    private static void side(BufferBuilder builder, float[] bottom, float[] top, int n, int ni, int nj, float b, float t,
                             float u, float shade, float a, float xa, float za, float xb, float zb, float nx, float nz) {
        boolean inside = ni >= 0 && nj >= 0 && ni < n && nj < n;
        float nb = inside ? bottom[ni * n + nj] : 0, nt = inside ? top[ni * n + nj] : 0;
        if (!inside || nt <= nb) {
            quad(builder, xa, b, za, xb, b, zb, xb, t, zb, xa, t, za, u, shade, a, nx, 0, nz);
            return;
        }
        if (t > nt) quad(builder, xa, Math.max(b, nt), za, xb, Math.max(b, nt), zb, xb, t, zb, xa, t, za, u, shade, a, nx, 0, nz);
        if (b < nb) quad(builder, xa, b, za, xb, b, zb, xb, Math.min(t, nb), zb, xa, Math.min(t, nb), za, u, shade, a, nx, 0, nz);
    }

    private static void quad(BufferBuilder builder, float x0, float y0, float z0, float x1, float y1, float z1,
                             float x2, float y2, float z2, float x3, float y3, float z3, float u, float shade, float a,
                             float nx, float ny, float nz) {
        builder.addVertex(x0, y0, z0).setUv(u, 0.5F).setColor(shade, shade, shade, a).setNormal(nx, ny, nz);
        builder.addVertex(x1, y1, z1).setUv(u, 0.5F).setColor(shade, shade, shade, a).setNormal(nx, ny, nz);
        builder.addVertex(x2, y2, z2).setUv(u, 0.5F).setColor(shade, shade, shade, a).setNormal(nx, ny, nz);
        builder.addVertex(x3, y3, z3).setUv(u, 0.5F).setColor(shade, shade, shade, a).setNormal(nx, ny, nz);
    }

    private CloudRenderer() {
    }
}
