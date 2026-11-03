package dev.venatorvictor.equinox.mixin.client;

import dev.venatorvictor.equinox.client.textures.SeasonalModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.ParticleUtils;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CherryLeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Cherry leaves only drop petals while they show their own (blooming) texture: while the seasonal textures show them
 * green or in bud, no petals fall.
 */
@Mixin(CherryLeavesBlock.class)
public abstract class CherryPetalsMixin {
    @Redirect(method = "animateTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/ParticleUtils;spawnParticleBelow(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/particles/ParticleOptions;)V"))
    private void equinox$petalsWhenBlooming(Level level, BlockPos pos, RandomSource random, ParticleOptions particle,
                                             BlockState state, Level ownLevel, BlockPos ownPos, RandomSource ownRandom) {
        if (level instanceof ClientLevel client && SeasonalModel.changed(client, pos, state)) return;
        ParticleUtils.spawnParticleBelow(level, pos, random, particle);
    }
}
