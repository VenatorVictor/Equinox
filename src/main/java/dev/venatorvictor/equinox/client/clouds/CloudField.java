package dev.venatorvictor.equinox.client.clouds;

import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * The cloud density on the main processor: the same function as the graphics card's (clouds_gl/common.glsl), for the
 * far clouds that Distant Horizons draws as boxes. Positions relative to the sky's centre, heights above the cloud
 * height (see CloudSky).
 */
public final class CloudField {
    private static final float MID_LOW = CloudSky.MID_LOW, MID_HIGH = CloudSky.MID_HIGH, HIGH_LOW = CloudSky.HIGH_LOW, HIGH_HIGH = CloudSky.HIGH_HIGH;
    /** Dome bins (blocks): each bin knows the domes over it. */
    private static final int BIN = 128;

    /** A box for renderers that take boxes: relative to the sky's centre, heights above the cloud height. */
    public record Box(float x0, float z0, float x1, float z1, float bottom, float top, float storm, float light, int kind) {
    }

    private final CloudSky.Sky sky;
    private final int cube, angle;
    private final float angleF;
    private final int binX0, binZ0, bins;
    private final int[][] binDomes;

    public CloudField(CloudSky.Sky sky, int cube, int reach) {
        this.sky = sky;
        this.cube = cube;
        double a = Math.round(Math.atan2(sky.windZ(), sky.windX()) / (Math.PI * 2) * 16 * 1000) / 1000.0;
        int ai = Mth.floor(a);
        angle = Math.floorMod(ai, 16);
        angleF = (float) (a - ai);
        binX0 = Math.floorDiv(-reach - BIN, BIN);
        binZ0 = binX0;
        bins = 2 * (reach + BIN) / BIN + 2;
        List<List<Integer>> lists = new ArrayList<>();
        for (int i = 0; i < bins * bins; i++) lists.add(new ArrayList<>());
        float[] d = sky.domes();
        for (int k = 0; k < sky.domeCount(); k++) {
            int o = k * CloudSky.DOME_FLOATS;
            float r = Math.max(d[o + 4], d[o + 5]) + 16;
            int i0 = Math.max(0, Mth.floor((d[o] - r) / BIN) - binX0), i1 = Math.min(bins - 1, Mth.floor((d[o] + r) / BIN) - binX0);
            int j0 = Math.max(0, Mth.floor((d[o + 1] - r) / BIN) - binZ0), j1 = Math.min(bins - 1, Mth.floor((d[o + 1] + r) / BIN) - binZ0);
            for (int i = i0; i <= i1; i++) for (int j = j0; j <= j1; j++) lists.get(j * bins + i).add(k);
        }
        binDomes = new int[bins * bins][];
        for (int i = 0; i < binDomes.length; i++) binDomes[i] = lists.get(i).stream().mapToInt(Integer::intValue).toArray();
    }

    public CloudSky.Sky sky() {
        return sky;
    }

    // ---- the same noise as the shader (32-bit unsigned maths in ints)

    static int hash3(int x, int y, int z, int salt) {
        int h = x * 0x8da6b343 ^ y * 0xd8163841 ^ z * 0xcb1ab31f ^ salt * 0x165667b1;
        // the PCG generator's output step
        int state = h * 747796405 + (int) 2891336453L;
        int word = ((state >>> ((state >>> 28) + 4)) ^ state) * 277803737;
        return (word >>> 22) ^ word;
    }

    static float u01(int h) {
        return (h >>> 8) * (1F / 16777216F);
    }

    private static float fade(float f) {
        return f * f * (3 - 2 * f);
    }

    static float vnoise3(int ax, int ay, int az, int s, int salt) {
        int ix = Math.floorDiv(ax, s), iy = Math.floorDiv(ay, s), iz = Math.floorDiv(az, s);
        float fx = fade((ax - ix * s) / (float) s), fy = fade((ay - iy * s) / (float) s), fz = fade((az - iz * s) / (float) s);
        float x00 = Mth.lerp(fx, u01(hash3(ix, iy, iz, salt)), u01(hash3(ix + 1, iy, iz, salt)));
        float x10 = Mth.lerp(fx, u01(hash3(ix, iy + 1, iz, salt)), u01(hash3(ix + 1, iy + 1, iz, salt)));
        float x01 = Mth.lerp(fx, u01(hash3(ix, iy, iz + 1, salt)), u01(hash3(ix + 1, iy, iz + 1, salt)));
        float x11 = Mth.lerp(fx, u01(hash3(ix, iy + 1, iz + 1, salt)), u01(hash3(ix + 1, iy + 1, iz + 1, salt)));
        return Mth.lerp(fz, Mth.lerp(fy, x00, x10), Mth.lerp(fy, x01, x11));
    }

    static float vnoise2(int ax, int az, int s, int salt) {
        int ix = Math.floorDiv(ax, s), iz = Math.floorDiv(az, s);
        float fx = fade((ax - ix * s) / (float) s), fz = fade((az - iz * s) / (float) s);
        return Mth.lerp(fz, Mth.lerp(fx, u01(hash3(ix, 0, iz, salt)), u01(hash3(ix + 1, 0, iz, salt))),
                Mth.lerp(fx, u01(hash3(ix, 0, iz + 1, salt)), u01(hash3(ix + 1, 0, iz + 1, salt))));
    }

    static float vnoise2f(float qx, float qz, int salt) {
        int ix = Mth.floor(qx), iz = Mth.floor(qz);
        float fx = fade(qx - ix), fz = fade(qz - iz);
        return Mth.lerp(fz, Mth.lerp(fx, u01(hash3(ix, 0, iz, salt)), u01(hash3(ix + 1, 0, iz, salt))),
                Mth.lerp(fx, u01(hash3(ix, 0, iz + 1, salt)), u01(hash3(ix + 1, 0, iz + 1, salt))));
    }

    private static float oriented(int ax, int az, int index, float along, float across, int salt) {
        int i = index & 15;
        float a = i * Mth.TWO_PI / 16, c = (float) Math.cos(a), s = (float) Math.sin(a);
        return vnoise2f((ax * c + az * s) / along, (-ax * s + az * c) / across, salt + i * 7919);
    }

    private float orientedWind(int ax, int az, float along, float across, int salt) {
        return Mth.lerp(angleF, oriented(ax, az, angle, along, across, salt), oriented(ax, az, angle + 1, along, across, salt));
    }

    private float band(float y, float lo, float hi) {
        float hb = Math.max((hi - lo) * 0.5F, cube * 0.5F);
        return hb - Math.abs(y - (lo + hi) * 0.5F) + 0.5F;
    }

    /** {blocks inside (> 0 inside), storm level, kind} at a position relative to the centre. */
    public float[] density(float px, float py, float pz, float[] out) {
        int ax = Mth.floor(px) + sky.centerX(), ay = Mth.floor(py), az = Mth.floor(pz) + sky.centerZ();
        float best = -99, storm = 0, kind = 0;
        int bi = Mth.floor(px / BIN) - binX0, bj = Mth.floor(pz / BIN) - binZ0;
        if (bi >= 0 && bj >= 0 && bi < bins && bj < bins && binDomes[bj * bins + bi].length > 0) {
            float bump = vnoise3(ax, ay, az, 24, 1) * 0.65F + vnoise3(ax, ay, az, 9, 2) * 0.35F - 0.5F;
            float[] d = sky.domes();
            for (int k : binDomes[bj * bins + bi]) {
                int o = k * CloudSky.DOME_FLOATS;
                if (py < d[o + 3]) continue;
                float dx = px - d[o], dz = pz - d[o + 1];
                float al = (dx * d[o + 7] + dz * d[o + 8]) / d[o + 4], ac = (-dx * d[o + 8] + dz * d[o + 7]) / d[o + 5];
                float up = (py - d[o + 2]) / d[o + 6];
                float r = Mth.sqrt(al * al + ac * ac + up * up);
                float amp = d[o + 10] > 0.5F ? 3 : Math.min(d[o + 11] * 0.45F, 14);
                float v = (1 - r) * d[o + 11] + bump * 2 * amp;
                if (v > best) {
                    best = v;
                    storm = d[o + 9];
                    kind = 0;
                }
            }
        }
        float low = sky.map(sky.map0(), 0, px, pz);
        float stormHere = sky.map(sky.map0(), 3, px, pz);
        if (low > 0.02F) {
            float bottom = sky.map(sky.map0(), 1, px, pz), top0 = sky.map(sky.map0(), 2, px, pz);
            if (py > bottom - cube && py < top0 + cube) {
                float m = vnoise2(ax, az, 96, 3) * 0.6F + vnoise2(ax, az, 32, 4) * 0.4F;
                float h = (low * 1.1F - 0.05F - m) * 60;
                float top = top0 - sky.map(sky.map1(), 2, px, pz) * (top0 - bottom) * 0.6F * vnoise2(ax, az, 16, 5);
                float v = Math.min(h, band(py, bottom, Math.max(top, bottom + 4)));
                if (v > best) {
                    best = v;
                    storm = stormHere;
                    kind = 0;
                }
            }
        }
        float mid = sky.map(sky.map1(), 0, px, pz);
        if (mid > 0.02F) {
            float bv = band(py, MID_LOW, MID_HIGH);
            if (bv > 0) {
                float val = orientedWind(ax, az, 26, 160, 6) * 0.55F + vnoise2(ax, az, 20, 7) * 0.45F;
                float v = Math.min((val - (1 - 0.6F * mid)) * 40, bv);
                if (v > best) {
                    best = v;
                    storm = stormHere * 0.8F;
                    kind = 1;
                }
            }
        }
        float high = sky.map(sky.map1(), 1, px, pz);
        if (high > 0.02F) {
            float bv = band(py, HIGH_LOW, HIGH_HIGH);
            if (bv > 0) {
                float val = orientedWind(ax, az, 420, 22, 8);
                float v = Math.min((val - (1 - 0.35F * high)) * 50, bv);
                if (v > best) {
                    best = v;
                    storm = stormHere * 0.5F;
                    kind = 2;
                }
            }
        }
        out[0] = best;
        out[1] = storm;
        out[2] = kind;
        return out;
    }

    /**
     * The clouds between two distances from the centre as boxes of `cube`-block cells: each column's runs of cloud,
     * equal runs next to each other along x joined. light: brighter on top of a run, darker at its base.
     */
    public List<Box> boxes(float inner, float outer) {
        List<Box> out = new ArrayList<>();
        int layers = (int) (CloudSky.TOP - CloudSky.BOTTOM) / cube;
        int n0 = Mth.floor(-outer / cube), n1 = Mth.floor(outer / cube);
        float[] d = new float[3];
        int[] runBottom = new int[layers], runTop = new int[layers], runKind = new int[layers];
        float[] runStorm = new float[layers];
        for (int j = n0; j <= n1; j++) {
            Box open = null;
            for (int i = n0; i <= n1 + 1; i++) {
                float x = (i + 0.5F) * cube, z = (j + 0.5F) * cube;
                float dist = Mth.sqrt(x * x + z * z);
                int runs = 0;
                if (i <= n1 && dist >= inner && dist <= outer) {
                    int start = -1;
                    float storm = 0;
                    int kind = 0;
                    for (int l = 0; l <= layers; l++) {
                        boolean on = false;
                        if (l < layers) {
                            density(x, CloudSky.BOTTOM + (l + 0.5F) * cube, z, d);
                            on = d[0] > 0;
                        }
                        if (on && start < 0) {
                            start = l;
                            storm = d[1];
                            kind = (int) d[2];
                        } else if (on) storm = Math.max(storm, d[1]);
                        if (!on && start >= 0) {
                            runBottom[runs] = start;
                            runTop[runs] = l;
                            runStorm[runs] = Math.round(storm * 8) / 8F;
                            runKind[runs] = kind;
                            runs++;
                            start = -1;
                        }
                    }
                }
                // join along x: only single-run columns continue a box (simple and enough far away)
                if (open != null && (runs != 1 || open.bottom() != CloudSky.BOTTOM + runBottom[0] * cube
                        || open.top() != CloudSky.BOTTOM + runTop[0] * cube || open.storm() != runStorm[0] || open.kind() != runKind[0])) {
                    out.add(open);
                    open = null;
                }
                for (int r = 0; r < runs; r++) {
                    float bottom = CloudSky.BOTTOM + runBottom[r] * cube, top = CloudSky.BOTTOM + runTop[r] * cube;
                    float light = Mth.clamp(0.55F + (top - bottom) / 120F, 0.55F, 0.85F);
                    if (runs == 1 && open != null) {
                        open = new Box(open.x0(), open.z0(), (i + 1) * cube, open.z1(), bottom, top, runStorm[r], light, runKind[r]);
                    } else if (runs == 1) {
                        open = new Box(i * cube, j * cube, (i + 1) * cube, (j + 1) * cube, bottom, top, runStorm[r], light, runKind[r]);
                    } else {
                        out.add(new Box(i * cube, j * cube, (i + 1) * cube, (j + 1) * cube, bottom, top, runStorm[r], light, runKind[r]));
                    }
                }
            }
            if (open != null) out.add(open);
        }
        return out;
    }
}
