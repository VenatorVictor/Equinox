package dev.venatorvictor.equinox.weather;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

import java.util.Arrays;

/**
 * The storm map: one smooth noise over (x, z, time) that slides with the wind and slowly changes shape, so storms form,
 * grow, move and die. A pure function of the seed and the game time: the server and every client work out the same
 * storms from the seed alone, and the past can be asked too (snow catching up on a chunk that was unloaded).
 * The noise is turned into an even 0..1 value (u): a spot with rain share f is under a storm when u > 1 - f.
 */
public final class StormField {
    /** The wind keeps its speed and direction for this many ticks, then takes the next step's. */
    public static final int STEP = 1200;
    /**
     * Main storm size and a second, smaller octave for ragged edges (blocks), and the ticks for the shapes to change.
     * Picked so a spot with vanilla's rain share gets vanilla's spells: rain ~18 000 ticks, clear ~92 000 (vanilla
     * means 18 000 and 96 000), with the wind at 0.05 blocks a tick.
     */
    private static final double SCALE = 4000, SCALE_SMALL = 800, SMALL = 0.15;
    private static final double SHAPE_TICKS = 80000, SHAPE_TICKS_SMALL = 30000;
    /** Wind speed range (blocks a tick) and how many steps the direction and speed take to change. */
    private static final double SPEED_MIN = 0.03, SPEED_MAX = 0.07, TURN_STEPS = 40, SPEED_STEPS = 30;
    private static final int QUANTILES = 256, SAMPLES = 16384;

    private final SimplexNoise big, small, direction, speed;
    /** Noise values at even steps of u (QUANTILES + 1), so u = the share of the map below a value. */
    private final double[] quantiles = new double[QUANTILES + 1];
    /** Wind offset at the start of each step, worked out from tick 0 as far as asked. */
    private double[] offsetX = new double[1024], offsetZ = new double[1024];
    private int known = 1;

    public StormField(long seed) {
        big = new SimplexNoise(RandomSource.create(seed));
        small = new SimplexNoise(RandomSource.create(seed ^ 0x5DEECE66DL));
        direction = new SimplexNoise(RandomSource.create(seed ^ 0x2545F4914F6CDD1DL));
        speed = new SimplexNoise(RandomSource.create(seed ^ 0x9E3779B97F4A7C15L));
        RandomSource random = RandomSource.create(seed ^ 0x51ED270B27C3A6EDL);
        double[] values = new double[SAMPLES];
        for (int i = 0; i < SAMPLES; i++)
            values[i] = raw(random.nextDouble() * 4e5, random.nextDouble() * 4e5, random.nextDouble() * 1e5);
        Arrays.sort(values);
        for (int i = 0; i <= QUANTILES; i++) quantiles[i] = values[(int) ((long) i * (SAMPLES - 1) / QUANTILES)];
    }

    private double raw(double x, double z, double time) {
        return big.getValue(x / SCALE, z / SCALE, time / SHAPE_TICKS)
                + SMALL * small.getValue(x / SCALE_SMALL, z / SCALE_SMALL, time / SHAPE_TICKS_SMALL + 100);
    }

    /** Wind direction (radians) during step k. */
    public double windAngle(long k) {
        return Math.PI * 2 * direction.getValue(k / TURN_STEPS, 0.5);
    }

    /** Wind speed (blocks a tick) during step k. */
    public double windSpeed(long k) {
        double n = speed.getValue(k / SPEED_STEPS, 7.5) * 0.5 + 0.5;
        return SPEED_MIN + (SPEED_MAX - SPEED_MIN) * Math.max(0, Math.min(1, n));
    }

    /** How far the storm map has moved by game time t: {x, z}. */
    public synchronized double[] offset(long time) {
        long t = Math.max(0, time);
        int k = (int) Math.min(Integer.MAX_VALUE - 2, t / STEP);
        while (known <= k) {
            if (known >= offsetX.length) {
                offsetX = Arrays.copyOf(offsetX, offsetX.length * 2);
                offsetZ = Arrays.copyOf(offsetZ, offsetZ.length * 2);
            }
            int prev = known - 1;
            double angle = windAngle(prev), v = windSpeed(prev) * STEP;
            offsetX[known] = offsetX[prev] + Math.cos(angle) * v;
            offsetZ[known] = offsetZ[prev] + Math.sin(angle) * v;
            known++;
        }
        double angle = windAngle(k), v = windSpeed(k) * (t - (long) k * STEP);
        return new double[] {offsetX[k] + Math.cos(angle) * v, offsetZ[k] + Math.sin(angle) * v};
    }

    /** The even storm value u (0..1) at (x, z) at game time t, with the map's offset at that time. */
    public float value(double x, double z, long time, double[] offset) {
        double n = raw(x - offset[0], z - offset[1], time);
        if (n <= quantiles[0]) return 0;
        if (n >= quantiles[QUANTILES]) return 1;
        int lo = 0, hi = QUANTILES;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (quantiles[mid] <= n) lo = mid;
            else hi = mid;
        }
        double span = quantiles[hi] - quantiles[lo];
        double part = span <= 0 ? 0 : (n - quantiles[lo]) / span;
        return (float) ((lo + part) / QUANTILES);
    }
}
