package dev.venatorvictor.equinox.snow;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import snownee.snow.CoreModule;
import snownee.snow.Hooks;
import snownee.snow.block.SnowFenceBlock;
import snownee.snow.block.SnowFenceGateBlock;
import snownee.snow.block.SnowSlabBlock;
import snownee.snow.block.SnowStairsBlock;
import snownee.snow.block.SnowVariant;
import snownee.snow.block.SnowWallBlock;

/** Compat with a snow mod: snowy plants, slabs, stairs, fences. Only loaded when it's installed. */
final class SnowModCompat {
    static void cover(ServerLevel level, BlockPos top, BlockState topState, BlockPos below, BlockState belowState) {
        if (Hooks.canContainState(topState) && !canMelt(topState)) Hooks.convert(level, top, topState, 1, Block.UPDATE_CLIENTS, true);
        else if (Hooks.canContainState(belowState) && !canMelt(belowState)) Hooks.convert(level, below.immutable(), belowState, 1, Block.UPDATE_CLIENTS, true);
    }

    static void melt(ServerLevel level, BlockPos top, BlockState topState, BlockPos below, BlockState belowState) {
        if (canMelt(topState)) unsnow(level, top, topState);
        else if (canMelt(belowState)) unsnow(level, below.immutable(), belowState);
    }

    private static boolean canMelt(BlockState state) {
        Block block = state.getBlock();
        return state.is(CoreModule.SNOW_TAG) || block instanceof SnowSlabBlock || block instanceof SnowStairsBlock
                || block instanceof SnowWallBlock || block instanceof SnowFenceBlock || block instanceof SnowFenceGateBlock;
    }

    private static void unsnow(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof SnowVariant snow) level.setBlock(pos, snow.srm$getRaw(state, level, pos), Block.UPDATE_ALL);
    }

    private SnowModCompat() {}
}
