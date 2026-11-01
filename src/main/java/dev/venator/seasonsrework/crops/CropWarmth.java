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
 * the chunk unloads or the config changes. The client uses it too for the crop looks (CropLookModel; the server
 * config is synced), from the chunk mesh threads, so every access locks the index.
 */
public record CropWarmth(float target, boolean heats, int radius, boolean needsLight) {
    /** Block light above this melts snow and ice in vanilla. */
    public static final int MELTING_LIGHT = 12;

    /** The configured heaters and coolers by block, rebuilt when the config loads. */
    private static volatile Map<Block, CropWarmth> sources = Map.of();

    private static final Map<Level, Long2ObjectOpenHashMap<LongArrayList>> INDEX = new WeakHashMap<>();
    private static int maxRadius;
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
        Long2ObjectOpenHashMap<LongArrayList> chunks = INDEX.get(level);
        if (chunks != null) chunks.remove(pos.toLong());
    }

    /** A block changed (LevelChunkMixin): keep the chunk's list right if it has one. */
    public static void onBlockChanged(Level level, BlockPos pos, BlockState old, BlockState now) {
        if (Math.max(radius(old), radius(now)) > 0) updateIndex(level, pos, now);
    }

    private static synchronized void updateIndex(Level level, BlockPos pos, BlockState now) {
        Long2ObjectOpenHashMap<LongArrayList> chunks = INDEX.get(level);
        LongArrayList list = chunks == null ? null : chunks.get(ChunkPos.asLong(pos));
        if (list == null) return;
        list.rem(pos.asLong());
        if (data(now) != null) list.add(pos.asLong());
    }

    /** The temperature t at pos with the strongest heater and cooler around applied. */
    static synchronized float apply(Level level, BlockPos pos, float t) {
        CropWarmth heater = null, cooler = null;
        if (maxRadius == 0) return t;
        int minX = (pos.getX() - maxRadius) >> 4, maxX = (pos.getX() + maxRadius) >> 4;
        int minZ = (pos.getZ() - maxRadius) >> 4, maxZ = (pos.getZ() + maxRadius) >> 4;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int cx = minX; cx <= maxX; cx++)
            for (int cz = minZ; cz <= maxZ; cz++) {
                LongArrayList list = sources(level, cx, cz);
                if (list == null) continue;
                for (int i = 0; i < list.size(); i++) {
                    cursor.set(list.getLong(i));
                    BlockState state = level.getBlockState(cursor);
                    CropWarmth warmth = data(state);
                    if (warmth == null || warmth.needsLight && state.getLightEmission() < MELTING_LIGHT) continue;
                    int distance = Math.abs(cursor.getX() - pos.getX()) + Math.abs(cursor.getY() - pos.getY()) + Math.abs(cursor.getZ() - pos.getZ());
                    if (distance > warmth.radius) continue;
                    if (warmth.heats && (heater == null || warmth.target > heater.target)) heater = warmth;
                    if (!warmth.heats && (cooler == null || warmth.target < cooler.target)) cooler = warmth;
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

    private static LongArrayList sources(Level level, int cx, int cz) {
        Long2ObjectOpenHashMap<LongArrayList> chunks = INDEX.computeIfAbsent(level, l -> new Long2ObjectOpenHashMap<>());
        long key = ChunkPos.asLong(cx, cz);
        LongArrayList list = chunks.get(key);
        if (list != null) return list;
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) return null; // never loads chunks
        list = new LongArrayList();
        LevelChunkSection[] sections = chunk.getSections();
        for (int s = 0; s < sections.length; s++) {
            LevelChunkSection section = sections[s];
            if (section.hasOnlyAir() || !section.maybeHas(state -> data(state) != null)) continue;
            int baseY = chunk.getSectionYFromSectionIndex(s) << 4;
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++)
                        if (data(section.getBlockState(x, y, z)) != null)
                            list.add(BlockPos.asLong((cx << 4) + x, baseY + y, (cz << 4) + z));
        }
        chunks.put(key, list);
        return list;
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
