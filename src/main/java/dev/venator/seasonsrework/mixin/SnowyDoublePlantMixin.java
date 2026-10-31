package dev.venator.seasonsrework.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.venator.seasonsrework.snow.SnowyBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Snow storage "block_entity": a tall plant's upper half stays when its lower half is inside a SnowyBlock. */
@Mixin(DoublePlantBlock.class)
public abstract class SnowyDoublePlantMixin {
    @ModifyExpressionValue(method = "canSurvive", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/LevelReader;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState seasonsrework$innerBelow(BlockState below, BlockState state, LevelReader level, BlockPos pos) {
        if (!(below.getBlock() instanceof SnowyBlock)) return below;
        BlockState inner = SnowyBlock.inner(level, pos.below());
        return inner.isAir() ? below : inner;
    }
}
