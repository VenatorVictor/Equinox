package dev.venator.seasonsrework.mixin;

import dev.venator.seasonsrework.snow.SnowyPlants;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Walls keep their shapes in a map built from snow-free states only: look them up without the snow (SnowyPlants). */
@Mixin(WallBlock.class)
public abstract class WallSnowShapeMixin {
    @ModifyVariable(method = {"getShape", "getCollisionShape"}, at = @At("HEAD"), argsOnly = true)
    private BlockState seasonsrework$noSnow(BlockState state) {
        return SnowyPlants.unsnowed(state);
    }
}
