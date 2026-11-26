package dev.venatorvictor.equinox.client.clouds;

import dev.venatorvictor.equinox.climate.Climate;
import dev.venatorvictor.equinox.climate.Zone;
import dev.venatorvictor.equinox.weather.StormField;
import dev.venatorvictor.equinox.weather.Weather;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

/**
 * What the sky holds around the camera: blocky cloud cells (CELL blocks, like vanilla's) in layers, worked out from the
 * storms (Weather.Side.cloudiness, 0..2: 0 fair, 1 the rain line, 2 the thunder core), the climate and the wind.
 * <p>
 * The base is vanilla's own cloud layer (its texture). Where a storm comes, that layer grows: gaps fill in, the clouds
 * get thicker and come lower, and they darken with vanilla's rain/thunder greys, the whole storm evenly. On top:
 * <ul>
 * <li>heap groups (warm weather): white round heaps gather, the middle climbs into a tower, a flat top spreads
 * downwind; small heaps in rows along the wind on fair warm days;</li>
 * <li>a rain front (cool weather) comes high clouds first: streaks along the wind, a thin veil, then bands across the
 * wind and a grey middle sheet, then the low layer closes;</li>
 * <li>after a front the low layer breaks into open cells (cloudy rims, clear middles).</li>
 * </ul>
 * Everything is a pure function of the storms and the game time, in wind space (cell indices before the wind moved the
 * map), so the clouds drift with the storms and grow or thin as the storms do. Built off the render thread.
 */
public final class CloudModel {
    public static final int CELL = 12;
    /** Layers and their heights above the game's cloud height (blocks). */
    public static final int LOW = 0, ANVIL = 1, MID = 2, HIGH = 3, LAYERS = 4;
    private static final float MID_BASE = 44, MID_THICK = 4, HIGH_BASE = 98, HIGH_THICK = 2;
    /** Grey steps in the cloud texture strip (CloudTexture). */
    public static final int GREYS = 64;
    /** Storm samples every this many cells (storms are kilometres wide); fronts every FRONT_STEP. */
    private static final int STEP = 4, FRONT_STEP = 8;
    /** Heap groups: one site per SITE cells (jittered), up to this many cells across, puffs per group. */
    private static final int SITE = 32, GROUP_REACH = 22, PUFFS = 12;
    /** Tower height above the base at full growth (blocks). */
    private static final float TOWER = 110;
    /** How far up- and downwind the fronts are looked for (blocks). */
    private static final double NEAR = 700, FAR = 1600, PASSED = 800;
    /** Wind directions the oriented patterns are made for (blended between the two nearest). */
    private static final int ANGLES = 16;

    /** One layer: per cell bottom and top (blocks above the cloud height; empty where top <= bottom), grey index, alpha. */
    public static final class Layer {
        public final float[] bottom, top;
        public final byte[] grey;
        public final float alpha;

        Layer(int cells, float alpha) {
            bottom = new float[cells];
            top = new float[cells];
            grey = new byte[cells];
            this.alpha = alpha;
        }
    }

    /** A built sky: n x n cells from (originX - radius, originZ - radius) in wind space. */
    public record Sky(int originX, int originZ, int radius, int n, Layer[] layers) {
    }

    private final long seed;
    private final SimplexNoise order, lump, rim, oriented;
    private final boolean[] vanilla;
    private final int vanillaSize;

    /** vanilla: the vanilla cloud texture's cloud pixels (size x size, row = x). */
    public CloudModel(long seed, boolean[] vanilla, int vanillaSize) {
        this.seed = seed;
        this.vanilla = vanilla;
        this.vanillaSize = vanillaSize;
        order = new SimplexNoise(RandomSource.create(seed ^ 0x1F83D9ABFB41BD6BL));
        lump = new SimplexNoise(RandomSource.create(seed ^ 0x5BE0CD19137E2179L));
        rim = new SimplexNoise(RandomSource.create(seed ^ 0x428A2F98D728AE22L));
        oriented = new SimplexNoise(RandomSource.create(seed ^ 0x7137449123EF65CDL));
    }

    public long seed() {
        return seed;
    }

    // ---- shared helpers

    private static float smooth(float a, float b, float x) {
        float t = Mth.clamp((x - a) / (b - a), 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** Simplex (about -1..1) to roughly even 0..1. */
    private static float even(double n) {
        return Mth.clamp((float) (n * 0.72 + 0.5), 0, 1);
    }

    private long hash(long a, long b, long salt) {
        long h = seed ^ salt;
        h ^= a * 0x9E3779B97F4A7C15L;
        h = Long.rotateLeft(h, 27) * 0xBF58476D1CE4E5B9L;
        h ^= b * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        h *= 0x632BE59BD9B4E5BBL;
        return h ^ (h >>> 29);
    }

    private float hash01(long a, long b, long salt) {
        return (hash(a, b, salt) >>> 40) / (float) (1L << 24);
    }

    /**
     * Vanilla's rain/thunder grey of a cloud for its storm level (1 white). The thunder part is a little lighter than
     * vanilla's sky-wide thunder grey (user 2026-11-26: the tower was too dark); under the storm the camera's own
     * vanilla darkening takes over anyway.
     */
    public static float grey(float c) {
        float rain = c <= 1 ? 0.5F * c : Math.min(1, 0.5F + (c - 1) * 2);
        float thunder = smooth(1 + (1 - Weather.THUNDER_SHARE) - 0.06F, 1 + (1 - Weather.THUNDER_SHARE) + 0.03F, c);
        return (1 - 0.38F * rain) * (1 - 0.76F * 0.6F * thunder);
    }

    /** Vanilla's grey for the rain and thunder levels where the camera is (its cloud colour already has it). */
    public static float cameraGrey(float rain, float thunder) {
        return (1 - 0.38F * rain) * (1 - 0.76F * thunder);
    }

    private static byte greyIndex(float g, float camGrey) {
        float f = Math.min(1, g / Math.max(0.05F, camGrey));
        return (byte) Math.round(f * (GREYS - 1));
    }

    /** How much the storms of a zone are heap/tower storms (warm) rather than layer fronts (cool), 0..1. */
    private static float convective(Zone zone, float summer) {
        return switch (zone) {
            case RAINFOREST, MONSOON, SAVANNA, HOT, WARM_OCEAN -> 1F;
            case TEMPERATE, MILD, LUKEWARM_OCEAN, HOT_DESERT -> 0.15F + 0.75F * summer;
            case BOREAL, ALPINE, COLD_DESERT, OCEAN -> 0.35F * summer;
            default -> 0F;
        };
    }

    /** An oriented pattern (0..1) for a wind angle: along = stretch along the wind, across = across it (blocks). */
    private float orientedAt(double x, double z, double angle, double along, double across, boolean bands) {
        double a = (angle / (Math.PI * 2)) * ANGLES;
        int i0 = Mth.floor(a);
        float f = (float) (a - i0);
        return Mth.lerp(f, oriented(x, z, i0, along, across, bands), oriented(x, z, i0 + 1, along, across, bands));
    }

    private float oriented(double x, double z, int index, double along, double across, boolean bands) {
        int i = Math.floorMod(index, ANGLES);
        double angle = i * Math.PI * 2 / ANGLES;
        double cos = Math.cos(angle), sin = Math.sin(angle);
        double u = x * cos + z * sin, v = -x * sin + z * cos; // u along the wind, v across
        double salt = i * 37.0;
        if (bands) {
            // Bands across the wind: a wave along u, its crests bent a little.
            double warp = 2.5 * oriented.getValue(x / 300 + salt, z / 300);
            double wave = 0.5 + 0.5 * Math.sin(u / along * Math.PI * 2 + warp);
            return (float) (0.45 * wave + 0.55 * even(oriented.getValue(x / across + salt, z / across + 50)));
        }
        return even(oriented.getValue(u / along + salt, v / across));
    }

    /** Open-cell rim value (1 on a cell's rim, 0 in its middle) in wind space, cells of about 8 cloud cells. */
    private float rimAt(int wx, int wz) {
        int size = 8;
        int bx = Math.floorDiv(wx, size), bz = Math.floorDiv(wz, size);
        double d1 = 1e9, d2 = 1e9;
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                long cx = bx + i, cz = bz + j;
                double px = (cx + hash01(cx, cz, 11)) * size, pz = (cz + hash01(cx, cz, 12)) * size;
                double d = Math.hypot(wx + 0.5 - px, wz + 0.5 - pz);
                if (d < d1) {
                    d2 = d1;
                    d1 = d;
                } else if (d < d2) d2 = d;
            }
        }
        float edge = (float) Mth.clamp(1 - (d2 - d1) / (size * 0.6), 0, 1);
        return Mth.clamp(edge + 0.2F * (float) rim.getValue(wx / 3.0, wz / 3.0), 0, 1);
    }

    private boolean vanillaAt(int wx, int wz) {
        if (vanilla == null) return false;
        return vanilla[Math.floorMod(wx, vanillaSize) * vanillaSize + Math.floorMod(wz, vanillaSize)];
    }

    // ---- the build

    /**
     * The sky around a wind-space cell. time and offset: the game time and the storm map's offset; summer: 0..1 how
     * much it is summer; day: 0..1 daylight; camGrey: the camera's own vanilla grey (cloud colour already has it).
     */
    public Sky build(Weather.Side side, long time, double[] offset, int originX, int originZ, int radius,
                     float summer, float day, float camGrey) {
        int n = 2 * radius + 1, cells = n * n;
        Layer low = new Layer(cells, 0.8F), anvil = new Layer(cells, 0.8F), mid = new Layer(cells, 0.8F), high = new Layer(cells, 0.45F);
        StormField field = side.field;
        double angle = field.windAngle(Math.max(0, time) / StormField.STEP);
        double dx = Math.cos(angle), dz = Math.sin(angle);
        int x0 = originX - radius, z0 = originZ - radius;

        // Storms and climate on a coarse lattice (margin for the heap groups), then read back bilinear.
        int margin = GROUP_REACH + SITE;
        int sx0 = Math.floorDiv(x0 - margin, STEP), sz0 = Math.floorDiv(z0 - margin, STEP);
        int sn = (n + 2 * margin) / STEP + 3;
        float[] cs = new float[sn * sn], ks = new float[sn * sn];
        for (int i = 0; i < sn; i++) {
            for (int j = 0; j < sn; j++) {
                double x = (sx0 + i) * (double) STEP * CELL + offset[0], z = (sz0 + j) * (double) STEP * CELL + offset[1];
                cs[i * sn + j] = side.cloudiness(x, z, time, offset);
                ks[i * sn + j] = convective(side.grid.zone(x, z), summer);
            }
        }
        int fx0 = Math.floorDiv(x0, FRONT_STEP), fz0 = Math.floorDiv(z0, FRONT_STEP);
        int fn = n / FRONT_STEP + 3;
        float[] near = new float[fn * fn], far = new float[fn * fn], passed = new float[fn * fn];
        for (int i = 0; i < fn; i++) {
            for (int j = 0; j < fn; j++) {
                double x = (fx0 + i) * (double) FRONT_STEP * CELL + offset[0], z = (fz0 + j) * (double) FRONT_STEP * CELL + offset[1];
                // Storms upwind will come here; one downwind has passed.
                near[i * fn + j] = side.cloudiness(x - dx * NEAR, z - dz * NEAR, time, offset);
                far[i * fn + j] = Math.max(side.cloudiness(x - dx * FAR, z - dz * FAR, time, offset), near[i * fn + j]);
                passed[i * fn + j] = side.cloudiness(x + dx * PASSED, z + dz * PASSED, time, offset);
            }
        }

        // Heap groups: rasterised first (their own height map), merged into the low layer below.
        float[] heapTop = new float[cells], heapC = new float[cells];
        heaps(cs, ks, sn, sx0, sz0, x0, z0, n, summer, day, angle, heapTop, heapC, anvil, camGrey);

        double windX = offset[0], windZ = offset[1];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int wx = x0 + i, wz = z0 + j, index = i * n + j;
                float c = lattice(cs, sn, sx0, sz0, wx, wz, STEP);
                float k = lattice(ks, sn, sx0, sz0, wx, wz, STEP);
                float frontal = 1 - k;
                float cNear = lattice(near, fn, fx0, fz0, wx, wz, FRONT_STEP);
                float cFar = lattice(far, fn, fx0, fz0, wx, wz, FRONT_STEP);
                float cPassed = lattice(passed, fn, fx0, fz0, wx, wz, FRONT_STEP);
                double bx = wx * (double) CELL, bz = wz * (double) CELL;
                float grow = Mth.clamp(c, 0, 1), rain = Mth.clamp(c - 1, 0, 1);

                // Low layer: vanilla's clouds, filling in and thickening as a storm comes (less while heaps do it).
                // (heap storms keep gaps between their heaps; only layer storms close the sky completely)
                float close = Math.max(grow * (1 - 0.85F * k), rain * (1 - 0.75F * k));
                float o = even(0.65 * order.getValue(wx / 5.0, wz / 5.0) + 0.35 * order.getValue(wx / 1.7 + 40, wz / 1.7));
                float fill = vanillaAt(wx, wz) ? 1 : Mth.clamp((close * 1.05F - 0.05F - o) / 0.08F, 0, 1);
                // After a front: the layer breaks into open cells (middles clear, rims stay).
                float after = smooth(1.1F, 1.6F, cPassed) * (1 - smooth(0.3F, 0.8F, c)) * frontal;
                if (after > 0) {
                    float r = rimAt(wx, wz);
                    if (r < after * 0.75F) fill = 0;
                    else if (r > 1 - after * 0.5F) fill = Math.max(fill, smooth(1 - after * 0.5F, 1 - after * 0.5F + 0.1F, r));
                }
                float l = even(lump.getValue(wx / 2.5, wz / 2.5));
                float thick = 4 + 4 * close * (1 + 2 * l) + rain * (1 - 0.75F * k) * (8 + 16 * l);
                float base = -16 * rain;
                float top = fill > 0 ? base + Math.max(0.5F, thick * fill) : 0;
                float bottom = fill > 0 ? base : 0;
                float g = grey(c);
                float h = heapTop[index];
                if (h > 0) {
                    float hb = -16 * Mth.clamp(heapC[index] - 1, 0, 1);
                    if (top <= bottom) bottom = hb;
                    else bottom = Math.min(bottom, hb);
                    top = Math.max(top, hb + h);
                    g = Math.min(g, grey(heapC[index]));
                }
                low.bottom[index] = bottom;
                low.top[index] = top;
                low.grey[index] = greyIndex(g, camGrey);

                // Middle layer: bands across the wind as a front nears, then a closed grey sheet before the rain.
                float bandsAmount = smooth(0.6F, 1.3F, cNear) * (1 - smooth(0.7F, 1.0F, c)) * frontal;
                float sheet = smooth(0.45F, 0.95F, c) * (1 - smooth(1.4F, 1.8F, c)) * frontal;
                boolean midFill = false;
                if (bandsAmount > 0.01F && orientedAt(bx - 0, bz, angle, 8 * CELL, 2.5 * CELL, true) > 1 - 0.55F * bandsAmount) midFill = true;
                if (sheet > 0.01F && o * 0.8F + 0.1F < sheet) midFill = true;
                if (midFill) {
                    mid.bottom[index] = MID_BASE;
                    mid.top[index] = MID_BASE + MID_THICK;
                    mid.grey[index] = greyIndex(grey(Math.max(c, 0.6F * sheet)), camGrey);
                }

                // High layer: streaks along the wind, far ahead of a front (and a few on fair days), thickening to a veil.
                float cirrus = Math.max(0.06F, smooth(0.5F, 1.2F, cFar) * frontal) * (1 - smooth(0.9F, 1.2F, c));
                float streak = orientedAt(bx, bz, angle, 40 * CELL, 1.6 * CELL, false);
                float veil = smooth(0.85F, 1.3F, cNear) * frontal * (1 - smooth(0.9F, 1.2F, c));
                if (streak > 1 - 0.35F * cirrus || veil > 0.5F) {
                    high.bottom[index] = HIGH_BASE;
                    high.top[index] = HIGH_BASE + HIGH_THICK;
                    high.grey[index] = greyIndex(grey(0.5F * c), camGrey);
                }
            }
        }
        return new Sky(originX, originZ, radius, n, new Layer[] {low, anvil, mid, high});
    }

    private static float lattice(float[] values, int sn, int sx0, int sz0, int wx, int wz, int step) {
        float gx = (wx + 0.5F) / step - sx0, gz = (wz + 0.5F) / step - sz0;
        int i = Mth.clamp(Mth.floor(gx), 0, sn - 2), j = Mth.clamp(Mth.floor(gz), 0, sn - 2);
        float fx = Mth.clamp(gx - i, 0, 1), fz = Mth.clamp(gz - j, 0, 1);
        float a = values[i * sn + j], b = values[(i + 1) * sn + j], c = values[i * sn + j + 1], d = values[(i + 1) * sn + j + 1];
        return (a + (b - a) * fx) * (1 - fz) + (c + (d - c) * fx) * fz;
    }

    /**
     * Heap groups at jittered sites. A site's storm level c sets its stage: heaps gather and swell (0.15..0.9), the
     * middle climbs into a tower (0.95..1.6, it rains), the top spreads downwind (1.5..1.9); as the storm goes, the
     * same steps run back (it sinks and thins). Only where storms are warm (convective), and as small fair-weather
     * heaps in rows along the wind on warm days.
     */
    private void heaps(float[] cs, float[] ks, int sn, int sx0, int sz0, int x0, int z0, int n, float summer, float day,
                       double angle, float[] heapTop, float[] heapC, Layer anvil, float camGrey) {
        double dx = Math.cos(angle), dz = Math.sin(angle);
        int s0x = Math.floorDiv(x0 - GROUP_REACH, SITE), s0z = Math.floorDiv(z0 - GROUP_REACH, SITE);
        int s1x = Math.floorDiv(x0 + n + GROUP_REACH, SITE), s1z = Math.floorDiv(z0 + n + GROUP_REACH, SITE);
        for (int si = s0x; si <= s1x; si++) {
            for (int sj = s0z; sj <= s1z; sj++) {
                float cx = (si + 0.15F + 0.7F * hash01(si, sj, 1)) * SITE, cz = (sj + 0.15F + 0.7F * hash01(si, sj, 2)) * SITE;
                float c = lattice(cs, sn, sx0, sz0, (int) cx, (int) cz, STEP);
                float k = lattice(ks, sn, sx0, sz0, (int) cx, (int) cz, STEP);
                float kind = hash01(si, sj, 3);
                boolean storm = kind < k;
                // Fair-weather heaps: warm days, in rows along the wind (sites near a row's crest).
                float row = orientedAt(cx * CELL, cz * CELL, angle, 60 * CELL, 9 * CELL, false);
                float fair = kind < k * 0.9F + 0.1F * summer ? 0.35F * day * smooth(0.45F, 0.75F, row) * (1 - smooth(0.8F, 1.0F, c)) : 0;
                float gather = Math.max(fair, storm ? smooth(0.15F, 0.9F, c) : 0);
                if (gather < 0.02F) continue;
                float climb = storm ? smooth(0.95F, 1.6F, c) : 0;
                float spread = storm ? smooth(1.5F, 1.9F, c) : 0;
                float size = 0.6F + 0.8F * hash01(si, sj, 4);
                float towerTop = 0;
                for (int p = 0; p < PUFFS; p++) {
                    float pa = hash01(si, sj, 10 + p) * Mth.TWO_PI;
                    float pd = p == 0 ? 0 : (2 + 11 * hash01(si, sj, 30 + p)) * size;
                    float px = cx + Mth.cos(pa) * pd, pz = cz + Mth.sin(pa) * pd * 0.8F;
                    float central = Math.max(0, 1 - pd / (9 * size));
                    float appear = smooth(p * 0.03F, 0.35F + p * 0.03F, gather);
                    float r = (2.2F + 1.6F * hash01(si, sj, 50 + p)) * size * (0.5F + 0.5F * gather) * appear * (1 + 0.6F * climb * central);
                    if (r <= 0.3F) continue;
                    float h = (6 + 18 * gather) * appear + TOWER * climb * (float) Math.pow(central, 1.5);
                    towerTop = Math.max(towerTop, h);
                    int ri = (int) Math.ceil(r);
                    for (int a = -ri; a <= ri; a++) {
                        for (int b = -ri; b <= ri; b++) {
                            int wx = Mth.floor(px) + a, wz = Mth.floor(pz) + b;
                            int i = wx - x0, j = wz - z0;
                            if (i < 0 || j < 0 || i >= n || j >= n) continue;
                            float d = (float) Math.hypot(wx + 0.5 - px, wz + 0.5 - pz) / r;
                            if (d >= 1) continue;
                            float dome = (float) Math.sqrt(1 - d * d);
                            float top = 4 + h * dome * (0.9F + 0.2F * even(lump.getValue(wx / 2.0, wz / 2.0)));
                            int index = i * n + j;
                            if (top > heapTop[index]) heapTop[index] = top;
                            heapC[index] = Math.max(heapC[index], c);
                        }
                    }
                }
                // The flat top, drawn out downwind.
                if (spread > 0.02F && towerTop > 50) {
                    float reach = 3 + 7 * spread, stretch = 1 + 1.4F * spread;
                    float ax = (float) (cx + dx * (4 + 16 * spread)), az = (float) (cz + dz * (4 + 16 * spread));
                    float bottom = towerTop - 6, top = bottom + 4 + 8 * spread;
                    int ri = (int) Math.ceil(reach * stretch) + 1;
                    for (int a = -ri; a <= ri; a++) {
                        for (int b = -ri; b <= ri; b++) {
                            int wx = Mth.floor(ax) + a, wz = Mth.floor(az) + b;
                            int i = wx - x0, j = wz - z0;
                            if (i < 0 || j < 0 || i >= n || j >= n) continue;
                            double rx = wx + 0.5 - ax, rz = wz + 0.5 - az;
                            double along = rx * dx + rz * dz, across = -rx * dz + rz * dx;
                            double d = Math.hypot(along / stretch, across) / reach;
                            if (d >= 1 - 0.15 * rim.getValue(wx / 2.0, wz / 2.0 + 90)) continue;
                            int index = i * n + j;
                            anvil.bottom[index] = bottom;
                            anvil.top[index] = top;
                            anvil.grey[index] = greyIndex(grey(Math.min(c, 1.6F)), camGrey);
                        }
                    }
                }
            }
        }
    }
}
