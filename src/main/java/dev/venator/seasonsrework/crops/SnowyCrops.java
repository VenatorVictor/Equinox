package dev.venator.seasonsrework.crops;

import dev.venator.seasonsrework.snow.FirstSnow;
import dev.venator.seasonsrework.snow.SnowRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Snow on crops, like snow on grass: farmland crops get a snowy block state (added by BlockSnowyMixin) that the
 * snowy textures follow. Set on the crop's random tick while it snows on it (open sky, no heater, first snow fallen);
 * melts on a later random tick under a roof, near a heater or once it's warm, so it goes block by block.
 * is() runs while blocks are built, so it touches only vanilla classes.
 */
public final class SnowyCrops {
    public static final BooleanProperty SNOWY = BlockStateProperties.SNOWY;

    /** Blocks that get the snowy state (the farmland crops, as Crops.mortal). */
    public static boolean is(Block block) {
        return block instanceof CropBlock || block instanceof StemBlock || block instanceof AttachedStemBlock;
    }

    /** Whether snow lies on the crop (or frozen dead crop) after this random tick; state holds SNOWY. */
    public static boolean snowy(BlockState state, ServerLevel level, BlockPos pos) {
        Holder<Biome> biome = level.getBiome(pos);
        // Melts: warm, a heater nearby, or a roof (glass too; leaves aren't one).
        if (!SnowRules.cold(biome, pos) || CropWarmth.heated(level, pos)
                || pos.getY() < level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ())) return false;
        if (state.getValue(SNOWY)) return true;
        // Settles only while it snows on it.
        return level.isRaining() && biome.value().hasPrecipitation()
                && pos.getY() >= level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ())
                && SnowRules.snowAllowed(biome, pos, FirstSnow.coverage);
    }

    private SnowyCrops() {}
}
