package dev.venatorvictor.equinox.client.colors;

import net.minecraft.world.level.FoliageColor;

/**
 * Seasonal color tables, indexed by week (16 a season, 64 a year: 0 = spring week 1, the spring equinox; 63 = winter
 * week 16), on the calendar. Each entry is the color reached at the end of its week (blended daily from the week
 * before). Converted 2026-11-14 from the older 24-step tables (tools/tables_to_weeks.py), same colors on the same dates;
 * re-cut 2026-11-25 to 16 weeks a season (tools/weeks_14_to_16.py), again on the same dates.
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

    public record Week(int grass, float mix, int leaf) {}

    public static final Week[] TEMPERATE = {
            t(WINTER, .26F, WINTER, .26F), t(WINTER, .2F, WINTER, .2F), t(WINTER, .14F, WINTER, .14F), t(WINTER, .08F, WINTER, .08F), t(WINTER, .029F, WINTER, .029F), t(0, 0F), t(0, 0F), t(0, 0F),   // spring 1-8
            t(GREEN, .06F), t(GREEN, .12F), t(GREEN, .18F), t(GREEN, .24F), t(GREEN, .3F), t(GREEN, .36F), t(GREEN, .42F), t(GREEN, .48F),   // spring 9-16
            t(GREEN, .48F), t(GREEN, .64F, GREEN, .1F), t(GREEN, .669F, GREEN, .118F), t(GREEN, .72F, GREEN, .15F), t(GREEN, .771F, GREEN, .187F), t(GREEN, .8F, GREEN, .225F), t(GREEN, .8F, GREEN, .262F), t(GREEN, .8F, GREEN, .3F),   // summer 1-8
            t(GREEN, .8F, GREEN, .3F), t(mix(GREEN, .8F, DRY, .2F), .8F, GREEN, .4F), t(mix(GREEN, .8F, DRY, .2F), .8F, GREEN, .4F), t(mix(GREEN, .6F, DRY, .4F), .8F, GOLD, .25F), t(mix(GREEN, .589F, DRY, .411F), .8F, GOLD, .264F), t(mix(GREEN, .55F, DRY, .45F), .8F, GOLD, .313F), t(mix(GREEN, .475F, DRY, .525F), .8F, GOLD, .406F), t(mix(GREEN, .4F, DRY, .6F), .8F, GOLD, .5F),   // summer 9-16
            t(mix(GREEN, .325F, DRY, .675F), .8F, GOLD, .594F), t(mix(GREEN, .25F, DRY, .75F), .8F, GOLD, .687F), t(mix(GREEN, .211F, DRY, .789F), .8F, GOLD, .736F), t(DRY, .8F, GOLD, 1F), t(DRY, .8F, GOLD, 1F), t(DRY, .8F, GOLD, 1F), t(DRY, .8F, GOLD, 1F), t(DRY, .8F, GOLD, 1F),   // autumn 1-8
            t(DRY, .8F, GOLD, 1F), t(mix(DRY, .8F, WINTER, .2F), .8F, GOLD, 1F), t(mix(DRY, .8F, WINTER, .2F), .8F, GOLD, 1F), t(mix(DRY, .6F, WINTER, .4F), .8F, mix(GOLD, .8F, WINTER, .2F), 1F), t(mix(DRY, .589F, WINTER, .411F), .8F, mix(GOLD, .789F, WINTER, .211F), 1F), t(mix(DRY, .55F, WINTER, .45F), .8F, mix(GOLD, .75F, WINTER, .25F), 1F), t(mix(DRY, .475F, WINTER, .525F), .8F, mix(GOLD, .675F, WINTER, .325F), 1F), t(mix(DRY, .4F, WINTER, .6F), .8F, mix(GOLD, .6F, WINTER, .4F), 1F),   // autumn 9-16
            t(mix(DRY, .325F, WINTER, .675F), .8F, mix(GOLD, .525F, WINTER, .475F), 1F), t(mix(DRY, .25F, WINTER, .75F), .8F, mix(GOLD, .45F, WINTER, .55F), 1F), t(mix(DRY, .211F, WINTER, .789F), .8F, mix(GOLD, .411F, WINTER, .589F), 1F), t(WINTER, .8F, mix(GOLD, .2F, WINTER, .8F), 1F), t(WINTER, .8F, mix(GOLD, .2F, WINTER, .8F), 1F), t(WINTER, .8F, mix(GOLD, .2F, WINTER, .8F), 1F), t(WINTER, .8F, WINTER, .8F), t(WINTER, .8F, WINTER, .8F),   // winter 1-8
            t(WINTER, .74F, WINTER, .74F), t(WINTER, .68F, WINTER, .68F), t(WINTER, .62F, WINTER, .62F), t(WINTER, .56F, WINTER, .56F), t(WINTER, .5F, WINTER, .5F), t(WINTER, .44F, WINTER, .44F), t(WINTER, .38F, WINTER, .38F), t(WINTER, .32F, WINTER, .32F)   // winter 9-16
    };

    public static final Week[] MONSOONAL = {
            t(0, 0F), t(0, 0F), t(0, 0F), t(0, 0F), t(0, 0F), t(0, 0F), t(0, 0F), t(0, 0F),   // spring 1-8
            t(GREEN, .06F), t(GREEN, .12F), t(GREEN, .18F), t(GREEN, .24F), t(GREEN, .3F), t(GREEN, .36F), t(GREEN, .42F), t(GREEN, .48F),   // spring 9-16
            t(GREEN, .48F), t(GREEN, .64F, GREEN, .1F), t(GREEN, .669F, GREEN, .118F), t(GREEN, .72F, GREEN, .15F), t(GREEN, .771F, GREEN, .187F), t(GREEN, .8F, GREEN, .225F), t(GREEN, .8F, GREEN, .262F), t(GREEN, .8F, GREEN, .3F),   // summer 1-8
            t(GREEN, .8F, GREEN, .3F), t(mix(GREEN, .8F, DRY, .2F), .8F, GREEN, .4F), t(mix(GREEN, .8F, DRY, .2F), .8F, GREEN, .4F), t(GREEN, .5F, GOLD, .25F), t(GREEN, .5F, GOLD, .264F), t(GREEN, .5F, GOLD, .313F), t(GREEN, .5F, GOLD, .406F), t(GREEN, .5F, GOLD, .5F),   // summer 9-16
            t(GREEN, .5F, GOLD, .594F), t(GREEN, .5F, GOLD, .687F), t(GREEN, .5F, GOLD, .781F), t(GREEN, .5F, GOLD, .875F), t(GREEN, .495F, GOLD, .956F), t(GREEN, .475F, GOLD, 1F), t(GREEN, .438F, GOLD, 1F), t(GREEN, .4F, GOLD, 1F),   // autumn 1-8
            t(GREEN, .362F, GOLD, 1F), t(GREEN, .325F, GOLD, 1F), t(GREEN, .305F, GOLD, 1F), t(GREEN, .2F, mix(GOLD, .8F, WINTER, .2F), 1F), t(GREEN, .195F, mix(GOLD, .789F, WINTER, .211F), 1F), t(GREEN, .175F, mix(GOLD, .75F, WINTER, .25F), 1F), t(GREEN, .138F, mix(GOLD, .675F, WINTER, .325F), 1F), t(GREEN, .1F, mix(GOLD, .6F, WINTER, .4F), 1F),   // autumn 9-16
            t(GREEN, .062F, mix(GOLD, .6F, WINTER, .4F), .625F), t(GREEN, .025F, mix(GOLD, .6F, WINTER, .4F), .25F), t(GREEN, .005F, mix(GOLD, .6F, WINTER, .4F), .054F), t(0, 0F), t(0, 0F), t(0, 0F), t(0, 0F), t(0, 0F),   // winter 1-8
            t(0, 0F), t(0, 0F), t(0, 0F), t(0, 0F), t(0, 0F), t(0, 0F), t(0, 0F), t(0, 0F)   // winter 9-16
    };

    public static final Week[] COLD = {
            t(WINTER, .182F, WINTER, .182F), t(WINTER, .163F, WINTER, .163F), t(WINTER, .126F, WINTER, .126F), t(WINTER, .075F, WINTER, .075F), t(WINTER, .027F, WINTER, .027F), t(0, 0F), t(0, 0F), t(0, 0F),   // spring 1-8
            t(WINTER, .038F, WINTER, .038F), t(WINTER, .075F, WINTER, .075F), t(WINTER, .105F, WINTER, .105F), t(WINTER, .13F, WINTER, .13F), t(WINTER, .141F, WINTER, .141F), t(WINTER, .12F, WINTER, .12F), t(WINTER, .06F, WINTER, .06F), t(0, 0F),   // spring 9-16
            t(GREEN, .06F), t(GREEN, .12F), t(GREEN, .151F), t(GREEN, .4F, GREEN, .2F), t(GREEN, .4F, GREEN, .203F), t(GREEN, .4F, GREEN, .212F), t(GREEN, .4F, GREEN, .232F), t(GREEN, .4F, GREEN, .25F),   // summer 1-8
            t(GREEN, .4F, GREEN, .25F), t(mix(GREEN, .8F, DRY, .2F), .4F, GREEN, .4F), t(mix(GREEN, .8F, DRY, .2F), .4F, GREEN, .4F), t(mix(GREEN, .4F, DRY, .6F), .4F, GOLD, .5F), t(mix(GREEN, .4F, DRY, .6F), .4F, GOLD, .5F), t(mix(GREEN, .4F, DRY, .6F), .4F, GOLD, .5F), t(DRY, .4F, GOLD, .5F), t(DRY, .4F, GOLD, .5F),   // summer 9-16
            t(DRY, .4F, GOLD, .5F), t(mix(DRY, .8F, WINTER, .2F), .4F, GOLD, .5F), t(mix(DRY, .8F, WINTER, .2F), .4F, GOLD, .5F), t(mix(DRY, .6F, WINTER, .4F), .4F, mix(GOLD, .8F, WINTER, .2F), .5F), t(mix(DRY, .595F, WINTER, .405F), .4F, mix(GOLD, .789F, WINTER, .211F), .5F), t(mix(DRY, .575F, WINTER, .425F), .4F, mix(GOLD, .75F, WINTER, .25F), .5F), t(mix(DRY, .538F, WINTER, .462F), .4F, mix(GOLD, .675F, WINTER, .325F), .5F), t(mix(DRY, .5F, WINTER, .5F), .4F, mix(GOLD, .6F, WINTER, .4F), .5F),   // autumn 1-8
            t(mix(DRY, .462F, WINTER, .538F), .4F, mix(GOLD, .592F, WINTER, .408F), .5F), t(mix(DRY, .425F, WINTER, .575F), .4F, mix(GOLD, .585F, WINTER, .415F), .5F), t(mix(DRY, .397F, WINTER, .603F), .4F, mix(GOLD, .575F, WINTER, .424F), .5F), t(mix(DRY, .375F, WINTER, .625F), .4F, mix(GOLD, .565F, WINTER, .435F), .5F), t(mix(DRY, .357F, WINTER, .643F), .4F, mix(GOLD, .553F, WINTER, .447F), .5F), t(mix(DRY, .343F, WINTER, .657F), .4F, mix(GOLD, .538F, WINTER, .463F), .5F), t(mix(DRY, .331F, WINTER, .669F), .4F, mix(GOLD, .518F, WINTER, .482F), .5F), t(mix(DRY, .32F, WINTER, .68F), .4F, mix(GOLD, .5F, WINTER, .5F), .5F),   // autumn 9-16
            t(mix(DRY, .32F, WINTER, .68F), .4F, mix(GOLD, .5F, WINTER, .5F), .5F), t(WINTER, .4F, WINTER, .4F), t(WINTER, .391F, WINTER, .391F), t(WINTER, .375F, WINTER, .375F), t(WINTER, .357F, WINTER, .357F), t(WINTER, .343F, WINTER, .343F), t(WINTER, .331F, WINTER, .331F), t(WINTER, .32F, WINTER, .32F),   // winter 1-8
            t(WINTER, .312F, WINTER, .312F), t(WINTER, .305F, WINTER, .305F), t(WINTER, .292F, WINTER, .292F), t(WINTER, .275F, WINTER, .275F), t(WINTER, .256F, WINTER, .256F), t(WINTER, .237F, WINTER, .237F), t(WINTER, .218F, WINTER, .218F), t(WINTER, .2F, WINTER, .2F)   // winter 9-16
    };

    public static final Week[] HOT = {
            t(WINTER, .062F, WINTER, .062F), t(WINTER, .025F, WINTER, .025F), t(GREEN, .023F), t(GREEN, .04F), t(GREEN, .07F), t(GREEN, .1F), t(GREEN, .13F), t(GREEN, .16F),   // spring 1-8
            t(GREEN, .175F), t(GREEN, .19F), t(GREEN, .206F), t(GREEN, .225F), t(GREEN, .241F), t(GREEN, .25F), t(GREEN, .3F, GREEN, .05F), t(GREEN, .3F, GREEN, .05F),   // spring 9-16
            t(GREEN, .318F, GREEN, .068F), t(GREEN, .338F, GREEN, .087F), t(GREEN, .356F, GREEN, .106F), t(GREEN, .375F, GREEN, .125F), t(GREEN, .391F, GREEN, .144F), t(GREEN, .4F, GREEN, .163F), t(GREEN, .4F, GREEN, .182F), t(GREEN, .4F, GREEN, .2F),   // summer 1-8
            t(GREEN, .4F, GREEN, .2F), t(mix(GREEN, .8F, DRY, .2F), .4F, GREEN, .2F), t(mix(GREEN, .8F, DRY, .2F), .4F, GREEN, .2F), t(mix(GREEN, .8F, DRY, .2F), .45F, GOLD, .25F), t(mix(GREEN, .795F, DRY, .205F), .447F, GOLD, .247F), t(mix(GREEN, .775F, DRY, .225F), .438F, GOLD, .237F), t(mix(GREEN, .738F, DRY, .262F), .418F, GOLD, .218F), t(mix(GREEN, .7F, DRY, .3F), .4F, GOLD, .2F),   // summer 9-16
            t(mix(GREEN, .662F, DRY, .338F), .4F, GOLD, .2F), t(mix(GREEN, .625F, DRY, .375F), .4F, GOLD, .2F), t(mix(GREEN, .601F, DRY, .399F), .4F, GOLD, .2F), t(mix(GREEN, .59F, DRY, .41F), .4F, GOLD, .2F), t(mix(GREEN, .582F, DRY, .418F), .4F, GOLD, .197F), t(mix(GREEN, .573F, DRY, .427F), .4F, GOLD, .188F), t(mix(GREEN, .561F, DRY, .439F), .4F, GOLD, .168F), t(mix(GREEN, .55F, DRY, .45F), .4F, GOLD, .15F),   // autumn 1-8
            t(mix(GREEN, .539F, DRY, .461F), .4F, GOLD, .139F), t(mix(GREEN, .527F, DRY, .473F), .4F, GOLD, .127F), t(mix(GREEN, .522F, DRY, .478F), .4F, GOLD, .121F), t(mix(DRY, .5F, WINTER, .5F), .35F, mix(GOLD, .8F, WINTER, .2F), .2F), t(mix(DRY, .5F, WINTER, .5F), .35F, mix(GOLD, .789F, WINTER, .211F), .203F), t(mix(DRY, .5F, WINTER, .5F), .35F, mix(GOLD, .75F, WINTER, .25F), .212F), t(mix(DRY, .5F, WINTER, .5F), .35F, mix(GOLD, .675F, WINTER, .325F), .232F), t(mix(DRY, .5F, WINTER, .5F), .35F, mix(GOLD, .6F, WINTER, .4F), .25F),   // autumn 9-16
            t(mix(DRY, .462F, WINTER, .538F), .35F, mix(GOLD, .562F, WINTER, .438F), .288F), t(mix(DRY, .425F, WINTER, .575F), .35F, mix(GOLD, .525F, WINTER, .475F), .325F), t(mix(DRY, .387F, WINTER, .613F), .35F, mix(GOLD, .452F, WINTER, .548F), .345F), t(mix(DRY, .35F, WINTER, .65F), .35F, mix(GOLD, .35F, WINTER, .65F), .35F), t(mix(DRY, .318F, WINTER, .682F), .35F, mix(GOLD, .254F, WINTER, .746F), .35F), t(mix(DRY, .3F, WINTER, .7F), .35F, mix(GOLD, .2F, WINTER, .8F), .35F), t(WINTER, .35F, WINTER, .35F), t(WINTER, .35F, WINTER, .35F),   // winter 1-8
            t(WINTER, .35F, WINTER, .332F), t(WINTER, .35F, WINTER, .312F), t(WINTER, .323F, WINTER, .285F), t(WINTER, .275F, WINTER, .25F), t(WINTER, .222F, WINTER, .213F), t(WINTER, .175F, WINTER, .175F), t(WINTER, .138F, WINTER, .138F), t(WINTER, .1F, WINTER, .1F)   // winter 9-16
    };

    /** "slightly": temperate colors at a fixed 10 % strength. */
    public static final Week[] SLIGHTLY = withMix(TEMPERATE, .1F);
    /** "stable": no change. */
    public static final Week[] STABLE = withMix(TEMPERATE, 0F);

    public static final float[][] SPRUCE = scale(new float[][]{
            {9880142, .043F}, {9880142, .065F}, {9880142, .089F}, {9880142, .115F}, {9880142, .139F}, {9880142, .157F}, {9880142, .169F}, {9880142, .18F},   // spring 1-8
            {9880142, .157F}, {9880142, .135F}, {9880142, .12F}, {9880142, .11F}, {9880142, .104F}, {9880142, .1F}, {6008355, .1F}, {6008355, .1F},   // spring 9-16
            {6008355, .085F}, {6008355, .07F}, {6008355, .052F}, {6008355, .03F}, {6008355, .016F}, {6008355, .025F}, {6008355, .062F}, {6008355, .1F},   // summer 1-8
            {6008355, .082F}, {6008355, .062F}, {6008355, .044F}, {6008355, .025F}, {6008355, .014F}, {12503350, .013F}, {12503350, .032F}, {12503350, .05F},   // summer 9-16
            {12503350, .068F}, {12503350, .087F}, {12503350, .101F}, {12503350, .11F}, {12503350, .118F}, {12503350, .127F}, {12503350, .139F}, {12503350, .15F},   // autumn 1-8
            {12503350, .161F}, {12503350, .173F}, {12503350, .168F}, {12503350, .15F}, {12503350, .131F}, {12503350, .12F}, {2440484, .18F}, {2440484, .18F},   // autumn 9-16
            {2440484, .188F}, {2440484, .195F}, {2440484, .208F}, {2440484, .225F}, {2440484, .236F}, {2440484, .225F}, {2440484, .188F}, {2440484, .15F},   // winter 1-8
            {2440484, .124F}, {2440484, .098F}, {2440484, .078F}, {2440484, .065F}, {2440484, .056F}, {2440484, .05F}, {9880142, .02F}, {9880142, .02F}   // winter 9-16
    }, 1.2F);

    public static final float[][] BIRCH = {
            {4436548, .038F}, {4436548, .075F}, {4436548, .113F}, {4436548, .15F}, {4436548, .184F}, {4436548, .212F}, {4436548, .232F}, {4436548, .25F},   // spring 1-8
            {4436548, .268F}, {4436548, .287F}, {4436548, .316F}, {4436548, .35F}, {4436548, .382F}, {4436548, .4F}, {13818234, .4F}, {13818234, .4F},   // spring 9-16
            {13818234, .438F}, {13818234, .475F}, {13818234, .513F}, {13818234, .55F}, {13818234, .585F}, {13818234, .613F}, {13818234, .632F}, {13818234, .65F},   // summer 1-8
            {13818234, .612F}, {13818234, .575F}, {13818234, .528F}, {13818234, .475F}, {13818234, .427F}, {13818234, .4F}, {16491540, .42F}, {16491540, .42F},   // summer 9-16
            {16491540, .525F}, {16491540, .63F}, {16491540, .721F}, {16491540, .8F}, {16491540, .867F}, {16491540, .913F}, {16491540, .932F}, {16491540, .95F},   // autumn 1-8
            {16491540, .95F}, {mix(15887135, .5F, 16491540, .5F), .8F}, {mix(15887135, .5F, 16491540, .5F), .8F}, {15887135, .9F}, {15887135, .9F}, {15887135, .9F}, {mix(15887135, .7F, 11378271, .3F), .7F}, {mix(15887135, .7F, 11378271, .3F), .7F},   // autumn 9-16
            {mix(15887135, .625F, 11378271, .375F), .587F}, {mix(15887135, .55F, 11378271, .45F), .475F}, {mix(15887135, .511F, 11378271, .489F), .416F}, {11378271, .8F}, {11378271, .795F}, {11378271, .775F}, {11378271, .738F}, {11378271, .7F},   // winter 1-8
            {11378271, .662F}, {11378271, .625F}, {11378271, .551F}, {11378271, .45F}, {11378271, .338F}, {11378271, .225F}, {11378271, .113F}, {4436548, 0F}   // winter 9-16
    };

    public static final float[][] MANGROVE = {
            {6143626, .043F}, {6143626, .065F}, {6143626, .089F}, {6143626, .115F}, {6143626, .14F}, {6143626, .163F}, {6143626, .182F}, {6143626, .2F},   // spring 1-8
            {6143626, .17F}, {6143626, .14F}, {6143626, .12F}, {6143626, .11F}, {6143626, .104F}, {6143626, .1F}, {2129740, .1F}, {2129740, .1F},   // spring 9-16
            {2129740, .085F}, {2129740, .07F}, {2129740, .052F}, {2129740, .03F}, {2129740, .017F}, {6008355, .025F}, {6008355, .062F}, {6008355, .1F},   // summer 1-8
            {6008355, .062F}, {6008355, .025F}, {6008355, .005F}, {0, 0F}, {3618868, .003F}, {3618868, .013F}, {3618868, .032F}, {3618868, .05F},   // summer 9-16
            {3618868, .068F}, {3618868, .087F}, {3618868, .083F}, {3618868, .06F}, {3618868, .036F}, {3618868, .027F}, {3618868, .039F}, {3618868, .05F},   // autumn 1-8
            {3618868, .061F}, {3618868, .073F}, {3618868, .085F}, {3618868, .1F}, {3618868, .113F}, {3618868, .12F}, {5982483, .18F}, {5982483, .18F},   // autumn 9-16
            {5982483, .188F}, {5982483, .195F}, {5982483, .208F}, {5982483, .225F}, {5982483, .236F}, {5982483, .225F}, {5982483, .188F}, {5982483, .15F},   // winter 1-8
            {5982483, .124F}, {5982483, .098F}, {5982483, .078F}, {5982483, .065F}, {5982483, .056F}, {5982483, .05F}, {6143626, .02F}, {6143626, .02F}   // winter 9-16
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
