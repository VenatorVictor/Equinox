package dev.venatorvictor.equinox.client;

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
import dev.venatorvictor.equinox.weather.ClimateGrid;
import dev.venatorvictor.equinox.weather.Weather;
import net.minecraft.Util;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

/**
 * Our own clouds (weather per region), drawn from the same storms as the rain, so clouds are where it rains. Each
 * cloud cell gets the sky's look there (Weather.Side.cloudiness, 0..2) and its cloud is a smooth mix by it, never a
 * switch: small white puffy clouds in fair weather (0), joining into a flatter grey layer as a storm comes (1, the rain
 * line), then thicker and darker with a lower base inside it, tallest and darkest at the thunder core (2).
 * Blocky 3D cells like vanilla's (12 blocks). The mesh is made in the background in "wind space" (the storm map before
 * the wind moved it) and moved with the wind every frame, so clouds drift with the storms; it is made again when the
 * camera moves on, every few seconds (storms change shape slowly) and when climate tiles arrive.
 */
public final class CloudRenderer {
    private static final boolean ENABLED = false;
    private static final int CELL = 12;
    private static final ResourceLocation WHITE = ResourceLocation.fromNamespaceAndPath(Equinox.MOD_ID, "textures/environment/cloud_white.png");
    /** Share of the sky with fair-weather clouds (far from storms, normal rain share), and in dry places. */
    private static final float FAIR_COVER = 0.3F, DRY_COVER = 0.4F;
    /** Puffs: noise scales (blocks) of the two octaves. */
    private static final double PUFF = 60, PUFF_SMALL = 24;
    /** Ticks between remakes, and cells the camera may move before one. */
    private static final int REBUILD_TICKS = 100, REBUILD_CELLS = 8;

    private static VertexBuffer buffer;
    private static boolean empty = true;
    /** The mesh shown: its origin cell (wind space) and the game time it was made for. */
    private static int shownX, shownZ;
    private static CompletableFuture<Built> pending;
    private static long builtTime = Long.MIN_VALUE;
    private static int builtVersion = -1, builtRadius = -1;
    private static CloudStatus builtStatus;
    private static Puffs puffs;

    private record Built(ByteBufferBuilder bytes, MeshData mesh, int originX, int originZ) {
    }

    /** Puff noise for a seed, turned into an even 0..1 value. */
    private static final class Puffs {
        final long seed;
        final SimplexNoise big, small;
        final double[] quantiles = new double[257];

        Puffs(long seed) {
            this.seed = seed;
            big = new SimplexNoise(RandomSource.create(seed ^ 0x6A09E667F3BCC909L));
            small = new SimplexNoise(RandomSource.create(seed ^ 0xBB67AE8584CAA73BL));
            RandomSource random = RandomSource.create(seed ^ 0x3C6EF372FE94F82BL);
            double[] values = new double[8192];
            for (int i = 0; i < values.length; i++) values[i] = raw(random.nextDouble() * 1e5, random.nextDouble() * 1e5);
            Arrays.sort(values);
            for (int i = 0; i <= 256; i++) quantiles[i] = values[(int) ((long) i * (values.length - 1) / 256)];
        }

        double raw(double x, double z) {
            return 0.65 * big.getValue(x / PUFF, z / PUFF) + 0.35 * small.getValue(x / PUFF_SMALL, z / PUFF_SMALL);
        }

        float value(double x, double z) {
            double n = raw(x, z);
            if (n <= quantiles[0]) return 0;
            if (n >= quantiles[256]) return 1;
            int lo = 0, hi = 256;
            while (hi - lo > 1) {
                int mid = (lo + hi) >>> 1;
                if (quantiles[mid] <= n) lo = mid;
                else hi = mid;
            }
            double span = quantiles[hi] - quantiles[lo];
            return (float) ((lo + (span <= 0 ? 0 : (n - quantiles[lo]) / span)) / 256);
        }
    }

    /** Our clouds take over from vanilla's here (weather per region on, setting on, storms known). */
    public static boolean active(ClientLevel level) {
        // Off (vanilla clouds) until a new look is picked from the cloud previews (user 2026-11-26: these were ugly
        // and heavy, 200 -> 6 fps).
        if (!ENABLED) return false;
        return level != null && ClientConfig.STORM_CLOUDS.get() && Weather.on(level) && Weather.client() != null;
    }

    /** Draws the clouds; called instead of vanilla's (same place, same render state). */
    public static void render(ClientLevel level, PoseStack poseStack, Matrix4f frustumMatrix, Matrix4f projectionMatrix,
                              float partialTick, double camX, double camY, double camZ) {
        float height = level.effects().getCloudHeight();
        if (Float.isNaN(height)) return;
        Minecraft mc = Minecraft.getInstance();
        Weather.Side side = Weather.client();
        if (puffs == null || puffs.seed != side.seed) puffs = new Puffs(side.seed);
        long time = level.getGameTime();
        double[] now = side.field.offset(time), next = side.field.offset(time + 1);
        double offX = Mth.lerp(partialTick, now[0], next[0]), offZ = Mth.lerp(partialTick, now[1], next[1]);
        int radius = Math.max(8, ClientConfig.CLOUD_DISTANCE.get() / CELL);
        CloudStatus status = mc.options.getCloudsType();

        // The camera's cell in wind space.
        int camCellX = Mth.floor((camX - offX) / CELL), camCellZ = Mth.floor((camZ - offZ) / CELL);
        if (pending != null && pending.isDone()) {
            Built built = pending.getNow(null);
            pending = null;
            if (built != null) upload(built);
        }
        boolean moved = Math.max(Math.abs(camCellX - shownX), Math.abs(camCellZ - shownZ)) > REBUILD_CELLS;
        if (pending == null && (buffer == null || moved || time - builtTime >= REBUILD_TICKS
                || builtVersion != side.grid.version() || builtRadius != radius || builtStatus != status)) {
            builtTime = time;
            builtVersion = side.grid.version();
            builtRadius = radius;
            builtStatus = status;
            float h = height;
            int ox = camCellX, oz = camCellZ;
            Puffs p = puffs;
            double[] offset = now;
            pending = CompletableFuture.supplyAsync(() -> build(side, p, time, offset, ox, oz, radius, h), Util.backgroundExecutor());
            if (buffer == null) return;
        }
        if (buffer == null || empty) return;

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
            RenderSystem.setShaderTexture(0, WHITE);
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

    private static void upload(Built built) {
        if (buffer == null) buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        shownX = built.originX;
        shownZ = built.originZ;
        empty = built.mesh == null;
        if (built.mesh != null) {
            buffer.bind();
            buffer.upload(built.mesh);
            VertexBuffer.unbind();
        }
        built.bytes.close();
    }

    /** Forget the mesh (leaving a world). */
    public static void clear() {
        if (buffer != null) buffer.close();
        buffer = null;
        empty = true;
        pending = null;
        builtTime = Long.MIN_VALUE;
        puffs = null;
    }

    private static float smooth(float t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    /** Background: the cells around the origin (wind space), then their faces. Heights relative to the cloud height. */
    private static Built build(Weather.Side side, Puffs puffs, long time, double[] offset, int originX, int originZ, int radius, float height) {
        int n = 2 * radius + 1;
        float[] bottom = new float[n * n], top = new float[n * n], grey = new float[n * n], alpha = new float[n * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int index = i * n + j;
                double wx = (originX + i - radius + 0.5) * CELL, wz = (originZ + j - radius + 0.5) * CELL;
                double x = wx + offset[0], z = wz + offset[1];
                float w = side.cloudiness(x, z, time, offset);
                float approach = smooth(w), storm = Math.max(0, w - 1);
                // Fair weather: fewer clouds where it rarely rains (deserts).
                float share = side.grid.share(x, z);
                float fair = FAIR_COVER * (DRY_COVER + (1 - DRY_COVER) * Math.min(1, share / ClimateGrid.VANILLA_SHARE));
                float cover = fair + (1 - fair) * approach;
                float p = puffs.value(wx, wz);
                if (p <= 1 - cover) continue;
                float e = cover >= 1 ? p : (p - (1 - cover)) / cover; // how deep inside its puff (0..1)
                float thick;
                if (w <= 1) thick = Mth.lerp(approach, 4 + 10 * e, 6 + 4 * e);
                else thick = Mth.lerp(storm, 6 + 4 * e, 14 + 50 * (0.5F + 0.5F * e));
                float drop = 16 * storm;
                float b = -Math.round(drop / 2) * 2F;
                bottom[index] = b;
                top[index] = b + Math.max(2, Math.round(thick / 2) * 2F);
                grey[index] = w <= 1 ? Mth.lerp(approach, 1F, 0.82F) : Mth.lerp(storm, 0.82F, 0.38F);
                alpha[index] = Mth.lerp(Math.min(1, w / 1.5F), 0.8F, 0.95F);
            }
        }
        ByteBufferBuilder bytes = new ByteBufferBuilder(1 << 20);
        BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int index = i * n + j;
                float b = bottom[index], t = top[index];
                if (t <= b) continue;
                float x0 = (i - radius) * CELL, x1 = x0 + CELL, z0 = (j - radius) * CELL, z1 = z0 + CELL;
                float g = grey[index], a = alpha[index];
                quad(builder, x0, t, z0, x0, t, z1, x1, t, z1, x1, t, z0, g, a, 0, 1, 0);
                quad(builder, x0, b, z0, x1, b, z0, x1, b, z1, x0, b, z1, g * 0.7F, a, 0, -1, 0);
                // Sides: only where this cloud reaches past its neighbour (above its top or below its bottom).
                side(builder, bottom, top, n, i - 1, j, b, t, g * 0.9F, a, x0, z0, x0, z1, -1, 0);
                side(builder, bottom, top, n, i + 1, j, b, t, g * 0.9F, a, x1, z1, x1, z0, 1, 0);
                side(builder, bottom, top, n, i, j - 1, b, t, g * 0.8F, a, x1, z0, x0, z0, 0, -1);
                side(builder, bottom, top, n, i, j + 1, b, t, g * 0.8F, a, x0, z1, x1, z1, 0, 1);
            }
        }
        MeshData mesh = builder.build();
        return new Built(bytes, mesh, originX, originZ);
    }

    private static void side(BufferBuilder builder, float[] bottom, float[] top, int n, int ni, int nj, float b, float t,
                             float g, float a, float xa, float za, float xb, float zb, float nx, float nz) {
        boolean inside = ni >= 0 && nj >= 0 && ni < n && nj < n;
        float nb = inside ? bottom[ni * n + nj] : 0, nt = inside ? top[ni * n + nj] : 0;
        if (!inside || nt <= nb) {
            quad(builder, xa, b, za, xb, b, zb, xb, t, zb, xa, t, za, g, a, nx, 0, nz);
            return;
        }
        if (t > nt) quad(builder, xa, Math.max(b, nt), za, xb, Math.max(b, nt), zb, xb, t, zb, xa, t, za, g, a, nx, 0, nz);
        if (b < nb) quad(builder, xa, b, za, xb, b, zb, xb, Math.min(t, nb), zb, xa, Math.min(t, nb), za, g, a, nx, 0, nz);
    }

    private static void quad(BufferBuilder builder, float x0, float y0, float z0, float x1, float y1, float z1,
                             float x2, float y2, float z2, float x3, float y3, float z3, float g, float a, float nx, float ny, float nz) {
        builder.addVertex(x0, y0, z0).setUv(0.5F, 0.5F).setColor(g, g, g, a).setNormal(nx, ny, nz);
        builder.addVertex(x1, y1, z1).setUv(0.5F, 0.5F).setColor(g, g, g, a).setNormal(nx, ny, nz);
        builder.addVertex(x2, y2, z2).setUv(0.5F, 0.5F).setColor(g, g, g, a).setNormal(nx, ny, nz);
        builder.addVertex(x3, y3, z3).setUv(0.5F, 0.5F).setColor(g, g, g, a).setNormal(nx, ny, nz);
    }

    private CloudRenderer() {
    }
}
