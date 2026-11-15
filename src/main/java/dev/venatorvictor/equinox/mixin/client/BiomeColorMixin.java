package dev.venatorvictor.equinox.mixin.client;

import dev.venatorvictor.equinox.client.colors.SeasonColors;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Seasonal grass and foliage colors: vanilla's color resolvers (and Distant Horizons', and Sodium's) read these. Grass
 * also blends with the biomes around (climate blending); foliage has no position here, so leaves blend in TreeColors.
 */
@Mixin(Biome.class)
public abstract class BiomeColorMixin {
    @Inject(method = "getGrassColor", at = @At("RETURN"), cancellable = true)
    private void equinox$grass(double x, double z, CallbackInfoReturnable<Integer> cir) {
        if (SeasonColors.RAW.get()) return; // the climate blend reads each biome's own color
        cir.setReturnValue(SeasonColors.blendedGrass((Biome) (Object) this, x, z, cir.getReturnValueI()));
    }

    @Inject(method = "getFoliageColor", at = @At("RETURN"), cancellable = true)
    private void equinox$foliage(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(SeasonColors.grassOrFoliage(false, cir.getReturnValueI(), (Biome) (Object) this));
    }
}
