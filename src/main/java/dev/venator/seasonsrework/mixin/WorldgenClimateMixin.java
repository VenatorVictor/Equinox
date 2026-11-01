package dev.venator.seasonsrework.mixin;

import dev.venator.seasonsrework.climate.Climate;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * World generation sees the plain vanilla climate: while a chunk's surface, carvers and features are made, the
 * season's temperature and rain changes are off (Climate.WORLDGEN, read by BiomeMixin). Otherwise surface rules and
 * features that look at the temperature (snowy surfaces, snow blocks, ice, frozen water) would bake the season of
 * the day the chunk was made into the world. These steps run start to end on one thread.
 */
@Mixin(ChunkStatusTasks.class)
public abstract class WorldgenClimateMixin {
    @Inject(method = {"generateSurface", "generateCarvers", "generateFeatures"}, at = @At("HEAD"))
    private static void seasonsrework$vanillaClimateOn(CallbackInfoReturnable<?> cir) {
        Climate.WORLDGEN.set(Boolean.TRUE);
    }

    @Inject(method = {"generateSurface", "generateCarvers", "generateFeatures"}, at = @At("RETURN"))
    private static void seasonsrework$vanillaClimateOff(CallbackInfoReturnable<?> cir) {
        Climate.WORLDGEN.set(Boolean.FALSE);
    }
}
