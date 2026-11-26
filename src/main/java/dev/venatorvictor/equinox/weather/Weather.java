package dev.venatorvictor.equinox.weather;

import dev.venatorvictor.equinox.Config;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Weather per spot: storms (StormField) that move with the wind, raining only where the land's climate lets them
 * (ClimateGrid). Strength 0..1 at a spot: 0 outside a storm and at its edge, 1 at its core; thunder in the core.
 * The server and the clients each keep a Side with the same seed, so both see the same storms.
 */
public final class Weather {
    /** Vanilla's share of the time it thunders (thunder 3 600-15 600 ticks, none 12 000-180 000): the storm core. */
    public static final float THUNDER_SHARE = 9600F / (9600F + 96000F);
    /** A column rains where the strength is above its own random number below this: a ragged storm edge. */
    public static final float EDGE = 0.1F;
    /** /weather rain: rain everywhere it can, without thunder. */
    private static final float RAIN_OVERRIDE = 0.5F;
    /** Lattice spacing (blocks) and time step (ticks) of the strength cache. */
    private static final int LATTICE = 16;
    public static final int TIME_STEP = 20;
    /** Chunk loading: how far back the snow history looks, and how often. */
    public static final int HISTORY_TICKS = 7 * 24000, HISTORY_STEP = 1200;

    public enum Override { NONE, CLEAR, RAIN, THUNDER }

    /**
     * Started storms (/weather rain or thunder, snowfall when cold and bare, rain when warm and snowy): radius
     * (blocks), strength at the core (rain; thunder 1), ticks the clouds take to gather before it rains, ticks for
     * the rain to come and go.
     */
    public static final double FORCED_RADIUS = 1200;
    private static final float FORCED_STRENGTH = 0.6F;
    public static final int FORCED_BUILD = 1800;
    private static final int FORCED_FADE = 600;

    /**
     * A storm started over a spot (a player's): the clouds gather there first (FORCED_BUILD), then it rains until
     * `until`; it drifts with the wind from there like any storm. Rain or snow follow the temperature, as always.
     */
    public record Forced(double x, double z, long from, long until, boolean thunder) {
    }

    /** One side's storms: the field, the climate grid, the /weather override, a cache of strengths. */
    public static final class Side {
        public final long seed;
        public final StormField field;
        public final ClimateGrid grid;
        private volatile Override override = Override.NONE;
        private volatile long from, until;
        private volatile java.util.List<Forced> forced = java.util.List.of();
        private final Long2FloatOpenHashMap corners = new Long2FloatOpenHashMap();
        private long cacheTime = Long.MIN_VALUE;
        private double[] cacheOffset;

        public Side(long seed, ClimateGrid grid) {
            this.seed = seed;
            this.field = new StormField(seed);
            this.grid = grid;
        }

        public Override override() {
            return override;
        }

        public long overrideFrom() {
            return from;
        }

        public long overrideUntil() {
            return until;
        }

        public synchronized void setOverride(Override kind, long from, long until) {
            this.override = kind;
            this.from = from;
            this.until = until;
            corners.clear();
        }

        public java.util.List<Forced> forced() {
            return forced;
        }

        /** Replaces the forced storms (ended ones are dropped). */
        public synchronized void setForced(java.util.List<Forced> list) {
            this.forced = java.util.List.copyOf(list);
            corners.clear();
        }

        /**
         * The sky of the started storms at a spot and time, 0..2 as cloudiness: the clouds gather (0 to 1) over
         * FORCED_BUILD ticks, then the rain comes (1 + strength), thinner toward the storm's edge.
         */
        private float forcedSky(double x, double z, long time, double[] offset) {
            float best = 0;
            for (Forced storm : forced) {
                if (time < storm.from() || time >= storm.until()) continue;
                double[] start = field.offset(storm.from());
                double cx = storm.x() + offset[0] - start[0], cz = storm.z() + offset[1] - start[1];
                double d = Math.sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz)) / FORCED_RADIUS;
                if (d >= 1) continue;
                float shape = (float) (1 - d * d * (3 - 2 * d)); // smooth to 0 at the edge
                long age = time - storm.from();
                float gather = Math.min(1, age / (float) FORCED_BUILD);
                float rain = Math.max(0, Math.min(1, Math.min((age - FORCED_BUILD) / (float) FORCED_FADE, (storm.until() - time) / (float) FORCED_FADE)));
                float peak = storm.thunder() ? 1 : FORCED_STRENGTH;
                best = Math.max(best, shape * (gather + peak * rain));
            }
            return best;
        }

        /** The /weather override at a time, or NONE. */
        public Override overrideAt(long time) {
            Override kind = override;
            return kind != Override.NONE && time >= from && time < until ? kind : Override.NONE;
        }

        /** Strength without the cache, at any time (the past too). */
        public float strengthExact(double x, double z, long time) {
            return strength(x, z, time, field.offset(time));
        }

        private float strength(double x, double z, long time, double[] offset) {
            float share = grid.share(x, z);
            if (share <= 0) return 0;
            Override kind = overrideAt(time);
            if (kind == Override.CLEAR) return 0;
            if (kind == Override.THUNDER) return 1;
            float u = field.value(x, z, time, offset);
            float threshold = 1 - share;
            float base = u <= threshold ? 0 : (u - threshold) / share;
            if (!forced.isEmpty()) base = Math.max(base, forcedSky(x, z, time, offset) - 1);
            if (kind == Override.RAIN) return Math.min(Math.max(base, RAIN_OVERRIDE), 1 - THUNDER_SHARE - 0.01F);
            return base;
        }

        /**
         * The sky's look at a spot (for clouds), 0..2: 0 far from storms, rising to 1 at a storm's edge (the rain line),
         * then 1 + strength inside (2 at the thunder core). Clouds close up as a storm comes: from 1.8 times the rain
         * share below the rain line (1.8 times: user 2026-11-26, the sky was too cloudy far from storms).
         */
        public float cloudiness(double x, double z, long time, double[] offset) {
            Override kind = overrideAt(time);
            float share = grid.share(x, z);
            if (kind == Override.CLEAR || share <= 0) return 0;
            if (kind == Override.THUNDER) return 2;
            float s = strength(x, z, time, offset);
            if (s > 0) return 1 + s;
            float u = field.value(x, z, time, offset);
            float approach = (u - (1 - 1.8F * share)) / (0.8F * share);
            float sky = Math.max(0, Math.min(1, approach));
            return forced.isEmpty() ? sky : Math.max(sky, Math.min(1, forcedSky(x, z, time, offset)));
        }

        /** Strength now at a block column, from a 16-block lattice worked out once a second. */
        public synchronized float strength(int x, int z, long time) {
            long step = Math.floorDiv(time, TIME_STEP) * TIME_STEP;
            if (step != cacheTime || corners.size() > 65536) {
                corners.clear();
                cacheTime = step;
                cacheOffset = field.offset(step);
            }
            int cx = Math.floorDiv(x, LATTICE), cz = Math.floorDiv(z, LATTICE);
            float fx = (x - cx * LATTICE) / (float) LATTICE, fz = (z - cz * LATTICE) / (float) LATTICE;
            float a = corner(cx, cz, step), b = corner(cx + 1, cz, step), c = corner(cx, cz + 1, step), d = corner(cx + 1, cz + 1, step);
            return (a + (b - a) * fx) * (1 - fz) + (c + (d - c) * fx) * fz;
        }

        private float corner(int cx, int cz, long step) {
            long key = ChunkPos.asLong(cx, cz);
            if (corners.containsKey(key)) return corners.get(key);
            float value = strength(cx * (double) LATTICE, cz * (double) LATTICE, step, cacheOffset);
            corners.put(key, value);
            return value;
        }

        /** Drops cached strengths (the climate grid changed). */
        public synchronized void forget() {
            corners.clear();
        }

        /** Whether a storm was over a column at any time since a game time (the last HISTORY_TICKS at most). */
        public boolean fellSince(int x, int z, long since, long now) {
            long start = Math.max(since, now - HISTORY_TICKS);
            for (long t = now; t > start; t -= HISTORY_STEP)
                if (strengthExact(x, z, t) > edge(x, z)) return true;
            return false;
        }
    }

    private static volatile Side server, client;

    public static Side server() {
        return server;
    }

    public static Side client() {
        return client;
    }

    static void setServer(Side side) {
        server = side;
    }

    public static void setClient(Side side) {
        client = side;
    }

    public static Side side(Level level) {
        return level.isClientSide() ? client : server;
    }

    /** Per-region weather runs in this level (seasonal dimension, setting on, storms known on this side). */
    public static boolean on(Level level) {
        return Config.regionalWeather() && Config.hasSeasons(level.dimension()) && side(level) != null;
    }

    /** Storm strength (0..1) now at a column. Only when on(level). */
    public static float strength(Level level, int x, int z) {
        return side(level).strength(x, z, level.getGameTime());
    }

    /** This column's own edge number (0..EDGE): it rains where the strength is above it. */
    public static float edge(int x, int z) {
        return (Mth.getSeed(x, 0, z) >>> 40 & 0xFFFF) / 65536F * EDGE;
    }

    /** Rain or snow falls on this column now (whatever the biome says it is). Only when on(level). */
    public static boolean fallsAt(Level level, int x, int z) {
        return strength(level, x, z) > edge(x, z);
    }

    /** The client's game thread and its seasonal level (set each client tick; null: none). */
    public static volatile Thread clientThread;
    public static volatile Level clientLevel;

    /**
     * Client, on its game thread: no storm over this column, so nothing falls here (rain and snow drawing, splashes,
     * rain sounds and rain particle mods ask the biome per column).
     */
    public static boolean clientDry(net.minecraft.core.BlockPos pos) {
        if (Thread.currentThread() != clientThread) return false;
        Level level = clientLevel;
        return level != null && client != null && !fallsAt(level, pos.getX(), pos.getZ());
    }

    /** Thunder over this column now. Only when on(level). */
    public static boolean thunderAt(Level level, int x, int z) {
        return strength(level, x, z) > 1 - THUNDER_SHARE;
    }
}
