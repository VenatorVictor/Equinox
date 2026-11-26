package dev.venatorvictor.equinox.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sugar cane doesn't need water next to it to be planted or to stay (vanilla breaks it when the water is gone, or
 * frozen into ice): only the soil counts. Without water it doesn't grow and looks dry, or frozen when its water froze
 * (Crops.caneWater).
 */
@Mixin(SugarCaneBlock.class)
public abstract class SugarCaneBlockMixin {
    @Inject(method = "canSurvive", at = @At("RETURN"), cancellable = true)
    private void equinox$noWaterNeeded(BlockState state, LevelReader level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() || !dev.venatorvictor.equinox.Config.seasonalCrops()) return;
        BlockState soil = level.getBlockState(pos.below());
        if (!soil.canSustainPlant(level, pos.below(), Direction.UP, state).isDefault()) return; // the soil said no
        if (soil.is(BlockTags.DIRT) || soil.is(BlockTags.SAND)) cir.setReturnValue(true);
    }
}
