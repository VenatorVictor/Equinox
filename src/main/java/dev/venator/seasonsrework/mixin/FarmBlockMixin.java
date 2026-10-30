package dev.venator.seasonsrework.mixin;

import dev.venator.seasonsrework.crops.Crops;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.FarmBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** In heat, farmland needs water within 2 blocks instead of 4. */
@Mixin(FarmBlock.class)
public abstract class FarmBlockMixin {
    @Inject(method = "isNearWater", at = @At("HEAD"), cancellable = true)
    private static void seasonsrework$heat(LevelReader level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        Boolean near = Crops.nearWater(level, pos);
        if (near != null) cir.setReturnValue(near);
    }
}
