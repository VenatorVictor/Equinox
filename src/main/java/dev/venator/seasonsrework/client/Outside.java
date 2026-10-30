package dev.venator.seasonsrework.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Whether snow can reach a position, for snowy looks on the client. Uses the motion-blocking height map like vanilla
 * snowfall and the server's snow cover, so glass is a roof (sky light passes through it, which is why the sky-light
 * check let snow show in greenhouses). Leaves don't count as a roof: snow lies under trees.
 */
public final class Outside {
    public static boolean outside(ClientLevel level, BlockPos pos) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
        if (pos.getY() >= top) return true;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = top - 1; y > pos.getY(); y--) {
            BlockState state = level.getBlockState(cursor.set(pos.getX(), y, pos.getZ()));
            if (!state.is(BlockTags.LEAVES) && (state.blocksMotion() || !state.getFluidState().isEmpty())) return false;
        }
        return true;
    }

    private Outside() {}
}
