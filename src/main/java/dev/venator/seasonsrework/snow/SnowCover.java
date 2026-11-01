package dev.venator.seasonsrework.snow;

import com.mojang.serialization.Codec;
import dev.venator.seasonsrework.Config;
import dev.venator.seasonsrework.SeasonsRework;
import dev.venator.seasonsrework.climate.Climate;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.Util;
import net.minecraft.util.RandomSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import dev.venator.seasonsrework.crops.DeadCropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import dev.venator.seasonsrework.crops.SnowyCrops;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

/**
 * Puts snow and ice on the ground following the snowfall rule (SnowRules.lies: patches, then everywhere, deeper when
 * colder) and takes them away when it thaws, in two ways:
 * - Chunks near players (loaded) change bit by bit: each tick a few random columns per chunk. Snow builds a layer at a
 *   time only while it's snowing there; it melts a layer at a time once it no longer lies. Snow a player breaks stays
 *   gone until the next snowfall (vanilla's own snowfall can still bring some back).
 * - A chunk that loads catches up at once (out at the view distance, where chunks appear anyway): melted where snow no
 *   longer lies, and covered where it lies if it has snowed in that biome since the chunk was last saved.
 * Water freezes and thaws the same way. Overworld only.
 */
public final class SnowCover {
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, SeasonsRework.MOD_ID);
    /** No longer used (the old versioned passes); still registered so chunks saved with it load without warnings. */
    @SuppressWarnings("unused")
    private static final Supplier<AttachmentType<Integer>> OLD_VERSION = ATTACHMENTS.register("snow_version",
            () -> AttachmentType.builder(() -> 0).serialize(Codec.INT).build());
    /** Saved in the chunk's data: the game time the chunk was last saved with its snow up to date. */
    private static final String STAMP = "seasonsrework:snow_stamp";

    private static final boolean SNOW_REAL_MAGIC = ModList.get().isLoaded("snowrealmagic");
    /** Chunks waiting to catch up, nearest to a player first. */
    private static final LongLinkedOpenHashSet QUEUE = new LongLinkedOpenHashSet();
    /** Loaded chunks (bit by bit changes); events may come off the server thread, so they wait in queues. */
    private static final LongOpenHashSet ACTIVE = new LongOpenHashSet();
    private static final ConcurrentLinkedQueue<Long> LOADED = new ConcurrentLinkedQueue<>(), UNLOADED = new ConcurrentLinkedQueue<>();
    /** Stamps read from loaded chunks' data, until they catch up. */
    private static final Map<Long, Long> STAMPS = new ConcurrentHashMap<>();
    private static final LongArrayList ORDER = new LongArrayList();
    private static boolean orderDirty;
    private static int cursor;
    /** Time left for the rest of the tick (other dimensions, network) when using the server's spare time. */
    private static final long RESERVE_NANOS = 10_000_000L;

    public static void init(IEventBus modBus) {
        ATTACHMENTS.register(modBus);
        NeoForge.EVENT_BUS.addListener(ChunkDataEvent.Load.class, e -> {
            if (e.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD && e.getData().contains(STAMP))
                STAMPS.put(e.getChunk().getPos().toLong(), e.getData().getLong(STAMP));
        });
        NeoForge.EVENT_BUS.addListener(ChunkDataEvent.Save.class, e -> {
            if (!(e.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
            long pos = e.getChunk().getPos().toLong();
            // Still waiting to catch up: keep the old stamp.
            Long old = STAMPS.get(pos);
            e.getData().putLong(STAMP, old != null ? old : level.getGameTime());
        });
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Load.class, e -> {
            if (e.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD && e.getChunk() instanceof LevelChunk) {
                long pos = e.getChunk().getPos().toLong();
                // A new chunk (no stamp) catches up as if never covered.
                STAMPS.putIfAbsent(pos, Long.MIN_VALUE);
                LOADED.add(pos);
            }
        });
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Unload.class, e -> {
            if (e.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) UNLOADED.add(e.getChunk().getPos().toLong());
        });
        NeoForge.EVENT_BUS.addListener(LevelTickEvent.Post.class, e -> {
            if (e.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) tick(level);
        });
        NeoForge.EVENT_BUS.addListener(LevelEvent.Unload.class, e -> {
            if (e.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
                QUEUE.clear();
                ACTIVE.clear();
                ORDER.clear();
                LOADED.clear();
                UNLOADED.clear();
                STAMPS.clear();
            }
        });
    }

    private static void tick(ServerLevel level) {
        if (!SnowConfig.SNOW_COVER.get() || !Config.hasSeasons(level.dimension())) return;
        for (Long pos; (pos = UNLOADED.poll()) != null; ) {
            ACTIVE.remove((long) pos);
            QUEUE.remove((long) pos);
            orderDirty = true;
        }
        // A loaded chunk may be the neighbour another chunk was waiting for, so its neighbours go too.
        for (Long pos; (pos = LOADED.poll()) != null; ) {
            int x = ChunkPos.getX(pos), z = ChunkPos.getZ(pos);
            ACTIVE.add((long) pos);
            orderDirty = true;
            QUEUE.add((long) pos);
            for (long next : new long[]{ChunkPos.asLong(x + 1, z), ChunkPos.asLong(x - 1, z), ChunkPos.asLong(x, z + 1), ChunkPos.asLong(x, z - 1)})
                if (STAMPS.containsKey(next)) QUEUE.add(next);
        }
        // Players move, so the queue is put back in nearest-first order every 5 seconds.
        if (level.getGameTime() % 100 == 0) sortQueue(level);

        long now = Util.getNanos();
        long deadline = now + SnowConfig.BUDGET_MICROS.get() * 1000L;
        // When the server is ahead of schedule, also use part of the time until the next tick.
        long spare = SnowConfig.SPARE_MICROS.get() * 1000L;
        if (spare > 0) deadline = Math.max(deadline, Math.min(now + spare, level.getServer().getNextTickTime() - RESERVE_NANOS));
        stepLoaded(level, now + SnowConfig.BUDGET_MICROS.get() * 1000L);
        while (!QUEUE.isEmpty() && Util.getNanos() < deadline) {
            long pos = QUEUE.removeFirstLong();
            int x = ChunkPos.getX(pos), z = ChunkPos.getZ(pos);
            LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
            Long stamp = STAMPS.get(pos);
            // Unloaded chunks catch up when they load again; chunks missing a neighbour when it loads.
            if (chunk == null || stamp == null || !neighboursLoaded(level, x, z)) continue;
            catchUp(level, chunk, stamp);
            STAMPS.remove(pos);
            chunk.setUnsaved(true);
        }
    }

    /** Bit by bit near players: a few random columns of every loaded, caught-up chunk, within the time budget. */
    private static void stepLoaded(ServerLevel level, long deadline) {
        double rate = SnowConfig.COLUMNS_PER_TICK.get();
        if (rate <= 0 || ACTIVE.isEmpty()) return;
        if (orderDirty) {
            ORDER.clear();
            ORDER.addAll(ACTIVE);
            orderDirty = false;
        }
        Map<ResourceKey<Biome>, Float> warmest = Snowpack.warmest();
        BlockPos.MutableBlockPos top = new BlockPos.MutableBlockPos();
        RandomSource random = level.random;
        // Starts where the last tick stopped, so all chunks get their turn when the budget runs out.
        int size = ORDER.size();
        for (int i = 0; i < size; i++) {
            if ((i & 15) == 0 && Util.getNanos() > deadline) return;
            cursor = (cursor + 1) % size;
            // A fraction of a column per tick: one column with that chance. Decided first: most chunks get none.
            int columns = (int) rate + (random.nextDouble() < rate - (int) rate ? 1 : 0);
            if (columns == 0) continue;
            long pos = ORDER.getLong(cursor);
            if (STAMPS.containsKey(pos)) continue; // not caught up yet
            int x = ChunkPos.getX(pos), z = ChunkPos.getZ(pos);
            LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
            if (chunk == null || !neighboursLoaded(level, x, z)) continue;
            for (int c = 0; c < columns; c++) {
                int lx = random.nextInt(16), lz = random.nextInt(16);
                top.set(chunk.getPos().getMinBlockX() + lx, chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, lx, lz) + 1, chunk.getPos().getMinBlockZ() + lz);
                Holder<Biome> biome = level.getBiome(top);
                boolean snowing = level.isRaining() && biome.value().hasPrecipitation() && SnowRules.cold(biome, top);
                column(level, chunk, biome, top, warmest, true, snowing);
                int yNoLeaves = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lx, lz) + 1;
                if (yNoLeaves != top.getY()) column(level, chunk, biome, top.setY(yNoLeaves), warmest, true, snowing);
            }
        }
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

    /** A chunk that loaded: every column at once; snow is only added in biomes where it snowed since stamp. */
    private static void catchUp(ServerLevel level, LevelChunk chunk, long stamp) {
        Map<ResourceKey<Biome>, Float> warmest = Snowpack.warmest();
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
                boolean snowedSince = Snowpack.lastSnowfall(biome) > stamp;
                column(level, chunk, biome, top, warmest, false, snowedSince);
                // Also the ground under trees.
                if (yNoLeaves != y) column(level, chunk, biome, top.setY(yNoLeaves), warmest, false, snowedSince);
            }
        }
    }

    /**
     * One column. top: the first non-blocking position above it (air, a plant or a snow layer). gradual: one layer at a
     * time (near players), else straight to the target (catching up). add: snow may be added (snowing now, or snowed
     * since the chunk was saved); melting and thinning always happen.
     */
    private static void column(ServerLevel level, LevelChunk chunk, Holder<Biome> biome, BlockPos.MutableBlockPos top,
                               Map<ResourceKey<Biome>, Float> warmest, boolean gradual, boolean add) {
        if (level.isOutsideBuildHeight(top) || top.getY() <= level.getMinBuildHeight()) return;
        BlockPos below = top.below();
        BlockState topState = chunk.getBlockState(top);
        BlockState belowState = chunk.getBlockState(below);
        // A crop at the top: snow on it is its snowy state (SnowyCrops), set by the snowfall like the ground's snow.
        if (SnowyCrops.holds(topState)) {
            if (SnowRules.lies(biome, top, warmest)) {
                if (add) SnowyCrops.cover(level, top.immutable(), topState);
            } else {
                SnowyCrops.melt(level, top.immutable(), topState);
            }
            return;
        }
        // Where the snow of this column is: a snow layer or plant at top, or a fence or wall just under it.
        boolean inFence = topState.isAir() && SnowyPlants.holds(belowState)
                && (SnowMap.holds(belowState) || SnowyPlants.maxLayers(belowState) == 1);
        int now = topState.getBlock() instanceof SnowLayerBlock ? topState.getValue(SnowLayerBlock.LAYERS)
                : SnowyPlants.layers(topState) > 0 ? SnowyPlants.layers(topState) : inFence ? SnowyPlants.layers(level, below, belowState) : 0;

        // One temperature lookup per position (the rules below all need it).
        float here = Climate.temperature(biome.value(), top);
        if (SnowRules.lies(biome, top, warmest, here)) {
            int depth = SnowRules.layers(biome, top, warmest, here);
            int target = now;
            if (now < depth && add) target = gradual ? now + 1 : depth;
            // Deeper than the climate gives now: thins toward it (deeper piles are a player's, left alone).
            else if (now > depth && SnowConfig.SNOW_DEPTH.get() && now <= SnowRules.maxLayers(biome)) target = gradual ? now - 1 : depth;
            if (target != now && setSnow(level, top, topState, below, belowState, inFence, now, target)) return;
            if (!freeze(level, below, belowState) && add && SNOW_REAL_MAGIC) SnowRealMagicCompat.cover(level, top.immutable(), topState, below, belowState);
            return;
        }

        // No snow lies here: it thawed, rained, or hasn't snowed since it got cold.
        if (now > 0) {
            setSnow(level, top, topState, below, belowState, inFence, now, gradual ? now - 1 : 0);
        } else if (here < Climate.SNOW_LINE) {
            // Water freezes without snowfall.
            freeze(level, below, belowState);
        } else if (belowState.is(Blocks.ICE)) {
            level.setBlock(below, IceBlock.meltsInto(), Block.UPDATE_ALL);
            level.neighborChanged(below, IceBlock.meltsInto().getBlock(), below);
        } else if (belowState.getBlock() instanceof DeadCropBlock && DeadCropBlock.setIced(level, below, false)) {
            // dead rice: its ice thaws back to water
        } else if (SNOW_REAL_MAGIC) {
            SnowRealMagicCompat.melt(level, top.immutable(), topState, below, belowState);
        }
    }

    /** Sets the column's snow from now to target layers. False if nothing could change (no room for snow, too bright). */
    private static boolean setSnow(ServerLevel level, BlockPos.MutableBlockPos top, BlockState topState, BlockPos below, BlockState belowState,
                                   boolean inFence, int now, int target) {
        if (target > now && !dark(level, inFence ? below : top)) return false;
        if (inFence) {
            if (target > 0 && !Blocks.SNOW.defaultBlockState().canSurvive(level, below)) return false;
            return SnowyPlants.setLayers(level, below, belowState, target, Block.UPDATE_ALL);
        }
        if (topState.getBlock() instanceof SnowLayerBlock) {
            // Flag 2: send to clients and update neighbour shapes (grass turns snowy), no redstone-style updates.
            level.setBlock(top, target == 0 ? Blocks.AIR.defaultBlockState() : topState.setValue(SnowLayerBlock.LAYERS, target), Block.UPDATE_CLIENTS);
            return true;
        }
        if (topState.isAir()) {
            if (!Blocks.SNOW.defaultBlockState().canSurvive(level, top)) return false;
            level.setBlock(top, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, target), Block.UPDATE_CLIENTS);
            return true;
        }
        // A plant: all updates when the snow comes or goes, so the grass under it turns snowy or back.
        if (now == 0) return SnowyPlants.cover(level, top.immutable(), topState, target, Block.UPDATE_ALL);
        return SnowyPlants.setLayers(level, top, topState, target, target == 0 ? Block.UPDATE_ALL : Block.UPDATE_CLIENTS);
    }

    /** Vanilla's freeze check, minus the temperature (already checked). */
    private static boolean freeze(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof DeadCropBlock && dark(level, pos) && DeadCropBlock.setIced(level, pos, true)) return true;
        if (!state.is(Blocks.WATER) || !state.getFluidState().isSource() || !dark(level, pos)) return false;
        level.setBlock(pos, Blocks.ICE.defaultBlockState(), Block.UPDATE_ALL);
        return true;
    }

    // Vanilla doesn't snow or freeze next to light 10 or more.
    private static boolean dark(ServerLevel level, BlockPos pos) {
        return level.getBrightness(LightLayer.BLOCK, pos) < 10;
    }

    private SnowCover() {}
}
