package dev.venator.seasonsrework.mixin.client;

import dev.venator.seasonsrework.client.colors.SeasonColors;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Seasonal grass and foliage colors: vanilla's color resolvers (and Distant Horizons') read these. */
@Mixin(Biome.class)
public abstract class BiomeColorMixin {
    @Inject(method = "getGrassColor", at = @At("RETURN"), cancellable = true)
    private void seasonsrework$grass(double x, double z, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(SeasonColors.grassOrFoliage(true, cir.getReturnValueI(), (Biome) (Object) this));
    }

    @Inject(method = "getFoliageColor", at = @At("RETURN"), cancellable = true)
    private void seasonsrework$foliage(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(SeasonColors.grassOrFoliage(false, cir.getReturnValueI(), (Biome) (Object) this));
    }
}
