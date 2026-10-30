package dev.venator.seasonsrework.crops;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.neoforge.registries.datamaps.DataMapType;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Heaters and coolers for crops (data map data/<namespace>/data_maps/block/crop_temperature.json): a block pulls the
 * temperature of crops within `radius` blocks (a box) toward `target` °C by at most |strength| (above 0 warms, below 0
 * cools), never past the target, so a heater does nothing on a warm day. Only the strongest heater and the strongest
 * cooler near a crop count. Blocks with a "lit" property count only while lit. Glass does nothing.
 *
 * No scans per crop: each chunk keeps the positions of its sources, built the first time a crop near it asks (a
 * section whose block palette has no source is skipped), updated when blocks change (LevelChunkMixin), dropped when
 * the chunk unloads or the data reloads. Server thread only.
 */
public record CropWarmth(float target, float strength, int radius) {
    public static final Codec<CropWarmth> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.FLOAT.fieldOf("target").forGetter(CropWarmth::target),
            Codec.FLOAT.fieldOf("strength").forGetter(CropWarmth::strength),
            Codec.intRange(1, 16).fieldOf("radius").forGetter(CropWarmth::radius)
    ).apply(instance, CropWarmth::new));

    public static final DataMapType<Block, CropWarmth> TYPE = DataMapType.builder(
            ResourceLocation.fromNamespaceAndPath("seasonsrework", "crop_temperature"), Registries.BLOCK, CODEC).build();

    private static final Map<Level, Long2ObjectOpenHashMap<LongArrayList>> INDEX = new WeakHashMap<>();
    private static int maxRadius;

    /** The data reloaded: forget every chunk's list. */
    static void reset() {
        INDEX.clear();
        int max = 0;
        for (Map.Entry<?, CropWarmth> entry : BuiltInRegistries.BLOCK.getDataMap(TYPE).entrySet()) max = Math.max(max, entry.getValue().radius);
        maxRadius = max;
    }

    static void onChunkUnload(Level level, ChunkPos pos) {
        Long2ObjectOpenHashMap<LongArrayList> chunks = INDEX.get(level);
        if (chunks != null) chunks.remove(pos.toLong());
    }

    /** A block changed (LevelChunkMixin): keep the chunk's list right if it has one. */
    public static void onBlockChanged(Level level, BlockPos pos, BlockState old, BlockState now) {
        if (!(level instanceof ServerLevel) || (data(old) == null && data(now) == null)) return;
        Long2ObjectOpenHashMap<LongArrayList> chunks = INDEX.get(level);
        LongArrayList list = chunks == null ? null : chunks.get(ChunkPos.asLong(pos));
        if (list == null) return;
        list.rem(pos.asLong());
        if (data(now) != null) list.add(pos.asLong());
    }

    /** The temperature t at pos with the strongest heater and cooler around applied. */
    static float apply(Level level, BlockPos pos, float t) {
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
                    if (warmth == null || state.hasProperty(BlockStateProperties.LIT) && !state.getValue(BlockStateProperties.LIT)) continue;
                    int r = warmth.radius;
                    if (Math.abs(cursor.getX() - pos.getX()) > r || Math.abs(cursor.getY() - pos.getY()) > r || Math.abs(cursor.getZ() - pos.getZ()) > r) continue;
                    if (warmth.strength > 0 && (heater == null || warmth.strength > heater.strength)) heater = warmth;
                    if (warmth.strength < 0 && (cooler == null || warmth.strength < cooler.strength)) cooler = warmth;
                }
            }
        if (heater != null && t < heater.target) t = Math.min(heater.target, t + heater.strength);
        if (cooler != null && t > cooler.target) t = Math.max(cooler.target, t + cooler.strength);
        return t;
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

    private static CropWarmth data(BlockState state) {
        return state.getBlockHolder().getData(TYPE);
    }
}
