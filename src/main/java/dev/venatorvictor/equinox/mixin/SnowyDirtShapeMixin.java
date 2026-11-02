package dev.venatorvictor.equinox.mixin;

import dev.venatorvictor.equinox.snow.SnowMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.SnowyDirtBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Grass under a fence with snow (SnowMap) stays snowy when the fence above changes shape. */
@Mixin(SnowyDirtBlock.class)
public abstract class SnowyDirtShapeMixin {
    @Inject(method = "updateShape", at = @At("RETURN"), cancellable = true)
    private void equinox$snowInFence(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level, BlockPos pos,
                                           BlockPos neighborPos, CallbackInfoReturnable<BlockState> cir) {
        BlockState result = cir.getReturnValue();
        if (direction == Direction.UP && result.getBlock() instanceof SnowyDirtBlock && !result.getValue(SnowyDirtBlock.SNOWY)
                && SnowMap.holds(neighbor) && SnowMap.get(level, neighborPos) > 0)
            cir.setReturnValue(result.setValue(SnowyDirtBlock.SNOWY, true));
    }
}
