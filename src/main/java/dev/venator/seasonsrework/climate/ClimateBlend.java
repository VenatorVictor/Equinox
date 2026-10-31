package dev.venator.seasonsrework.climate;

import dev.venator.seasonsrework.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Climate blending: each chunk gets the mix of biomes around it (nearer ones count more, Config.blendRadius), read from
 * the world's biome generator so it doesn't depend on what's loaded or generated. A biome's temperature then moves
 * toward the mix's temperature now (seasons included): by Config.blendStrength for land, fully for rivers and beaches.
 * Between chunk centers the mix's temperature is blended, so there are no chunk edges. World generation doesn't change.
 * Worked out on the server when a chunk loads (or is sent), sent to clients with the chunk (ClimateBlendPayload).
 * Overworld only (biomes in #minecraft:is_overworld, not caves).
 */
public final class ClimateBlend {
    /** The biomes around a chunk center and their weights (summing to 1). */
    public record Mix(Biome[] biomes, float[] weights) {
        float temperature() {
            float sum = 0;
            for (int i = 0; i < biomes.length; i++) sum += weights[i] * Climate.surfaceTemperature(biomes[i]);
            return sum;
        }
    }

    /** Ignored below this weight (a biome barely touching the edge). */
    private static final float MIN_WEIGHT = 0.02F;
    /** Kept this many chunks at most (both sides share it in single player); cleared past it, mixes come back on load. */
    private static final int MAX_CHUNKS = 200_000;

    private static final Map<Long, Mix> MIXES = new ConcurrentHashMap<>();
    /** Biomes sampled on the grid (server), shared by neighbouring chunks. */
    private static final Map<Long, Holder<Biome>> GRID = new ConcurrentHashMap<>();
    private static volatile int gridStep;

    /** The temperature change at pos for this biome (0 when not blended or no mix is known around). */
    public static float delta(Biome biome, BlockPos pos, byte kind) {
        if (kind == ClimateBiome.BLEND_NONE || MIXES.isEmpty()) return 0;
        float strength = kind == ClimateBiome.BLEND_FULL ? 1 : Config.blendStrength();
        if (strength == 0) return 0;
        // Chunk centers around pos and how far pos is between them.
        float gx = (pos.getX() - 8) / 16F, gz = (pos.getZ() - 8) / 16F;
        int x0 = (int) Math.floor(gx), z0 = (int) Math.floor(gz);
        float fx = gx - x0, fz = gz - z0;
        float sum = 0, weight = 0;
        for (int i = 0; i < 4; i++) {
            int dx = i & 1, dz = i >> 1;
            Mix mix = MIXES.get(ChunkPos.asLong(x0 + dx, z0 + dz));
            if (mix == null) continue;
            float w = (dx == 0 ? 1 - fx : fx) * (dz == 0 ? 1 - fz : fz);
            sum += w * mix.temperature();
            weight += w;
        }
        if (weight < 1e-4F) return 0;
        return strength * (sum / weight - Climate.surfaceTemperature(biome));
    }

    /** The mix of a chunk, or null if not known (here). */
    public static Mix mix(int chunkX, int chunkZ) {
        return MIXES.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    /** Client: the server sent a chunk's mix. */
    public static void put(long chunk, Mix mix) {
        if (MIXES.size() > MAX_CHUNKS) MIXES.clear();
        MIXES.put(chunk, mix);
    }

    /** Leaving a world (either side). */
    public static void clear() {
        MIXES.clear();
        GRID.clear();
        gridStep = 0;
    }

    /** Server: the mix of a chunk, worked out the first time. Null when blending is off. */
    public static Mix compute(ServerLevel level, ChunkPos chunk) {
        long key = chunk.toLong();
        Mix known = MIXES.get(key);
        if (known != null) return known;
        int radius = radius(level);
        if (radius <= 0) return null;
        // About 50 samples in the circle, on a grid aligned to the world so neighbours share them.
        int step = Math.max(8, radius / 4);
        if (step != gridStep) {
            GRID.clear();
            gridStep = step;
        }
        BiomeSource source = level.getChunkSource().getGenerator().getBiomeSource();
        var sampler = level.getChunkSource().randomState().sampler();
        int cx = chunk.getMiddleBlockX(), cz = chunk.getMiddleBlockZ();
        Map<Biome, Float> weights = new IdentityHashMap<>();
        float total = 0;
        for (int x = Math.floorDiv(cx - radius, step) * step; x <= cx + radius; x += step) {
            for (int z = Math.floorDiv(cz - radius, step) * step; z <= cz + radius; z += step) {
                double distance = Math.sqrt((double) (x - cx) * (x - cx) + (double) (z - cz) * (z - cz));
                if (distance >= radius) continue;
                int sx = x, sz = z;
                Holder<Biome> holder = GRID.computeIfAbsent(ChunkPos.asLong(x / step, z / step) ^ ((long) step << 58),
                        k -> source.getNoiseBiome(QuartPos.fromBlock(sx), QuartPos.fromBlock(level.getSeaLevel()), QuartPos.fromBlock(sz), sampler));
                Biome biome = holder.value();
                if (((ClimateBiome) (Object) biome).seasonsrework$blendKind() == ClimateBiome.BLEND_NONE) continue;
                float w = (float) (1 - distance / radius);
                weights.merge(biome, w, Float::sum);
                total += w;
            }
        }
        if (total <= 0) return null;
        List<Biome> biomes = new ArrayList<>();
        List<Float> values = new ArrayList<>();
        float kept = 0;
        for (Map.Entry<Biome, Float> entry : weights.entrySet()) {
            float w = entry.getValue() / total;
            if (w < MIN_WEIGHT) continue;
            biomes.add(entry.getKey());
            values.add(w);
            kept += w;
        }
        float[] array = new float[values.size()];
        for (int i = 0; i < array.length; i++) array[i] = values.get(i) / kept;
        Mix mix = new Mix(biomes.toArray(Biome[]::new), array);
        put(key, mix);
        return mix;
    }

    /** Config radius, or 64 / 256 (Large Biomes) by world type. */
    public static int radius(ServerLevel level) {
        int radius = Config.blendRadius();
        if (radius >= 0) return radius;
        boolean large = level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator
                && generator.generatorSettings().is(NoiseGeneratorSettings.LARGE_BIOMES);
        return large ? 256 : 64;
    }

    private ClimateBlend() {}
}
