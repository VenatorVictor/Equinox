package dev.venator.seasonsrework.mixin;

import dev.venator.seasonsrework.snow.SnowyPlants;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SpreadingSnowyDirtBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Grass stays grass under snow (vanilla turns it to dirt under more than one layer): dormant, not dead. */
@Mixin(SpreadingSnowyDirtBlock.class)
public abstract class SpreadingSnowyDirtMixin {
    @Inject(method = "canBeGrass", at = @At("RETURN"), cancellable = true)
    private static void seasonsrework$grassUnderSnow(BlockState state, LevelReader level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) return;
        BlockState above = level.getBlockState(pos.above());
        if (above.getBlock() instanceof SnowLayerBlock || SnowyPlants.layers(level, pos.above(), above) > 0) cir.setReturnValue(true);
    }
}
