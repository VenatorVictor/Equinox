package dev.venatorvictor.equinox.weather;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server side of the per-region weather: the storms of the overworld (seed from the world seed), the /weather override
 * (saved), and the climate grid streamed to players around them, nearest tiles first, within a time budget.
 */
public final class ServerWeather {
    /** Tiles (512 blocks) sent around each player: out to 2048 blocks, for far clouds. */
    private static final int SEND_RADIUS = 4;
    /** Tiles a client keeps around itself (ClientWeather trims past this). */
    public static final int KEEP_RADIUS = 6;
    private static final int BATCH = 16;
    private static final long BUDGET_NANOS = 2_000_000L;

    /** Tiles sent per player (server thread). */
    private static final Map<UUID, LongOpenHashSet> SENT = new HashMap<>();

    public static void init() {
        NeoForge.EVENT_BUS.addListener(LevelEvent.Load.class, e -> {
            if (e.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) start(level);
        });
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, e -> {
            Weather.setServer(null);
            SENT.clear();
        });
        NeoForge.EVENT_BUS.addListener(LevelTickEvent.Post.class, e -> {
            if (e.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD && Weather.on(level)) tick(level);
        });
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer player) {
                SENT.remove(player.getUUID());
                send(player);
            }
        });
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, e -> SENT.remove(e.getEntity().getUUID()));
        // Back in the overworld: the client dropped its tiles when it left.
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerChangedDimensionEvent.class, e -> SENT.remove(e.getEntity().getUUID()));
        // Climate zones can change with the tags: the grid is made again.
        NeoForge.EVENT_BUS.addListener(TagsUpdatedEvent.class, e -> {
            Weather.Side side = Weather.server();
            if (side == null || e.getUpdateCause() != TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) return;
            side.grid.clear();
            side.forget();
            SENT.clear();
        });
    }

    private static void start(ServerLevel level) {
        long seed = level.getSeed() * 0x9E3779B97F4A7C15L + 0x2F6B1A3C5D7E9F01L;
        ClimateGrid grid = new ClimateGrid(key -> ClimateGrid.compute(level, ChunkPos.getX(key), ChunkPos.getZ(key)));
        Weather.Side side = new Weather.Side(seed, grid);
        Data data = data(level);
        side.setOverride(data.override, data.from, data.until);
        Weather.setServer(side);
        SENT.clear();
    }

    private static Data data(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(Data::new, Data::load, null), "equinox_weather");
    }

    /** The seed and override to one player (on login). */
    private static void send(ServerPlayer player) {
        Weather.Side side = Weather.server();
        if (side != null) PacketDistributor.sendToPlayer(player, payload(side));
    }

    private static WeatherPayload payload(Weather.Side side) {
        return new WeatherPayload(side.seed, side.override().ordinal(), side.overrideFrom(), side.overrideUntil());
    }

    /**
     * /weather (or anything setting the world's weather): clear = no storms for that long, rain = rain everywhere it
     * can, thunder = thunder too. Duration 0 or less: back to the storms.
     */
    public static void command(ServerLevel level, int clearTime, int weatherTime, boolean raining, boolean thundering) {
        Weather.Side side = Weather.server();
        if (side == null) return;
        Weather.Override kind = raining ? thundering ? Weather.Override.THUNDER : Weather.Override.RAIN : Weather.Override.CLEAR;
        int duration = raining ? weatherTime : clearTime;
        // from the start of the strength cache's second, so it shows at once
        long now = Math.floorDiv(level.getGameTime(), Weather.TIME_STEP) * Weather.TIME_STEP;
        if (duration <= 0) kind = Weather.Override.NONE;
        side.setOverride(kind, now, now + Math.max(0, duration));
        Data data = data(level);
        data.override = kind;
        data.from = now;
        data.until = now + Math.max(0, duration);
        data.setDirty();
        PacketDistributor.sendToPlayersInDimension(level, payload(side));
    }

    /** Sends missing climate tiles around players, nearest first, within the time budget. */
    private static void tick(ServerLevel level) {
        Weather.Side side = Weather.server();
        long deadline = Util.getNanos() + BUDGET_NANOS;
        for (ServerPlayer player : level.players()) {
            LongOpenHashSet sent = SENT.computeIfAbsent(player.getUUID(), id -> new LongOpenHashSet());
            int tx = Math.floorDiv(player.getBlockX(), ClimateGrid.TILE_BLOCKS), tz = Math.floorDiv(player.getBlockZ(), ClimateGrid.TILE_BLOCKS);
            List<Long> keys = new ArrayList<>();
            outer:
            for (int ring = 0; ring <= SEND_RADIUS; ring++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                        long key = ClimateGrid.tileKey(tx + dx, tz + dz);
                        if (sent.contains(key)) continue;
                        if (!side.grid.has(key) && Util.getNanos() > deadline) break outer;
                        side.grid.tile(key); // made now if missing (biome placement reads)
                        keys.add(key);
                        if (keys.size() >= BATCH) break outer;
                    }
                }
            }
            if (keys.isEmpty()) continue;
            long[] array = new long[keys.size()];
            byte[] data = new byte[keys.size() * ClimateGrid.CELLS * 2];
            for (int i = 0; i < array.length; i++) {
                array[i] = keys.get(i);
                System.arraycopy(side.grid.tile(array[i]), 0, data, i * ClimateGrid.CELLS * 2, ClimateGrid.CELLS * 2);
            }
            // The client drops tiles past KEEP_RADIUS: forget them here too, so they come again on the way back.
            int span = 2 * KEEP_RADIUS + 1;
            if (sent.size() > span * span) sent.removeIf(key -> Math.max(Math.abs(ChunkPos.getX(key) - tx), Math.abs(ChunkPos.getZ(key) - tz)) > KEEP_RADIUS);
            for (long key : array) sent.add(key);
            PacketDistributor.sendToPlayer(player, new WeatherPayload.Grid(array, data));
        }
    }

    /**
     * Rain or snow falls in a biome now, for the biome-wide snowpack: at a player standing in it if there is one,
     * else at the biome's own far-off spot of the storm map with the biome's own rain share (so each biome gets
     * snowfalls about as often as its climate says).
     */
    public static boolean fallsInBiome(ServerLevel level, Holder<Biome> biome) {
        Weather.Side side = Weather.server();
        boolean seen = false;
        for (ServerPlayer player : level.players()) {
            BlockPos pos = player.blockPosition();
            if (!level.getBiome(pos).is(biome)) continue;
            seen = true;
            if (Weather.fallsAt(level, pos.getX(), pos.getZ())) return true;
        }
        if (seen) return false;
        long time = level.getGameTime();
        Weather.Override kind = side.overrideAt(time);
        float share = ClimateGrid.share(biome.value());
        if (share <= 0 || kind == Weather.Override.CLEAR) return false;
        if (kind != Weather.Override.NONE) return true;
        int hash = biome.unwrapKey().map(k -> k.location().hashCode()).orElse(0);
        double x = 2.0e7 + (hash & 0xFFFF) * 4096.0, z = 2.0e7 + (hash >>> 16 & 0xFFFF) * 4096.0;
        return side.field.value(x, z, time, side.field.offset(time)) > 1 - share;
    }

    /** Whether storms are on in this level and a column got rain or snow since a game time. */
    public static boolean fellSince(ServerLevel level, int x, int z, long since) {
        return Weather.server().fellSince(x, z, since, level.getGameTime());
    }

    private static final class Data extends SavedData {
        Weather.Override override = Weather.Override.NONE;
        long from, until;

        static Data load(CompoundTag tag, HolderLookup.Provider registries) {
            Data data = new Data();
            int kind = tag.getInt("override");
            Weather.Override[] kinds = Weather.Override.values();
            data.override = kind >= 0 && kind < kinds.length ? kinds[kind] : Weather.Override.NONE;
            data.from = tag.getLong("from");
            data.until = tag.getLong("until");
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putInt("override", override.ordinal());
            tag.putLong("from", from);
            tag.putLong("until", until);
            return tag;
        }
    }

    private ServerWeather() {
    }
}
