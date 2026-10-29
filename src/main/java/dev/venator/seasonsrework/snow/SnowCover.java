package dev.venator.seasonsrework.snow;

import com.mojang.serialization.Codec;
import dev.venator.seasonsrework.Config;
import dev.venator.seasonsrework.SeasonsRework;
import dev.venator.seasonsrework.climate.Climate;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

/**
 * Brings loaded chunks up to the season's snow and ice in one pass: vanilla snowfall only adds snow near players and
 * never melts it.
 * Work only happens when the target changes (the temperature change moved by STEP, or the first snowfall of the cold season),
 * or when a chunk loads that was last done for an older target. Nothing re-checks chunks on a timer,
 * and each tick only spends the configured time budget. Overworld only.
 */
public final class SnowCover {
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, SeasonsRework.MOD_ID);
    /** The target version a chunk was last brought to; saved with the chunk. */
    private static final Supplier<AttachmentType<Integer>> DONE = ATTACHMENTS.register("snow_version",
            () -> AttachmentType.builder(() -> 0).serialize(Codec.INT).build());

    private static final boolean SNOW_REAL_MAGIC = ModList.get().isLoaded("snowrealmagic");
    private static final LongLinkedOpenHashSet QUEUE = new LongLinkedOpenHashSet();
    // Chunk loads may be reported off the server thread; they're moved into QUEUE on tick.
    private static final ConcurrentLinkedQueue<Long> LOADED = new ConcurrentLinkedQueue<>();
    /** Time left for the rest of the tick (other dimensions, network) when using the server's spare time. */
    private static final long RESERVE_NANOS = 10_000_000L;
    /** The seasonal temperature change is blended every tick; chunks are redone each time it moves this much. */
    private static final float STEP = 0.05F;

    public static void init(IEventBus modBus) {
        ATTACHMENTS.register(modBus);
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Load.class, e -> {
            if (e.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) LOADED.add(e.getChunk().getPos().toLong());
        });
        NeoForge.EVENT_BUS.addListener(LevelTickEvent.Post.class, e -> {
            if (e.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) tick(level);
        });
        NeoForge.EVENT_BUS.addListener(LevelEvent.Unload.class, e -> {
            if (e.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
                QUEUE.clear();
                LOADED.clear();
            }
        });
    }

    private static void tick(ServerLevel level) {
        if (!SnowConfig.SNOW_COVER.get() || !Config.hasSeasons(level.dimension())) return;
        Data data = data(level);
        if (level.getGameTime() % 20 == 0) checkTarget(level, data);
        // Players move, so the queue is put back in nearest-first order every 5 seconds.
        if (level.getGameTime() % 100 == 0) sortQueue(level);

        // A loaded chunk may be the neighbour another chunk was waiting for, so its neighbours go too.
        for (Long pos; (pos = LOADED.poll()) != null; ) {
            int x = ChunkPos.getX(pos), z = ChunkPos.getZ(pos);
            QUEUE.add((long) pos);
            QUEUE.add(ChunkPos.asLong(x + 1, z));
            QUEUE.add(ChunkPos.asLong(x - 1, z));
            QUEUE.add(ChunkPos.asLong(x, z + 1));
            QUEUE.add(ChunkPos.asLong(x, z - 1));
        }
        if (QUEUE.isEmpty()) return;

        long now = Util.getNanos();
        long deadline = now + SnowConfig.BUDGET_MICROS.get() * 1000L;
        // When the server is ahead of schedule, also use part of the time until the next tick.
        long spare = SnowConfig.SPARE_MICROS.get() * 1000L;
        if (spare > 0) deadline = Math.max(deadline, Math.min(now + spare, level.getServer().getNextTickTime() - RESERVE_NANOS));
        do {
            long pos = QUEUE.removeFirstLong();
            int x = ChunkPos.getX(pos), z = ChunkPos.getZ(pos);
            LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
            // Unloaded chunks are done when they load again; chunks missing a neighbour when it loads.
            if (chunk == null || chunk.getData(DONE) == data.version || !neighboursLoaded(level, x, z)) continue;
            process(level, chunk);
            chunk.setData(DONE, data.version);
            chunk.setUnsaved(true);
        } while (!QUEUE.isEmpty() && Util.getNanos() < deadline);
    }

    /** The target is the temperature change (in STEPs) plus whether the first snow has fallen. A change redoes every loaded chunk. */
    private static void checkTarget(ServerLevel level, Data data) {
        float adjustment = Math.round(Climate.offset() / STEP) * STEP;
        float coverage = FirstSnow.coverage;
        if (data.version != 0 && adjustment == data.adjustment && coverage == data.coverage) return;
        data.version = data.version == Integer.MAX_VALUE ? 1 : data.version + 1;
        data.adjustment = adjustment;
        data.coverage = coverage;
        data.setDirty();
        queueLoadedChunks(level);
    }

    /** Every loaded chunk, nearest to a player first. */
    private static void queueLoadedChunks(ServerLevel level) {
        List<ServerPlayer> players = level.players();
        LongArrayList chunks = new LongArrayList();
        for (ChunkHolder holder : level.getChunkSource().chunkMap.getChunks()) chunks.add(holder.getPos().toLong());
        if (!players.isEmpty()) chunks.sort(Comparator.comparingLong(pos -> distanceToPlayers(pos, players)));
        QUEUE.addAll(chunks);
    }

    private static void sortQueue(ServerLevel level) {
        List<ServerPlayer> players = level.players();
        if (QUEUE.size() < 2 || players.isEmpty()) return;
        LongArrayList chunks = new LongArrayList(QUEUE);
        chunks.sort(Comparator.comparingLong(pos -> distanceToPlayers(pos, players)));
        QUEUE.clear();
        QUEUE.addAll(chunks);
    }

    private static long distanceToPlayers(long pos, List<ServerPlayer> players) {
        long best = Long.MAX_VALUE;
        for (ServerPlayer player : players) {
            long dx = ChunkPos.getX(pos) - (player.getBlockX() >> 4);
            long dz = ChunkPos.getZ(pos) - (player.getBlockZ() >> 4);
            best = Math.min(best, dx * dx + dz * dz);
        }
        return best;
    }

    // Block updates spread one block sideways, so the 4 neighbours must be loaded (or they'd be loaded from disk).
    private static boolean neighboursLoaded(ServerLevel level, int x, int z) {
        var source = level.getChunkSource();
        return source.getChunkNow(x + 1, z) != null && source.getChunkNow(x - 1, z) != null
                && source.getChunkNow(x, z + 1) != null && source.getChunkNow(x, z - 1) != null;
    }

    private static void process(ServerLevel level, LevelChunk chunk) {
        BlockPos.MutableBlockPos top = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
        // Biomes come from vanilla's fuzzy lookup (irregular edges); with a cell size above 1, one lookup per cell.
        int cell = SnowConfig.BIOME_CELL.get(), cells = (16 + cell - 1) / cell;
        @SuppressWarnings("unchecked")
        Holder<Biome>[] biomes = new Holder[cells * cells];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, lx, lz) + 1;
                int yNoLeaves = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lx, lz) + 1;
                top.set(minX + lx, y, minZ + lz);
                int index = lx / cell * cells + lz / cell;
                Holder<Biome> biome = biomes[index];
                if (biome == null) biome = biomes[index] = level.getBiome(top);
                column(level, chunk, biome, top);
                // Also the ground under trees.
                if (yNoLeaves != y) column(level, chunk, biome, top.setY(yNoLeaves));
            }
        }
    }

    /** top: the first non-blocking position above a column (air, a plant or a snow layer). */
    private static void column(ServerLevel level, LevelChunk chunk, Holder<Biome> biome, BlockPos.MutableBlockPos top) {
        if (level.isOutsideBuildHeight(top) || top.getY() <= level.getMinBuildHeight()) return;
        BlockPos below = top.below();
        BlockState topState = chunk.getBlockState(top);
        BlockState belowState = chunk.getBlockState(below);
        boolean cold = SnowRules.cold(biome, top);

        if (cold) {
            if (!SnowRules.snowAllowed(biome, top, FirstSnow.coverage)) {
                // No snowfall yet this cold season; water still freezes.
                freeze(level, below, belowState);
                return;
            }
            if (topState.isAir() && dark(level, top) && Blocks.SNOW.defaultBlockState().canSurvive(level, top)) {
                // Flag 2: send to clients and update neighbour shapes (grass turns snowy), no redstone-style updates.
                level.setBlock(top, Blocks.SNOW.defaultBlockState(), Block.UPDATE_CLIENTS);
            } else if (!freeze(level, below, belowState) && SNOW_REAL_MAGIC) {
                SnowRealMagicCompat.cover(level, top.immutable(), topState, below, belowState);
            }
            return;
        }

        if (topState.is(Blocks.SNOW)) {
            level.setBlock(top, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        } else if (belowState.is(Blocks.ICE)) {
            level.setBlock(below, IceBlock.meltsInto(), Block.UPDATE_ALL);
            level.neighborChanged(below, IceBlock.meltsInto().getBlock(), below);
        } else if (SNOW_REAL_MAGIC) {
            SnowRealMagicCompat.melt(level, top.immutable(), topState, below, belowState);
        }
    }

    /** Vanilla's freeze check, minus the temperature (already checked). */
    private static boolean freeze(ServerLevel level, BlockPos pos, BlockState state) {
        if (!state.is(Blocks.WATER) || !state.getFluidState().isSource() || !dark(level, pos)) return false;
        level.setBlock(pos, Blocks.ICE.defaultBlockState(), Block.UPDATE_ALL);
        return true;
    }

    // Vanilla doesn't snow or freeze next to light 10 or more.
    private static boolean dark(ServerLevel level, BlockPos pos) {
        return level.getBrightness(LightLayer.BLOCK, pos) < 10;
    }

    private static Data data(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(Data::new, Data::load, null), "seasonsrework_snow_cover");
    }

    private static final class Data extends SavedData {
        int version;
        float adjustment;
        float coverage;

        static Data load(CompoundTag tag, HolderLookup.Provider registries) {
            Data data = new Data();
            data.version = tag.getInt("version");
            data.adjustment = tag.getFloat("adjustment");
            data.coverage = tag.getFloat("coverage");
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putInt("version", version);
            tag.putFloat("adjustment", adjustment);
            tag.putFloat("coverage", coverage);
            return tag;
        }
    }

    private SnowCover() {}
}
