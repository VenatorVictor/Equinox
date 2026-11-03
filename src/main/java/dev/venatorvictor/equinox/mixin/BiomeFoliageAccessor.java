package dev.venatorvictor.equinox.mixin;

import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The biome's foliage color without a season on it (the colormap one; overrides are read from the effects). */
@Mixin(Biome.class)
public interface BiomeFoliageAccessor {
    @Invoker("getFoliageColorFromTexture")
    int equinox$foliageFromTexture();
}
