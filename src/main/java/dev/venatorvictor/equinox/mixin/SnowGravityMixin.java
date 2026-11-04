package dev.venatorvictor.equinox.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Snow gravity: when the block under a snow layer goes and the space below is open, the snow falls (a falling block,
 * like sand) instead of vanishing; FallingSnowMixin lands it. Vanilla still pops it when it can't stay for another
 * reason (ice under it). Only in a running world (not while chunks generate).
 */
@Mixin(SnowLayerBlock.class)
public abstract class SnowGravityMixin extends Block {
    private SnowGravityMixin(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Inject(method = "updateShape", at = @At("HEAD"), cancellable = true)
    private void equinox$fall(BlockState state, Direction facing, BlockState facingState, LevelAccessor level, BlockPos pos, BlockPos facingPos,
                              CallbackInfoReturnable<BlockState> cir) {
        if (facing != Direction.DOWN || !(level instanceof Level) || !FallingBlock.isFree(facingState) || pos.getY() <= level.getMinBuildHeight()) return;
        if (!level.isClientSide()) level.scheduleTick(pos, this, 2);
        cir.setReturnValue(state); // the server decides (tick below); the client keeps it until told
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (FallingBlock.isFree(level.getBlockState(pos.below())) && pos.getY() > level.getMinBuildHeight()) {
            FallingBlockEntity.fall(level, pos, state).dropItem = false;
        } else if (!state.canSurvive(level, pos)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }
}
