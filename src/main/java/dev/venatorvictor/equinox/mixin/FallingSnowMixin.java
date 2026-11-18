package dev.venatorvictor.equinox.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.venatorvictor.equinox.snow.SnowMap;
import dev.venatorvictor.equinox.snow.SnowyPlants;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Falling snow (SnowGravityMixin): it piles onto snow it lands on (layers added, up to a full 8), turns still water it
 * falls into to ice, goes into a fence, gate, wall, stair or slab it lands on, and is gone in flowing water or lava
 * (with a fizz). Other falling blocks as in vanilla.
 */
@Mixin(FallingBlockEntity.class)
public abstract class FallingSnowMixin {
    @Shadow
    public abstract BlockState getBlockState();

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void equinox$intoLiquid(CallbackInfo ci) {
        FallingBlockEntity self = (FallingBlockEntity) (Object) this;
        Level level = self.level();
        if (level.isClientSide || !(getBlockState().getBlock() instanceof SnowLayerBlock) || self.time < 2) return;
        BlockPos pos = self.blockPosition();
        FluidState fluid = level.getFluidState(pos);
        if (fluid.isEmpty()) return;
        if (fluid.is(FluidTags.WATER) && fluid.isSource() && level.getBlockState(pos).is(Blocks.WATER)) {
            level.setBlock(pos, Blocks.ICE.defaultBlockState(), Block.UPDATE_ALL);
        } else {
            level.levelEvent(1501, pos, 0); // fizz and smoke
        }
        self.discard();
        ci.cancel();
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;canBeReplaced(Lnet/minecraft/world/item/context/BlockPlaceContext;)Z"))
    private boolean equinox$pileOnSnow(BlockState target, BlockPlaceContext context, Operation<Boolean> original) {
        BlockState snow = getBlockState();
        if (snow.getBlock() instanceof SnowLayerBlock && target.getBlock() instanceof SnowLayerBlock) {
            int layers = Math.min(8, target.getValue(SnowLayerBlock.LAYERS) + snow.getValue(SnowLayerBlock.LAYERS));
            Level level = context.getLevel();
            BlockPos pos = context.getClickedPos();
            level.setBlock(pos, target.setValue(SnowLayerBlock.LAYERS, layers), Block.UPDATE_ALL);
            level.playSound(null, pos, SoundEvents.SNOW_FALL, SoundSource.BLOCKS, 1, 1);
            return false; // landed: the entity goes, nothing drops (dropItem off)
        }
        if (snow.getBlock() instanceof SnowLayerBlock) {
            // Landed on a stair or slab's step, or on top of a fence, gate, wall or stair: its snow goes in (up to what
            // the block holds, like snowfall). The rest is gone.
            Level level = context.getLevel();
            BlockPos at = context.getClickedPos();
            BlockState holder = target;
            if (!(SnowMap.holds(holder) && SnowMap.onTop(holder))) {
                at = at.below();
                holder = level.getBlockState(at);
                if (!SnowMap.holds(holder) || !target.canBeReplaced()) holder = null;
            }
            if (holder != null) {
                int layers = Math.min(SnowMap.maxLayers(holder), SnowyPlants.layers(level, at, holder) + snow.getValue(SnowLayerBlock.LAYERS));
                if (SnowyPlants.cover(level, at, holder, layers, Block.UPDATE_ALL)) {
                    level.playSound(null, at, SoundEvents.SNOW_FALL, SoundSource.BLOCKS, 1, 1);
                }
                return false;
            }
        }
        return original.call(target, context);
    }
}
