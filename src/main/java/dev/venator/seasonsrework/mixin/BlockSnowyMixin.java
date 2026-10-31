package dev.venator.seasonsrework.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.venator.seasonsrework.crops.SnowyCrops;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Farmland crops get a snowy block state (SnowyCrops), like grass. Added after the block's own properties (beetroots
 * don't call super), and off by default.
 */
@Mixin(Block.class)
public abstract class BlockSnowyMixin {
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;createBlockStateDefinition(Lnet/minecraft/world/level/block/state/StateDefinition$Builder;)V"))
    private void seasonsrework$snowyCrops(Block block, StateDefinition.Builder<Block, BlockState> builder, Operation<Void> original) {
        original.call(block, builder);
        if (SnowyCrops.is(block) && !((StateDefinitionBuilderAccessor) builder).seasonsrework$properties().containsKey(SnowyCrops.SNOWY.getName()))
            builder.add(SnowyCrops.SNOWY);
    }

    @ModifyVariable(method = "registerDefaultState", at = @At("HEAD"), argsOnly = true)
    private BlockState seasonsrework$noSnow(BlockState state) {
        return SnowyCrops.is(state.getBlock()) && state.hasProperty(SnowyCrops.SNOWY) ? state.setValue(SnowyCrops.SNOWY, false) : state;
    }
}
