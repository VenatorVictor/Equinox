package dev.venatorvictor.equinox.mixin.compat;

import dev.venatorvictor.equinox.snow.SnowyPlants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Shaders pick a block id for each face that has a cull side from the block's look on that side. A plant with snow
 * in it looks like the snow layer there (SnowyPlantModel gives all the snow's faces a cull side, the plant's own have
 * none): the snow on the ground gets the snow's id, so it doesn't wave or light like foliage, while the plant and
 * the snow on it still do. Not applied when that shader loader isn't installed.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.platform.IrisForgeHelpers", remap = false)
public abstract class ShaderBlockAppearanceMixin {
    @Inject(method = "getBlockAppearance", at = @At("HEAD"), cancellable = true)
    private void equinox$snowLooksLikeSnow(BlockAndTintGetter level, BlockState state, Direction side, BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        int layers = SnowyPlants.stateLayers(state);
        if (layers > 0) cir.setReturnValue(Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers));
    }
}
