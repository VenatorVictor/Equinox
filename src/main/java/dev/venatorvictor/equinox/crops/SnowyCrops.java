package dev.venatorvictor.equinox.crops;

import dev.venatorvictor.equinox.snow.Snowpack;
import dev.venatorvictor.equinox.snow.SnowRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * Snow on crops, like snow on grass: farmland crops (and frozen dead crops) get a snowy block state (added by
 * BlockSnowyMixin) that the snowy textures follow. It comes with the snowfall, the same snowfall that lays snow on
 * the ground: the snow cover's columns (SnowCover) and vanilla's own snowfall (SnowyPlants.onSnowfall) put it on the
 * crop at the top of a column instead of failing on it. So a glass roof keeps it off (the snowfall stops on the roof)
 * and crops in the middle of a field get it like the ground around them. Not while a heater is near or in block light
 * 10 or more (vanilla's snowfall rule). It melts on a later random tick, or by the snow cover, where snow no longer
 * lies (a thaw, rain), near a heater or in block light above 11 (as vanilla snow layers); a roof built over it later
 * doesn't melt it. It stays when the crop grows, is bonemealed, a stem takes its fruit or the crop freezes (keep).
 * is() runs while blocks are built, so it touches only vanilla classes.
 */
public final class SnowyCrops {
    public static final BooleanProperty SNOWY = BlockStateProperties.SNOWY;

    /** Blocks that get the snowy state (the farmland crops, as Crops.mortal). */
    public static boolean is(Block block) {
        return block instanceof CropBlock || block instanceof StemBlock || block instanceof AttachedStemBlock;
    }

    /** Whether this block takes snow as a crop: a living farmland crop or a frost-killed dead crop. */
    public static boolean holds(BlockState state) {
        if (state.getBlock() instanceof DeadCropBlock) return state.getValue(DeadCropBlock.CAUSE) == DeadCropBlock.Cause.FROST;
        return is(state.getBlock()) && state.hasProperty(SNOWY);
    }

    /** Snowfall reached the crop at pos (it's the top of its column): it gets snow. False if it can't. */
    public static boolean cover(ServerLevel level, BlockPos pos, BlockState state) {
        if (!holds(state) || state.getValue(SNOWY) || level.getBrightness(LightLayer.BLOCK, pos) >= 10 || CropWarmth.heated(level, pos)) return false;
        level.setBlock(pos, state.setValue(SNOWY, true), Block.UPDATE_CLIENTS);
        return true;
    }

    /** Takes the crop's snow off. False if it had none. */
    public static boolean melt(ServerLevel level, BlockPos pos, BlockState state) {
        if (!holds(state) || !state.getValue(SNOWY)) return false;
        level.setBlock(pos, state.setValue(SNOWY, false), Block.UPDATE_CLIENTS);
        return true;
    }

    /** Random tick: whether the crop's snow (if any) stays. Snow only comes with the snowfall, never from this. */
    public static boolean snowy(BlockState state, ServerLevel level, BlockPos pos) {
        if (!state.getValue(SNOWY)) return false;
        Holder<Biome> biome = level.getBiome(pos);
        return SnowRules.lies(biome, pos, Snowpack.warmest()) && !CropWarmth.heated(level, pos) && level.getBrightness(LightLayer.BLOCK, pos) <= 11;
    }

    /**
     * LevelChunkMixin, a block being set: a snowy crop that becomes another state of a plant (grows, bonemeal, stem to
     * attached stem) keeps its snow. Only taking the snow off (melting) or turning into something else drops it.
     */
    public static BlockState keep(BlockState old, BlockState now) {
        if (old == now || !old.hasProperty(SNOWY) || !old.getValue(SNOWY) || !now.hasProperty(SNOWY) || now.getValue(SNOWY)) return now;
        if (!is(old.getBlock()) || !is(now.getBlock())) return now;
        if (now == old.setValue(SNOWY, false)) return now; // melted
        return now.setValue(SNOWY, true);
    }

    private SnowyCrops() {}
}
