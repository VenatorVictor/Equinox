package dev.venator.seasonsrework.crops;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import dev.venator.seasonsrework.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Heaters and coolers for crops (server config crop_heaters / crop_coolers). A heater keeps crops within `radius`
 * blocks at least `target` °C, a cooler at most `target` °C; only the strongest of each near a crop counts. Distance is
 * counted like light (steps along x, y and z), and the default heaters are the blocks that melt snow in vanilla (block
 * light above 11 reaches radius = light - 12 blocks), so crops are kept from frost exactly where snow can't lie. A light
 * source counts only while it shines that bright (lit furnaces, lamps, cave vines with berries, a full respawn anchor).
 *
 * No scans per crop: each chunk keeps the positions of its sources, built the first time a crop near it asks (a
 * section whose block palette has no source is skipped), updated when blocks change (LevelChunkMixin), dropped when
 * the chunk unloads or the config changes. Kept per section, and a lookup only reads the sections within reach. The
 * client uses it too (crop looks, snowy textures) from the chunk mesh threads: the lists are copied on change, so
 * lookups don't wait on each other.
 */
public record CropWarmth(float target, boolean heats, int radius, boolean needsLight) {
    /** Block light above this melts snow and ice in vanilla. */
    public static final int MELTING_LIGHT = 12;

    /** The configured heaters and coolers by block, rebuilt when the config loads. */
    private static volatile Map<Block, CropWarmth> sources = Map.of();

    /**
     * Per level and chunk: the sources' positions per section (null: none). Arrays are never changed, only replaced
     * (copy on write), so the lookups read them without holding the lock.
     */
    private static final Map<Level, Long2ObjectOpenHashMap<long[][]>> INDEX = new WeakHashMap<>();
    private static final long[] NONE = new long[0];
    private static volatile int maxRadius;
    /** The config changed: rebuild the sources, forget every chunk's list. */
    public static synchronized void reset() {
        INDEX.clear();
        Map<Block, CropWarmth> map = new java.util.IdentityHashMap<>();
        put(map, Config.cropHeaters(), true);
        put(map, Config.cropCoolers(), false);
        int max = 0;
        for (CropWarmth warmth : map.values()) max = Math.max(max, warmth.radius);
        sources = map;
        maxRadius = max;
    }

    private static void put(Map<Block, CropWarmth> map, Map<ResourceLocation, float[]> entries, boolean heats) {
        entries.forEach((id, values) -> BuiltInRegistries.BLOCK.getOptional(id).ifPresent(block -> {
            int brightest = 0;
            for (BlockState state : block.getStateDefinition().getPossibleStates()) brightest = Math.max(brightest, state.getLightEmission());
            map.put(block, new CropWarmth(values[0], heats, (int) values[1], brightest >= MELTING_LIGHT));
        }));
    }

    static synchronized void onChunkUnload(Level level, ChunkPos pos) {
        Long2ObjectOpenHashMap<long[][]> chunks = INDEX.get(level);
        if (chunks != null) chunks.remove(pos.toLong());
    }

    /** A block changed (LevelChunkMixin): keep the chunk's list right if it has one. */
    public static void onBlockChanged(Level level, BlockPos pos, BlockState old, BlockState now) {
        if (Math.max(radius(old), radius(now)) > 0) updateIndex(level, pos, now);
    }

    private static synchronized void updateIndex(Level level, BlockPos pos, BlockState now) {
        Long2ObjectOpenHashMap<long[][]> chunks = INDEX.get(level);
        long[][] sections = chunks == null ? null : chunks.get(ChunkPos.asLong(pos));
        if (sections == null) return;
        int index = level.getSectionIndex(pos.getY());
        if (index < 0 || index >= sections.length) return;
        LongArrayList list = new LongArrayList(sections[index] == null ? NONE : sections[index]);
        list.rem(pos.asLong());
        if (data(now) != null) list.add(pos.asLong());
        sections[index] = list.isEmpty() ? null : list.toLongArray();
    }

    /** The temperature t at pos with the strongest heater and cooler around applied. */
    static float apply(Level level, BlockPos pos, float t) {
        int reach = maxRadius;
        if (reach == 0) return t;
        CropWarmth heater = null, cooler = null;
        int px = pos.getX(), py = pos.getY(), pz = pos.getZ();
        int minX = (px - reach) >> 4, maxX = (px + reach) >> 4;
        int minZ = (pz - reach) >> 4, maxZ = (pz + reach) >> 4;
        int minSection = level.getSectionIndex(py - reach), maxSection = level.getSectionIndex(py + reach);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int cx = minX; cx <= maxX; cx++)
            for (int cz = minZ; cz <= maxZ; cz++) {
                long[][] sections = sources(level, cx, cz);
                if (sections == null) continue;
                // Only the sections within reach above and below (not the whole column).
                for (int s = Math.max(0, minSection); s <= Math.min(sections.length - 1, maxSection); s++) {
                    long[] list = sections[s];
                    if (list == null) continue;
                    for (long packed : list) {
                        int distance = Math.abs(BlockPos.getX(packed) - px) + Math.abs(BlockPos.getY(packed) - py) + Math.abs(BlockPos.getZ(packed) - pz);
                        if (distance > reach) continue;
                        BlockState state = level.getBlockState(cursor.set(packed));
                        CropWarmth warmth = data(state);
                        if (warmth == null || distance > warmth.radius || warmth.needsLight && state.getLightEmission() < MELTING_LIGHT) continue;
                        if (warmth.heats && (heater == null || warmth.target > heater.target)) heater = warmth;
                        if (!warmth.heats && (cooler == null || warmth.target < cooler.target)) cooler = warmth;
                    }
                }
            }
        if (heater != null) t = Math.max(t, heater.target);
        if (cooler != null) t = Math.min(t, cooler.target);
        return t;
    }

    /** A lit heater reaches pos (no snow on crops there). */
    public static boolean heated(Level level, BlockPos pos) {
        return apply(level, pos, -1000) > -1000;
    }

    private static long[][] sources(Level level, int cx, int cz) {
        long key = ChunkPos.asLong(cx, cz);
        synchronized (CropWarmth.class) {
            Long2ObjectOpenHashMap<long[][]> chunks = INDEX.get(level);
            long[][] known = chunks == null ? null : chunks.get(key);
            if (known != null) return known;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) return null; // never loads chunks
        LevelChunkSection[] chunkSections = chunk.getSections();
        long[][] sections = new long[chunkSections.length][];
        LongArrayList list = new LongArrayList();
        for (int s = 0; s < chunkSections.length; s++) {
            LevelChunkSection section = chunkSections[s];
            if (section.hasOnlyAir() || !section.maybeHas(state -> data(state) != null)) continue;
            int baseY = chunk.getSectionYFromSectionIndex(s) << 4;
            list.clear();
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++)
                        if (data(section.getBlockState(x, y, z)) != null)
                            list.add(BlockPos.asLong((cx << 4) + x, baseY + y, (cz << 4) + z));
            if (!list.isEmpty()) sections[s] = list.toLongArray();
        }
        synchronized (CropWarmth.class) {
            // Another thread may have built it meanwhile: keep the first (block changes went to that one).
            long[][] known = INDEX.computeIfAbsent(level, l -> new Long2ObjectOpenHashMap<>()).putIfAbsent(key, sections);
            return known != null ? known : sections;
        }
    }

    /** A heater's or cooler's reach, 0 for other blocks. */
    private static int radius(BlockState state) {
        CropWarmth warmth = data(state);
        return warmth == null ? 0 : warmth.radius;
    }

    private static CropWarmth data(BlockState state) {
        return sources.get(state.getBlock());
    }
}
