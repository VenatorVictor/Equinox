package dev.venatorvictor.equinox.mixin;

import dev.venatorvictor.equinox.snow.SnowyPlants;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Snow lies on a block filled with snow (a fence or plant with 8 layers), as vanilla lets it lie on a full snow
 * layer: so a fence on top of a snowed-in fence gets its snow too.
 */
@Mixin(SnowLayerBlock.class)
public abstract class SnowOnSnowMixin {
    @Inject(method = "canSurvive", at = @At("RETURN"), cancellable = true)
    private void equinox$onFullSnow(BlockState state, LevelReader level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) return;
        BlockPos below = pos.below();
        if (SnowyPlants.layers(level, below, level.getBlockState(below)) >= 8) cir.setReturnValue(true);
    }
}
