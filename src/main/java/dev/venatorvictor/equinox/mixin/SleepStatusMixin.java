package dev.venatorvictor.equinox.mixin;

import dev.venatorvictor.equinox.Config;
import net.minecraft.server.players.SleepStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Real time: beds set the spawn point and players can lie in them, but the night is never skipped. */
@Mixin(SleepStatus.class)
public abstract class SleepStatusMixin {
    @Inject(method = "areEnoughSleeping", at = @At("HEAD"), cancellable = true)
    private void equinox$noSkipInRealTime(int requiredSleepPercentage, CallbackInfoReturnable<Boolean> cir) {
        if (Config.realTime()) cir.setReturnValue(false);
    }
}
