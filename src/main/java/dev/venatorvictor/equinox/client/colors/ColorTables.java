package dev.venatorvictor.equinox.client.colors;

import net.minecraft.world.level.FoliageColor;

/**
 * Seasonal color tables, indexed by file week (12 a season, 48 a year by date: 0 = spring week 1, the spring equinox; 47 =
 * winter week 12), on the calendar. Each entry is the color reached at the end of its week (blended daily from the week
 * before). Converted 2026-11-14 from the older 24-step tables (tools/tables_to_weeks.py), same colors on the same dates;
 * re-cut 2026-11-25 to 16 weeks a season (tools/weeks_14_to_16.py), again on the same dates;
 * and to 12 (tools/weeks_recut.py), the grid of the season files.
 * Grass entries: {grass color, mix, leaf color}; the leaf color is pre-blended with the default foliage
 * color exactly like the seasonal texture format expects. Leaf tables: {color, mix}.
 */
public final class ColorTables {
    public static final int STEPS = dev.venatorvictor.equinox.api.SeasonState.FILE_WEEKS;

    // Base colors.
    private static final int GREEN = 8370190;   // #7FB80E
    private static final int DRY = 13734463;    // #D1923F
    private static final int WINTER = 12689779; // #C1A173
    private static final int GOLD = 16765952;   // #FFD400

    public record Week(int grass, float mix, int leaf) {}

    public static final Week[] TEMPERATE = {
            t(WINTER, .24F, WINTER, .24F), t(WINTER, .16F, WINTER, .16F), t(WINTER, .08F, WINTER, .08F), t(WINTER, .019F, WINTER, .019F), t(0, 0F), t(0, 0F),   // spring 1-6
            t(GREEN, .08F), t(GREEN, .16F), t(GREEN, .24F), t(GREEN, .32F), t(GREEN, .4F), t(GREEN, .48F),   // spring 7-12
            t(GREEN, .48F), t(GREEN, .659F, GREEN, .112F), t(GREEN, .72F, GREEN, .15F), t(GREEN, .781F, GREEN, .2F), t(GREEN, .8F, GREEN, .25F), t(GREEN, .8F, GREEN, .3F),   // summer 1-6
            t(GREEN, .8F, GREEN, .3F), t(mix(GREEN, .8F, DRY, .2F), .8F, GREEN, .4F), t(mix(GREEN, .6F, DRY, .4F), .8F, GOLD, .25F), t(mix(GREEN, .576F, DRY, .424F), .8F, GOLD, .28F), t(mix(GREEN, .5F, DRY, .5F), .8F, GOLD, .375F), t(mix(GREEN, .4F, DRY, .6F), .8F, GOLD, .5F),   // summer 7-12
            t(mix(GREEN, .3F, DRY, .7F), .8F, GOLD, .625F), t(mix(GREEN, .224F, DRY, .776F), .8F, GOLD, .72F), t(DRY, .8F, GOLD, 1F), t(DRY, .8F, GOLD, 1F), t(DRY, .8F, GOLD, 1F), t(DRY, .8F, GOLD, 1F),   // autumn 1-6
            t(DRY, .8F, GOLD, 1F), t(mix(DRY, .8F, WINTER, .2F), .8F, GOLD, 1F), t(mix(DRY, .6F, WINTER, .4F), .8F, mix(GOLD, .8F, WINTER, .2F), 1F), t(mix(DRY, .576F, WINTER, .424F), .8F, mix(GOLD, .776F, WINTER, .224F), 1F), t(mix(DRY, .5F, WINTER, .5F), .8F, mix(GOLD, .7F, WINTER, .3F), 1F), t(mix(DRY, .4F, WINTER, .6F), .8F, mix(GOLD, .6F, WINTER, .4F), 1F),   // autumn 7-12
            t(mix(DRY, .3F, WINTER, .7F), .8F, mix(GOLD, .5F, WINTER, .5F), 1F), t(mix(DRY, .224F, WINTER, .776F), .8F, mix(GOLD, .424F, WINTER, .576F), 1F), t(WINTER, .8F, mix(GOLD, .2F, WINTER, .8F), 1F), t(WINTER, .8F, mix(GOLD, .2F, WINTER, .8F), 1F), t(WINTER, .8F, WINTER, .8F), t(WINTER, .8F, WINTER, .8F),   // winter 1-6
            t(WINTER, .72F, WINTER, .72F), t(WINTER, .64F, WINTER, .64F), t(WINTER, .56F, WINTER, .56F), t(WINTER, .48F, WINTER, .48F), t(WINTER, .4F, WINTER, .4F), t(WINTER, .32F, WINTER, .32F)   // winter 7-12
    };

    public static final Week[] MONSOONAL = {
            t(0, 0F), t(0, 0F), t(0, 0F), t(0, 0F), t(0, 0F), t(0, 0F),   // spring 1-6
            t(GREEN, .08F), t(GREEN, .16F), t(GREEN, .24F), t(GREEN, .32F), t(GREEN, .4F), t(GREEN, .48F),   // spring 7-12
            t(GREEN, .48F), t(GREEN, .659F, GREEN, .112F), t(GREEN, .72F, GREEN, .15F), t(GREEN, .781F, GREEN, .2F), t(GREEN, .8F, GREEN, .25F), t(GREEN, .8F, GREEN, .3F),   // summer 1-6
            t(GREEN, .8F, GREEN, .3F), t(mix(GREEN, .8F, DRY, .2F), .8F, GREEN, .4F), t(GREEN, .5F, GOLD, .25F), t(GREEN, .5F, GOLD, .28F), t(GREEN, .5F, GOLD, .375F), t(GREEN, .5F, GOLD, .5F),   // summer 7-12
            t(GREEN, .5F, GOLD, .625F), t(GREEN, .5F, GOLD, .75F), t(GREEN, .5F, GOLD, .875F), t(GREEN, .488F, GOLD, .971F), t(GREEN, .45F, GOLD, 1F), t(GREEN, .4F, GOLD, 1F),   // autumn 1-6
            t(GREEN, .35F, GOLD, 1F), t(GREEN, .312F, GOLD, 1F), t(GREEN, .2F, mix(GOLD, .8F, WINTER, .2F), 1F), t(GREEN, .188F, mix(GOLD, .776F, WINTER, .224F), 1F), t(GREEN, .15F, mix(GOLD, .7F, WINTER, .3F), 1F), t(GREEN, .1F, mix(GOLD, .6F, WINTER, .4F), 1F),   // autumn 7-12
            t(GREEN, .05F, mix(GOLD, .6F, WINTER, .4F), .5F), t(GREEN, .012F, mix(GOLD, .6F, WINTER, .4F), .119F), t(0, 0F), t(0, 0F), t(0, 0F), t(0, 0F),   // winter 1-6
            t(0, 0F), t(0, 0F), t(0, 0F), t(0, 0F), t(0, 0F), t(0, 0F)   // winter 7-12
    };

    public static final Week[] COLD = {
            t(WINTER, .176F, WINTER, .176F), t(WINTER, .138F, WINTER, .138F), t(WINTER, .075F, WINTER, .075F), t(WINTER, .018F, WINTER, .018F), t(0, 0F), t(0, 0F),   // spring 1-6
            t(WINTER, .05F, WINTER, .05F), t(WINTER, .095F, WINTER, .095F), t(WINTER, .13F, WINTER, .13F), t(WINTER, .134F, WINTER, .134F), t(WINTER, .08F, WINTER, .08F), t(0, 0F),   // spring 7-12
            t(GREEN, .08F), t(GREEN, .141F), t(GREEN, .4F, GREEN, .2F), t(GREEN, .4F, GREEN, .206F), t(GREEN, .4F, GREEN, .225F), t(GREEN, .4F, GREEN, .25F),   // summer 1-6
            t(GREEN, .4F, GREEN, .25F), t(mix(GREEN, .8F, DRY, .2F), .4F, GREEN, .4F), t(mix(GREEN, .4F, DRY, .6F), .4F, GOLD, .5F), t(mix(GREEN, .4F, DRY, .6F), .4F, GOLD, .5F), t(DRY, .4F, GOLD, .5F), t(DRY, .4F, GOLD, .5F),   // summer 7-12
            t(DRY, .4F, GOLD, .5F), t(mix(DRY, .8F, WINTER, .2F), .4F, GOLD, .5F), t(mix(DRY, .6F, WINTER, .4F), .4F, mix(GOLD, .8F, WINTER, .2F), .5F), t(mix(DRY, .588F, WINTER, .412F), .4F, mix(GOLD, .776F, WINTER, .224F), .5F), t(mix(DRY, .55F, WINTER, .45F), .4F, mix(GOLD, .7F, WINTER, .3F), .5F), t(mix(DRY, .5F, WINTER, .5F), .4F, mix(GOLD, .6F, WINTER, .4F), .5F),   // autumn 1-6
            t(mix(DRY, .45F, WINTER, .55F), .4F, mix(GOLD, .59F, WINTER, .41F), .5F), t(mix(DRY, .406F, WINTER, .594F), .4F, mix(GOLD, .578F, WINTER, .421F), .5F), t(mix(DRY, .375F, WINTER, .625F), .4F, mix(GOLD, .565F, WINTER, .435F), .5F), t(mix(DRY, .352F, WINTER, .648F), .4F, mix(GOLD, .548F, WINTER, .452F), .5F), t(mix(DRY, .335F, WINTER, .665F), .4F, mix(GOLD, .525F, WINTER, .476F), .5F), t(mix(DRY, .32F, WINTER, .68F), .4F, mix(GOLD, .5F, WINTER, .5F), .5F),   // autumn 7-12
            t(mix(DRY, .32F, WINTER, .68F), .4F, mix(GOLD, .5F, WINTER, .5F), .5F), t(WINTER, .394F, WINTER, .394F), t(WINTER, .375F, WINTER, .375F), t(WINTER, .352F, WINTER, .352F), t(WINTER, .335F, WINTER, .335F), t(WINTER, .32F, WINTER, .32F),   // winter 1-6
            t(WINTER, .31F, WINTER, .31F), t(WINTER, .296F, WINTER, .296F), t(WINTER, .275F, WINTER, .275F), t(WINTER, .25F, WINTER, .25F), t(WINTER, .224F, WINTER, .224F), t(WINTER, .2F, WINTER, .2F)   // winter 7-12
    };

    public static final Week[] HOT = {
            t(WINTER, .05F, WINTER, .05F), t(GREEN, .023F), t(GREEN, .04F), t(GREEN, .08F), t(GREEN, .12F), t(GREEN, .16F),   // spring 1-6
            t(GREEN, .18F), t(GREEN, .201F), t(GREEN, .225F), t(GREEN, .244F), t(GREEN, .3F, GREEN, .05F), t(GREEN, .3F, GREEN, .05F),   // spring 7-12
            t(GREEN, .325F, GREEN, .074F), t(GREEN, .35F, GREEN, .1F), t(GREEN, .375F, GREEN, .125F), t(GREEN, .394F, GREEN, .15F), t(GREEN, .4F, GREEN, .176F), t(GREEN, .4F, GREEN, .2F),   // summer 1-6
            t(GREEN, .4F, GREEN, .2F), t(mix(GREEN, .8F, DRY, .2F), .4F, GREEN, .2F), t(mix(GREEN, .8F, DRY, .2F), .45F, GOLD, .25F), t(mix(GREEN, .788F, DRY, .212F), .444F, GOLD, .244F), t(mix(GREEN, .75F, DRY, .25F), .425F, GOLD, .224F), t(mix(GREEN, .7F, DRY, .3F), .4F, GOLD, .2F),   // summer 7-12
            t(mix(GREEN, .65F, DRY, .35F), .4F, GOLD, .2F), t(mix(GREEN, .609F, DRY, .391F), .4F, GOLD, .2F), t(mix(GREEN, .59F, DRY, .41F), .4F, GOLD, .2F), t(mix(GREEN, .579F, DRY, .421F), .4F, GOLD, .194F), t(mix(GREEN, .565F, DRY, .435F), .4F, GOLD, .175F), t(mix(GREEN, .55F, DRY, .45F), .4F, GOLD, .15F),   // autumn 1-6
            t(mix(GREEN, .535F, DRY, .465F), .4F, GOLD, .135F), t(mix(GREEN, .524F, DRY, .476F), .4F, GOLD, .123F), t(mix(DRY, .5F, WINTER, .5F), .35F, mix(GOLD, .8F, WINTER, .2F), .2F), t(mix(DRY, .5F, WINTER, .5F), .35F, mix(GOLD, .776F, WINTER, .224F), .206F), t(mix(DRY, .5F, WINTER, .5F), .35F, mix(GOLD, .7F, WINTER, .3F), .225F), t(mix(DRY, .5F, WINTER, .5F), .35F, mix(GOLD, .6F, WINTER, .4F), .25F),   // autumn 7-12
            t(mix(DRY, .45F, WINTER, .55F), .35F, mix(GOLD, .55F, WINTER, .45F), .3F), t(mix(DRY, .4F, WINTER, .6F), .35F, mix(GOLD, .476F, WINTER, .524F), .338F), t(mix(DRY, .35F, WINTER, .65F), .35F, mix(GOLD, .35F, WINTER, .65F), .35F), t(mix(DRY, .312F, WINTER, .688F), .35F, mix(GOLD, .236F, WINTER, .764F), .35F), t(WINTER, .35F, WINTER, .35F), t(WINTER, .35F, WINTER, .35F),   // winter 1-6
            t(WINTER, .35F, WINTER, .325F), t(WINTER, .332F, WINTER, .294F), t(WINTER, .275F, WINTER, .25F), t(WINTER, .206F, WINTER, .2F), t(WINTER, .15F, WINTER, .15F), t(WINTER, .1F, WINTER, .1F)   // winter 7-12
    };

    /** "slightly": temperate colors at a fixed 10 % strength. */
    public static final Week[] SLIGHTLY = withMix(TEMPERATE, .1F);
    /** "stable": no change. */
    public static final Week[] STABLE = withMix(TEMPERATE, 0F);

    public static final float[][] SPRUCE = scale(new float[][]{
            {9880142, .05F}, {9880142, .081F}, {9880142, .115F}, {9880142, .145F}, {9880142, .165F}, {9880142, .18F},   // spring 1-6
            {9880142, .15F}, {9880142, .125F}, {9880142, .11F}, {9880142, .103F}, {6008355, .1F}, {6008355, .1F},   // spring 7-12
            {6008355, .08F}, {6008355, .058F}, {6008355, .03F}, {6008355, .019F}, {6008355, .05F}, {6008355, .1F},   // summer 1-6
            {6008355, .075F}, {6008355, .05F}, {6008355, .025F}, {6008355, .014F}, {12503350, .026F}, {12503350, .05F},   // summer 7-12
            {12503350, .074F}, {12503350, .096F}, {12503350, .11F}, {12503350, .121F}, {12503350, .135F}, {12503350, .15F},   // autumn 1-6
            {12503350, .165F}, {12503350, .17F}, {12503350, .15F}, {12503350, .127F}, {2440484, .18F}, {2440484, .18F},   // autumn 7-12
            {2440484, .19F}, {2440484, .204F}, {2440484, .225F}, {2440484, .232F}, {2440484, .2F}, {2440484, .15F},   // winter 1-6
            {2440484, .115F}, {2440484, .085F}, {2440484, .065F}, {2440484, .054F}, {9880142, .02F}, {9880142, .02F}   // winter 7-12
    }, 1.2F);

    public static final float[][] BIRCH = {
            {4436548, .05F}, {4436548, .1F}, {4436548, .15F}, {4436548, .193F}, {4436548, .225F}, {4436548, .25F},   // spring 1-6
            {4436548, .274F}, {4436548, .306F}, {4436548, .35F}, {4436548, .388F}, {13818234, .4F}, {13818234, .4F},   // spring 7-12
            {13818234, .45F}, {13818234, .5F}, {13818234, .55F}, {13818234, .594F}, {13818234, .626F}, {13818234, .65F},   // summer 1-6
            {13818234, .6F}, {13818234, .544F}, {13818234, .475F}, {13818234, .418F}, {16491540, .42F}, {16491540, .42F},   // summer 7-12
            {16491540, .56F}, {16491540, .691F}, {16491540, .8F}, {16491540, .882F}, {16491540, .926F}, {16491540, .95F},   // autumn 1-6
            {16491540, .95F}, {mix(15887135, .5F, 16491540, .5F), .8F}, {15887135, .9F}, {15887135, .9F}, {mix(15887135, .7F, 11378271, .3F), .7F}, {mix(15887135, .7F, 11378271, .3F), .7F},   // autumn 7-12
            {mix(15887135, .6F, 11378271, .4F), .55F}, {mix(15887135, .524F, 11378271, .476F), .436F}, {11378271, .8F}, {11378271, .788F}, {11378271, .75F}, {11378271, .7F},   // winter 1-6
            {11378271, .65F}, {11378271, .576F}, {11378271, .45F}, {11378271, .3F}, {11378271, .15F}, {4436548, 0F}   // winter 7-12
    };

    public static final float[][] MANGROVE = {
            {6143626, .05F}, {6143626, .081F}, {6143626, .115F}, {6143626, .148F}, {6143626, .176F}, {6143626, .2F},   // spring 1-6
            {6143626, .16F}, {6143626, .127F}, {6143626, .11F}, {6143626, .103F}, {2129740, .1F}, {2129740, .1F},   // spring 7-12
            {2129740, .08F}, {2129740, .058F}, {2129740, .03F}, {2129740, .017F}, {6008355, .05F}, {6008355, .1F},   // summer 1-6
            {6008355, .05F}, {6008355, .012F}, {0, 0F}, {3618868, .006F}, {3618868, .026F}, {3618868, .05F},   // summer 7-12
            {3618868, .074F}, {3618868, .084F}, {3618868, .06F}, {3618868, .033F}, {3618868, .035F}, {3618868, .05F},   // autumn 1-6
            {3618868, .065F}, {3618868, .081F}, {3618868, .1F}, {3618868, .115F}, {5982483, .18F}, {5982483, .18F},   // autumn 7-12
            {5982483, .19F}, {5982483, .204F}, {5982483, .225F}, {5982483, .232F}, {5982483, .2F}, {5982483, .15F},   // winter 1-6
            {5982483, .115F}, {5982483, .085F}, {5982483, .065F}, {5982483, .054F}, {6143626, .02F}, {6143626, .02F}   // winter 7-12
    };

    /** Simple color mix: per-channel weighted sum (alpha included). */
    public static int mix(int c1, float a1, int c2, float a2) {
        int r = (int) ((c1 >> 16 & 0xFF) * a1 + (c2 >> 16 & 0xFF) * a2);
        int g = (int) ((c1 >> 8 & 0xFF) * a1 + (c2 >> 8 & 0xFF) * a2);
        int b = (int) ((c1 & 0xFF) * a1 + (c2 & 0xFF) * a2);
        int a = (int) ((c1 >>> 24) * a1 + (c2 >>> 24) * a2);
        return a << 24 | r << 16 | g << 8 | b;
    }

    private static Week t(int grass, float mix, int birch, float birchAlpha) {
        return new Week(grass, mix, mix(birch, birchAlpha, FoliageColor.getDefaultColor(), 1F - birchAlpha));
    }

    private static Week t(int grass, float mix) {
        return new Week(grass, mix, FoliageColor.getDefaultColor());
    }

    private static Week[] withMix(Week[] base, float mix) {
        Week[] out = new Week[base.length];
        for (int i = 0; i < base.length; i++) out[i] = new Week(base[i].grass(), mix, base[i].leaf());
        return out;
    }

    // Colors are stored as floats only to keep the tables compact; ints below 2^24 are exact in a float.
    private static float[][] scale(float[][] table, float factor) {
        for (float[] row : table) row[1] *= factor;
        return table;
    }

    private ColorTables() {}
}
