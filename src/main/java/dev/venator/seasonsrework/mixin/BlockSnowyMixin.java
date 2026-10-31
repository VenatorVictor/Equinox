package dev.venator.seasonsrework.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.venator.seasonsrework.crops.SnowyCrops;
import dev.venator.seasonsrework.snow.SnowyPlants;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Farmland crops get a snowy block state (SnowyCrops), like grass; other plants, fences and walls a snow layers state
 * (SnowyPlants). Added after the block's own properties (beetroots don't call super), and off/0 by default.
 */
@Mixin(Block.class)
public abstract class BlockSnowyMixin {
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;createBlockStateDefinition(Lnet/minecraft/world/level/block/state/StateDefinition$Builder;)V"))
    private void seasonsrework$snowyCrops(Block block, StateDefinition.Builder<Block, BlockState> builder, Operation<Void> original) {
        original.call(block, builder);
        Map<String, Property<?>> properties = ((StateDefinitionBuilderAccessor) builder).seasonsrework$properties();
        if (SnowyCrops.is(block) && !properties.containsKey(SnowyCrops.SNOWY.getName())) builder.add(SnowyCrops.SNOWY);
        IntegerProperty snow = SnowyPlants.propertyFor(block, properties);
        if (snow != null) builder.add(snow);
    }

    @ModifyVariable(method = "registerDefaultState", at = @At("HEAD"), argsOnly = true)
    private BlockState seasonsrework$noSnow(BlockState state) {
        if (SnowyCrops.is(state.getBlock()) && state.hasProperty(SnowyCrops.SNOWY)) state = state.setValue(SnowyCrops.SNOWY, false);
        return SnowyPlants.unsnowed(state);
    }
}
