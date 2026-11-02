package dev.venatorvictor.equinox.mixin;

import dev.venatorvictor.equinox.crops.CropWarmth;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import dev.venatorvictor.equinox.crops.SnowyCrops;
import dev.venatorvictor.equinox.snow.SnowyPlants;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps the crop heaters and coolers list of a chunk right when blocks change, and snow on growing crops and plants. */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Shadow
    @Final
    Level level;

    @ModifyVariable(method = "setBlockState", at = @At("HEAD"), argsOnly = true)
    private BlockState equinox$keepSnow(BlockState state, BlockPos pos, BlockState original, boolean isMoving) {
        BlockState old = ((LevelChunk) (Object) this).getBlockState(pos);
        return SnowyPlants.keep(old, SnowyCrops.keep(old, state));
    }

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void equinox$warmth(BlockPos pos, BlockState state, boolean isMoving, CallbackInfoReturnable<BlockState> cir) {
        BlockState old = cir.getReturnValue();
        if (old == null) return;
        CropWarmth.onBlockChanged(level, pos, old, state);
        dev.venatorvictor.equinox.snow.SnowMap.onBlockChanged(level, pos, old, state);
    }
}
