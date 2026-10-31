package dev.venator.seasonsrework.mixin;

import dev.venator.seasonsrework.snow.SnowyPlants;
import net.minecraft.world.level.block.SnowyDirtBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Grass, podzol and mycelium under a plant or fence with snow turn snowy, as under a snow layer. */
@Mixin(SnowyDirtBlock.class)
public abstract class SnowyDirtMixin {
    @Inject(method = "isSnowySetting", at = @At("RETURN"), cancellable = true)
    private static void seasonsrework$snowInPlant(BlockState above, CallbackInfoReturnable<Boolean> cir) {
        if (SnowyPlants.layers(above) > 0) cir.setReturnValue(true);
    }
}
