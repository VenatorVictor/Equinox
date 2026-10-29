package dev.venator.seasonsrework.client.colors;

import dev.venator.seasonsrework.client.ClientConfig;
import dev.venator.seasonsrework.climate.ClimateBiome;
import dev.venator.seasonsrework.climate.ColorProfile;
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

/** Ecliptic's grass/foliage/leaf formulas, fed by {@link ColorClock}. */
public final class SeasonColors {
    private static final int END_OF_HEAT = 13;
    private static final int LIGHT_SNOW = 19;
    private static final ColorTables.Term[][] TABLES = new ColorTables.Term[ColorProfile.VALUES.length][];

    static {
        TABLES[ColorProfile.STABLE.ordinal()] = ColorTables.STABLE;
        TABLES[ColorProfile.SLIGHTLY.ordinal()] = ColorTables.SLIGHTLY;
        TABLES[ColorProfile.MONSOONAL.ordinal()] = ColorTables.MONSOONAL;
        TABLES[ColorProfile.SEASONAL_HOT.ordinal()] = ColorTables.HOT;
        TABLES[ColorProfile.SEASONAL_COLD.ordinal()] = ColorTables.COLD;
        TABLES[ColorProfile.SEASONAL.ordinal()] = ColorTables.TEMPERATE;
    }

    public static void registerLeaves(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tint) -> leaves(FoliageColor.getEvergreenColor(), ColorTables.SPRUCE, false, pos,
                ClientConfig.SPRUCE.get()), Blocks.SPRUCE_LEAVES);
        event.register((state, level, pos, tint) -> leaves(FoliageColor.getBirchColor(), ColorTables.BIRCH, ClientConfig.BIRCH_PATCHES.get(), pos,
                ClientConfig.BIRCH.get()), Blocks.BIRCH_LEAVES);
        event.register((state, level, pos, tint) -> leaves(FoliageColor.getMangroveColor(), ColorTables.MANGROVE, false, pos,
                ClientConfig.MANGROVE.get()), Blocks.MANGROVE_LEAVES);
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
        if (!ColorClock.active || !(grass ? ClientConfig.GRASS.get() : ClientConfig.FOLIAGE.get())) return original;
        ColorTables.Term[] terms = TABLES[((ClimateBiome) (Object) biome).seasonsrework$colorProfile().ordinal()];
        int now = apply(grass, terms[ColorClock.step], original);
        if (!ClientConfig.SMOOTH.get()) return now;
        ColorTables.Term prevTerm = terms[ColorClock.previousStep()];
        if (prevTerm.equals(terms[ColorClock.step])) return now;
        float p = ColorClock.progress;
        return ColorTables.mix(now, p, apply(grass, prevTerm, original), 1F - p);
    }

    private static int apply(boolean grass, ColorTables.Term term, int original) {
        return ColorTables.mix(grass ? term.grass() : term.leaf(), term.mix(), original, 1F - term.mix());
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
        if (patches && step >= END_OF_HEAT && step <= LIGHT_SNOW) color = autumnPatch(pos);
        return ColorTables.mix(color, mix, base, 1F - mix);
    }

    // Ecliptic's autumn birch: hue drifts between orange and yellow with position.
    private static int autumnPatch(BlockPos pos) {
        float x = (float) ((Math.sin(pos.getX() / 16F) + 1) / 2);
        float y = (float) ((Math.sin(pos.getY() / 128F) + 1) / 2);
        float z = (float) ((Math.sin(pos.getZ() / 32F) + 1) / 2);
        float hue = 0.025F + (x + y + z) / 3F * 0.14F;
        return 0xFF000000 | Mth.hsvToRgb(hue, 1F, 1F);
    }

    private SeasonColors() {}
}
