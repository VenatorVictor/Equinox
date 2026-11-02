package dev.venatorvictor.equinox.climate;

import dev.venatorvictor.equinox.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
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
 * <p>
 * Climate zones: each mix also keeps the world's temperature map (the noise vanilla places biomes by, -1..1) at its
 * center as a cooling factor, 1 = full winter, fading to 0 across Config.zoneWarmStart..zoneWarmEnd. The cold part of
 * the seasons is scaled by it (blended between chunk centers like the mixes), so near jungles and savannas there's no
 * winter (mountains, the alpine climate, keep theirs). Read with the world's sampler, never during world generation; only with vanilla's multi-noise placement.
 */
public final class ClimateBlend {
    /** The biomes around a chunk center and their weights (summing to 1). */
    public static final class Mix {
        private final Biome[] biomes;
        private final float[] weights;
        /** Climate zone: how much of the seasonal cold applies here (0..1), NaN if not known. */
        private final float cooling;
        /** The temperature for one climate phase (it changes once per tick), read on any thread. */
        private volatile Cached cached;

        private record Cached(Climate.Phase phase, float temperature) {}

        public Mix(Biome[] biomes, float[] weights, float cooling) {
            this.biomes = biomes;
            this.weights = weights;
            this.cooling = cooling;
        }

        public float cooling() {
            return cooling;
        }

        public Biome[] biomes() {
            return biomes;
        }

        public float[] weights() {
            return weights;
        }

        /** NaN without biomes (a mix that only carries the cooling, blending off). */
        float temperature() {
            if (biomes.length == 0) return Float.NaN;
            Climate.Phase phase = Climate.phase();
            Cached known = cached;
            if (known != null && known.phase == phase) return known.temperature;
            float sum = 0;
            for (int i = 0; i < biomes.length; i++) sum += weights[i] * Climate.surfaceTemperature(biomes[i], cooling);
            cached = new Cached(phase, sum);
            return sum;
        }
    }

    /** Ignored below this weight (a biome barely touching the edge). */
    private static final float MIN_WEIGHT = 0.02F;
    /** Kept this many chunks at most per side; cleared past it, mixes come back on load. */
    private static final int MAX_CHUNKS = 200_000;

    /** Each side's mixes (single player runs both, with separate biome objects). */
    private static final Map<Long, Mix> SERVER = new ConcurrentHashMap<>(), CLIENT = new ConcurrentHashMap<>();
    /** Client: mixes on a coarser grid of farCell blocks out to Distant Horizons' distance (FarBlend), used where no chunk's is known. */
    private static final Map<Long, Mix> FAR = new ConcurrentHashMap<>();
    private static volatile int farCell;
    /** Biomes sampled on the grid (server), shared by neighbouring chunks. */
    private static final Map<Long, Holder<Biome>> GRID = new ConcurrentHashMap<>();
    private static volatile int gridStep;

    /** Scratch for around(): blended temperature and cooling. */
    private static final ThreadLocal<float[]> AROUND = ThreadLocal.withInitial(() -> new float[2]);

    /**
     * The biome's seasonal change at pos (seasonal: season + snow tuning) in its climate zone, plus the blending's
     * change. Biomes without blending or with a snow lock keep their own climate.
     */
    public static float adjust(Biome biome, BlockPos pos, byte kind, float seasonal) {
        if (kind == ClimateBiome.BLEND_NONE) return seasonal;
        ClimateBiome climate = (ClimateBiome) (Object) biome;
        // Snowy and snowless biomes (snow locks) keep their climate; only the ones in between change near them.
        if (climate.equinox$snowLock() != ClimateBiome.SNOW_FREE) return seasonal;
        float[] here = sample(climate.equinox$clientSide(), pos);
        float cooling = here[1];
        float result = Climate.zoned(biome, seasonal, cooling);
        if (Float.isNaN(here[0])) return result;
        float strength = kind == ClimateBiome.BLEND_FULL ? 1 : Config.blendStrength();
        if (strength == 0) return result;
        return result + strength * (here[0] - Climate.surfaceTemperature(biome, cooling));
    }

    /** The blending's temperature change at pos for this biome (0 when not blended, locked, or no mix is known around). For /season. */
    public static float delta(Biome biome, BlockPos pos, byte kind) {
        if (kind == ClimateBiome.BLEND_NONE) return 0;
        ClimateBiome climate = (ClimateBiome) (Object) biome;
        if (climate.equinox$snowLock() != ClimateBiome.SNOW_FREE) return 0;
        float[] here = sample(climate.equinox$clientSide(), pos);
        float strength = kind == ClimateBiome.BLEND_FULL ? 1 : Config.blendStrength();
        if (Float.isNaN(here[0]) || strength == 0) return 0;
        return strength * (here[0] - Climate.surfaceTemperature(biome, here[1]));
    }

    /** The climate zone's cooling at pos (0..1), NaN if not known. For /season. */
    public static float coolingAt(boolean client, BlockPos pos) {
        return sample(client, pos)[1];
    }

    /** [temperature of the mixes around, cooling] at pos, from the chunk mixes or (client) the far ones; NaN where unknown. */
    private static float[] sample(boolean client, BlockPos pos) {
        float[] out = AROUND.get();
        around(client ? CLIENT : SERVER, 16, pos, out);
        if (client && farCell > 0 && (Float.isNaN(out[0]) || Float.isNaN(out[1]))) {
            float temperature = out[0], cooling = out[1];
            around(FAR, farCell, pos, out);
            if (!Float.isNaN(temperature)) out[0] = temperature;
            if (!Float.isNaN(cooling)) out[1] = cooling;
        }
        return out;
    }

    /** The mixes' temperature and cooling at pos, blended between the centers of the cells around it; NaN if none is known. */
    private static void around(Map<Long, Mix> mixes, int cell, BlockPos pos, float[] out) {
        out[0] = out[1] = Float.NaN;
        if (mixes.isEmpty()) return;
        float half = cell / 2F;
        float gx = (pos.getX() - half) / cell, gz = (pos.getZ() - half) / cell;
        int x0 = (int) Math.floor(gx), z0 = (int) Math.floor(gz);
        float fx = gx - x0, fz = gz - z0;
        float sum = 0, weight = 0, coolSum = 0, coolWeight = 0;
        for (int i = 0; i < 4; i++) {
            int dx = i & 1, dz = i >> 1;
            Mix mix = mixes.get(ChunkPos.asLong(x0 + dx, z0 + dz));
            if (mix == null) continue;
            float w = (dx == 0 ? 1 - fx : fx) * (dz == 0 ? 1 - fz : fz);
            float temperature = mix.temperature();
            if (!Float.isNaN(temperature)) {
                sum += w * temperature;
                weight += w;
            }
            if (!Float.isNaN(mix.cooling)) {
                coolSum += w * mix.cooling;
                coolWeight += w;
            }
        }
        if (weight >= 1e-4F) out[0] = sum / weight;
        if (coolWeight >= 1e-4F) out[1] = coolSum / coolWeight;
    }

    /** The server's mix of a chunk, or null if not known. */
    public static Mix mix(int chunkX, int chunkZ) {
        return SERVER.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    /** Client: the server sent a chunk's mix. */
    public static void put(long chunk, Mix mix) {
        if (CLIENT.size() > MAX_CHUNKS) CLIENT.clear();
        CLIENT.put(chunk, mix);
    }

    /** Client: the server sent far mixes (cells of cell blocks; a new size replaces the old ones). */
    public static void putFar(int cell, long key, Mix mix) {
        if (cell != farCell || FAR.size() > MAX_CHUNKS) {
            FAR.clear();
            farCell = cell;
        }
        FAR.put(key, mix);
    }

    /** Client: leaving a world. */
    public static void clearClient() {
        CLIENT.clear();
        FAR.clear();
        farCell = 0;
    }

    /** Server stopped. */
    public static void clearServer() {
        SERVER.clear();
        GRID.clear();
        gridStep = 0;
    }

    /** Server: the mix of a chunk, worked out the first time. Null when blending and climate zones are off. */
    public static Mix compute(ServerLevel level, ChunkPos chunk) {
        long key = chunk.toLong();
        Mix known = SERVER.get(key);
        if (known != null) return known;
        Mix mix = mixAt(level, chunk.getMiddleBlockX(), chunk.getMiddleBlockZ());
        if (mix == null) return null;
        if (SERVER.size() > MAX_CHUNKS) SERVER.clear();
        SERVER.put(key, mix);
        return mix;
    }

    /** Server: the mix around a block column (not kept). Null when blending and climate zones are off. */
    public static Mix mixAt(ServerLevel level, int cx, int cz) {
        float cooling = cooling(level, cx, cz);
        Mix coolingOnly = Float.isNaN(cooling) ? null : new Mix(new Biome[0], new float[0], cooling);
        int radius = radius(level);
        if (radius <= 0) return coolingOnly;
        // About 50 samples in the circle, on a grid aligned to the world so neighbours share them.
        int step = Math.max(8, radius / 4);
        if (step != gridStep || GRID.size() > MAX_CHUNKS * 4) {
            GRID.clear();
            gridStep = step;
        }
        BiomeSource source = level.getChunkSource().getGenerator().getBiomeSource();
        var sampler = level.getChunkSource().randomState().sampler();
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
                if (((ClimateBiome) (Object) biome).equinox$blendKind() == ClimateBiome.BLEND_NONE) continue;
                float w = (float) (1 - distance / radius);
                weights.merge(biome, w, Float::sum);
                total += w;
            }
        }
        if (total <= 0) return coolingOnly;
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
        return new Mix(biomes.toArray(Biome[]::new), array, cooling);
    }

    /**
     * Server: the climate zone's cooling at a block column from the world's temperature map (the noise vanilla places
     * biomes by; it doesn't change with height). NaN when zones are off or the world doesn't place biomes by it.
     */
    public static float cooling(ServerLevel level, int x, int z) {
        if (!Config.climateZones()) return Float.NaN;
        var chunks = level.getChunkSource();
        if (!(chunks.getGenerator().getBiomeSource() instanceof MultiNoiseBiomeSource)) return Float.NaN;
        var point = chunks.randomState().sampler().sample(QuartPos.fromBlock(x), QuartPos.fromBlock(level.getSeaLevel()), QuartPos.fromBlock(z));
        float temperature = point.temperature() / 10000F;
        float start = Config.zoneWarmStart(), end = Config.zoneWarmEnd();
        if (temperature <= start) return 1;
        if (temperature >= end) return 0;
        float t = (temperature - start) / (end - start);
        return 1 - t * t * (3 - 2 * t);
    }

    /** The configured radius for the world type (Large Biomes has its own). */
    public static int radius(ServerLevel level) {
        boolean large = level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator
                && generator.generatorSettings().is(NoiseGeneratorSettings.LARGE_BIOMES);
        return large ? Config.blendRadiusLarge() : Config.blendRadius();
    }

    private ClimateBlend() {}
}
