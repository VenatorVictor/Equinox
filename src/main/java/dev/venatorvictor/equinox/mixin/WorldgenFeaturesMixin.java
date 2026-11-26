package dev.venatorvictor.equinox.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.venatorvictor.equinox.climate.Climate;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;

/** A chunk's features are placed with the plain vanilla climate (see WorldgenClimateMixin). */
@Mixin(ChunkGenerator.class)
public abstract class WorldgenFeaturesMixin {
    @WrapMethod(method = "applyBiomeDecoration")
    private void equinox$vanillaFeatures(WorldGenLevel level, ChunkAccess chunk, StructureManager structures, Operation<Void> original) {
        Boolean before = Climate.WORLDGEN.get();
        Climate.WORLDGEN.set(Boolean.TRUE);
        try {
            original.call(level, chunk, structures);
        } finally {
            Climate.WORLDGEN.set(before);
        }
    }
}
