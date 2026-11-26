package dev.venatorvictor.equinox.client.clouds;

import dev.venatorvictor.equinox.climate.Zone;
import dev.venatorvictor.equinox.weather.StormField;
import dev.venatorvictor.equinox.weather.Weather;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

import java.util.ArrayList;
import java.util.List;

/**
 * What the sky holds around the camera, worked out from the storms (Weather.Side.cloudiness, 0..2: 0 fair, 1 the rain
 * line, 2 the thunder core), the climate and the wind. A pure function of the storms and the game time, in wind space
 * (positions before the wind moved the storm map), so clouds drift with the storms and grow or thin as they do.
 * <ul>
 * <li>Layers of blocky cells, vanilla's look: the low layer is vanilla's own cloud layer (its texture) that fills in,
 * thickens and comes lower where a storm comes; a middle layer and a high layer come ahead of rain fronts (bands across
 * the wind, a grey sheet; streaks along the wind, a thin veil); after a front the low layer breaks into open cells.
 * Heights in 4-block steps. Far away the cells are bigger (levels of detail: 12, 24, 48, 96 blocks).</li>
 * <li>Heaps: groups of white round cubes that gather and swell. Storms that will thunder (the storms are known ahead,
 * so this is looked up before the thunder) grow them into a tower whose top spreads downwind; on fair warm days small
 * heaps line up along the wind. Given for the build time and the next build's, so the renderer grows them smoothly.</li>
 * <li>Grey: vanilla's rain/thunder grey for the storm level at each corner (blended smoothly between corners), heap
 * bases darker than their tops.</li>
 * </ul>
 */
public final class CloudModel {
    public static final int CELL = 12;
    /** Layers of the cell grids, their alpha, heights above the game's cloud height (blocks). */
    public static final int LOW = 0, MID = 1, HIGH = 2, LAYERS = 3;
    public static final float[] ALPHA = {0.8F, 0.8F, 0.45F};
    /** Texels in the grey strip texture (black to white). */
    public static final int GREYS_TEXELS = 64;
    private static final int MID_BASE = 44, MID_THICK = 4, HIGH_BASE = 100, HIGH_THICK = 2;
    /** Height steps (blocks), like vanilla's 4-block clouds. */
    private static final int STEP_H = 4;
    /** Levels of detail: level L has cells of 2^L base cells and reaches LEVEL_REACH * 2^L base cells. */
    public static final int MAX_LEVEL = 3, LEVEL_REACH = 32;
    /** Storm samples every this many base cells (storms are kilometres wide). */
    private static final int STEP = 8;
    /** Heap groups: one site per SITE base cells (jittered), puffs per group. */
    private static final int SITE = 32, PUFFS = 10;
    /** Tower height above the base at full growth (blocks). */
    private static final float TOWER = 112;
    /** How far up- and downwind the fronts are looked for (blocks). */
    private static final double NEAR = 700, FAR = 1600, PASSED = 800;
    /** Thunder look-ahead: ticks back and forward, step. */
    private static final int AHEAD_FROM = -2400, AHEAD_TO = 12000, AHEAD_STEP = 2400;
    /** Wind directions the oriented patterns are made for (blended between the two nearest). */
    private static final int ANGLES = 16;
    /** Storm level a storm must reach to grow towers (thunder). */
    private static final float THUNDER_LEVEL = 1 + (1 - Weather.THUNDER_SHARE) - 0.02F;

    /** One level of detail: cells of `scale` base cells, a square from (x0, z0) (its own cells, wind space), n wide, with a hole. */
    public static final class Level {
        public final int level, scale, x0, z0, n, holeX0, holeZ0, holeN;
        /** [layer][i * n + j]: bottom and top (blocks above the cloud height); empty where top <= bottom. */
        public final short[][] bottom = new short[LAYERS][], top = new short[LAYERS][];

        Level(int level, int x0, int z0, int n, int holeX0, int holeZ0, int holeN) {
            this.level = level;
            this.scale = 1 << level;
            this.x0 = x0;
            this.z0 = z0;
            this.n = n;
            this.holeX0 = holeX0;
            this.holeZ0 = holeZ0;
            this.holeN = holeN;
            for (int l = 0; l < LAYERS; l++) {
                bottom[l] = new short[n * n];
                top[l] = new short[n * n];
            }
        }

        public boolean inHole(int i, int j) {
            return holeN > 0 && i >= holeX0 && j >= holeZ0 && i < holeX0 + holeN && j < holeZ0 + holeN;
        }

        /** Cell size in blocks. */
        public int blocks() {
            return scale * CELL;
        }
    }

    /**
     * A heap cube at this build (0) and the next (1): middle (wind-space blocks), half width, bottom and top (blocks
     * above the cloud height), storm level (grey).
     */
    public record Cube(float x0, float z0, float r0, float b0, float t0, float x1, float z1, float r1, float b1, float t1,
                       float c0, float c1) {
    }

    /** The storm level on a lattice (STEP base cells), for greys anywhere in the sky. */
    public record Storms(float[] values, int sn, int sx0, int sz0) {
        /** Storm level at a wind-space block position. */
        public float at(double bx, double bz) {
            float gx = (float) (bx / (STEP * CELL)) - sx0 - 0.5F, gz = (float) (bz / (STEP * CELL)) - sz0 - 0.5F;
            int i = Mth.clamp(Mth.floor(gx), 0, sn - 2), j = Mth.clamp(Mth.floor(gz), 0, sn - 2);
            float fx = Mth.clamp(gx - i, 0, 1), fz = Mth.clamp(gz - j, 0, 1);
            float a = values[i * sn + j], b = values[(i + 1) * sn + j], c = values[i * sn + j + 1], d = values[(i + 1) * sn + j + 1];
            return (a + (b - a) * fx) * (1 - fz) + (c + (d - c) * fx) * fz;
        }
    }

    /** A built sky: levels of cells around the center (base cells, wind space), heap cubes, the storm lattice. */
    public record Sky(long time, int next, int centerX, int centerZ, List<Level> levels, List<Cube> cubes, Storms storms) {
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

    private static short step(float h) {
        return (short) (Math.round(h / STEP_H) * STEP_H);
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
     * Vanilla's rain/thunder grey of a cloud for its storm level (1 white). The thunder part is lighter than vanilla's
     * sky-wide thunder grey (user 2026-11-26: the tower was way too dark).
     */
    public static float grey(float c) {
        float rain = c <= 1 ? 0.5F * c : Math.min(1, 0.5F + (c - 1) * 2);
        float thunder = smooth(THUNDER_LEVEL - 0.04F, THUNDER_LEVEL + 0.05F, c);
        return (1 - 0.38F * rain) * (1 - 0.76F * 0.6F * thunder);
    }

    /** How warm the weather is for fair-weather heaps, 0..1. */
    private static float warmth(Zone zone, float summer) {
        return switch (zone) {
            case RAINFOREST, MONSOON, SAVANNA, HOT, WARM_OCEAN -> 1F;
            case TEMPERATE, MILD, LUKEWARM_OCEAN, HOT_DESERT -> 0.15F + 0.75F * summer;
            case BOREAL, ALPINE, COLD_DESERT, OCEAN -> 0.35F * summer;
            default -> 0F;
        };
    }

    /** An oriented pattern (0..1) for a wind angle: along / across the wind (blocks); bands: a wave along the wind. */
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
        double u = x * cos + z * sin, v = -x * sin + z * cos;
        double salt = i * 37.0;
        if (bands) {
            double warp = 2.5 * oriented.getValue(x / 300 + salt, z / 300);
            double wave = 0.5 + 0.5 * Math.sin(u / along * Math.PI * 2 + warp);
            return (float) (0.45 * wave + 0.55 * even(oriented.getValue(x / across + salt, z / across + 50)));
        }
        return even(oriented.getValue(u / along + salt, v / across));
    }

    /** Open-cell rim value (1 on a cell's rim, 0 in its middle), cells of about 8 base cells. bx, bz in base cells. */
    private float rimAt(double bx, double bz) {
        int size = 8;
        int cx0 = Mth.floor(bx / size), cz0 = Mth.floor(bz / size);
        double d1 = 1e9, d2 = 1e9;
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                long cx = cx0 + i, cz = cz0 + j;
                double px = (cx + hash01(cx, cz, 11)) * size, pz = (cz + hash01(cx, cz, 12)) * size;
                double d = Math.hypot(bx - px, bz - pz);
                if (d < d1) {
                    d2 = d1;
                    d1 = d;
                } else if (d < d2) d2 = d;
            }
        }
        float edge = (float) Mth.clamp(1 - (d2 - d1) / (size * 0.6), 0, 1);
        return Mth.clamp(edge + 0.2F * (float) rim.getValue(bx / 3.0, bz / 3.0), 0, 1);
    }

    /** Vanilla's cloud texture over a cell of `scale` base cells from (bx, bz): cloudy if half its pixels are. */
    private boolean vanillaAt(int bx, int bz, int scale) {
        if (vanilla == null) return false;
        if (scale == 1) return vanilla[Math.floorMod(bx, vanillaSize) * vanillaSize + Math.floorMod(bz, vanillaSize)];
        int count = 0, step = Math.max(1, scale / 4), samples = 0;
        for (int a = 0; a < scale; a += step) {
            for (int b = 0; b < scale; b += step) {
                samples++;
                if (vanilla[Math.floorMod(bx + a, vanillaSize) * vanillaSize + Math.floorMod(bz + b, vanillaSize)]) count++;
            }
        }
        return count * 2 >= samples;
    }

    // ---- the build

    /**
     * The sky around the camera's base cell (wind space). time, offset: the game time and the storm map's offset; reach:
     * base cells; next: ticks to the next build (cubes are given for both); summer, day: 0..1.
     */
    public Sky build(Weather.Side side, long time, double[] offset, int camX, int camZ, int reach, int next,
                     float summer, float day) {
        StormField field = side.field;
        double angle = field.windAngle(Math.max(0, time) / StormField.STEP);
        double dx = Math.cos(angle), dz = Math.sin(angle);
        int coarse = 1 << MAX_LEVEL;
        int cx = Math.floorDiv(camX, coarse) * coarse, cz = Math.floorDiv(camZ, coarse) * coarse;
        reach = Math.max(LEVEL_REACH, Math.floorDiv(reach + coarse - 1, coarse) * coarse);

        // Storm lattice (STEP base cells) with a margin, and what is ahead/behind/coming (thunder look-ahead).
        int margin = SITE + 8;
        int sx0 = Math.floorDiv(cx - reach - margin, STEP), sz0 = Math.floorDiv(cz - reach - margin, STEP);
        int sn = (2 * (reach + margin)) / STEP + 3;
        float[] cs = new float[sn * sn], near = new float[sn * sn], far = new float[sn * sn], passed = new float[sn * sn], thunder = new float[sn * sn];
        double[][] offsets = new double[(AHEAD_TO - AHEAD_FROM) / AHEAD_STEP + 1][];
        for (int k = 0; k < offsets.length; k++) offsets[k] = field.offset(Math.max(0, time + AHEAD_FROM + (long) k * AHEAD_STEP));
        for (int i = 0; i < sn; i++) {
            for (int j = 0; j < sn; j++) {
                double wx = (sx0 + i + 0.5) * STEP * CELL, wz = (sz0 + j + 0.5) * STEP * CELL;
                double x = wx + offset[0], z = wz + offset[1];
                int index = i * sn + j;
                float c = side.cloudiness(x, z, time, offset);
                cs[index] = c;
                near[index] = side.cloudiness(x - dx * NEAR, z - dz * NEAR, time, offset);
                far[index] = Math.max(side.cloudiness(x - dx * FAR, z - dz * FAR, time, offset), near[index]);
                passed[index] = side.cloudiness(x + dx * PASSED, z + dz * PASSED, time, offset);
                // The most this patch of sky (it moves with the wind) sees in the look-ahead window.
                float peak = c;
                for (int k = 0; k < offsets.length; k++) {
                    long t = Math.max(0, time + AHEAD_FROM + (long) k * AHEAD_STEP);
                    peak = Math.max(peak, side.cloudiness(wx + offsets[k][0], wz + offsets[k][1], t, offsets[k]));
                }
                thunder[index] = smooth(THUNDER_LEVEL - 0.25F, THUNDER_LEVEL, peak);
            }
        }
        Storms storms = new Storms(cs, sn, sx0, sz0);
        Storms aheadNear = new Storms(near, sn, sx0, sz0), aheadFar = new Storms(far, sn, sx0, sz0);
        Storms behind = new Storms(passed, sn, sx0, sz0), thunders = new Storms(thunder, sn, sx0, sz0);

        List<Level> levels = new ArrayList<>();
        int inner = 0;
        for (int l = 0; l <= MAX_LEVEL; l++) {
            int scale = 1 << l;
            int outer = Math.min(reach, LEVEL_REACH << l);
            if (outer <= inner) break;
            int hole = inner == 0 ? 0 : (outer - inner) / scale;
            Level level = new Level(l, Math.floorDiv(cx - outer, scale), Math.floorDiv(cz - outer, scale), 2 * outer / scale,
                    hole, hole, inner == 0 ? 0 : 2 * inner / scale);
            fill(level, storms, aheadNear, aheadFar, behind, thunders, angle);
            levels.add(level);
            inner = outer;
        }

        List<Cube> cubes = new ArrayList<>();
        heaps(side, time, offset, next, cx, cz, reach, angle, summer, day, thunders, cubes);
        return new Sky(time, next, cx, cz, levels, cubes, storms);
    }

    /** The cell layers of one level. */
    private void fill(Level level, Storms storms, Storms near, Storms far, Storms passed, Storms thunders, double angle) {
        int n = level.n, scale = level.scale;
        float size = scale * CELL;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (level.inHole(i, j)) continue;
                int index = i * n + j;
                int bx = (level.x0 + i) * scale, bz = (level.z0 + j) * scale; // base cells
                double mx = (bx + scale * 0.5) * CELL, mz = (bz + scale * 0.5) * CELL; // middle, blocks
                float c = storms.at(mx, mz);
                float k = thunders.at(mx, mz);
                float frontal = 1 - k;
                float cNear = near.at(mx, mz), cFar = far.at(mx, mz), cPassed = passed.at(mx, mz);
                float grow = Mth.clamp(c, 0, 1), rain = Mth.clamp(c - 1, 0, 1);
                double ux = bx + scale * 0.5, uz = bz + scale * 0.5; // middle, base cells

                // Low layer: vanilla's clouds, filling in and thickening as a storm comes; thunderstorms keep gaps
                // between their heaps (only layer storms close the sky).
                float close = Math.max(grow * grow * (1 - 0.85F * k), rain * (1 - 0.75F * k));
                float o = even(0.65 * order.getValue(ux / 5.0, uz / 5.0) + 0.35 * order.getValue(ux / 1.7 + 40, uz / 1.7));
                boolean on = vanillaAt(bx, bz, scale) || o < close * 1.05F - 0.05F;
                float after = smooth(1.1F, 1.6F, cPassed) * (1 - smooth(0.3F, 0.8F, c)) * frontal;
                if (after > 0) {
                    float r = rimAt(ux, uz);
                    if (r < after * 0.75F) on = false;
                    else if (r > 1 - after * 0.5F) on = true;
                }
                if (on) {
                    float l = even(lump.getValue(ux / 2.5, uz / 2.5));
                    float thick = 4 + 4 * close * (1 + 2 * l) + rain * (1 - 0.75F * k) * (8 + 16 * l);
                    short base = step(-16 * rain);
                    level.bottom[LOW][index] = base;
                    level.top[LOW][index] = (short) (base + Math.max(STEP_H, step(thick)));
                }

                // Middle layer, only when rain is really falling upwind (a front coming): bands across the wind as it
                // nears, then a grey sheet closing just before the rain.
                float coming = smooth(1.0F, 1.35F, cNear);
                float bandsAmount = coming * (1 - smooth(0.7F, 1.0F, c)) * frontal;
                float sheet = coming * smooth(0.7F, 1.0F, c) * (1 - smooth(1.4F, 1.8F, c)) * frontal;
                if ((bandsAmount > 0.01F && orientedAt(mx, mz, angle, 8 * CELL, Math.max(2.5 * CELL, size), true) > 1 - 0.55F * bandsAmount)
                        || (sheet > 0.01F && o * 0.8F + 0.1F < sheet)) {
                    level.bottom[MID][index] = MID_BASE;
                    level.top[MID][index] = MID_BASE + MID_THICK;
                }

                // High layer: streaks along the wind far ahead of a front (a few on fair days), thickening to a veil.
                float cirrus = Math.max(0.06F, smooth(0.5F, 1.2F, cFar) * frontal) * (1 - smooth(0.9F, 1.2F, c));
                float veil = smooth(1.1F, 1.5F, cNear) * frontal * (1 - smooth(0.9F, 1.2F, c));
                if (veil > 0.5F || orientedAt(mx, mz, angle, 40 * CELL, Math.max(1.6 * CELL, size), false) > 1 - 0.35F * cirrus) {
                    level.bottom[HIGH][index] = HIGH_BASE;
                    level.top[HIGH][index] = HIGH_BASE + HIGH_THICK;
                }
            }
        }
    }

    /** Stage of a heap site at storm level c: gather (heaps swell), climb (tower), spread (flat top). */
    private record Stage(float gather, float climb, float spread) {
    }

    private static Stage stage(float c, boolean storm, float fair) {
        float gather = Math.max(fair, storm ? smooth(0.15F, 0.9F, c) : 0);
        float climb = storm ? smooth(0.95F, 1.6F, c) : 0;
        float spread = storm ? smooth(1.5F, 1.9F, c) : 0;
        return new Stage(gather, climb, spread);
    }

    /**
     * Heap groups at jittered sites (SITE base cells): storms that will thunder grow them into towers; fair warm days
     * give small heaps in rows along the wind. Cubes for now and `next` ticks later, so they grow smoothly.
     */
    private void heaps(Weather.Side side, long time, double[] offset, int next, int cx, int cz, int reach, double angle,
                       float summer, float day, Storms thunders, List<Cube> cubes) {
        double[] later = side.field.offset(time + next);
        double dx = Math.cos(angle), dz = Math.sin(angle);
        int s0x = Math.floorDiv(cx - reach, SITE), s0z = Math.floorDiv(cz - reach, SITE);
        int s1x = Math.floorDiv(cx + reach, SITE), s1z = Math.floorDiv(cz + reach, SITE);
        for (int si = s0x; si <= s1x; si++) {
            for (int sj = s0z; sj <= s1z; sj++) {
                double ux = (si + 0.15 + 0.7 * hash01(si, sj, 1)) * SITE, uz = (sj + 0.15 + 0.7 * hash01(si, sj, 2)) * SITE;
                double wx = ux * CELL, wz = uz * CELL;
                float c0 = side.cloudiness(wx + offset[0], wz + offset[1], time, offset);
                float c1 = side.cloudiness(wx + later[0], wz + later[1], time + next, later);
                boolean storm = thunders.at(wx, wz) > 0.5F;
                float warm = warmth(side.grid.zone(wx + offset[0], wz + offset[1]), summer);
                float kind = hash01(si, sj, 3);
                float row = orientedAt(wx, wz, angle, 60 * CELL, 9 * CELL, false);
                float fairBase = kind < warm ? 0.35F * day * smooth(0.45F, 0.75F, row) : 0;
                Stage a = stage(c0, storm, fairBase * (1 - smooth(0.8F, 1.0F, c0)));
                Stage b = stage(c1, storm, fairBase * (1 - smooth(0.8F, 1.0F, c1)));
                if (a.gather < 0.02F && b.gather < 0.02F) continue;
                float size = 0.6F + 0.8F * hash01(si, sj, 4);
                float base0 = baseOf(c0), base1 = baseOf(c1);
                float top0 = 0, top1 = 0;
                for (int p = 0; p < PUFFS; p++) {
                    float pa = hash01(si, sj, 10 + p) * Mth.TWO_PI;
                    float pd = p == 0 ? 0 : (2 + 9 * hash01(si, sj, 30 + p)) * size; // base cells from the middle
                    float px = (float) (ux + Mth.cos(pa) * pd) * CELL, pz = (float) (uz + Mth.sin(pa) * pd * 0.8F) * CELL;
                    float central = Math.max(0, 1 - pd / (8 * size));
                    float r = (1.6F + 1.4F * hash01(si, sj, 50 + p)) * size * CELL;
                    float[] s0 = puff(a, p, r, central), s1 = puff(b, p, r, central);
                    if (s0[0] < 3 && s1[0] < 3) continue;
                    cubes.add(new Cube(px, pz, s0[0], base0, base0 + s0[1], px, pz, s1[0], base1, base1 + s1[1], c0, c1));
                    top0 = Math.max(top0, base0 + s0[1]);
                    top1 = Math.max(top1, base1 + s1[1]);
                }
                // The flat top: three flat cubes stepping downwind, growing with the spread.
                if ((a.spread > 0.02F && top0 > 60) || (b.spread > 0.02F && top1 > 60)) {
                    for (int q = 0; q < 3; q++) {
                        float[] f0 = flat(a, q, top0), f1 = flat(b, q, top1);
                        float ox0 = (float) (wx + dx * f0[0]), oz0 = (float) (wz + dz * f0[0]);
                        float ox1 = (float) (wx + dx * f1[0]), oz1 = (float) (wz + dz * f1[0]);
                        cubes.add(new Cube(ox0, oz0, f0[1], f0[2], f0[3], ox1, oz1, f1[1], f1[2], f1[3],
                                Math.min(c0, 1.6F), Math.min(c1, 1.6F)));
                    }
                }
            }
        }
    }

    private static float baseOf(float c) {
        return -16 * Mth.clamp(c - 1, 0, 1);
    }

    /** A puff's half width and height (blocks) at a stage. */
    private static float[] puff(Stage s, int p, float r, float central) {
        float appear = smooth(p * 0.03F, 0.35F + p * 0.03F, s.gather);
        float half = r * (0.45F + 0.55F * s.gather) * appear * (1 + 0.5F * s.climb * central);
        float h = (8 + 20 * s.gather) * appear + TOWER * s.climb * (float) Math.pow(central, 1.5);
        return new float[] {half, h};
    }

    /** A flat-top cube q at a stage: distance downwind, half width, bottom, top. */
    private static float[] flat(Stage s, int q, float towerTop) {
        float spread = s.spread;
        float along = (q * 1.6F + 0.6F) * (2 + 6 * spread) * CELL * spread;
        float half = (3 + 5 * spread) * CELL * spread * (1 - 0.2F * q);
        float bottom = towerTop - 8;
        return new float[] {along, half, bottom, bottom + 4 + 8 * spread};
    }
}
