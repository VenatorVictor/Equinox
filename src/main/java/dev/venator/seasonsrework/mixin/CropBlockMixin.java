package dev.venator.seasonsrework.mixin;

import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ripe crops random tick too (vanilla stops them), so frost and heat still reach them. Their own tick does nothing. */
@Mixin(CropBlock.class)
public abstract class CropBlockMixin {
    @Inject(method = "isRandomlyTicking", at = @At("HEAD"), cancellable = true)
    private void seasonsrework$alwaysTicks(BlockState state, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(true);
    }
}
