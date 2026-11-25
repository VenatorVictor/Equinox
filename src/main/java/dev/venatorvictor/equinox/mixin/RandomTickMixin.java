package dev.venatorvictor.equinox.mixin;

import dev.venatorvictor.equinox.crops.Crops;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frost and heat kill farmland crops on their random tick (ripe ones too, see CropBlockMixin). Whether a state is a
 * crop is worked out once per state (initCache), so every other block's random tick only reads one field.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class RandomTickMixin {
    @Shadow
    protected abstract BlockState asState();

    @Unique
    private boolean equinox$mortal;

    @Inject(method = "initCache", at = @At("TAIL"))
    private void equinox$cacheMortal(CallbackInfo ci) {
        equinox$mortal = Crops.mortal(asState().getBlock());
    }

    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void equinox$frostAndHeat(ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        if (equinox$mortal && Crops.onRandomTick((BlockState) (Object) this, level, pos)) ci.cancel();
    }
}
