package dev.venatorvictor.equinox.client.colors;

import dev.venatorvictor.equinox.client.ClientConfig;
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
 * tree's autumn color, winter = its dry leaves. All on the calendar (step 0 = the spring equinox).
 * <p>
 * Biomes whose own foliage color is clearly an autumn one (gold, orange, red) use it as their autumn color and are
 * green in spring and summer.
 */
public final class TreeColors {
    /** How a table step colors the leaves: the tree's original color, or a shift toward one of its colors. */
    private static final int O = 0, G = 1, A = 2, H = 3, W = 4, T = 5;

    private static final int SUMMER_GREEN = 0x3F8A1C;

    /** A tree: its autumn, winter and extra color, and 24 steps of {kind, mix}. */
    public record Tree(int autumn, int winter, int extra, float[][] table) {}

    // Retuned 2026-11-14 to real dates (central Europe; step 0 = Mar 20, ~15 days a step: 2 = Apr 19, 12 = Sep 19,
    // 13 = Oct 4, 14 = Oct 19, 15 = Nov 3, 16 = Nov 19, 17 = Dec 4), matching the leaf fall steps in the season files:
    // each tree turns before its leaves start to fall. New leaves come out in the tree's own color.

    /** Leaf-out Apr 19; turns Oct 19, gold-brown by the first leaf fall (Nov 3), brown as the last ones go (Dec 4). */
    public static final Tree OAK = new Tree(0xB8862B, 0x7C5A32, 0, new float[][]{
            {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F}, {G, .15F}, {G, .2F},
            {G, .25F}, {G, .25F}, {G, .25F}, {G, .25F}, {G, .25F}, {G, .25F},
            {G, .2F}, {G, .1F}, {A, .6F}, {A, .85F}, {H, .85F}, {W, .75F},
            {W, .7F}, {W, .7F}, {W, .7F}, {W, .7F}, {W, .7F}, {W, .7F}});

    /** Like a beech: turns Oct 19, copper Nov 3, then dry brown (darker, no green) kept until the new leaves (Apr 19). */
    public static final Tree DARK_OAK = new Tree(0xC0642A, 0x8B5A33, 0, new float[][]{
            {W, .85F}, {W, .85F}, {O, 0F}, {O, 0F}, {G, .1F}, {G, .2F},
            {G, .2F}, {G, .2F}, {G, .2F}, {G, .2F}, {G, .2F}, {G, .2F},
            {G, .2F}, {G, .15F}, {A, .5F}, {A, .85F}, {H, .85F}, {W, .85F},
            {W, .85F}, {W, .85F}, {W, .85F}, {W, .85F}, {W, .85F}, {W, .85F}});

    /** One of the first to turn: leaf-out Apr 4; yellow from Sep 19, full by the first leaf fall (Oct 4), bare Nov 19. */
    public static final Tree BIRCH = new Tree(0xF2C230, 0xAD9F5F, 0, new float[][]{
            {O, 0F}, {O, 0F}, {O, 0F}, {G, .1F}, {G, .2F}, {G, .2F},
            {G, .2F}, {G, .2F}, {G, .2F}, {G, .2F}, {G, .2F}, {G, .1F},
            {A, .5F}, {A, .85F}, {A, .9F}, {H, .8F}, {W, .7F}, {W, .6F},
            {W, .6F}, {W, .6F}, {W, .6F}, {W, .6F}, {W, .6F}, {W, .6F}});

    /** Evergreen: bright new tips in May-June, duller and darker in winter. */
    public static final Tree SPRUCE = new Tree(0, 0x4A6A4A, 0x7FB24A, new float[][]{
            {W, .25F}, {W, .2F}, {W, .1F}, {T, .2F}, {T, .3F}, {T, .2F},
            {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F}, {O, 0F},
            {O, 0F}, {O, 0F}, {W, .1F}, {W, .15F}, {W, .2F}, {W, .25F},
            {W, .3F}, {W, .3F}, {W, .3F}, {W, .3F}, {W, .3F}, {W, .3F}});

    /** Acacia and jungle leaves in autumn-colored biomes (aspen-like: gold in September, bare in early October). */
    public static final Tree AUTUMN_BIOME = new Tree(0, 0x8F7A55, 0, new float[][]{
            {W, .6F}, {W, .5F}, {W, .25F}, {O, 0F}, {G, .15F}, {G, .15F},
            {G, .15F}, {G, .15F}, {G, .15F}, {G, .15F}, {G, .15F}, {A, .6F},
            {A, .9F}, {H, .8F}, {W, .7F}, {W, .6F}, {W, .6F}, {W, .6F},
            {W, .6F}, {W, .6F}, {W, .6F}, {W, .6F}, {W, .6F}, {W, .6F}});

    // ---- The biome's own foliage color, without the season (blended like vanilla's)

    private static int raw(Biome biome) {
        return biome.getSpecialEffects().getFoliageColorOverride()
                .orElseGet(((BiomeFoliageAccessor) (Object) biome)::equinox$foliageFromTexture);
    }

    /** Spring/summer color: the biome's own, or green where the biome's own is an autumn color. */
    public static final ColorResolver BASE = (biome, x, z) -> {
        int c = raw(biome);
        return autumnal(c) ? FoliageColor.getDefaultColor() & 0xFFFFFF : c;
    };
    /** The biome's own color where it is an autumn one, else black (summed, so blending weighs it). */
    public static final ColorResolver BIOME_AUTUMN = (biome, x, z) -> {
        int c = raw(biome);
        return autumnal(c) ? c : 0;
    };
    /** White where the biome's color is an autumn one: the blended red channel is the autumn share. */
    public static final ColorResolver AUTUMN_SHARE = (biome, x, z) -> autumnal(raw(biome)) ? 0xFFFFFF : 0;

    public static void registerResolvers(RegisterColorHandlersEvent.ColorResolvers event) {
        event.register(BASE);
        event.register(BIOME_AUTUMN);
        event.register(AUTUMN_SHARE);
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
        return color(tree, base, autumn, pos, false);
    }

    /** Acacia and jungle: the biome tables, except in autumn-colored biomes. */
    public static int autumnBiomeLeaves(BlockAndTintGetter level, BlockPos pos) {
        if (level == null || pos == null) return FoliageColor.getDefaultColor();
        int seasoned = BiomeColors.getAverageFoliageColor(level, pos);
        if (!ColorClock.active || ClientConfig.TREE_COLORS.get() == ClientConfig.TreeColors.OFF) return seasoned;
        int share = level.getBlockTint(pos, AUTUMN_SHARE) >> 16 & 0xFF;
        if (share == 0) return seasoned;
        float s = share / 255F;
        int autumn = scale(level.getBlockTint(pos, BIOME_AUTUMN), 1F / s);
        int tree = color(AUTUMN_BIOME, level.getBlockTint(pos, BASE), autumn, pos, false);
        return ColorTables.mix(tree, s, seasoned, 1F - s);
    }

    /** Leaves with a fixed color (birch, spruce). */
    public static int fixedTree(Tree tree, int base, BlockPos pos, boolean patches) {
        return color(tree, base, tree.autumn, pos, patches);
    }

    private static int color(Tree tree, int base, int autumn, BlockPos pos, boolean patches) {
        int step = ColorClock.step;
        int now = at(tree, base, autumn, step, pos, patches);
        if (ClientConfig.SMOOTH.get()) {
            float p = ColorClock.progress;
            int previous = ColorClock.previous(step);
            if (p < 1F && !java.util.Arrays.equals(tree.table[step], tree.table[previous]))
                now = ColorTables.mix(now, p, at(tree, base, autumn, previous, pos, patches), 1F - p);
        }
        return 0xFF000000 | (ClientConfig.TREE_COLORS.get() == ClientConfig.TreeColors.FILTER ? brightnessOf(now, base) : now);
    }

    private static int at(Tree tree, int base, int autumn, int step, BlockPos pos, boolean patches) {
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
        return ColorTables.mix(target & 0xFFFFFF, entry[1], base & 0xFFFFFF, 1F - entry[1]);
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
