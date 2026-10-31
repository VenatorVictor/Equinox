package dev.venator.seasonsrework.climate;

import dev.venator.seasonsrework.Config;
import dev.venator.seasonsrework.SeasonsRework;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Climate blending for Distant Horizons: LODs show land far past the loaded chunks, where there are no chunk mixes.
 * A client with DH asks for mixes out to its LOD distance (Request); the server works them out on a coarser grid (cells
 * of half the blend radius, at least 32 blocks), rings around the player from the nearest, a little each tick, and sends
 * them in batches (Batch). The client uses them where it has no chunk's mix (ClimateBlend.delta).
 */
public final class FarBlend {
    /** Server time per tick for all players together. */
    private static final long BUDGET_NANOS = 1_000_000L;
    private static final int BATCH = 256;
    /** The player moved this many cells from where the rings started: start again around them (sent cells are skipped). */
    private static final int RECENTER = 4;

    /** Server thread only. */
    private static final Map<UUID, Watcher> WATCHERS = new HashMap<>();

    private static final class Watcher {
        final int distance;
        final LongOpenHashSet sent = new LongOpenHashSet();
        int cell, centerX, centerZ, ring, index;
        boolean done;

        Watcher(int distance) {
            this.distance = distance;
        }
    }

    /** Client to server: DH is installed, send mixes out to this many blocks. */
    public record Request(int distance) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SeasonsRework.MOD_ID, "far_blend_request"));
        public static final StreamCodec<ByteBuf, Request> CODEC = ByteBufCodecs.VAR_INT.map(Request::new, Request::distance);

        @Override
        public Type<Request> type() {
            return TYPE;
        }
    }

    /** One cell's mix. */
    public record Entry(long key, List<Holder<Biome>> biomes, List<Float> weights) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Entry::key,
                ByteBufCodecs.holderRegistry(Registries.BIOME).apply(ByteBufCodecs.list()), Entry::biomes,
                ByteBufCodecs.FLOAT.apply(ByteBufCodecs.list()), Entry::weights,
                Entry::new);
    }

    /** Server to client: far mixes, cells of cell blocks. */
    public record Batch(int cell, List<Entry> entries) implements CustomPacketPayload {
        public static final Type<Batch> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SeasonsRework.MOD_ID, "far_blend"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Batch> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Batch::cell,
                Entry.CODEC.apply(ByteBufCodecs.list()), Batch::entries,
                Batch::new);

        @Override
        public Type<Batch> type() {
            return TYPE;
        }
    }

    /** Client: called when far mixes arrive (to repaint LODs once they stop coming). */
    public static volatile Runnable onReceived = () -> {};

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1").optional();
        registrar.playToServer(Request.TYPE, Request.CODEC, (payload, context) -> context.enqueueWork(() -> request(payload, context)));
        registrar.playToClient(Batch.TYPE, Batch.CODEC, (payload, context) -> context.enqueueWork(() -> {
            for (Entry entry : payload.entries) ClimateBlend.putFar(payload.cell, entry.key, ClimateBlendPayload.toMix(entry.biomes, entry.weights));
            onReceived.run();
        }));
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> tick(e.getServer()));
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, e -> WATCHERS.remove(e.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.server.ServerStoppedEvent.class, e -> WATCHERS.clear());
    }

    private static void request(Request payload, IPayloadContext context) {
        int distance = Math.min(payload.distance, Config.dhBlendDistance());
        if (distance <= 0) WATCHERS.remove(context.player().getUUID());
        else WATCHERS.put(context.player().getUUID(), new Watcher(distance));
    }

    private static void tick(MinecraftServer server) {
        if (WATCHERS.isEmpty()) return;
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level == null || !Config.hasSeasons(level.dimension())) return;
        int radius = ClimateBlend.radius(level);
        if (radius <= 0) return;
        int cell = Math.max(32, radius / 2);
        long deadline = System.nanoTime() + BUDGET_NANOS;
        for (ServerPlayer player : level.players()) {
            Watcher watcher = WATCHERS.get(player.getUUID());
            if (watcher == null) continue;
            int px = Math.floorDiv(player.getBlockX(), cell), pz = Math.floorDiv(player.getBlockZ(), cell);
            if (watcher.cell != cell) {
                watcher.cell = cell;
                watcher.sent.clear();
                watcher.done = false;
                watcher.ring = Integer.MAX_VALUE;
            }
            if (watcher.ring == Integer.MAX_VALUE || Math.abs(px - watcher.centerX) >= RECENTER || Math.abs(pz - watcher.centerZ) >= RECENTER) {
                watcher.centerX = px;
                watcher.centerZ = pz;
                watcher.ring = 0;
                watcher.index = 0;
                watcher.done = false;
                // Far behind the player: forgotten, sent again if they come back (the client keeps a limited number too).
                if (watcher.sent.size() > 200_000) watcher.sent.clear();
            }
            if (!watcher.done && send(level, player, watcher, deadline)) return;
        }
    }

    /** Works through the rings until the deadline. True when out of time. */
    private static boolean send(ServerLevel level, ServerPlayer player, Watcher watcher, long deadline) {
        int cell = watcher.cell;
        int rings = watcher.distance / cell + 1;
        long rings2 = (long) rings * rings;
        List<Entry> batch = new ArrayList<>();
        boolean outOfTime = false;
        while (watcher.ring <= rings) {
            int r = watcher.ring;
            int perimeter = r == 0 ? 1 : 8 * r;
            if (watcher.index >= perimeter) {
                watcher.ring++;
                watcher.index = 0;
                continue;
            }
            if ((watcher.index & 7) == 0 && System.nanoTime() > deadline) {
                outOfTime = true;
                break;
            }
            int i = watcher.index++;
            int dx, dz;
            if (r == 0) {
                dx = 0;
                dz = 0;
            } else {
                int side = i / (2 * r), offset = i % (2 * r);
                switch (side) {
                    case 0 -> { dx = -r + offset; dz = -r; }
                    case 1 -> { dx = r; dz = -r + offset; }
                    case 2 -> { dx = r - offset; dz = r; }
                    default -> { dx = -r; dz = r - offset; }
                }
            }
            if ((long) dx * dx + (long) dz * dz > rings2) continue;
            int x = watcher.centerX + dx, z = watcher.centerZ + dz;
            long key = ChunkPos.asLong(x, z);
            if (!watcher.sent.add(key)) continue;
            ClimateBlend.Mix mix = ClimateBlend.mixAt(level, x * cell + cell / 2, z * cell + cell / 2);
            List<Holder<Biome>> holders = new ArrayList<>();
            List<Float> weights = new ArrayList<>();
            if (mix != null && ClimateBlendPayload.toHolders(level, mix, holders, weights)) batch.add(new Entry(key, holders, weights));
            if (batch.size() >= BATCH) {
                PacketDistributor.sendToPlayer(player, new Batch(cell, batch));
                batch = new ArrayList<>();
            }
        }
        if (!batch.isEmpty()) PacketDistributor.sendToPlayer(player, new Batch(cell, batch));
        if (!outOfTime) watcher.done = true;
        return outOfTime;
    }

    private FarBlend() {}
}
