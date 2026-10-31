package dev.venator.seasonsrework.mixin;

import dev.venator.seasonsrework.snow.SnowyPlantBlock;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Placing snow on a snowy plant adds a layer and keeps the plant (vanilla only stacks on plain snow). */
@Mixin(SnowLayerBlock.class)
public abstract class SnowLayerPlacementMixin {
    @Inject(method = "getStateForPlacement", at = @At("HEAD"), cancellable = true)
    private void seasonsrework$stackOnPlant(BlockPlaceContext context, CallbackInfoReturnable<BlockState> cir) {
        BlockState state = context.getLevel().getBlockState(context.getClickedPos());
        if (state.getBlock() instanceof SnowyPlantBlock)
            cir.setReturnValue(state.setValue(SnowLayerBlock.LAYERS, Math.min(8, state.getValue(SnowLayerBlock.LAYERS) + 1)));
    }
}
