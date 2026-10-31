package dev.venator.seasonsrework.mixin;

import dev.venator.seasonsrework.snow.SnowyBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Snow storage "block_entity": a block next to a SnowyBlock sees the block inside it (a fence stays connected to a
 * snowy fence, a tall plant's upper half keeps its lower half).
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class SnowyNeighbourMixin {
    @ModifyVariable(method = "updateShape", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private BlockState seasonsrework$innerNeighbour(BlockState neighbor, Direction direction, BlockState sameNeighbor, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (!(neighbor.getBlock() instanceof SnowyBlock)) return neighbor;
        BlockState inner = SnowyBlock.inner(level, neighborPos);
        return inner.isAir() ? neighbor : inner;
    }
}
