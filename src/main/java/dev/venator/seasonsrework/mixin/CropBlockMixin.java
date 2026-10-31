package dev.venator.seasonsrework.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.venator.seasonsrework.crops.SnowyCrops;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ripe crops random tick too (vanilla stops them), so frost and heat still reach them. Their own tick does nothing.
 * Growing keeps the snow on the crop (vanilla sets the next age's default state).
 */
@Mixin(CropBlock.class)
public abstract class CropBlockMixin {
    @Inject(method = "isRandomlyTicking", at = @At("HEAD"), cancellable = true)
    private void seasonsrework$alwaysTicks(BlockState state, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(true);
    }

    @WrapOperation(method = "randomTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean seasonsrework$keepSnow(ServerLevel level, BlockPos pos, BlockState now, int flags, Operation<Boolean> original,
                                           @Local(argsOnly = true) BlockState state) {
        if (state.hasProperty(SnowyCrops.SNOWY) && now.hasProperty(SnowyCrops.SNOWY)) now = now.setValue(SnowyCrops.SNOWY, state.getValue(SnowyCrops.SNOWY));
        return original.call(level, pos, now, flags);
    }
}
