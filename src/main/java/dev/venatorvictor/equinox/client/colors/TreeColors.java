package dev.venatorvictor.equinox.client.colors;

import dev.venatorvictor.equinox.client.ClientConfig;
import dev.venatorvictor.equinox.climate.ClimateBiome;
import dev.venatorvictor.equinox.climate.ClimateBlend;
import dev.venatorvictor.equinox.mixin.BiomeFoliageAccessor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

/**
 * Trees through the year: each tree has its own timing (real leaf-out, autumn color and leaf fall dates, mapped on
 * our calendar) and its own autumn color. Spring = the tree's original color, summer = a bit greener, autumn = the
 * tree's autumn color, winter = its dry leaves. All on the calendar, by week (week 0 = the spring equinox).
 * <p>
 * Biomes whose own foliage color is clearly an autumn one (gold, orange, red) use it as their autumn color and are
 * green in spring and summer.
 */
public final class TreeColors {
    /** How a table step colors the leaves: the tree's original color, or a shift toward one of its colors. */
    private static final int O = 0, G = 1, A = 2, H = 3, W = 4, T = 5;

    private static final int SUMMER_GREEN = 0x3F8A1C;

    /** A tree: its autumn, winter and extra color, and 64 weeks of {kind, mix} (the color reached at the end of each week). */
    public record Tree(int autumn, int winter, int extra, float[][] table) {}

    // Retuned 2026-11-14 to real dates (central Europe; step 0 = Mar 20, ~15 days a step: 2 = Apr 19, 12 = Sep 19,
    // 13 = Oct 4, 14 = Oct 19, 15 = Nov 3, 16 = Nov 19, 17 = Dec 4), matching the leaf fall steps in the season files:
    // each tree turns before its leaves start to fall. New leaves come out in the tree's own color.

    /** Leaf-out Apr 19; turns Oct 19, gold-brown by the first leaf fall (Nov 3), brown as the last ones go (Dec 4). */
    public static final Tree OAK = new Tree(0xB8862B, 0x7C5A32, 0, new float[][]{
            {W, .438F}, {W, .175F}, {W, .038F}, {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F},   // spring 1-8
            {O, 0F}, {O, 0F}, {G, .027F}, {G, .075F}, {G, .126F}, {G, .163F}, {G, .182F}, {G, .2F},   // spring 9-16
            {G, .218F}, {G, .237F}, {G, .247F}, {G, .25F}, {G, .25F}, {G, .25F}, {G, .25F}, {G, .25F},   // summer 1-8
            {G, .25F}, {G, .25F}, {G, .25F}, {G, .25F}, {G, .25F}, {G, .25F}, {G, .25F}, {G, .25F},   // summer 9-16
            {A, .1F}, {A, .175F}, {A, .263F}, {A, .35F}, {A, .438F}, {A, .525F}, {A, .606F}, {A, .65F},   // autumn 1-8: turns from autumn week 1, softly
            {A, .7F}, {A, .787F}, {A, .837F}, {H, .85F}, {H, .85F}, {H, .85F}, {W, .75F}, {W, .75F},   // autumn 9-16
            {W, .732F}, {W, .712F}, {W, .703F}, {W, .7F}, {W, .7F}, {W, .7F}, {W, .7F}, {W, .7F},   // winter 1-8
            {W, .7F}, {W, .7F}, {W, .7F}, {W, .7F}, {W, .7F}, {W, .7F}, {W, .7F}, {W, .7F}   // winter 9-16
    });

    /** Like a beech: turns Oct 19, copper Nov 3, then dry brown (darker, no green) kept until the new leaves (Apr 19). */
    public static final Tree DARK_OAK = new Tree(0xC0642A, 0x8B5A33, 0, new float[][]{
            {W, .85F}, {W, .85F}, {W, .85F}, {W, .85F}, {W, .805F}, {W, .638F}, {W, .319F}, {O, 0F},   // spring 1-8
            {O, 0F}, {O, 0F}, {G, .018F}, {G, .05F}, {G, .087F}, {G, .125F}, {G, .162F}, {G, .2F},   // spring 9-16
            {G, .2F}, {G, .2F}, {G, .2F}, {G, .2F}, {G, .2F}, {G, .2F}, {G, .2F}, {G, .2F},   // summer 1-8
            {G, .2F}, {G, .2F}, {G, .2F}, {G, .2F}, {G, .2F}, {G, .2F}, {G, .2F}, {G, .2F},   // summer 9-16
            {G, .2F}, {G, .2F}, {G, .191F}, {G, .175F}, {G, .159F}, {G, .15F}, {A, .5F}, {A, .5F},   // autumn 1-8
            {A, .631F}, {A, .763F}, {A, .831F}, {H, .85F}, {H, .85F}, {H, .85F}, {W, .85F}, {W, .85F},   // autumn 9-16
            {W, .85F}, {W, .85F}, {W, .85F}, {W, .85F}, {W, .85F}, {W, .85F}, {W, .85F}, {W, .85F},   // winter 1-8
            {W, .85F}, {W, .85F}, {W, .85F}, {W, .85F}, {W, .85F}, {W, .85F}, {W, .85F}, {W, .85F}   // winter 9-16
    });

    /** One of the first to turn: leaf-out Apr 4; yellow from Sep 19, full by the first leaf fall (Oct 4), bare Nov 19. */
    public static final Tree BIRCH = new Tree(0xF2C230, 0xAD9F5F, 0, new float[][]{
            {W, .375F}, {W, .15F}, {W, .032F}, {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F},   // spring 1-8
            {G, .038F}, {G, .075F}, {G, .113F}, {G, .15F}, {G, .182F}, {G, .2F}, {G, .2F}, {G, .2F},   // spring 9-16
            {G, .2F}, {G, .2F}, {G, .2F}, {G, .2F}, {G, .2F}, {G, .2F}, {G, .2F}, {G, .2F},   // summer 1-8
            {G, .2F}, {G, .2F}, {G, .2F}, {G, .2F}, {G, .195F}, {G, .175F}, {G, .138F}, {G, .1F},   // summer 9-16
            {G, .1F}, {A, .5F}, {A, .562F}, {A, .675F}, {A, .79F}, {A, .863F}, {A, .882F}, {A, .9F},   // autumn 1-8
            {A, .9F}, {H, .8F}, {H, .8F}, {W, .7F}, {W, .695F}, {W, .675F}, {W, .638F}, {W, .6F},   // autumn 9-16
            {W, .6F}, {W, .6F}, {W, .6F}, {W, .6F}, {W, .6F}, {W, .6F}, {W, .6F}, {W, .6F},   // winter 1-8
            {W, .6F}, {W, .6F}, {W, .6F}, {W, .6F}, {W, .6F}, {W, .6F}, {W, .6F}, {W, .6F}   // winter 9-16
    });

    /**
     * Cherry: one painted texture (packs/.../trees/cherry_leaves.png, tint 0) colored by this table, like birch. Leaves
     * come out after the blossoms (spring 9) in CHERRY_GREEN; yellow from summer 15 (the extra color), red-orange from
     * autumn 3 as they start to fall (the painted leaves make the red, orange and yellow ones). Before spring 9 and
     * after autumn 10 the season file shows untinted blossoms and twigs, so those weeks don't matter.
     */
    public static final int CHERRY_GREEN = 0xBADD3E;
    public static final Tree CHERRY = new Tree(0xE8622A, 0x8B5A33, 0xE8B830, new float[][]{
            {W, .088F}, {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F},   // spring 1-8
            {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F},   // spring 9-16
            {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F},   // summer 1-8
            {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F}, {T, .037F}, {T, .3F},   // summer 9-16: yellowing starts
            {T, .519F}, {T, .738F}, {A, .8F}, {A, .85F}, {A, .919F}, {A, .95F}, {A, .95F}, {A, .95F},   // autumn 1-8: yellow, then red-orange
            {A, .95F}, {A, .95F}, {W, .7F}, {W, .7F}, {W, .7F}, {W, .7F}, {W, .7F}, {W, .7F},   // autumn 9-16
            {W, .7F}, {W, .7F}, {W, .7F}, {W, .7F}, {W, .7F}, {W, .7F}, {W, .7F}, {W, .7F},   // winter 1-8
            {W, .7F}, {W, .7F}, {W, .7F}, {W, .7F}, {W, .7F}, {W, .7F}, {W, .7F}, {W, .7F}   // winter 9-16
    });

    /** Evergreen: bright new tips in May-June, duller and darker in winter. */
    public static final Tree SPRUCE = new Tree(0, 0x4A6A4A, 0x7FB24A, new float[][]{
            {W, .282F}, {W, .263F}, {W, .244F}, {W, .225F}, {W, .203F}, {W, .175F}, {W, .138F}, {W, .1F},   // spring 1-8
            {W, .1F}, {T, .2F}, {T, .218F}, {T, .25F}, {T, .277F}, {T, .275F}, {T, .238F}, {T, .2F},   // spring 9-16
            {T, .125F}, {T, .05F}, {T, .011F}, {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F},   // summer 1-8
            {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F},   // summer 9-16
            {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F}, {W, .005F}, {W, .025F}, {W, .062F}, {W, .1F},   // autumn 1-8
            {W, .118F}, {W, .137F}, {W, .156F}, {W, .175F}, {W, .194F}, {W, .212F}, {W, .232F}, {W, .25F},   // autumn 9-16
            {W, .268F}, {W, .287F}, {W, .297F}, {W, .3F}, {W, .3F}, {W, .3F}, {W, .3F}, {W, .3F},   // winter 1-8
            {W, .3F}, {W, .3F}, {W, .3F}, {W, .3F}, {W, .3F}, {W, .3F}, {W, .3F}, {W, .3F}   // winter 9-16
    });

    /** Acacia and jungle leaves in autumn-colored biomes (aspen-like: gold in September, bare in early October). */
    public static final Tree AUTUMN_BIOME = new Tree(0, 0x8F7A55, 0, new float[][]{
            {W, .6F}, {W, .6F}, {W, .582F}, {W, .55F}, {W, .505F}, {W, .437F}, {W, .344F}, {W, .25F},   // spring 1-8
            {W, .156F}, {W, .063F}, {G, .043F}, {G, .075F}, {G, .123F}, {G, .15F}, {G, .15F}, {G, .15F},   // spring 9-16
            {G, .15F}, {G, .15F}, {G, .15F}, {G, .15F}, {G, .15F}, {G, .15F}, {G, .15F}, {G, .15F},   // summer 1-8
            {G, .15F}, {G, .15F}, {G, .15F}, {G, .15F}, {G, .15F}, {G, .15F}, {A, .6F}, {A, .6F},   // summer 9-16
            {A, .713F}, {A, .825F}, {A, .884F}, {H, .8F}, {H, .8F}, {H, .8F}, {W, .7F}, {W, .7F},   // autumn 1-8
            {W, .662F}, {W, .625F}, {W, .605F}, {W, .6F}, {W, .6F}, {W, .6F}, {W, .6F}, {W, .6F},   // autumn 9-16
            {W, .6F}, {W, .6F}, {W, .6F}, {W, .6F}, {W, .6F}, {W, .6F}, {W, .6F}, {W, .6F},   // winter 1-8
            {W, .6F}, {W, .6F}, {W, .6F}, {W, .6F}, {W, .6F}, {W, .6F}, {W, .6F}, {W, .6F}   // winter 9-16
    });

    // ---- The biome's own foliage color, without the season (blended like vanilla's)

    private static int raw(Biome biome) {
        return biome.getSpecialEffects().getFoliageColorOverride()
                .orElseGet(((BiomeFoliageAccessor) (Object) biome)::equinox$foliageFromTexture);
    }

    private static int base(Biome biome) {
        int c = raw(biome);
        return autumnal(c) ? FoliageColor.getDefaultColor() & 0xFFFFFF : c;
    }

    private static int biomeAutumn(Biome biome) {
        int c = raw(biome);
        return autumnal(c) ? c : 0;
    }

    // Each resolver blends the biomes around the column like the climate (blended(): ClimateBlend.colorMix), then
    // Minecraft's Biome Blend smooths on top.

    /** Spring/summer color: the biome's own, or green where the biome's own is an autumn color. */
    public static final ColorResolver BASE = (biome, x, z) -> blended(biome, x, z, TreeColors::base);
    /** The biome's own color where it is an autumn one, else black (summed, so blending weighs it). */
    public static final ColorResolver BIOME_AUTUMN = (biome, x, z) -> blended(biome, x, z, TreeColors::biomeAutumn);
    /** White where the biome's color is an autumn one: the blended red channel is the autumn share. */
    public static final ColorResolver AUTUMN_SHARE = (biome, x, z) -> blended(biome, x, z, b -> autumnal(raw(b)) ? 0xFFFFFF : 0);
    /** The biome's seasonal foliage color (its color profile's table), for acacia and jungle. */
    public static final ColorResolver SEASONED_FOLIAGE = (biome, x, z) -> {
        ClimateBlend.ColorMix mix = ClimateBlend.colorMix(Mth.floor(x), Mth.floor(z));
        float cooling = mix.cooling;
        return blend(biome, mix, b -> SeasonColors.grassOrFoliage(false, raw(b), b, SeasonColors.cold(b, cooling)) & 0xFFFFFF);
    };
    /**
     * How strong the seasons are here for tree colors, as grey (red channel 0..255 = 0..1): the biomes' season
     * strength (their summer-to-winter drop) by their shares, times the climate zone's cold.
     */
    public static final ColorResolver STRENGTH = (biome, x, z) -> {
        if (!ClientConfig.CLIMATE_COLOR_STRENGTH.get()) return 0xFFFFFF;
        ClimateBlend.ColorMix mix = ClimateBlend.colorMix(Mth.floor(x), Mth.floor(z));
        float cooling = mix.cooling;
        float own = strength(biome, cooling), s = own;
        float k = ClientConfig.CLIMATE_COLOR_BLEND.get().floatValue();
        if (k > 0 && mix.count > 0) {
            float sum = 0;
            for (int i = 0; i < mix.count; i++) sum += mix.weights[i] * strength(mix.biomes[i], cooling);
            s = own + (sum - own) * k;
        }
        int v = Mth.clamp(Math.round(s * 255), 0, 255);
        return v << 16 | v << 8 | v;
    };

    private static float strength(Biome biome, float cooling) {
        return ((ClimateBiome) (Object) biome).equinox$seasonStrength() * SeasonColors.cold(biome, cooling);
    }

    private static int blended(Biome own, double x, double z, java.util.function.ToIntFunction<Biome> color) {
        return blend(own, ClimateBlend.colorMix(Mth.floor(x), Mth.floor(z)), color);
    }

    /** A color of each biome in the mix by its share, mixed with the own biome's by climate_color_blend. */
    private static int blend(Biome own, ClimateBlend.ColorMix mix, java.util.function.ToIntFunction<Biome> color) {
        int ownColor = color.applyAsInt(own);
        float k = ClientConfig.CLIMATE_COLOR_BLEND.get().floatValue();
        if (k <= 0 || mix.count == 0) return ownColor;
        float r = 0, g = 0, b = 0;
        for (int i = 0; i < mix.count; i++) {
            int c = color.applyAsInt(mix.biomes[i]);
            float w = mix.weights[i];
            r += w * (c >> 16 & 0xFF);
            g += w * (c >> 8 & 0xFF);
            b += w * (c & 0xFF);
        }
        int mixed = Math.round(r) << 16 | Math.round(g) << 8 | Math.round(b);
        return k >= 1 ? mixed : ColorTables.mix(mixed, k, ownColor, 1F - k) & 0xFFFFFF;
    }

    public static void registerResolvers(RegisterColorHandlersEvent.ColorResolvers event) {
        event.register(BASE);
        event.register(BIOME_AUTUMN);
        event.register(AUTUMN_SHARE);
        event.register(SEASONED_FOLIAGE);
        event.register(STRENGTH);
    }

    /** The season strength at pos (0..1). */
    private static float strengthAt(BlockAndTintGetter level, BlockPos pos) {
        return (level.getBlockTint(pos, STRENGTH) >> 16 & 0xFF) / 255F;
    }

    /** Gold, orange or red, strong enough not to be a dull green-brown. */
    static boolean autumnal(int color) {
        float r = (color >> 16 & 0xFF) / 255F, g = (color >> 8 & 0xFF) / 255F, b = (color & 0xFF) / 255F;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max < .4F || max - min < .4F * max || max != r) return false; // dark, grey, or not red-led
        float hue = 60F * (g - b) / (max - min); // red is the max: -60..60
        return hue >= -25F && hue <= 55F;
    }

    // ---- Leaves

    /** Leaves that take the biome's color (oak, dark oak). */
    public static int biomeTree(Tree tree, BlockAndTintGetter level, BlockPos pos) {
        if (level == null || pos == null) return FoliageColor.getDefaultColor();
        if (!ColorClock.active || ClientConfig.TREE_COLORS.get() == ClientConfig.TreeColors.OFF)
            return BiomeColors.getAverageFoliageColor(level, pos);
        int base = level.getBlockTint(pos, BASE);
        int share = level.getBlockTint(pos, AUTUMN_SHARE) >> 16 & 0xFF;
        int autumn = share == 0 ? tree.autumn
                : add(level.getBlockTint(pos, BIOME_AUTUMN), scale(tree.autumn, 1F - share / 255F));
        return color(tree, base, autumn, pos, false, strengthAt(level, pos));
    }

    /** Acacia and jungle: the biome tables, except in autumn-colored biomes. */
    public static int autumnBiomeLeaves(BlockAndTintGetter level, BlockPos pos) {
        if (level == null || pos == null) return FoliageColor.getDefaultColor();
        int seasoned = level.getBlockTint(pos, SEASONED_FOLIAGE);
        if (!ColorClock.active || ClientConfig.TREE_COLORS.get() == ClientConfig.TreeColors.OFF) return seasoned;
        int share = level.getBlockTint(pos, AUTUMN_SHARE) >> 16 & 0xFF;
        if (share == 0) return seasoned;
        float s = share / 255F;
        int autumn = scale(level.getBlockTint(pos, BIOME_AUTUMN), 1F / s);
        int tree = color(AUTUMN_BIOME, level.getBlockTint(pos, BASE), autumn, pos, false, strengthAt(level, pos));
        return ColorTables.mix(tree, s, seasoned, 1F - s);
    }

    /** Leaves with a fixed color (birch, spruce). */
    public static int fixedTree(Tree tree, int base, BlockAndTintGetter level, BlockPos pos, boolean patches) {
        return color(tree, base, tree.autumn, pos, patches, level == null ? 1F : strengthAt(level, pos));
    }

    /** strength: how strong the seasons are here (0..1, STRENGTH); scales each week's shift. */
    private static int color(Tree tree, int base, int autumn, BlockPos pos, boolean patches, float strength) {
        int step = ColorClock.step;
        int now = at(tree, base, autumn, step, pos, patches, strength);
        if (ClientConfig.SMOOTH.get()) {
            float p = ColorClock.progress;
            int previous = ColorClock.previous(step);
            if (p < 1F && !java.util.Arrays.equals(tree.table[step], tree.table[previous]))
                now = ColorTables.mix(now, p, at(tree, base, autumn, previous, pos, patches, strength), 1F - p);
        }
        return 0xFF000000 | (ClientConfig.TREE_COLORS.get() == ClientConfig.TreeColors.FILTER ? brightnessOf(now, base) : now);
    }

    private static int at(Tree tree, int base, int autumn, int step, BlockPos pos, boolean patches, float strength) {
        float[] entry = tree.table[step];
        int kind = (int) entry[0];
        if (kind == O) return base & 0xFFFFFF;
        if (patches && (kind == A || kind == H)) autumn = SeasonColors.autumnPatch(pos);
        int target = switch (kind) {
            case G -> SUMMER_GREEN;
            case A -> autumn;
            case H -> ColorTables.mix(autumn, .5F, tree.winter, .5F);
            case W -> tree.winter;
            default -> tree.extra;
        };
        float mix = entry[1] * strength;
        return ColorTables.mix(target & 0xFFFFFF, mix, base & 0xFFFFFF, 1F - mix);
    }

    /** FILTER: the new color's hue, the original color's brightness. */
    private static int brightnessOf(int color, int original) {
        float want = luma(original), have = luma(color);
        if (have <= 0F) return original & 0xFFFFFF;
        return scale(color, want / have);
    }

    private static int scale(int c, float f) {
        int r = Mth.clamp(Math.round((c >> 16 & 0xFF) * f), 0, 255);
        int g = Mth.clamp(Math.round((c >> 8 & 0xFF) * f), 0, 255);
        int b = Mth.clamp(Math.round((c & 0xFF) * f), 0, 255);
        return r << 16 | g << 8 | b;
    }

    private static float luma(int c) {
        return .299F * (c >> 16 & 0xFF) + .587F * (c >> 8 & 0xFF) + .114F * (c & 0xFF);
    }

    private static int add(int a, int b) {
        int r = Math.min(255, (a >> 16 & 0xFF) + (b >> 16 & 0xFF));
        int g = Math.min(255, (a >> 8 & 0xFF) + (b >> 8 & 0xFF));
        int bl = Math.min(255, (a & 0xFF) + (b & 0xFF));
        return r << 16 | g << 8 | bl;
    }

    private TreeColors() {}
}
