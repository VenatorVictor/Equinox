package dev.venator.seasonsrework.mixin;

import dev.venator.seasonsrework.crops.DeadCropBlock;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Ice draws no face against dead rice frozen in ice (DeadCropBlock.ICED), so the frozen paddy looks like one sheet. */
@Mixin(HalfTransparentBlock.class)
public abstract class IceSeamMixin {
    @Inject(method = "skipRendering", at = @At("HEAD"), cancellable = true)
    private void seasonsrework$iceSeam(BlockState state, BlockState adjacent, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (state.is(Blocks.ICE) && adjacent.getBlock() instanceof DeadCropBlock && adjacent.getValue(DeadCropBlock.ICED)) cir.setReturnValue(true);
    }
}
