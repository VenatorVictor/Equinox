package dev.venatorvictor.equinox.snow;

import com.mojang.serialization.Codec;
import dev.venatorvictor.equinox.Equinox;
import dev.venatorvictor.equinox.mixin.PathNavigationRegionAccessor;
import it.unimi.dsi.fastutil.ints.Int2ByteMap;
import it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.SnowyDirtBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Snow in blocks that keep it outside their block state: fences (0..8 layers). Each chunk keeps a small map of
 * position -> layers in its own saved data (an attachment), sent to the clients with the chunk and on every change.
 * The fence stays the same block with the same states: no extra states, no block entity, removing the mod just
 * drops the snow. Shapes, collision, sound, breaking and melting read the map by position (SnowStateMixin,
 * BlockStateSnowBreakMixin); the look is SnowMapModel, which reads the client's copy while chunks are drawn.
 */
public final class SnowMap {
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Equinox.MOD_ID);

    /** A chunk's snow: packed local position -> layers (1..8). Saved as a list of ints (position << 4 | layers). */
    public static final class Data {
        final Int2ByteOpenHashMap layers = new Int2ByteOpenHashMap();

        int[] entries() {
            int[] out = new int[layers.size()];
            int i = 0;
            for (Int2ByteMap.Entry entry : layers.int2ByteEntrySet()) out[i++] = entry.getIntKey() << 4 | entry.getByteValue();
            return out;
        }

        static Data of(int[] entries) {
            Data data = new Data();
            for (int entry : entries) if ((entry & 15) > 0) data.layers.put(entry >>> 4, (byte) Math.min(entry & 15, 8));
            return data;
        }
    }

    private static final Codec<Data> CODEC = Codec.INT_STREAM.xmap(stream -> Data.of(stream.toArray()), data -> java.util.Arrays.stream(data.entries()));
    public static final Supplier<AttachmentType<Data>> TYPE = ATTACHMENTS.register("block_snow",
            () -> AttachmentType.builder(Data::new).serialize(CODEC, data -> !data.layers.isEmpty()).build());

    /** The client's copy, per chunk; read from the chunk drawing threads, so each chunk's map is replaced, never changed. */
    private static final Map<Long, Int2ByteOpenHashMap> CLIENT = new ConcurrentHashMap<>();

    /** Local position in a chunk: x and z 4 bits each, y + 2048 above them. */
    private static int key(BlockPos pos) {
        return (pos.getY() + 2048) << 8 | (pos.getZ() & 15) << 4 | (pos.getX() & 15);
    }

    private static BlockPos posOf(ChunkPos chunk, int key) {
        return new BlockPos(chunk.getMinBlockX() + (key & 15), (key >>> 8) - 2048, chunk.getMinBlockZ() + (key >>> 4 & 15));
    }

    /** Blocks whose snow lives here: fences, not while waterlogged. */
    public static boolean holds(BlockState state) {
        return state.getBlock() instanceof FenceBlock
                && !(state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED));
    }

    /** Whether the block keeps its snow here (any state of it, waterlogged or not). */
    public static boolean uses(BlockState state) {
        return state.getBlock() instanceof FenceBlock;
    }

    /** Layers at pos (0: none). Works from the level, path finding regions, and the client's chunk drawing. */
    public static int get(BlockGetter getter, BlockPos pos) {
        if (getter instanceof PathNavigationRegion region) getter = ((PathNavigationRegionAccessor) region).equinox$level();
        if (getter instanceof Level level) {
            if (level.isClientSide) return client(pos);
            LevelChunk chunk = level instanceof ServerLevel server ? server.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) : null;
            Data data = chunk == null ? null : chunk.getExistingDataOrNull(TYPE);
            return data == null ? 0 : data.layers.get(key(pos));
        }
        // Chunk drawing (vanilla's region or a renderer's copy of the level): the client's map.
        return client(pos);
    }

    public static int client(BlockPos pos) {
        Int2ByteOpenHashMap map = CLIENT.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        return map == null ? 0 : map.get(key(pos));
    }

    /** Sets the snow at pos (0 removes it), saves it with the chunk and tells the clients. False if nothing changed. */
    public static boolean set(ServerLevel level, BlockPos pos, int layers) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return false;
        layers = Math.max(0, Math.min(layers, 8));
        Data data = layers == 0 ? chunk.getExistingDataOrNull(TYPE) : chunk.getData(TYPE);
        if (data == null) return false;
        int key = key(pos);
        int old = data.layers.get(key);
        if (old == layers) return false;
        if (layers == 0) data.layers.remove(key);
        else data.layers.put(key, (byte) layers);
        chunk.setUnsaved(true);
        PacketDistributor.sendToPlayersTrackingChunk(level, chunk.getPos(),
                new Payload(chunk.getPos().toLong(), false, new int[] {key << 4 | layers}));
        if ((old == 0) != (layers == 0)) snowyGround(level, pos.below(), layers > 0);
        return true;
    }

    /** Grass, podzol or mycelium under the block turns snowy with the snow, or back. */
    private static void snowyGround(ServerLevel level, BlockPos below, boolean snowy) {
        BlockState ground = level.getBlockState(below);
        if (ground.getBlock() instanceof SnowyDirtBlock && ground.getValue(SnowyDirtBlock.SNOWY) != snowy)
            level.setBlock(below, ground.setValue(SnowyDirtBlock.SNOWY, snowy), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
    }

    /** LevelChunkMixin: the block at pos changed; its snow goes if it's no longer a block that keeps it. */
    public static void onBlockChanged(Level level, BlockPos pos, BlockState old, BlockState now) {
        if (level instanceof ServerLevel server && uses(old) && !holds(now)) set(server, pos, 0);
    }

    public static void init(IEventBus modBus) {
        ATTACHMENTS.register(modBus);
        modBus.addListener(RegisterPayloadHandlersEvent.class, event ->
                event.registrar("1").optional().playToClient(Payload.TYPE, Payload.CODEC, (payload, context) -> context.enqueueWork(payload::receive)));
        NeoForge.EVENT_BUS.addListener(ChunkWatchEvent.Sent.class, event -> {
            Data data = event.getChunk().getExistingDataOrNull(TYPE);
            if (data != null && !data.layers.isEmpty())
                PacketDistributor.sendToPlayer(event.getPlayer(), new Payload(event.getPos().toLong(), true, data.entries()));
        });
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Unload.class, event -> {
            if (event.getLevel().isClientSide()) CLIENT.remove(event.getChunk().getPos().toLong());
        });
    }

    /** Client: logging out forgets everything. */
    public static void clearClient() {
        CLIENT.clear();
    }

    /** Server to client: a chunk's snow (whole: replaces it) or changes to it. */
    public record Payload(long chunk, boolean whole, int[] entries) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Equinox.MOD_ID, "block_snow"));
        public static final StreamCodec<FriendlyByteBuf, Payload> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Payload::chunk,
                ByteBufCodecs.BOOL, Payload::whole,
                StreamCodec.of((buf, array) -> buf.writeVarIntArray(array), FriendlyByteBuf::readVarIntArray), Payload::entries,
                Payload::new);

        @Override
        public Type<Payload> type() {
            return TYPE;
        }

        private void receive() {
            Int2ByteOpenHashMap old = CLIENT.get(chunk);
            Int2ByteOpenHashMap map = whole || old == null ? new Int2ByteOpenHashMap() : new Int2ByteOpenHashMap(old);
            IntArrayList changed = new IntArrayList();
            if (whole && old != null) changed.addAll(old.keySet());
            for (int entry : entries) {
                int key = entry >>> 4, layers = entry & 15;
                if (layers == 0) map.remove(key);
                else map.put(key, (byte) layers);
                changed.add(key);
            }
            if (map.isEmpty()) CLIENT.remove(chunk);
            else CLIENT.put(chunk, map);
            ChunkPos pos = new ChunkPos(chunk);
            for (int i = 0; i < changed.size(); i++) dev.venatorvictor.equinox.client.SnowMapModel.redraw(posOf(pos, changed.getInt(i)));
        }
    }

    private SnowMap() {}
}
