package dev.venatorvictor.equinox.client.clouds;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a sky (CloudModel) into faces. The cell layers are cut into chunks (CHUNK cells of a level, fixed in wind
 * space); in each chunk equal neighbouring cells are joined into boxes, and walls are drawn only where a box reaches
 * past its neighbour. A chunk whose faces are the same as last time keeps its old buffer, so only changed chunks are
 * sent to the graphics card. Greys are given per corner (the texture strip is sampled smoothly between them), heap
 * bases darker than their tops. Vanilla's face shading goes in the vertex colour.
 */
public final class CloudMesher {
    public static final int CHUNK = 16;
    /** Grey steps kept in the faces. */
    private static final int GREY_STEPS = 32;
    /**
     * A chunk is remade on its own turn, once every ROUND builds (staggered by chunk), unless its outline changed (the
     * camera moved on): storms change the sky slowly, and this way only a few chunks go to the graphics card per build.
     */
    public static final int ROUND = 8;
    private static final int FLOATS = 9; // x y z u shade alpha nx ny nz

    /** One chunk's faces: key, origin (wind-space blocks), outline, hash; mesh null when unchanged (keep the old one). */
    public record Chunk(long key, double originX, double originZ, long outline, long hash, MeshData mesh, ByteBufferBuilder bytes) {
    }

    /** What is shown of a chunk: its outline (which cells it covers) and its faces' hash. */
    public record Shown(long outline, long hash) {
    }

    /** A box for renderers that take boxes: wind-space blocks, heights above the cloud height, grey, alpha. */
    public record Box(double x0, double z0, double x1, double z1, float bottom, float top, float grey, float alpha) {
    }

    public static long key(int level, int chunkX, int chunkZ) {
        return ((long) level << 60) ^ (((long) chunkX & 0x3FFFFFFFL) << 30) ^ ((long) chunkZ & 0x3FFFFFFFL);
    }

    private static float quantGrey(float g) {
        return Math.round(Mth.clamp(g, 0, 1) * GREY_STEPS) / (float) GREY_STEPS;
    }

    private static float layerGrey(CloudModel.Sky sky, int layer, double x, double z) {
        float c = sky.storms().at(x, z);
        if (layer == CloudModel.HIGH) c *= 0.5F;
        return quantGrey(CloudModel.grey(c));
    }

    // ---- cell chunks

    /**
     * All chunks of a sky. A shown chunk with the same outline that isn't due this build, or whose faces came out the
     * same, comes back without a mesh (keep the old one).
     */
    public static List<Chunk> chunks(CloudModel.Sky sky, Map<Long, Shown> shown) {
        long build = Math.floorDiv(sky.time(), sky.next());
        List<Chunk> out = new ArrayList<>();
        FloatList verts = new FloatList();
        for (CloudModel.Level level : sky.levels()) {
            int cx0 = Math.floorDiv(level.x0, CHUNK), cz0 = Math.floorDiv(level.z0, CHUNK);
            int cx1 = Math.floorDiv(level.x0 + level.n - 1, CHUNK), cz1 = Math.floorDiv(level.z0 + level.n - 1, CHUNK);
            for (int chunkX = cx0; chunkX <= cx1; chunkX++) {
                for (int chunkZ = cz0; chunkZ <= cz1; chunkZ++) {
                    verts.clear();
                    double originX = (double) chunkX * CHUNK * level.blocks(), originZ = (double) chunkZ * CHUNK * level.blocks();
                    int i0 = Math.max(0, chunkX * CHUNK - level.x0), i1 = Math.min(level.n, (chunkX + 1) * CHUNK - level.x0);
                    int j0 = Math.max(0, chunkZ * CHUNK - level.z0), j1 = Math.min(level.n, (chunkZ + 1) * CHUNK - level.z0);
                    long key = key(level.level, chunkX, chunkZ);
                    long outline = outline(level, i0, i1, j0, j1);
                    Shown old = shown.get(key);
                    boolean due = Math.floorMod(build + (key * 0x9E3779B97F4A7C15L >>> 40), ROUND) == 0;
                    if (old != null && old.outline() == outline && !due) {
                        out.add(new Chunk(key, originX, originZ, outline, old.hash(), null, null));
                        continue;
                    }
                    for (int layer = 0; layer < CloudModel.LAYERS; layer++)
                        chunkLayer(sky, level, layer, i0, i1, j0, j1, originX, originZ, verts);
                    if (verts.size == 0) continue;
                    long hash = verts.hash();
                    if (old != null && old.hash() == hash) {
                        out.add(new Chunk(key, originX, originZ, outline, hash, null, null));
                        continue;
                    }
                    ByteBufferBuilder bytes = new ByteBufferBuilder(Math.max(256, verts.size / FLOATS * 28));
                    BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
                    float[] v = verts.data;
                    for (int p = 0; p < verts.size; p += FLOATS)
                        builder.addVertex(v[p], v[p + 1], v[p + 2]).setUv(u(v[p + 3]), 0.5F)
                                .setColor(v[p + 4], v[p + 4], v[p + 4], v[p + 5]).setNormal(v[p + 6], v[p + 7], v[p + 8]);
                    out.add(new Chunk(key, originX, originZ, outline, hash, builder.build(), bytes));
                }
            }
        }
        return out;
    }

    /** Which cells of a chunk are in its level (clipped by the level's edge and the finer level's hole). */
    private static long outline(CloudModel.Level level, int i0, int i1, int j0, int j1) {
        long h = level.level;
        h = h * 31 + i0;
        h = h * 31 + i1;
        h = h * 31 + j0;
        h = h * 31 + j1;
        if (level.holeN > 0) {
            int hi0 = Math.max(i0, level.holeX0), hi1 = Math.min(i1, level.holeX0 + level.holeN);
            int hj0 = Math.max(j0, level.holeZ0), hj1 = Math.min(j1, level.holeZ0 + level.holeN);
            if (hi0 < hi1 && hj0 < hj1) {
                h = h * 31 + hi0;
                h = h * 31 + hi1;
                h = h * 31 + hj0;
                h = h * 31 + hj1;
            }
        }
        return h;
    }

    /** Texture coordinate of a grey in the strip (sampled smoothly, so greys blend between corners). */
    public static float u(float grey) {
        return (grey * (CloudModel.GREYS_TEXELS - 1) + 0.5F) / CloudModel.GREYS_TEXELS;
    }

    /** Equal neighbouring cells joined into rectangles (greedy), then their faces. */
    private static void chunkLayer(CloudModel.Sky sky, CloudModel.Level level, int layer, int i0, int i1, int j0, int j1,
                                   double originX, double originZ, FloatList out) {
        int n = level.n, w = i1 - i0, d = j1 - j0;
        if (w <= 0 || d <= 0) return;
        short[] bottom = level.bottom[layer], top = level.top[layer];
        boolean[] done = new boolean[w * d];
        float size = level.blocks(), alpha = CloudModel.ALPHA[layer];
        for (int a = 0; a < w; a++) {
            for (int b = 0; b < d; b++) {
                if (done[a * d + b]) continue;
                int i = i0 + a, j = j0 + b, index = i * n + j;
                if (level.inHole(i, j) || top[index] <= bottom[index]) continue;
                short bb = bottom[index], tt = top[index];
                int e = b + 1;
                while (e < d && same(level, layer, i, j0 + e, bb, tt) && !done[a * d + e]) e++;
                int f = a + 1;
                grow:
                while (f < w) {
                    for (int q = b; q < e; q++) if (done[f * d + q] || !same(level, layer, i0 + f, j0 + q, bb, tt)) break grow;
                    f++;
                }
                for (int p = a; p < f; p++) for (int q = b; q < e; q++) done[p * d + q] = true;
                // Rectangle cells [i, i0+f) x [j, j0+e).
                int ia = i, ib = i0 + f, ja = j, jb = j0 + e;
                float x0 = (float) ((level.x0 + ia) * (double) size - originX), x1 = (float) ((level.x0 + ib) * (double) size - originX);
                float z0 = (float) ((level.z0 + ja) * (double) size - originZ), z1 = (float) ((level.z0 + jb) * (double) size - originZ);
                double wx0 = originX + x0, wx1 = originX + x1, wz0 = originZ + z0, wz1 = originZ + z1;
                float g00 = layerGrey(sky, layer, wx0, wz0), g01 = layerGrey(sky, layer, wx0, wz1);
                float g10 = layerGrey(sky, layer, wx1, wz0), g11 = layerGrey(sky, layer, wx1, wz1);
                // top and bottom
                out.quad(x0, tt, z0, g00, x0, tt, z1, g01, x1, tt, z1, g11, x1, tt, z0, g10, 1F, alpha, 0, 1, 0);
                out.quad(x0, bb, z0, g00, x1, bb, z0, g10, x1, bb, z1, g11, x0, bb, z1, g01, 0.7F, alpha, 0, -1, 0);
                // walls: -x, +x along z; -z, +z along x
                wall(sky, level, layer, bb, tt, ia - 1, ja, jb, true, x0, z0, size, originX, originZ, -1, alpha, out);
                wall(sky, level, layer, bb, tt, ib, ja, jb, true, x1, z0, size, originX, originZ, 1, alpha, out);
                wall(sky, level, layer, bb, tt, ja - 1, ia, ib, false, z0, x0, size, originX, originZ, -1, alpha, out);
                wall(sky, level, layer, bb, tt, jb, ia, ib, false, z1, x0, size, originX, originZ, 1, alpha, out);
            }
        }
    }

    private static boolean same(CloudModel.Level level, int layer, int i, int j, short b, short t) {
        if (level.inHole(i, j)) return false;
        int index = i * level.n + j;
        return level.bottom[layer][index] == b && level.top[layer][index] == t;
    }

    /**
     * The wall on one side of a rectangle: along its edge cell by cell, the part of [b, t] its neighbour doesn't cover;
     * equal neighbouring pieces joined. xAxis: the wall faces x (neighbour column `across`, running along z from
     * `from` to `to`); else it faces z (neighbour row `across`, running along x). fixed: the wall's own coordinate;
     * start: where the run starts (local blocks).
     */
    private static void wall(CloudModel.Sky sky, CloudModel.Level level, int layer, short b, short t, int across,
                             int from, int to, boolean xAxis, float fixed, float start, float size, double originX,
                             double originZ, int dir, float alpha, FloatList out) {
        int n = level.n;
        float shade = xAxis ? 0.9F : 0.8F;
        int runFrom = -1;
        float runLo = 0, runHi = 0;
        float lo2 = 0, hi2 = 0;
        int run2From = -1;
        for (int k = from; k <= to; k++) {
            float lo = 0, hi = 0, l2 = 0, h2 = 0; // up to two visible pieces: above and below the neighbour
            if (k < to) {
                int i = xAxis ? across : k, j = xAxis ? k : across;
                boolean inside = i >= 0 && j >= 0 && i < n && j < n && !level.inHole(i, j);
                int index = inside ? i * n + j : 0;
                short nb = inside ? level.bottom[layer][index] : 0, nt = inside ? level.top[layer][index] : 0;
                if (!inside || nt <= nb) {
                    lo = b;
                    hi = t;
                } else {
                    if (t > nt) {
                        lo = Math.max(b, nt);
                        hi = t;
                    }
                    if (b < nb) {
                        l2 = b;
                        h2 = Math.min(t, nb);
                    }
                }
            }
            // piece 1 run
            if (runFrom >= 0 && (k == to || lo != runLo || hi != runHi)) {
                emitWall(sky, layer, xAxis, fixed, start + (runFrom - from) * size, start + (k - from) * size, runLo, runHi, originX, originZ, dir, shade, alpha, out);
                runFrom = -1;
            }
            if (runFrom < 0 && hi > lo && k < to) {
                runFrom = k;
                runLo = lo;
                runHi = hi;
            }
            // piece 2 run
            if (run2From >= 0 && (k == to || l2 != lo2 || h2 != hi2)) {
                emitWall(sky, layer, xAxis, fixed, start + (run2From - from) * size, start + (k - from) * size, lo2, hi2, originX, originZ, dir, shade, alpha, out);
                run2From = -1;
            }
            if (run2From < 0 && h2 > l2 && k < to) {
                run2From = k;
                lo2 = l2;
                hi2 = h2;
            }
        }
    }

    private static void emitWall(CloudModel.Sky sky, int layer, boolean xAxis, float fixed, float a, float b, float lo,
                                 float hi, double originX, double originZ, int dir, float shade, float alpha, FloatList out) {
        // corner greys: wind-space positions of the wall's two ends
        if (xAxis) {
            double wx = originX + fixed, wz0 = originZ + a, wz1 = originZ + b;
            float g0 = layerGrey(sky, layer, wx, wz0), g1 = layerGrey(sky, layer, wx, wz1);
            if (dir < 0) out.quad(fixed, lo, a, g0, fixed, lo, b, g1, fixed, hi, b, g1, fixed, hi, a, g0, shade, alpha, -1, 0, 0);
            else out.quad(fixed, lo, b, g1, fixed, lo, a, g0, fixed, hi, a, g0, fixed, hi, b, g1, shade, alpha, 1, 0, 0);
        } else {
            double wz = originZ + fixed, wx0 = originX + a, wx1 = originX + b;
            float g0 = layerGrey(sky, layer, wx0, wz), g1 = layerGrey(sky, layer, wx1, wz);
            if (dir < 0) out.quad(b, lo, fixed, g1, a, lo, fixed, g0, a, hi, fixed, g0, b, hi, fixed, g1, shade, alpha, 0, 0, -1);
            else out.quad(a, lo, fixed, g0, b, lo, fixed, g1, b, hi, fixed, g1, a, hi, fixed, g0, shade, alpha, 0, 0, 1);
        }
    }

    // ---- heap cubes

    /**
     * The heap cubes at a blend f (0 this build .. 1 the next), sizes in whole blocks (they grow a block at a time).
     * Positions relative to (anchorX, anchorZ) wind-space blocks. Each puff is two cubes (a wide one and a narrower,
     * taller one on it) for a rounder heap; bases darker than tops.
     */
    public static void cubes(CloudModel.Sky sky, float f, double anchorX, double anchorZ, BufferBuilder builder) {
        for (CloudModel.Cube cube : sky.cubes()) {
            float r = Mth.lerp(f, cube.r0(), cube.r1());
            if (r < 2) continue;
            float x = Mth.lerp(f, cube.x0(), cube.x1()) - (float) anchorX, z = Mth.lerp(f, cube.z0(), cube.z1()) - (float) anchorZ;
            float bottom = Math.round(Mth.lerp(f, cube.b0(), cube.b1())) + 0.05F;
            float top = Math.round(Mth.lerp(f, cube.t0(), cube.t1()));
            if (top - bottom < 1) continue;
            float c = Mth.lerp(f, cube.c0(), cube.c1());
            float g = CloudModel.grey(c);
            float tall = top - bottom;
            boolean flat = tall < 20 && bottom > 40;
            if (flat) {
                box(builder, Math.round(x - r), Math.round(z - r), Math.round(x + r), Math.round(z + r), bottom, top, g, g, 0.8F);
                continue;
            }
            float split = bottom + Math.round(tall * 0.55F);
            float gb = g * 0.86F, gm = g * Mth.lerp(Math.min(1, tall / 120F), 0.95F, 0.92F), gt = Math.min(1, g * 1.04F);
            box(builder, Math.round(x - r), Math.round(z - r), Math.round(x + r), Math.round(z + r), bottom, split, gb, gm, 0.8F);
            float r2 = Math.max(2, Math.round(r * 0.62F));
            box(builder, Math.round(x - r2), Math.round(z - r2), Math.round(x + r2), Math.round(z + r2), split - 0.05F, top, gm, gt, 0.8F);
        }
    }

    private static void box(BufferBuilder b, float x0, float z0, float x1, float z1, float y0, float y1, float g0, float g1, float a) {
        float u0 = u(g0), u1 = u(g1);
        // top, bottom
        v(b, x0, y1, z0, u1, 1F, a, 0, 1, 0); v(b, x0, y1, z1, u1, 1F, a, 0, 1, 0); v(b, x1, y1, z1, u1, 1F, a, 0, 1, 0); v(b, x1, y1, z0, u1, 1F, a, 0, 1, 0);
        v(b, x0, y0, z0, u0, 0.7F, a, 0, -1, 0); v(b, x1, y0, z0, u0, 0.7F, a, 0, -1, 0); v(b, x1, y0, z1, u0, 0.7F, a, 0, -1, 0); v(b, x0, y0, z1, u0, 0.7F, a, 0, -1, 0);
        // -x, +x
        v(b, x0, y0, z0, u0, 0.9F, a, -1, 0, 0); v(b, x0, y0, z1, u0, 0.9F, a, -1, 0, 0); v(b, x0, y1, z1, u1, 0.9F, a, -1, 0, 0); v(b, x0, y1, z0, u1, 0.9F, a, -1, 0, 0);
        v(b, x1, y0, z1, u0, 0.9F, a, 1, 0, 0); v(b, x1, y0, z0, u0, 0.9F, a, 1, 0, 0); v(b, x1, y1, z0, u1, 0.9F, a, 1, 0, 0); v(b, x1, y1, z1, u1, 0.9F, a, 1, 0, 0);
        // -z, +z
        v(b, x1, y0, z0, u0, 0.8F, a, 0, 0, -1); v(b, x0, y0, z0, u0, 0.8F, a, 0, 0, -1); v(b, x0, y1, z0, u1, 0.8F, a, 0, 0, -1); v(b, x1, y1, z0, u1, 0.8F, a, 0, 0, -1);
        v(b, x0, y0, z1, u0, 0.8F, a, 0, 0, 1); v(b, x1, y0, z1, u0, 0.8F, a, 0, 0, 1); v(b, x1, y1, z1, u1, 0.8F, a, 0, 0, 1); v(b, x0, y1, z1, u1, 0.8F, a, 0, 0, 1);
    }

    private static void v(BufferBuilder b, float x, float y, float z, float u, float shade, float a, float nx, float ny, float nz) {
        b.addVertex(x, y, z).setUv(u, 0.5F).setColor(shade, shade, shade, a).setNormal(nx, ny, nz);
    }

    // ---- boxes (Distant Horizons)

    /** The sky as boxes: the joined cell rectangles of every level and layer, and the heap cubes at this build. */
    public static List<Box> boxes(CloudModel.Sky sky) {
        List<Box> out = new ArrayList<>();
        for (CloudModel.Level level : sky.levels()) {
            int n = level.n;
            float size = level.blocks();
            for (int layer = 0; layer < CloudModel.LAYERS; layer++) {
                short[] bottom = level.bottom[layer], top = level.top[layer];
                boolean[] done = new boolean[n * n];
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < n; j++) {
                        int index = i * n + j;
                        if (done[index] || level.inHole(i, j) || top[index] <= bottom[index]) continue;
                        short bb = bottom[index], tt = top[index];
                        int e = j + 1;
                        while (e < n && !done[i * n + e] && same(level, layer, i, e, bb, tt)) e++;
                        int f = i + 1;
                        grow:
                        while (f < n) {
                            for (int q = j; q < e; q++) if (done[f * n + q] || !same(level, layer, f, q, bb, tt)) break grow;
                            f++;
                        }
                        for (int p = i; p < f; p++) for (int q = j; q < e; q++) done[p * n + q] = true;
                        double x0 = (level.x0 + i) * (double) size, x1 = (level.x0 + f) * (double) size;
                        double z0 = (level.z0 + j) * (double) size, z1 = (level.z0 + e) * (double) size;
                        out.add(new Box(x0, z0, x1, z1, bb, tt, layerGrey(sky, layer, (x0 + x1) / 2, (z0 + z1) / 2), CloudModel.ALPHA[layer]));
                    }
                }
            }
        }
        for (CloudModel.Cube cube : sky.cubes()) {
            float r = Math.round(cube.r0());
            if (r < 2 || cube.t0() - cube.b0() < 1) continue;
            out.add(new Box(Math.round(cube.x0() - r), Math.round(cube.z0() - r), Math.round(cube.x0() + r), Math.round(cube.z0() + r),
                    Math.round(cube.b0()) + 0.05F, Math.round(cube.t0()), CloudModel.grey(cube.c0()), 0.8F));
        }
        return out;
    }

    /** A growable float list of vertices (FLOATS each), with a hash of its contents. */
    static final class FloatList {
        float[] data = new float[4096];
        int size;

        void clear() {
            size = 0;
        }

        void add(float x, float y, float z, float u, float shade, float a, float nx, float ny, float nz) {
            if (size + FLOATS > data.length) data = java.util.Arrays.copyOf(data, data.length * 2);
            data[size++] = x;
            data[size++] = y;
            data[size++] = z;
            data[size++] = u;
            data[size++] = shade;
            data[size++] = a;
            data[size++] = nx;
            data[size++] = ny;
            data[size++] = nz;
        }

        void quad(float x0, float y0, float z0, float g0, float x1, float y1, float z1, float g1, float x2, float y2, float z2, float g2,
                  float x3, float y3, float z3, float g3, float shade, float a, float nx, float ny, float nz) {
            add(x0, y0, z0, g0, shade, a, nx, ny, nz);
            add(x1, y1, z1, g1, shade, a, nx, ny, nz);
            add(x2, y2, z2, g2, shade, a, nx, ny, nz);
            add(x3, y3, z3, g3, shade, a, nx, ny, nz);
        }

        long hash() {
            long h = 0xCBF29CE484222325L;
            for (int i = 0; i < size; i++) {
                h ^= Float.floatToIntBits(data[i]);
                h *= 0x100000001B3L;
            }
            return h ^ size;
        }
    }

    private CloudMesher() {
    }
}
