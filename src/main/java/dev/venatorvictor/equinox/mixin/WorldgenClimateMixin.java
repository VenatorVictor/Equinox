package dev.venatorvictor.equinox.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.venatorvictor.equinox.climate.Climate;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * World generation sees the plain vanilla climate: while a chunk's surface and carvers are made (and its features,
 * WorldgenFeaturesMixin), the season's temperature and rain changes are off (Climate.WORLDGEN, read by BiomeMixin).
 * Otherwise surface rules and features that look at the temperature (snowy surfaces, snow blocks, ice, frozen water)
 * would bake the season of the day the chunk was made into the world. Hooked in the generator itself, so terrain
 * made outside the normal chunk steps (Distant Horizons' own generator) gets it too; the flag is put back even if
 * the generation fails.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class WorldgenClimateMixin {
    @WrapMethod(method = "buildSurface(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;)V")
    private void equinox$vanillaSurface(WorldGenRegion level, StructureManager structures, RandomState random, ChunkAccess chunk, Operation<Void> original) {
        Boolean before = Climate.WORLDGEN.get();
        Climate.WORLDGEN.set(Boolean.TRUE);
        try {
            original.call(level, structures, random, chunk);
        } finally {
            Climate.WORLDGEN.set(before);
        }
    }

    @WrapMethod(method = "applyCarvers")
    private void equinox$vanillaCarvers(WorldGenRegion level, long seed, RandomState random, BiomeManager biomes, StructureManager structures,
                                        ChunkAccess chunk, GenerationStep.Carving step, Operation<Void> original) {
        Boolean before = Climate.WORLDGEN.get();
        Climate.WORLDGEN.set(Boolean.TRUE);
        try {
            original.call(level, seed, random, biomes, structures, chunk, step);
        } finally {
            Climate.WORLDGEN.set(before);
        }
    }
}
