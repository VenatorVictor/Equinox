package dev.venatorvictor.equinox.client.colors;

import dev.venatorvictor.equinox.client.ClientConfig;
import dev.venatorvictor.equinox.climate.ClimateBiome;
import dev.venatorvictor.equinox.climate.ColorProfile;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

import java.util.ArrayList;
import java.util.List;

/** Seasonal grass/foliage/leaf formulas, fed by {@link ColorClock}. */
public final class SeasonColors {
    /** Birch autumn patches (tree_colors = OFF tables): autumn week 1 to winter week 2. */
    private static final int PATCHES_FROM = 28, PATCHES_TO = 43;
    private static final ColorTables.Week[][] TABLES = new ColorTables.Week[ColorProfile.VALUES.length][];

    static {
        TABLES[ColorProfile.STABLE.ordinal()] = ColorTables.STABLE;
        TABLES[ColorProfile.SLIGHTLY.ordinal()] = ColorTables.SLIGHTLY;
        TABLES[ColorProfile.MONSOONAL.ordinal()] = ColorTables.MONSOONAL;
        TABLES[ColorProfile.SEASONAL_HOT.ordinal()] = ColorTables.HOT;
        TABLES[ColorProfile.SEASONAL_COLD.ordinal()] = ColorTables.COLD;
        TABLES[ColorProfile.SEASONAL.ordinal()] = ColorTables.TEMPERATE;
    }

    public static void registerLeaves(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tint) -> fixedTree(FoliageColor.getEvergreenColor(), ColorTables.SPRUCE,
                TreeColors.SPRUCE, false, level, pos, ClientConfig.SPRUCE.get()), Blocks.SPRUCE_LEAVES);
        event.register((state, level, pos, tint) -> fixedTree(FoliageColor.getBirchColor(), ColorTables.BIRCH,
                TreeColors.BIRCH, ClientConfig.BIRCH_PATCHES.get(), level, pos, ClientConfig.BIRCH.get()), Blocks.BIRCH_LEAVES);
        event.register((state, level, pos, tint) -> leaves(FoliageColor.getMangroveColor(), ColorTables.MANGROVE, false, pos,
                ClientConfig.MANGROVE.get()), Blocks.MANGROVE_LEAVES);
        event.register((state, level, pos, tint) -> TreeColors.biomeTree(TreeColors.OAK, level, pos), Blocks.OAK_LEAVES);
        event.register((state, level, pos, tint) -> TreeColors.biomeTree(TreeColors.DARK_OAK, level, pos), Blocks.DARK_OAK_LEAVES);
        event.register((state, level, pos, tint) -> TreeColors.autumnBiomeLeaves(level, pos), Blocks.ACACIA_LEAVES, Blocks.JUNGLE_LEAVES);
        // cherry: only its tinted season textures (tint 0) ask; vanilla's pink leaves have no tint
        event.register((state, level, pos, tint) -> ClientConfig.TREE_COLORS.get() == ClientConfig.TreeColors.OFF
                || pos == null || !ColorClock.active ? 0xFF000000 | TreeColors.CHERRY_GREEN
                : TreeColors.fixedTree(TreeColors.CHERRY, TreeColors.CHERRY_GREEN, level, pos, false), Blocks.CHERRY_LEAVES);
    }

    /** Birch and spruce: per-tree colors, or the earlier tables with tree_colors = OFF. */
    private static int fixedTree(int base, float[][] oldTable, TreeColors.Tree tree, boolean patches,
                                 net.minecraft.world.level.BlockAndTintGetter level, BlockPos pos, boolean enabled) {
        if (ClientConfig.TREE_COLORS.get() == ClientConfig.TreeColors.OFF) return leaves(base, oldTable, patches, pos, enabled);
        if (!enabled || pos == null || !ColorClock.active) return base;
        return TreeColors.fixedTree(tree, base, level, pos, patches);
    }

    /**
     * Grass color for plants that have no color of their own (flowers, crops, saplings, modded plants), so resource
     * packs can tint their green parts like grass (tintindex 0; the Seasonal Plants pack does). Models without a
     * tintindex never ask, so other plants look as before. Pumpkin and melon stems too, in place of vanilla's
     * green-to-yellow ripening tint, so they match the other crops.
     */
    public static void registerPlants(RegisterColorHandlersEvent.Block event) {
        List<Block> plants = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block instanceof BushBlock && !event.getBlockColors().blockColors.containsKey(block)) plants.add(block);
        }
        plants.addAll(List.of(Blocks.PUMPKIN_STEM, Blocks.MELON_STEM, Blocks.ATTACHED_PUMPKIN_STEM, Blocks.ATTACHED_MELON_STEM));
        event.register((state, level, pos, tint) -> level != null && pos != null
                ? BiomeColors.getAverageGrassColor(level, pos) : GrassColor.getDefaultColor(), plants.toArray(Block[]::new));
    }

    /** The biome's own grass or foliage color (vanilla, or a biome mod's), with the season on top. BiomeColorMixin. */
    public static int grassOrFoliage(boolean grass, int original, Biome biome) {
        return grassOrFoliage(grass, original, biome, 1F);
    }

    /**
     * The same, with the cold seasons' colors (seasonal profiles) scaled by cold, 0..1: how much of the winter's cold
     * the place gets (climate zones; ClimateBlend). Dry seasons and the other profiles don't change.
     */
    public static int grassOrFoliage(boolean grass, int original, Biome biome, float cold) {
        if (!ColorClock.active || !(grass ? ClientConfig.GRASS.get() : ClientConfig.FOLIAGE.get())) return original;
        ColorProfile profile = ((ClimateBiome) (Object) biome).equinox$colorProfile();
        ColorTables.Week[] weeks = TABLES[profile.ordinal()];
        float scale = profile == ColorProfile.SEASONAL || profile == ColorProfile.SEASONAL_HOT || profile == ColorProfile.SEASONAL_COLD ? cold : 1F;
        int now = apply(grass, weeks[ColorClock.step], original, scale);
        if (!ClientConfig.SMOOTH.get()) return now;
        ColorTables.Week prevWeek = weeks[ColorClock.previousStep()];
        if (prevWeek.equals(weeks[ColorClock.step])) return now;
        float p = ColorClock.progress;
        return ColorTables.mix(now, p, apply(grass, prevWeek, original, scale), 1F - p);
    }

    private static int apply(boolean grass, ColorTables.Week week, int original, float scale) {
        float mix = week.mix() * scale;
        return ColorTables.mix(grass ? week.grass() : week.leaf(), mix, original, 1F - mix);
    }

    // ---- Climate color blending (ClientConfig.CLIMATE_COLOR_BLEND)

    /** Set while blendedGrass reads the biomes' own colors (BiomeColorMixin then leaves them alone). */
    public static final ThreadLocal<Boolean> RAW = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** How much of the winter's cold a biome gets at a place (cooling: the climate zone there, NaN = unknown). */
    public static float cold(Biome biome, float cooling) {
        if (!ClientConfig.CLIMATE_COLOR_STRENGTH.get() || Float.isNaN(cooling)) return 1F;
        return ((ClimateBiome) (Object) biome).equinox$zone() == dev.venatorvictor.equinox.climate.Zone.ALPINE ? 1F : cooling;
    }

    /**
     * Grass color at a column: the seasonal grass colors of the land biomes around it (ClimateBlend.colorMix), by their
     * shares, mixed with the column's own biome by climate_color_blend. ownRaw: the own biome's color without the season.
     */
    public static int blendedGrass(Biome own, double x, double z, int ownRaw) {
        dev.venatorvictor.equinox.climate.ClimateBlend.ColorMix mix =
                dev.venatorvictor.equinox.climate.ClimateBlend.colorMix(Mth.floor(x), Mth.floor(z));
        int ownColor = grassOrFoliage(true, ownRaw, own, cold(own, mix.cooling));
        float k = ClientConfig.CLIMATE_COLOR_BLEND.get().floatValue();
        if (k <= 0 || mix.count == 0) return ownColor;
        float r = 0, g = 0, b = 0;
        RAW.set(Boolean.TRUE);
        try {
            for (int i = 0; i < mix.count; i++) {
                Biome biome = mix.biomes[i];
                int c = grassOrFoliage(true, biome.getGrassColor(x, z), biome, cold(biome, mix.cooling));
                float w = mix.weights[i];
                r += w * (c >> 16 & 0xFF);
                g += w * (c >> 8 & 0xFF);
                b += w * (c & 0xFF);
            }
        } finally {
            RAW.set(Boolean.FALSE);
        }
        int mixed = Math.round(r) << 16 | Math.round(g) << 8 | Math.round(b);
        return k >= 1 ? mixed : ColorTables.mix(mixed, k, ownColor, 1F - k) & 0xFFFFFF;
    }

    private static int leaves(int base, float[][] table, boolean patches, BlockPos pos, boolean enabled) {
        if (!enabled || pos == null || !ColorClock.active) return base;
        int step = ColorClock.step;
        int now = leafColor(base, table, step, patches, pos);
        if (!ClientConfig.SMOOTH.get()) return now;
        float p = ColorClock.progress;
        return ColorTables.mix(now, p, leafColor(base, table, ColorClock.previousStep(), patches, pos), 1F - p);
    }

    private static int leafColor(int base, float[][] table, int step, boolean patches, BlockPos pos) {
        int color = (int) table[step][0];
        float mix = table[step][1];
        if (patches && step >= PATCHES_FROM && step <= PATCHES_TO) color = autumnPatch(pos);
        return ColorTables.mix(color, mix, base, 1F - mix);
    }

    // Autumn birch: hue drifts between orange and yellow with position.
    static int autumnPatch(BlockPos pos) {
        float x = (float) ((Math.sin(pos.getX() / 16F) + 1) / 2);
        float y = (float) ((Math.sin(pos.getY() / 128F) + 1) / 2);
        float z = (float) ((Math.sin(pos.getZ() / 32F) + 1) / 2);
        float hue = 0.025F + (x + y + z) / 3F * 0.14F;
        return 0xFF000000 | Mth.hsvToRgb(hue, 1F, 1F);
    }

    private SeasonColors() {}
}
