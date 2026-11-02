package dev.venatorvictor.equinox.mixin;

import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Attached stems random tick (vanilla never does), so frost and heat reach them and their fruit. Their own tick does nothing. */
@Mixin(BlockBehaviour.class)
public abstract class AttachedStemTickMixin {
    @Inject(method = "isRandomlyTicking", at = @At("HEAD"), cancellable = true)
    private void equinox$attachedStemTicks(BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof AttachedStemBlock) cir.setReturnValue(true);
    }
}
