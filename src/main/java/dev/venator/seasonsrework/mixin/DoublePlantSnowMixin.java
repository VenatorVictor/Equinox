package dev.venator.seasonsrework.mixin;

import dev.venator.seasonsrework.snow.SnowyPlantBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tall plants in snow: the upper half stands on its lower half inside a snowy plant block (SnowyPlantBlock), and
 * breaking the upper half drops the plant as breaking a normal one does (vanilla drops it from the lower half).
 */
@Mixin(DoublePlantBlock.class)
public abstract class DoublePlantSnowMixin {
    private static boolean seasonsrework$lowerInSnow(BlockState upper, BlockState below) {
        return upper.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER && below.getBlock() instanceof SnowyPlantBlock snowy
                && snowy.plantBlock() == upper.getBlock();
    }

    @Inject(method = "canSurvive", at = @At("HEAD"), cancellable = true)
    private void seasonsrework$standOnSnow(BlockState state, LevelReader level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (seasonsrework$lowerInSnow(state, level.getBlockState(pos.below()))) cir.setReturnValue(true);
    }

    @Inject(method = "updateShape", at = @At("HEAD"), cancellable = true)
    private void seasonsrework$keepOnSnow(BlockState state, Direction facing, BlockState facingState, LevelAccessor level, BlockPos pos,
                                          BlockPos facingPos, CallbackInfoReturnable<BlockState> cir) {
        if (facing == Direction.DOWN && seasonsrework$lowerInSnow(state, facingState)) cir.setReturnValue(state);
    }

    @Inject(method = "playerWillDestroy", at = @At("HEAD"))
    private void seasonsrework$dropFromSnow(Level level, BlockPos pos, BlockState state, Player player, CallbackInfoReturnable<BlockState> cir) {
        BlockPos below = pos.below();
        BlockState belowState = level.getBlockState(below);
        if (level.isClientSide || player.isCreative() || !seasonsrework$lowerInSnow(state, belowState)) return;
        BlockState lower = ((SnowyPlantBlock) belowState.getBlock()).plant(belowState, level, below);
        Block.dropResources(lower, level, below, null, player, player.getMainHandItem());
    }
}
