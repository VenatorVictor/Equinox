package dev.venatorvictor.equinox.client.clouds;

import dev.venatorvictor.equinox.climate.Zone;
import dev.venatorvictor.equinox.weather.StormField;
import dev.venatorvictor.equinox.weather.Weather;
import net.minecraft.util.Mth;

/**
 * What the sky holds around the camera, from the storms (Weather.Side.cloudiness, 0..2: 0 fair, 1 the rain line, 2 the
 * thunder core), the climate, the season and the time of day. Everything is in wind space (positions before the wind
 * moved the storm map), so clouds drift with the storms. Heights are blocks above the game's cloud height.
 * <p>
 * Two parts, which the graphics card turns into cubes (cloud_density.glsl):
 * <ul>
 * <li>Domes: round puffs (an ellipsoid cut flat at its base). Fair-weather heaps are a few puffs each, every heap with
 * its own life (it forms, lives and dissolves); a storm that will thunder grows heaps that gather, climb into a tower
 * of stacked puffs and spread an anvil downwind. Timed like real life on the game's clock (1 game hour = 1 000 ticks):
 * the heaps gather over about 2 hours before the rain and the tower reaches its top as the rain starts.</li>
 * <li>A map (MAP_CELL blocks a texel) of the layer clouds: the low deck that closes in and thickens before rain (lumpy
 * stratocumulus, then flat thick nimbostratus), middle clouds in rows before a front, high streaks far ahead of it,
 * and the storm level for the greys.</li>
 * </ul>
 * Also a ground shadow map (how much sun gets through each column). Positions in a sky are blocks relative to its
 * centre (whole wind-space blocks), so floats stay exact far from the world's middle.
 */
public final class CloudSky {
    /** Map texel (blocks), dome floats, height range the cubes cover (blocks above the cloud height). */
    public static final int MAP_CELL = 64, DOME_FLOATS = 12;
    public static final float BOTTOM = -48, TOP = 208;
    /** Kinds of dome. */
    public static final int PUFF = 0, ANVIL = 1;
    /** Heights of the middle and high layers (blocks above the cloud height). */
    public static final float MID_LOW = 64, MID_HIGH = 72, HIGH_LOW = 150, HIGH_HIGH = 156;
    /** Storm heap sites and fair heap sites (blocks, jittered). */
    private static final int STORM_SITE = 384, BIG_SITE = 384, SMALL_SITE = 160;
    /** Storm life (ticks): heaps gather, the tower climbs (ends as the rain starts), the anvil spreads, it melts away. */
    public static final int GATHER = 2000, CLIMB = 1600, SPREAD = 900, MELT = 2400;
    /** Look-ahead for a storm site: samples every AHEAD_STEP ticks from -AHEAD_BACK to +AHEAD_FORWARD. */
    private static final int AHEAD_STEP = 300, AHEAD_BACK = 8, AHEAD_FORWARD = 12;
    /** Tower height above its base at full growth (blocks). */
    private static final float TOWER = 170;
    /** How far up- and downwind the fronts are looked for (blocks). */
    private static final double NEAR = 700, FAR = 1600;
    /** Storm level a storm must reach to thunder. */
    private static final float THUNDER_LEVEL = 1 + (1 - Weather.THUNDER_SHARE) - 0.02F;
    /** Ground shadow map: texel size (blocks) and reach (blocks). */
    public static final int SHADOW_CELL = 8, SHADOW_REACH = 1024;

    /**
     * A built sky. map0: per texel (low amount, low bottom, low top, storm level); map1: (middle amount, high amount,
     * lumpiness, thunder). domes: DOME_FLOATS each (x, z, centre y, base, radius along, radius across, radius up,
     * along x, along z, storm level, kind, size). shadow: sunlight reaching the ground per texel (0..1).
     */
    public record Sky(long time, int centerX, int centerZ, int mapSize, float mapX0, float mapZ0, float[] map0,
                      float[] map1, float[] domes, int domeCount, int shadowSize, float shadowX0, float shadowZ0,
                      float[] shadow, float windX, float windZ) {
        /** A map channel at a position relative to the centre (bilinear between texel centres, like the shader). */
        public float map(float[] map, int channel, double x, double z) {
            double gx = (x - mapX0) / MAP_CELL - 0.5, gz = (z - mapZ0) / MAP_CELL - 0.5;
            int i = Mth.clamp(Mth.floor(gx), 0, mapSize - 2), j = Mth.clamp(Mth.floor(gz), 0, mapSize - 2);
            float fx = (float) Mth.clamp(gx - i, 0, 1), fz = (float) Mth.clamp(gz - j, 0, 1);
            float a = map[(j * mapSize + i) * 4 + channel], b = map[(j * mapSize + i + 1) * 4 + channel];
            float c = map[((j + 1) * mapSize + i) * 4 + channel], d = map[((j + 1) * mapSize + i + 1) * 4 + channel];
            return (a + (b - a) * fx) * (1 - fz) + (c + (d - c) * fx) * fz;
        }

    }

    private final long seed;
    /**
     * Fair-weather sky held per map texel (wind space): the weather under a drifting cloud changes a little all the time,
     * and fair clouds would follow it, so their edges never stood still. They keep their weather until it has really
     * changed (HOLD); only where rain is falling or coming (storms forming) does the sky follow the weather freely.
     */
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<float[]> held = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private static final float HOLD = 0.25F, STORM_HOLD = 0.1F;
    /** The wind pattern angle held (one of 16), so streaks and rows don't change when the wind turns a little. */
    private int patternAngle = -1;

    public CloudSky(long seed) {
        this.seed = seed;
    }

    public long seed() {
        return seed;
    }

    // ---- helpers

    private static float smooth(float a, float b, float x) {
        float t = Mth.clamp((x - a) / (b - a), 0, 1);
        return t * t * (3 - 2 * t);
    }

    private long hash(long a, long b, long salt) {
        long h = seed ^ salt * 0xD6E8FEB86659FD93L;
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

    /** Vanilla-like grey of a cloud for its storm level (1 white); the thunder part lighter than vanilla's sky-wide one. */
    public static float grey(float c) {
        // white (fair) -> grey (rain) -> dark grey (thunder): the first look, with its two sharp steps softened
        float rain = 0.5F * Math.min(c, 1) + 0.5F * smooth(0.85F, 1.4F, c);
        float thunder = smooth(THUNDER_LEVEL - 0.2F, THUNDER_LEVEL + 0.15F, c);
        return (1 - 0.38F * rain) * (1 - 0.76F * 0.6F * thunder);
    }

    /** Daylight for heaps (0 night .. 1 day) at a time of day in ticks. */
    public static float dayAt(long dayTime) {
        long t = Math.floorMod(dayTime, 24000L);
        return t < 12500 ? Mth.clamp(Math.min(t + 500, 12500 - t) / 1500F, 0, 1) : 0;
    }

    /** How warm the weather is for fair-weather heaps, 0..1. */
    private static float warmth(Zone zone, float summer) {
        return switch (zone) {
            case RAINFOREST, MONSOON, SAVANNA, HOT, WARM_OCEAN -> 1F;
            case TEMPERATE, MILD, LUKEWARM_OCEAN, HOT_DESERT -> 0.25F + 0.75F * summer;
            case BOREAL, ALPINE, COLD_DESERT, OCEAN -> 0.15F + 0.35F * summer;
            default -> 0.1F;
        };
    }

    /** Dome writer. */
    private static final class Domes {
        float[] data = new float[DOME_FLOATS * 512];
        int count;

        void add(float x, float z, float cy, float base, float along, float across, float up, float dirX, float dirZ,
                 float c, int kind) {
            if (along < 1.5F || across < 1.5F || up < 1.5F) return;
            if ((count + 1) * DOME_FLOATS > data.length) data = java.util.Arrays.copyOf(data, data.length * 2);
            int o = count++ * DOME_FLOATS;
            data[o] = x;
            data[o + 1] = z;
            data[o + 2] = cy;
            data[o + 3] = base;
            data[o + 4] = along;
            data[o + 5] = across;
            data[o + 6] = up;
            data[o + 7] = dirX;
            data[o + 8] = dirZ;
            data[o + 9] = c;
            data[o + 10] = kind;
            data[o + 11] = Math.min(Math.min(along, across), up);
        }

        void puff(float x, float z, float cy, float base, float r, float up, float c) {
            add(x, z, cy, base, r, r, up, 1, 0, c, PUFF);
        }
    }

    // ---- the build

    /**
     * The sky around the camera (wind-space blocks camX, camZ) out to `reach` blocks. time, offset: the game time and
     * the storm map's offset; summer, day: 0..1; amount: fair-weather cloud amount (1 normal).
     */
    public synchronized Sky build(Weather.Side side, long time, double[] offset, double camX, double camZ, int reach, float summer,
                     long dayTime, float amount) {
        StormField field = side.field;
        double angle = field.windAngle(Math.max(0, time) / StormField.STEP);
        float wx = (float) Math.cos(angle), wz = (float) Math.sin(angle);
        // the pattern angle moves only when the wind has turned past halfway to the next of 16 angles, and then a whole step
        double raw = Math.floorMod((long) Math.floor(angle / (Math.PI * 2) * 16 * 1000), 16000) / 1000.0;
        if (patternAngle < 0 || Math.abs(Math.floorMod((long) Math.round((raw - patternAngle) * 1000) + 8000, 16000) - 8000) / 1000.0 > 0.75)
            patternAngle = (int) Math.round(raw) & 15;
        float pwx = (float) Math.cos(patternAngle * Math.PI * 2 / 16), pwz = (float) Math.sin(patternAngle * Math.PI * 2 / 16);
        it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<float[]> keep = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        boolean testLog = Boolean.getBoolean("equinox.cloudTest");
        int refreshed = 0;
        int[] why = new int[5];
        int angleBefore = patternAngle;
        int cx = Mth.floor(camX), cz = Mth.floor(camZ);

        // ---- map of the layer clouds (texel corners on a fixed wind-space lattice, so it doesn't shift as you move)
        int half = reach + 2 * STORM_SITE;
        int mapSize = 2 * half / MAP_CELL + 2;
        int mapAbsX0 = Math.floorDiv(cx - half, MAP_CELL) * MAP_CELL, mapAbsZ0 = Math.floorDiv(cz - half, MAP_CELL) * MAP_CELL;
        float mapX0 = mapAbsX0 - cx, mapZ0 = mapAbsZ0 - cz;
        float[] map0 = new float[mapSize * mapSize * 4], map1 = new float[mapSize * mapSize * 4];
        long[] times = new long[AHEAD_FORWARD / 2 + 1];
        double[][] offsets = new double[times.length][];
        for (int k = 0; k < times.length; k++) {
            times[k] = Math.max(0, time + (long) (k * 2 - 2) * AHEAD_STEP * 2);
            offsets[k] = field.offset(times[k]);
        }
        for (int j = 0; j < mapSize; j++) {
            for (int i = 0; i < mapSize; i++) {
                double mx = mapAbsX0 + (i + 0.5) * MAP_CELL, mz = mapAbsZ0 + (j + 0.5) * MAP_CELL;
                double x = mx + offset[0], z = mz + offset[1];
                float c = side.cloudiness(x, z, time, offset);
                float near = side.cloudiness(x - wx * NEAR, z - wz * NEAR, time, offset);
                float far = Math.max(side.cloudiness(x - wx * FAR, z - wz * FAR, time, offset), near);
                // the most this patch of sky (it moves with the wind) sees soon: thunder coming?
                float peak = c;
                for (int k = 0; k < times.length; k++)
                    peak = Math.max(peak, side.cloudiness(mx + offsets[k][0], mz + offsets[k][1], times[k], offsets[k]));
                float warm = warmth(side.grid.zone(x, z), summer);
                long key = ((long) (mapAbsX0 / MAP_CELL + i) << 32) ^ ((mapAbsZ0 / MAP_CELL + j) & 0xFFFFFFFFL);
                float[] fresh = {c, near, far, peak, warm}, old = held.get(key);
                // storms (rain falling or coming) follow the weather more closely, but still not every small wobble
                boolean storming = Math.max(c, Math.max(near, peak)) > 1;
                if (old != null) {
                    float hold = storming ? STORM_HOLD : HOLD;
                    boolean same = true;
                    for (int q = 0; q < fresh.length; q++) same &= Math.abs(fresh[q] - old[q]) <= hold;
                    if (same) fresh = old;
                    else if (testLog) {
                        refreshed++;
                        for (int q = 0; q < 5; q++) if (Math.abs(fresh[q] - old[q]) > hold) why[q]++;
                    }
                }
                keep.put(key, fresh);
                c = fresh[0];
                near = fresh[1];
                far = fresh[2];
                peak = fresh[3];
                warm = fresh[4];
                float thunder = smooth(THUNDER_LEVEL - 0.25F, THUNDER_LEVEL, peak);
                float grow = Mth.clamp(c, 0, 1), rain = Mth.clamp(c - 1, 0, 1);
                float frontal = 1 - thunder;
                // Low deck: closes in and thickens as rain comes (thunderstorms keep gaps between their heaps).
                float close = Math.max(grow * grow * (1 - 0.85F * thunder), rain * (1 - 0.7F * thunder));
                float bottom = -16 * rain - 4 * grow;
                float thick = 8 + 16 * close + 40 * rain * (1 - 0.6F * thunder);
                int o = (j * mapSize + i) * 4;
                map0[o] = close;
                map0[o + 1] = bottom;
                map0[o + 2] = bottom + thick;
                map0[o + 3] = c;
                // Middle clouds in rows when rain is really falling upwind; high streaks far ahead (a few on fair days).
                float coming = smooth(1.0F, 1.35F, near);
                map1[o] = coming * (1 - smooth(1.4F, 1.8F, c)) * frontal;
                map1[o + 1] = Math.max(0.12F * amount, smooth(0.5F, 1.2F, far) * frontal) * (1 - smooth(0.9F, 1.2F, c));
                // lumpy (stratocumulus) in warm air before the rain; flat (stratus) in cold calm air and in the rain
                map1[o + 2] = (1 - rain) * (0.25F + 0.75F * warm);
                map1[o + 3] = thunder;
            }
        }
        held.clear();
        held.putAll(keep);
        Sky partial = new Sky(time, cx, cz, mapSize, mapX0, mapZ0, map0, map1, new float[0], 0, 0, 0, 0, new float[0], pwx, pwz);

        // ---- domes
        Domes domes = new Domes();
        stormSites(side, time, cx, cz, reach, wx, wz, partial, domes);
        int stormDomes = domes.count;
        fairSites(cx, cz, reach, amount, partial, domes, BIG_SITE, 7, true);
        fairSites(cx, cz, reach, amount, partial, domes, SMALL_SITE, 8, false);

        // ---- ground shadows
        int shadowReach = Math.min(reach, SHADOW_REACH);
        int shadowSize = 2 * shadowReach / SHADOW_CELL;
        float shadowX0 = -shadowReach, shadowZ0 = -shadowReach;
        float[] shadow = shadows(partial, domes, shadowSize, shadowX0, shadowZ0);

        if (testLog) {
            float maxC = 0, maxPeak = 0;
            for (float[] v : held.values()) {
                maxC = Math.max(maxC, v[0]);
                maxPeak = Math.max(maxPeak, v[3]);
            }
            dev.venatorvictor.equinox.Equinox.LOGGER.info("CLOUDTEST sky: {} spots refreshed (c {} near {} far {} peak {} warm {}), angle {} -> {}, override {} from {} now {}, max c {} max peak {}, storm domes {} all domes {}",
                    refreshed, why[0], why[1], why[2], why[3], why[4], angleBefore, patternAngle, side.overrideAt(time), side.overrideFrom(), time, maxC, maxPeak, stormDomes, domes.count);
        }
        return new Sky(time, cx, cz, mapSize, mapX0, mapZ0, map0, map1, java.util.Arrays.copyOf(domes.data, domes.count * DOME_FLOATS),
                domes.count, shadowSize, shadowX0, shadowZ0, shadow, pwx, pwz);
    }

    /** Thunderstorm heaps: where the look-ahead says it will thunder, timed by when the rain comes to the site. */
    private void stormSites(Weather.Side side, long time, int cx, int cz, int reach, float wx, float wz, Sky map, Domes out) {
        int s0x = Math.floorDiv(cx - reach - STORM_SITE, STORM_SITE), s1x = Math.floorDiv(cx + reach + STORM_SITE, STORM_SITE);
        int s0z = Math.floorDiv(cz - reach - STORM_SITE, STORM_SITE), s1z = Math.floorDiv(cz + reach + STORM_SITE, STORM_SITE);
        // Look-ahead samples on a fixed time grid (so they don't jump as time goes on), plus now itself.
        long grid = Math.floorDiv(time, AHEAD_STEP) * AHEAD_STEP;
        int n = AHEAD_BACK + AHEAD_FORWARD + 2;
        long[] times = new long[n];
        double[][] offsets = new double[n][];
        for (int k = 0; k < n; k++) {
            times[k] = k == AHEAD_BACK + 1 ? time : grid + (long) (k <= AHEAD_BACK ? k - AHEAD_BACK : k - AHEAD_BACK - 1) * AHEAD_STEP;
            times[k] = Math.max(0, times[k]);
            offsets[k] = side.field.offset(times[k]);
        }
        int now = AHEAD_BACK + 1;
        float[] c = new float[n];
        for (int si = s0x; si <= s1x; si++) {
            for (int sj = s0z; sj <= s1z; sj++) {
                double ax = (si + 0.2 + 0.6 * hash01(si, sj, 1)) * STORM_SITE, az = (sj + 0.2 + 0.6 * hash01(si, sj, 2)) * STORM_SITE;
                float x = (float) (ax - cx), z = (float) (az - cz);
                float thunder = map.map(map.map1(), 3, x, z);
                if (thunder < 0.3F) continue;
                // When does rain reach this patch of sky? (it moves with the wind; wind-space positions stay put).
                // Crossings are found between samples, so the stages move smoothly with time.
                for (int k = 0; k < n; k++) c[k] = side.cloudiness(ax + offsets[k][0], az + offsets[k][1], times[k], offsets[k]);
                float level = c[now];
                float gather, climb, spread, melt;
                if (level > 1) {
                    float start = Float.MAX_VALUE;
                    for (int k = now; k > 0; k--) if (c[k - 1] <= 1) {
                        start = cross(times[k - 1], c[k - 1], times[k], c[k]);
                        break;
                    }
                    gather = climb = 1;
                    spread = start == Float.MAX_VALUE ? 1 : smooth(0, SPREAD, time - start);
                    melt = 0;
                } else {
                    float until = Float.MAX_VALUE, since = Float.MAX_VALUE;
                    for (int k = now + 1; k < n; k++) if (c[k] > 1) {
                        until = cross(times[k - 1], c[k - 1], times[k], c[k]) - time;
                        break;
                    }
                    for (int k = now - 1; k >= 0; k--) if (c[k] > 1) {
                        since = time - cross(times[k], c[k], times[k + 1], c[k + 1]);
                        break;
                    }
                    if (until <= since) {
                        // gathering: heaps over GATHER ticks, the tower's climb ends as the rain starts
                        gather = 1 - smooth(0, GATHER, until - CLIMB);
                        climb = 1 - smooth(0, CLIMB, until);
                        spread = melt = 0;
                    } else {
                        gather = climb = spread = 1;
                        melt = smooth(0, MELT, since);
                    }
                }
                // fades with the thunder outlook instead of vanishing
                melt = Math.max(melt, 1 - smooth(0.3F, 0.6F, thunder));
                if (gather < 0.02F || melt > 0.98F) continue;
                level = Math.max(level, 0.4F + 0.6F * gather + 0.6F * climb);
                tower(si, sj, x, z, gather, climb, spread, melt, level, wx, wz, out);
            }
        }
    }

    /** When the storm level crosses 1 (the rain line) between two samples (ticks). */
    private static float cross(long t0, float c0, long t1, float c1) {
        float f = Math.abs(c1 - c0) < 1e-4F ? 0.5F : Mth.clamp((1 - c0) / (c1 - c0), 0, 1);
        return t0 + (t1 - t0) * f;
    }

    /** One storm's puffs: a wide body, a tower of stacked puffs narrowing upward, the anvil, an overshooting top. */
    private void tower(int si, int sj, float x, float z, float gather, float climb, float spread, float melt, float c,
                       float wx, float wz, Domes out) {
        float keep = 1 - 0.85F * melt;
        float base = -16 * climb;
        float size = 0.8F + 0.4F * hash01(si, sj, 3);
        // body: wide heaps on a shared flat base
        for (int p = 0; p < 14; p++) {
            float a = hash01(si, sj, 10 + p) * Mth.TWO_PI, d = (p == 0 ? 0 : 40 + 150 * Mth.sqrt(hash01(si, sj, 30 + p))) * size;
            float appear = smooth(p * 0.04F, 0.3F + p * 0.04F, gather);
            float r = (45 + 35 * hash01(si, sj, 50 + p)) * size * (0.5F + 0.5F * gather) * appear * keep;
            float h = (14 + 40 * gather) * appear * keep;
            out.puff(x + Mth.cos(a) * d, z + Mth.sin(a) * d * 0.85F, base + h * 0.35F, base, r, Math.max(h, 2), c);
        }
        // tower: puffs stacked to the top as it climbs (bulges all the way up, narrower higher)
        float top = TOWER * climb * (1 - melt);
        for (int k = 0; 50 + k * 26 < top; k++) {
            float y = base + 50 + k * 26, u = (50 + k * 26) / TOWER;
            for (int p = 0; p < 3; p++) {
                float pr = (55 + 28 * hash01(si, sj, 100 + k * 3 + p)) * (1.15F - 0.3F * u) * size * keep;
                float ox = (hash01(si, sj, 200 + k * 3 + p) - 0.5F) * 60 * size, oz = (hash01(si, sj, 300 + k * 3 + p) - 0.5F) * 50 * size;
                out.puff(x + ox, z + oz, y, base, pr, pr * 0.85F, c * (1 - 0.25F * u));
            }
        }
        // anvil: a flat sheet at the top, drawn out downwind; an overshooting dome over the strongest part
        if (spread > 0.02F && top > 100) {
            float along = (100 + 280 * spread) * size, across = (70 + 150 * spread) * size;
            float cy = base + top - 4;
            out.add(x + wx * along * 0.55F, z + wz * along * 0.55F, cy, cy - 14, along, across, 9 + 7 * spread, wx, wz,
                    Math.min(c, 1.6F) * 0.85F, ANVIL);
            if (spread < 0.95F) out.puff(x, z, cy + 6, cy - 10, 55 * size, 16 * (1 - melt), c * 0.7F);
        }
    }

    /**
     * Fair-weather heaps at jittered sites: each lives its own life (forms, lives, dissolves), more on warm summer days,
     * fewer as a deck closes in. big: cumulus (taller, congestus, where it's damp and warm); else small humilis.
     */
    private void fairSites(int cx, int cz, int reach, float amount, Sky map, Domes out, int site, long salt, boolean big) {
        if (amount <= 0) return;
        int s0x = Math.floorDiv(cx - reach - site, site), s1x = Math.floorDiv(cx + reach + site, site);
        int s0z = Math.floorDiv(cz - reach - site, site), s1z = Math.floorDiv(cz + reach + site, site);
        for (int si = s0x; si <= s1x; si++) {
            for (int sj = s0z; sj <= s1z; sj++) {
                double ax = (si + 0.15 + 0.7 * hash01(si, sj, salt)) * site, az = (sj + 0.15 + 0.7 * hash01(si, sj, salt + 1)) * site;
                float x = (float) (ax - cx), z = (float) (az - cz);
                // A fair-weather heap keeps its shape: it only drifts with the wind. Whether it's there and how big comes
                // from the held sky at its spot (which only changes when the weather there really changes, i.e. rain or
                // a storm coming), not from a timer or the time of day.
                float deck = map.map(map.map0(), 0, x, z), thunder = map.map(map.map1(), 3, x, z), c = map.map(map.map0(), 3, x, z);
                float warm = map.map(map.map1(), 2, x, z) / Math.max(1e-3F, 1 - Mth.clamp(c - 1, 0, 1));
                warm = Mth.clamp((warm - 0.25F) / 0.75F, 0, 1);
                float presence = (big ? 0.25F + 0.55F * warm : 0.3F + 0.4F * warm) * amount
                        * (1 - smooth(0.4F, 0.85F, deck)) * (1 - smooth(0.35F, 0.6F, thunder));
                float gate = smooth(0, 0.12F, presence - hash01(si, sj, salt + 2));
                if (gate < 0.05F) continue;
                float base = 4 * hash01(si, sj, salt + 5);
                if (big) {
                    float tall = 1 + 1.6F * warm * smooth(0.3F, 0.9F, c);
                    float r = (55 + 45 * hash01(si, sj, salt + 6)) * gate;
                    heap(si, sj, salt, x, z, base, r, r * (0.7F + 0.4F * hash01(si, sj, salt + 7)) * tall, 9, 0.9F, 0, c * 0.5F, out);
                } else {
                    float r = (26 + 22 * hash01(si, sj, salt + 6)) * gate;
                    heap(si, sj, salt, x, z, base, r, (14 + 12 * hash01(si, sj, salt + 7)) * gate, 4, 0.7F, 0.3F, c * 0.5F, out);
                }
            }
        }
    }

    /** A heap: `puffs` round puffs clustered, centred higher in the middle, cut flat at the base. */
    private void heap(int si, int sj, long salt, float x, float z, float base, float r, float h, int puffs, float spreadShare,
                      float flat, float c, Domes out) {
        for (int p = 0; p < puffs; p++) {
            float a = hash01(si, sj, salt * 31 + 20 + p) * Mth.TWO_PI;
            float d = p == 0 ? 0 : r * spreadShare * Mth.sqrt(hash01(si, sj, salt * 31 + 40 + p));
            float central = 1 - d / (r * spreadShare + 1e-3F);
            float pr = r * (0.45F + 0.3F * hash01(si, sj, salt * 31 + 60 + p));
            float cy = base + Math.max(0, h - pr * 0.9F) * (0.35F + 0.65F * central) * (0.7F + 0.3F * hash01(si, sj, salt * 31 + 80 + p));
            out.puff(x + Mth.cos(a) * d, z + Mth.sin(a) * d, cy, base, pr, pr * (1 - flat), c);
        }
    }

    /** Sunlight reaching the ground per column (straight up; the shader shifts it along the sun). */
    private static float[] shadows(Sky map, Domes domes, int size, float x0, float z0) {
        float[] thick = new float[size * size];
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                double x = x0 + (i + 0.5) * SHADOW_CELL, z = z0 + (j + 0.5) * SHADOW_CELL;
                float amount = map.map(map.map0(), 0, x, z);
                if (amount > 0.3F)
                    thick[j * size + i] += (amount - 0.3F) / 0.7F * (map.map(map.map0(), 2, x, z) - map.map(map.map0(), 1, x, z));
            }
        }
        float[] d = domes.data;
        for (int k = 0; k < domes.count; k++) {
            int o = k * DOME_FLOATS;
            float rx = Math.max(d[o + 4], d[o + 5]);
            int i0 = Math.max(0, Mth.floor((d[o] - rx - x0) / SHADOW_CELL)), i1 = Math.min(size - 1, Mth.floor((d[o] + rx - x0) / SHADOW_CELL));
            int j0 = Math.max(0, Mth.floor((d[o + 1] - rx - z0) / SHADOW_CELL)), j1 = Math.min(size - 1, Mth.floor((d[o + 1] + rx - z0) / SHADOW_CELL));
            for (int j = j0; j <= j1; j++) {
                for (int i = i0; i <= i1; i++) {
                    double dx = x0 + (i + 0.5) * SHADOW_CELL - d[o], dz = z0 + (j + 0.5) * SHADOW_CELL - d[o + 1];
                    double along = (dx * d[o + 7] + dz * d[o + 8]) / d[o + 4], across = (-dx * d[o + 8] + dz * d[o + 7]) / d[o + 5];
                    double r2 = along * along + across * across;
                    if (r2 >= 1) continue;
                    float t = (float) (2 * d[o + 6] * Math.sqrt(1 - r2) * 0.8);
                    thick[j * size + i] = Math.max(thick[j * size + i], t);
                }
            }
        }
        for (int n = 0; n < thick.length; n++) thick[n] = (float) Math.exp(-0.03 * thick[n]);
        return thick;
    }
}
