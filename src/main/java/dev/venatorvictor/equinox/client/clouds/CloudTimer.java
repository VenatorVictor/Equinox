package dev.venatorvictor.equinox.client.clouds;

import dev.venatorvictor.equinox.Equinox;

import static org.lwjgl.opengl.GL15.GL_QUERY_RESULT_AVAILABLE;
import static org.lwjgl.opengl.GL15.glGenQueries;
import static org.lwjgl.opengl.GL15.glGetQueryObjecti;
import static org.lwjgl.opengl.GL33.GL_TIMESTAMP;
import static org.lwjgl.opengl.GL33.glGetQueryObjecti64;
import static org.lwjgl.opengl.GL33.glQueryCounter;

/**
 * Graphics card time of each part of the clouds, for the cloud test only (-Dequinox.cloudTest=true): a timestamp
 * before each part, read a few frames later (no waiting), averaged and logged every 5 s with the CPU time.
 */
public final class CloudTimer {
    public static final String[] PARTS = {"make", "shadows", "depth", "colour", "rain"};
    private static final int FRAMES = 4;
    private static final boolean ON = Boolean.getBoolean("equinox.cloudTest");
    private static int[][] queries;
    private static final boolean[] used = new boolean[FRAMES];
    private static final boolean[][] marked = new boolean[FRAMES][PARTS.length + 1];
    private static int frame;
    private static final double[] gpu = new double[PARTS.length];
    private static double cpu;
    private static final double[] cpuPart = new double[PARTS.length];
    private static long cpuStart, logged, partStart;
    private static int part = -1;
    private static int counted, cpuFrames;

    /** Before the first part of a frame. */
    public static void start() {
        if (!ON) return;
        if (queries == null) {
            queries = new int[FRAMES][PARTS.length + 1];
            for (int[] q : queries) for (int i = 0; i < q.length; i++) q[i] = glGenQueries();
        }
        frame = (frame + 1) % FRAMES;
        // read this slot's results from FRAMES frames ago, if they're in
        if (used[frame]) {
            int[] q = queries[frame];
            if (glGetQueryObjecti(q[PARTS.length], GL_QUERY_RESULT_AVAILABLE) != 0) {
                long last = -1;
                int lastPart = -1;
                for (int i = 0; i <= PARTS.length; i++) {
                    if (!marked[frame][i]) continue;
                    long t = glGetQueryObjecti64(q[i], org.lwjgl.opengl.GL15.GL_QUERY_RESULT);
                    if (lastPart >= 0) gpu[lastPart] += (t - last) / 1e6;
                    last = t;
                    lastPart = i < PARTS.length ? i : -1;
                }
                counted++;
            }
        }
        used[frame] = true;
        java.util.Arrays.fill(marked[frame], false);
        cpuStart = partStart = System.nanoTime();
        part = -1;
    }

    /** Before a part (index into PARTS); the part ends at the next mark or at end(). */
    public static void mark(int next) {
        if (!ON || queries == null) return;
        long now = System.nanoTime();
        if (part >= 0) cpuPart[part] += (now - partStart) / 1e6;
        part = next;
        partStart = now;
        glQueryCounter(queries[frame][next], GL_TIMESTAMP);
        marked[frame][next] = true;
    }

    /** After the last part of a frame. */
    public static void end() {
        if (!ON || queries == null) return;
        glQueryCounter(queries[frame][PARTS.length], GL_TIMESTAMP);
        marked[frame][PARTS.length] = true;
        if (part >= 0) cpuPart[part] += (System.nanoTime() - partStart) / 1e6;
        part = -1;
        cpu += (System.nanoTime() - cpuStart) / 1e6;
        cpuFrames++;
        long now = System.nanoTime();
        if (now - logged < 5_000_000_000L) return;
        logged = now;
        StringBuilder sb = new StringBuilder("CLOUDTEST time ms/frame: cpu ").append(String.format(java.util.Locale.ROOT, "%.3f", cpu / Math.max(1, cpuFrames)));
        for (int i = 0; i < PARTS.length; i++)
            sb.append(' ').append(PARTS[i]).append(' ').append(String.format(java.util.Locale.ROOT, "%.3f", gpu[i] / Math.max(1, counted)));
        sb.append(" | cpu parts");
        for (int i = 0; i < PARTS.length; i++)
            sb.append(' ').append(PARTS[i]).append(' ').append(String.format(java.util.Locale.ROOT, "%.3f", cpuPart[i] / Math.max(1, cpuFrames)));
        Equinox.LOGGER.info(sb.toString());
        java.util.Arrays.fill(cpuPart, 0);
        java.util.Arrays.fill(gpu, 0);
        cpu = 0;
        counted = cpuFrames = 0;
    }

    private CloudTimer() {}
}
