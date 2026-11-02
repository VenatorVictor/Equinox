package dev.venatorvictor.equinox.client.colors;

import net.minecraft.world.level.FoliageColor;

/**
 * Seasonal color tables, indexed by year step (24 a year, 2 per season part: 0 = start of early spring,
 * 23 = end of late winter; the solar terms).
 * Grass entries: {grass color, mix, leaf color}; the leaf color is pre-blended with the default foliage
 * color exactly like the seasonal texture format expects. Leaf tables: {color, mix}.
 */
public final class ColorTables {
    public static final int STEPS = 24;

    // Base colors.
    private static final int GREEN = 8370190;   // #7FB80E
    private static final int DRY = 13734463;    // #D1923F
    private static final int WINTER = 12689779; // #C1A173
    private static final int GOLD = 16765952;   // #FFD400

    public record Term(int grass, float mix, int leaf) {}

    public static final Term[] TEMPERATE = {
            t(WINTER, .32F, WINTER, .32F),
            t(WINTER, .16F, WINTER, .16F),
            t(0, 0F),
            t(0, 0F),
            t(GREEN, .16F),
            t(GREEN, .32F),
            t(GREEN, .48F),
            t(GREEN, .64F, GREEN, .1F),
            t(GREEN, .8F, GREEN, .2F),
            t(GREEN, .8F, GREEN, .3F),
            t(mix(GREEN, .8F, DRY, .2F), .8F, GREEN, .4F),
            t(mix(GREEN, .6F, DRY, .4F), .8F, GOLD, .25F),
            t(mix(GREEN, .4F, DRY, .6F), .8F, GOLD, .5F),
            t(mix(GREEN, .2F, DRY, .8F), .8F, GOLD, .75F),
            t(DRY, .8F, GOLD, 1F),
            t(DRY, .8F, GOLD, 1F),
            t(mix(DRY, .8F, WINTER, .2F), .8F, GOLD, 1F),
            t(mix(DRY, .6F, WINTER, .4F), .8F, mix(GOLD, .8F, WINTER, .2F), 1F),
            t(mix(DRY, .4F, WINTER, .6F), .8F, mix(GOLD, .6F, WINTER, .4F), 1F),
            t(mix(DRY, .2F, WINTER, .8F), .8F, mix(GOLD, .4F, WINTER, .6F), 1F),
            t(WINTER, .8F, mix(GOLD, .2F, WINTER, .8F), 1F),
            t(WINTER, .8F, WINTER, .8F),
            t(WINTER, .64F, WINTER, .64F),
            t(WINTER, .48F, WINTER, .48F)};

    public static final Term[] MONSOONAL = {
            t(0, 0F),
            t(0, 0F),
            t(0, 0F),
            t(0, 0F),
            t(GREEN, .16F),
            t(GREEN, .32F),
            t(GREEN, .48F),
            t(GREEN, .64F, GREEN, .1F),
            t(GREEN, .8F, GREEN, .2F),
            t(GREEN, .8F, GREEN, .3F),
            t(mix(GREEN, .8F, DRY, .2F), .8F, GREEN, .4F),
            t(GREEN, .5F, GOLD, .25F),
            t(GREEN, .5F, GOLD, .5F),
            t(GREEN, .5F, GOLD, .75F),
            t(GREEN, .5F, GOLD, 1F),
            t(GREEN, .4F, GOLD, 1F),
            t(GREEN, .3F, GOLD, 1F),
            t(GREEN, .2F, mix(GOLD, .8F, WINTER, .2F), 1F),
            t(GREEN, .1F, mix(GOLD, .6F, WINTER, .4F), 1F),
            t(0, 0F),
            t(0, 0F),
            t(0, 0F),
            t(0, 0F),
            t(0, 0F)};

    public static final Term[] COLD = {
            t(WINTER, .2F, WINTER, .2F),
            t(WINTER, .15F, WINTER, .15F),
            t(0, 0F),
            t(0, 0F),
            t(WINTER, .1F, WINTER, .1F),
            t(WINTER, .16F, WINTER, .16F),
            t(0, 0F),
            t(GREEN, .16F),
            t(GREEN, .4F, GREEN, .2F),
            t(GREEN, .4F, GREEN, .25F),
            t(mix(GREEN, .8F, DRY, .2F), .4F, GREEN, .4F),
            t(mix(GREEN, .4F, DRY, .6F), .4F, GOLD, .5F),
            t(DRY, .4F, GOLD, .5F),
            t(mix(DRY, .8F, WINTER, .2F), .4F, GOLD, .5F),
            t(mix(DRY, .6F, WINTER, .4F), .4F, mix(GOLD, .8F, WINTER, .2F), .5F),
            t(mix(DRY, .5F, WINTER, .5F), .4F, mix(GOLD, .6F, WINTER, .4F), .5F),
            t(mix(DRY, .4F, WINTER, .6F), .4F, mix(GOLD, .58F, WINTER, .42F), .5F),
            t(mix(DRY, .35F, WINTER, .65F), .4F, mix(GOLD, .55F, WINTER, .45F), .5F),
            t(mix(DRY, .32F, WINTER, .68F), .4F, mix(GOLD, .5F, WINTER, .5F), .5F),
            t(WINTER, .4F, WINTER, .4F),
            t(WINTER, .35F, WINTER, .35F),
            t(WINTER, .32F, WINTER, .32F),
            t(WINTER, .3F, WINTER, .3F),
            t(WINTER, .25F, WINTER, .25F)};

    public static final Term[] HOT = {
            t(WINTER, .1F, WINTER, .1F),
            t(0, 0F),
            t(GREEN, .08F),
            t(GREEN, .16F),
            t(GREEN, .2F),
            t(GREEN, .25F),
            t(GREEN, .3F, GREEN, .05F),
            t(GREEN, .35F, GREEN, .1F),
            t(GREEN, .4F, GREEN, .15F),
            t(GREEN, .4F, GREEN, .2F),
            t(mix(GREEN, .8F, DRY, .2F), .4F, GREEN, .2F),
            t(mix(GREEN, .8F, DRY, .2F), .45F, GOLD, .25F),
            t(mix(GREEN, .7F, DRY, .3F), .4F, GOLD, .2F),
            t(mix(GREEN, .6F, DRY, .4F), .4F, GOLD, .2F),
            t(mix(GREEN, .58F, DRY, .42F), .4F, GOLD, .2F),
            t(mix(GREEN, .55F, DRY, .45F), .4F, GOLD, .15F),
            t(mix(GREEN, .52F, DRY, .48F), .4F, GOLD, .12F),
            t(mix(DRY, .5F, WINTER, .5F), .35F, mix(GOLD, .8F, WINTER, .2F), .2F),
            t(mix(DRY, .5F, WINTER, .5F), .35F, mix(GOLD, .6F, WINTER, .4F), .25F),
            t(mix(DRY, .4F, WINTER, .6F), .35F, mix(GOLD, .5F, WINTER, .5F), .35F),
            t(mix(DRY, .3F, WINTER, .7F), .35F, mix(GOLD, .2F, WINTER, .8F), .35F),
            t(WINTER, .35F, WINTER, .35F),
            t(WINTER, .35F, WINTER, .3F),
            t(WINTER, .2F, WINTER, .2F)};

    /** "slightly": temperate colors at a fixed 10 % strength. */
    public static final Term[] SLIGHTLY = withMix(TEMPERATE, .1F);
    /** "stable": no change. */
    public static final Term[] STABLE = withMix(TEMPERATE, 0F);

    public static final float[][] SPRUCE = scale(new float[][]{
            {9880142, .02F}, {9880142, .08F}, {9880142, .15F}, {9880142, .18F}, {9880142, .12F}, {9880142, .1F},
            {6008355, .1F}, {6008355, .06F}, {0, 0F}, {6008355, .1F}, {6008355, .05F}, {0, 0F},
            {12503350, .05F}, {12503350, .1F}, {12503350, .12F}, {12503350, .15F}, {12503350, .18F}, {12503350, .12F},
            {2440484, .18F}, {2440484, .2F}, {2440484, .25F}, {2440484, .15F}, {2440484, .08F}, {2440484, .05F}}, 1.2F);

    public static final float[][] BIRCH = {
            {4436548, 0F}, {4436548, .1F}, {4436548, .2F}, {4436548, .25F}, {4436548, .3F}, {4436548, .4F},
            {13818234, .4F}, {13818234, .5F}, {13818234, .6F}, {13818234, .65F}, {13818234, .55F}, {13818234, .4F},
            {16491540, .42F}, {16491540, .7F}, {16491540, .9F}, {16491540, .95F},
            {mix(15887135, .5F, 16491540, .5F), .8F}, {15887135, .9F},
            {mix(15887135, .7F, 11378271, .3F), .7F}, {mix(15887135, .5F, 11378271, .5F), .4F},
            {11378271, .8F}, {11378271, .7F}, {11378271, .6F}, {11378271, .3F}};

    public static final float[][] MANGROVE = {
            {6143626, .02F}, {6143626, .08F}, {6143626, .15F}, {6143626, .2F}, {6143626, .12F}, {6143626, .1F},
            {2129740, .1F}, {2129740, .06F}, {0, 0F}, {6008355, .1F}, {0, 0F}, {0, 0F},
            {3618868, .05F}, {3618868, .1F}, {3618868, .02F}, {3618868, .05F}, {3618868, .08F}, {3618868, .12F},
            {5982483, .18F}, {5982483, .2F}, {5982483, .25F}, {5982483, .15F}, {5982483, .08F}, {5982483, .05F}};

    /** Simple color mix: per-channel weighted sum (alpha included). */
    public static int mix(int c1, float a1, int c2, float a2) {
        int r = (int) ((c1 >> 16 & 0xFF) * a1 + (c2 >> 16 & 0xFF) * a2);
        int g = (int) ((c1 >> 8 & 0xFF) * a1 + (c2 >> 8 & 0xFF) * a2);
        int b = (int) ((c1 & 0xFF) * a1 + (c2 & 0xFF) * a2);
        int a = (int) ((c1 >>> 24) * a1 + (c2 >>> 24) * a2);
        return a << 24 | r << 16 | g << 8 | b;
    }

    private static Term t(int grass, float mix, int birch, float birchAlpha) {
        return new Term(grass, mix, mix(birch, birchAlpha, FoliageColor.getDefaultColor(), 1F - birchAlpha));
    }

    private static Term t(int grass, float mix) {
        return new Term(grass, mix, FoliageColor.getDefaultColor());
    }

    private static Term[] withMix(Term[] base, float mix) {
        Term[] out = new Term[base.length];
        for (int i = 0; i < base.length; i++) out[i] = new Term(base[i].grass(), mix, base[i].leaf());
        return out;
    }

    // Colors are stored as floats only to keep the tables compact; ints below 2^24 are exact in a float.
    private static float[][] scale(float[][] table, float factor) {
        for (float[] row : table) row[1] *= factor;
        return table;
    }

    private ColorTables() {}
}
