package dev.venatorvictor.equinox.mixin;

import dev.venatorvictor.equinox.snow.SnowMap;
import dev.venatorvictor.equinox.snow.SnowyPlants;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.SoundType;
import org.jetbrains.annotations.Nullable;
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
        if (SnowyPlants.layers(level, pos, state) > 0) {
            SnowyPlants.breakSnow(state, level, pos, player);
            return false;
        }
        return state.getBlock().onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
    }

    /** A fence with snow by it (SnowMap) sounds like snow (steps, placing, breaking); the snow state's blocks do it in SnowStateMixin. */
    @Override
    public SoundType getSoundType(LevelReader level, BlockPos pos, @Nullable Entity entity) {
        BlockState state = (BlockState) (Object) this;
        if (SnowMap.uses(state) && SnowMap.get(level, pos) > 0) return SoundType.SNOW;
        return state.getBlock().getSoundType(state, level, pos, entity);
    }
}
