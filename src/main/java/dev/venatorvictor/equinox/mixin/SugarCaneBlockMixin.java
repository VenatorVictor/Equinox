package dev.venatorvictor.equinox.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sugar cane whose water is gone (taken away, or frozen into ice) stays instead of breaking: it doesn't grow and
 * looks dry or frozen (Crops.caneWater). Its soil still counts. Planting it still needs water, as in vanilla.
 */
@Mixin(SugarCaneBlock.class)
public abstract class SugarCaneBlockMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;canSurvive(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean equinox$staysWithoutWater(BlockState state, LevelReader level, BlockPos pos, Operation<Boolean> original) {
        if (original.call(state, level, pos)) return true;
        BlockState soil = level.getBlockState(pos.below());
        if (soil.is(state.getBlock())) return true;
        if (!soil.canSustainPlant(level, pos.below(), Direction.UP, state).isDefault()) return false; // the soil said no
        return soil.is(BlockTags.DIRT) || soil.is(BlockTags.SAND);
    }
}
