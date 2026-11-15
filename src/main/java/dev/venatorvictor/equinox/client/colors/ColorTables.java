package dev.venatorvictor.equinox.client.colors;

import net.minecraft.world.level.FoliageColor;

/**
 * Seasonal color tables, indexed by week (14 a season, 56 a year: 0 = spring week 1, the spring equinox; 55 = winter
 * week 14), on the calendar. Each entry is the color reached at the end of its week (blended daily from the week
 * before). Converted 2026-11-14 from the older 24-step tables (tools/tables_to_weeks.py), same colors on the same dates.
 * Grass entries: {grass color, mix, leaf color}; the leaf color is pre-blended with the default foliage
 * color exactly like the seasonal texture format expects. Leaf tables: {color, mix}.
 */
public final class ColorTables {
    public static final int STEPS = dev.venatorvictor.equinox.api.SeasonState.WEEKS;

    // Base colors.
    private static final int GREEN = 8370190;   // #7FB80E
    private static final int DRY = 13734463;    // #D1923F
    private static final int WINTER = 12689779; // #C1A173
    private static final int GOLD = 16765952;   // #FFD400

    public record Term(int grass, float mix, int leaf) {}

    public static final Term[] TEMPERATE = {
            t(WINTER, .251F, WINTER, .251F), t(WINTER, .183F, WINTER, .183F), t(WINTER, .114F, WINTER, .114F), t(WINTER, .046F, WINTER, .046F), t(0, 0F), t(0, 0F), t(0, 0F),   // spring 1-7
            t(GREEN, .069F), t(GREEN, .137F), t(GREEN, .206F), t(GREEN, .274F), t(GREEN, .343F), t(GREEN, .411F), t(GREEN, .48F),   // spring 8-14
            t(GREEN, .48F), t(GREEN, .64F, GREEN, .1F), t(GREEN, .686F, GREEN, .129F), t(GREEN, .754F, GREEN, .171F), t(GREEN, .8F, GREEN, .214F), t(GREEN, .8F, GREEN, .257F), t(GREEN, .8F, GREEN, .3F),   // summer 1-7
            t(GREEN, .8F, GREEN, .3F), t(mix(GREEN, .8F, DRY, .2F), .8F, GREEN, .4F), t(mix(GREEN, .8F, DRY, .2F), .8F, GREEN, .4F), t(mix(GREEN, .6F, DRY, .4F), .8F, GOLD, .25F), t(mix(GREEN, .571F, DRY, .429F), .8F, GOLD, .286F), t(mix(GREEN, .486F, DRY, .514F), .8F, GOLD, .393F), t(mix(GREEN, .4F, DRY, .6F), .8F, GOLD, .5F),   // summer 8-14
            t(mix(GREEN, .314F, DRY, .686F), .8F, GOLD, .607F), t(mix(GREEN, .229F, DRY, .771F), .8F, GOLD, .714F), t(mix(GREEN, .2F, DRY, .8F), .8F, GOLD, .75F), t(DRY, .8F, GOLD, 1F), t(DRY, .8F, GOLD, 1F), t(DRY, .8F, GOLD, 1F), t(DRY, .8F, GOLD, 1F),   // autumn 1-7
            t(DRY, .8F, GOLD, 1F), t(mix(DRY, .8F, WINTER, .2F), .8F, GOLD, 1F), t(mix(DRY, .8F, WINTER, .2F), .8F, GOLD, 1F), t(mix(DRY, .6F, WINTER, .4F), .8F, mix(GOLD, .8F, WINTER, .2F), 1F), t(mix(DRY, .571F, WINTER, .429F), .8F, mix(GOLD, .771F, WINTER, .229F), 1F), t(mix(DRY, .486F, WINTER, .514F), .8F, mix(GOLD, .686F, WINTER, .314F), 1F), t(mix(DRY, .4F, WINTER, .6F), .8F, mix(GOLD, .6F, WINTER, .4F), 1F),   // autumn 8-14
            t(mix(DRY, .314F, WINTER, .686F), .8F, mix(GOLD, .514F, WINTER, .486F), 1F), t(mix(DRY, .229F, WINTER, .771F), .8F, mix(GOLD, .429F, WINTER, .571F), 1F), t(mix(DRY, .2F, WINTER, .8F), .8F, mix(GOLD, .4F, WINTER, .6F), 1F), t(WINTER, .8F, mix(GOLD, .2F, WINTER, .8F), 1F), t(WINTER, .8F, mix(GOLD, .2F, WINTER, .8F), 1F), t(WINTER, .8F, WINTER, .8F), t(WINTER, .8F, WINTER, .8F),   // winter 1-7
            t(WINTER, .731F, WINTER, .731F), t(WINTER, .663F, WINTER, .663F), t(WINTER, .594F, WINTER, .594F), t(WINTER, .526F, WINTER, .526F), t(WINTER, .457F, WINTER, .457F), t(WINTER, .389F, WINTER, .389F), t(WINTER, .32F, WINTER, .32F)   // winter 8-14
    };

    public static final Term[] MONSOONAL = {
            t(0, 0F), t(0, 0F), t(0, 0F), t(0, 0F), t(0, 0F), t(0, 0F), t(0, 0F),   // spring 1-7
            t(GREEN, .069F), t(GREEN, .137F), t(GREEN, .206F), t(GREEN, .274F), t(GREEN, .343F), t(GREEN, .411F), t(GREEN, .48F),   // spring 8-14
            t(GREEN, .48F), t(GREEN, .64F, GREEN, .1F), t(GREEN, .686F, GREEN, .129F), t(GREEN, .754F, GREEN, .171F), t(GREEN, .8F, GREEN, .214F), t(GREEN, .8F, GREEN, .257F), t(GREEN, .8F, GREEN, .3F),   // summer 1-7
            t(GREEN, .8F, GREEN, .3F), t(mix(GREEN, .8F, DRY, .2F), .8F, GREEN, .4F), t(mix(GREEN, .8F, DRY, .2F), .8F, GREEN, .4F), t(GREEN, .5F, GOLD, .25F), t(GREEN, .5F, GOLD, .286F), t(GREEN, .5F, GOLD, .393F), t(GREEN, .5F, GOLD, .5F),   // summer 8-14
            t(GREEN, .5F, GOLD, .607F), t(GREEN, .5F, GOLD, .714F), t(GREEN, .5F, GOLD, .821F), t(GREEN, .5F, GOLD, .929F), t(GREEN, .486F, GOLD, 1F), t(GREEN, .443F, GOLD, 1F), t(GREEN, .4F, GOLD, 1F),   // autumn 1-7
            t(GREEN, .357F, GOLD, 1F), t(GREEN, .314F, GOLD, 1F), t(GREEN, .3F, GOLD, 1F), t(GREEN, .2F, mix(GOLD, .8F, WINTER, .2F), 1F), t(GREEN, .186F, mix(GOLD, .771F, WINTER, .229F), 1F), t(GREEN, .143F, mix(GOLD, .686F, WINTER, .314F), 1F), t(GREEN, .1F, mix(GOLD, .6F, WINTER, .4F), 1F),   // autumn 8-14
            t(GREEN, .057F, mix(GOLD, .6F, WINTER, .4F), .571F), t(GREEN, .014F, mix(GOLD, .6F, WINTER, .4F), .143F), t(0, 0F), t(0, 0F), t(0, 0F), t(0, 0F), t(0, 0F),   // winter 1-7
            t(0, 0F), t(0, 0F), t(0, 0F), t(0, 0F), t(0, 0F), t(0, 0F), t(0, 0F)   // winter 8-14
    };

    public static final Term[] COLD = {
            t(WINTER, .179F, WINTER, .179F), t(WINTER, .157F, WINTER, .157F), t(WINTER, .107F, WINTER, .107F), t(WINTER, .043F, WINTER, .043F), t(0, 0F), t(0, 0F), t(0, 0F),   // spring 1-7
            t(WINTER, .043F, WINTER, .043F), t(WINTER, .086F, WINTER, .086F), t(WINTER, .117F, WINTER, .117F), t(WINTER, .143F, WINTER, .143F), t(WINTER, .137F, WINTER, .137F), t(WINTER, .069F, WINTER, .069F), t(0, 0F),   // spring 8-14
            t(GREEN, .069F), t(GREEN, .137F), t(GREEN, .16F), t(GREEN, .4F, GREEN, .2F), t(GREEN, .4F, GREEN, .207F), t(GREEN, .4F, GREEN, .229F), t(GREEN, .4F, GREEN, .25F),   // summer 1-7
            t(GREEN, .4F, GREEN, .25F), t(mix(GREEN, .8F, DRY, .2F), .4F, GREEN, .4F), t(mix(GREEN, .8F, DRY, .2F), .4F, GREEN, .4F), t(mix(GREEN, .4F, DRY, .6F), .4F, GOLD, .5F), t(mix(GREEN, .4F, DRY, .6F), .4F, GOLD, .5F), t(DRY, .4F, GOLD, .5F), t(DRY, .4F, GOLD, .5F),   // summer 8-14
            t(DRY, .4F, GOLD, .5F), t(mix(DRY, .8F, WINTER, .2F), .4F, GOLD, .5F), t(mix(DRY, .8F, WINTER, .2F), .4F, GOLD, .5F), t(mix(DRY, .6F, WINTER, .4F), .4F, mix(GOLD, .8F, WINTER, .2F), .5F), t(mix(DRY, .586F, WINTER, .414F), .4F, mix(GOLD, .771F, WINTER, .229F), .5F), t(mix(DRY, .543F, WINTER, .457F), .4F, mix(GOLD, .686F, WINTER, .314F), .5F), t(mix(DRY, .5F, WINTER, .5F), .4F, mix(GOLD, .6F, WINTER, .4F), .5F),   // autumn 1-7
            t(mix(DRY, .457F, WINTER, .543F), .4F, mix(GOLD, .591F, WINTER, .409F), .5F), t(mix(DRY, .414F, WINTER, .586F), .4F, mix(GOLD, .583F, WINTER, .417F), .5F), t(mix(DRY, .386F, WINTER, .614F), .4F, mix(GOLD, .571F, WINTER, .429F), .5F), t(mix(DRY, .364F, WINTER, .636F), .4F, mix(GOLD, .559F, WINTER, .441F), .5F), t(mix(DRY, .346F, WINTER, .654F), .4F, mix(GOLD, .543F, WINTER, .457F), .5F), t(mix(DRY, .333F, WINTER, .667F), .4F, mix(GOLD, .521F, WINTER, .479F), .5F), t(mix(DRY, .32F, WINTER, .68F), .4F, mix(GOLD, .5F, WINTER, .5F), .5F),   // autumn 8-14
            t(mix(DRY, .32F, WINTER, .68F), .4F, mix(GOLD, .5F, WINTER, .5F), .5F), t(WINTER, .4F, WINTER, .4F), t(WINTER, .386F, WINTER, .386F), t(WINTER, .364F, WINTER, .364F), t(WINTER, .346F, WINTER, .346F), t(WINTER, .333F, WINTER, .333F), t(WINTER, .32F, WINTER, .32F),   // winter 1-7
            t(WINTER, .311F, WINTER, .311F), t(WINTER, .303F, WINTER, .303F), t(WINTER, .286F, WINTER, .286F), t(WINTER, .264F, WINTER, .264F), t(WINTER, .243F, WINTER, .243F), t(WINTER, .221F, WINTER, .221F), t(WINTER, .2F, WINTER, .2F)   // winter 8-14
    };

    public static final Term[] HOT = {
            t(WINTER, .057F, WINTER, .057F), t(WINTER, .014F, WINTER, .014F), t(GREEN, .023F), t(GREEN, .057F), t(GREEN, .091F), t(GREEN, .126F), t(GREEN, .16F),   // spring 1-7
            t(GREEN, .177F), t(GREEN, .194F), t(GREEN, .214F), t(GREEN, .236F), t(GREEN, .25F), t(GREEN, .3F, GREEN, .05F), t(GREEN, .3F, GREEN, .05F),   // spring 8-14
            t(GREEN, .321F, GREEN, .071F), t(GREEN, .343F, GREEN, .093F), t(GREEN, .364F, GREEN, .114F), t(GREEN, .386F, GREEN, .136F), t(GREEN, .4F, GREEN, .157F), t(GREEN, .4F, GREEN, .179F), t(GREEN, .4F, GREEN, .2F),   // summer 1-7
            t(GREEN, .4F, GREEN, .2F), t(mix(GREEN, .8F, DRY, .2F), .4F, GREEN, .2F), t(mix(GREEN, .8F, DRY, .2F), .4F, GREEN, .2F), t(mix(GREEN, .8F, DRY, .2F), .45F, GOLD, .25F), t(mix(GREEN, .786F, DRY, .214F), .443F, GOLD, .243F), t(mix(GREEN, .743F, DRY, .257F), .421F, GOLD, .221F), t(mix(GREEN, .7F, DRY, .3F), .4F, GOLD, .2F),   // summer 8-14
            t(mix(GREEN, .657F, DRY, .343F), .4F, GOLD, .2F), t(mix(GREEN, .614F, DRY, .386F), .4F, GOLD, .2F), t(mix(GREEN, .594F, DRY, .406F), .4F, GOLD, .2F), t(mix(GREEN, .586F, DRY, .414F), .4F, GOLD, .2F), t(mix(GREEN, .576F, DRY, .424F), .4F, GOLD, .193F), t(mix(GREEN, .563F, DRY, .437F), .4F, GOLD, .171F), t(mix(GREEN, .55F, DRY, .45F), .4F, GOLD, .15F),   // autumn 1-7
            t(mix(GREEN, .537F, DRY, .463F), .4F, GOLD, .137F), t(mix(GREEN, .524F, DRY, .476F), .4F, GOLD, .124F), t(mix(GREEN, .52F, DRY, .48F), .4F, GOLD, .12F), t(mix(DRY, .5F, WINTER, .5F), .35F, mix(GOLD, .8F, WINTER, .2F), .2F), t(mix(DRY, .5F, WINTER, .5F), .35F, mix(GOLD, .771F, WINTER, .229F), .207F), t(mix(DRY, .5F, WINTER, .5F), .35F, mix(GOLD, .686F, WINTER, .314F), .229F), t(mix(DRY, .5F, WINTER, .5F), .35F, mix(GOLD, .6F, WINTER, .4F), .25F),   // autumn 8-14
            t(mix(DRY, .457F, WINTER, .543F), .35F, mix(GOLD, .557F, WINTER, .443F), .293F), t(mix(DRY, .414F, WINTER, .586F), .35F, mix(GOLD, .514F, WINTER, .486F), .336F), t(mix(DRY, .371F, WINTER, .629F), .35F, mix(GOLD, .414F, WINTER, .586F), .35F), t(mix(DRY, .329F, WINTER, .671F), .35F, mix(GOLD, .286F, WINTER, .714F), .35F), t(mix(DRY, .3F, WINTER, .7F), .35F, mix(GOLD, .2F, WINTER, .8F), .35F), t(WINTER, .35F, WINTER, .35F), t(WINTER, .35F, WINTER, .35F),   // winter 1-7
            t(WINTER, .35F, WINTER, .329F), t(WINTER, .35F, WINTER, .307F), t(WINTER, .307F, WINTER, .271F), t(WINTER, .243F, WINTER, .229F), t(WINTER, .186F, WINTER, .186F), t(WINTER, .143F, WINTER, .143F), t(WINTER, .1F, WINTER, .1F)   // winter 8-14
    };

    /** "slightly": temperate colors at a fixed 10 % strength. */
    public static final Term[] SLIGHTLY = withMix(TEMPERATE, .1F);
    /** "stable": no change. */
    public static final Term[] STABLE = withMix(TEMPERATE, 0F);

    public static final float[][] SPRUCE = scale(new float[][]{
            {9880142, .046F}, {9880142, .071F}, {9880142, .1F}, {9880142, .13F}, {9880142, .154F}, {9880142, .167F}, {9880142, .18F},   // spring 1-7
            {9880142, .154F}, {9880142, .129F}, {9880142, .114F}, {9880142, .106F}, {9880142, .1F}, {6008355, .1F}, {6008355, .1F},   // spring 8-14
            {6008355, .083F}, {6008355, .066F}, {6008355, .043F}, {6008355, .017F}, {6008355, .014F}, {6008355, .057F}, {6008355, .1F},   // summer 1-7
            {6008355, .079F}, {6008355, .057F}, {6008355, .036F}, {6008355, .014F}, {12503350, .007F}, {12503350, .029F}, {12503350, .05F},   // summer 8-14
            {12503350, .071F}, {12503350, .093F}, {12503350, .106F}, {12503350, .114F}, {12503350, .124F}, {12503350, .137F}, {12503350, .15F},   // autumn 1-7
            {12503350, .163F}, {12503350, .176F}, {12503350, .163F}, {12503350, .137F}, {12503350, .12F}, {2440484, .18F}, {2440484, .18F},   // autumn 8-14
            {2440484, .189F}, {2440484, .197F}, {2440484, .214F}, {2440484, .236F}, {2440484, .236F}, {2440484, .193F}, {2440484, .15F},   // winter 1-7
            {2440484, .12F}, {2440484, .09F}, {2440484, .071F}, {2440484, .059F}, {2440484, .05F}, {9880142, .02F}, {9880142, .02F}   // winter 8-14
    }, 1.2F);

    public static final float[][] BIRCH = {
            {4436548, .043F}, {4436548, .086F}, {4436548, .129F}, {4436548, .171F}, {4436548, .207F}, {4436548, .229F}, {4436548, .25F},   // spring 1-7
            {4436548, .271F}, {4436548, .293F}, {4436548, .329F}, {4436548, .371F}, {4436548, .4F}, {13818234, .4F}, {13818234, .4F},   // spring 8-14
            {13818234, .443F}, {13818234, .486F}, {13818234, .529F}, {13818234, .571F}, {13818234, .607F}, {13818234, .629F}, {13818234, .65F},   // summer 1-7
            {13818234, .607F}, {13818234, .564F}, {13818234, .507F}, {13818234, .443F}, {13818234, .4F}, {16491540, .42F}, {16491540, .42F},   // summer 8-14
            {16491540, .54F}, {16491540, .66F}, {16491540, .757F}, {16491540, .843F}, {16491540, .907F}, {16491540, .929F}, {16491540, .95F},   // autumn 1-7
            {16491540, .95F}, {mix(15887135, .5F, 16491540, .5F), .8F}, {mix(15887135, .5F, 16491540, .5F), .8F}, {15887135, .9F}, {15887135, .9F}, {mix(15887135, .7F, 11378271, .3F), .7F}, {mix(15887135, .7F, 11378271, .3F), .7F},   // autumn 8-14
            {mix(15887135, .614F, 11378271, .386F), .571F}, {mix(15887135, .529F, 11378271, .471F), .443F}, {mix(15887135, .5F, 11378271, .5F), .4F}, {11378271, .8F}, {11378271, .786F}, {11378271, .743F}, {11378271, .7F},   // winter 1-7
            {11378271, .657F}, {11378271, .614F}, {11378271, .514F}, {11378271, .386F}, {11378271, .257F}, {11378271, .129F}, {4436548, 0F}   // winter 8-14
    };

    public static final float[][] MANGROVE = {
            {6143626, .046F}, {6143626, .071F}, {6143626, .1F}, {6143626, .13F}, {6143626, .157F}, {6143626, .179F}, {6143626, .2F},   // spring 1-7
            {6143626, .166F}, {6143626, .131F}, {6143626, .114F}, {6143626, .106F}, {6143626, .1F}, {2129740, .1F}, {2129740, .1F},   // spring 8-14
            {2129740, .083F}, {2129740, .066F}, {2129740, .043F}, {2129740, .017F}, {6008355, .014F}, {6008355, .057F}, {6008355, .1F},   // summer 1-7
            {6008355, .057F}, {6008355, .014F}, {0, 0F}, {0, 0F}, {3618868, .007F}, {3618868, .029F}, {3618868, .05F},   // summer 8-14
            {3618868, .071F}, {3618868, .093F}, {3618868, .077F}, {3618868, .043F}, {3618868, .024F}, {3618868, .037F}, {3618868, .05F},   // autumn 1-7
            {3618868, .063F}, {3618868, .076F}, {3618868, .091F}, {3618868, .109F}, {3618868, .12F}, {5982483, .18F}, {5982483, .18F},   // autumn 8-14
            {5982483, .189F}, {5982483, .197F}, {5982483, .214F}, {5982483, .236F}, {5982483, .236F}, {5982483, .193F}, {5982483, .15F},   // winter 1-7
            {5982483, .12F}, {5982483, .09F}, {5982483, .071F}, {5982483, .059F}, {5982483, .05F}, {6143626, .02F}, {6143626, .02F}   // winter 8-14
    };

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
