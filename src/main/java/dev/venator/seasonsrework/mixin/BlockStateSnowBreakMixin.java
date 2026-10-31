package dev.venator.seasonsrework.mixin;

import dev.venator.seasonsrework.snow.SnowyPlants;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.common.extensions.IBlockStateExtension;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Breaking a plant or fence with snow in it takes the snow off first (SnowyPlants.breakSnow); the block stays.
 * Overrides NeoForge's default (both the server and the client's prediction call it), so the block's own
 * onDestroyedByPlayer still runs when there is no snow.
 */
@Mixin(BlockState.class)
public abstract class BlockStateSnowBreakMixin implements IBlockStateExtension {
    @Override
    public boolean onDestroyedByPlayer(Level level, BlockPos pos, Player player, boolean willHarvest, FluidState fluid) {
        BlockState state = (BlockState) (Object) this;
        if (SnowyPlants.layers(state) > 0) {
            SnowyPlants.breakSnow(state, level, pos, player);
            return false;
        }
        return state.getBlock().onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
    }
}
