package dev.venatorvictor.equinox.trees;

import com.mojang.serialization.Codec;
import dev.venatorvictor.equinox.Equinox;
import dev.venatorvictor.equinox.api.Season;
import dev.venatorvictor.equinox.api.SeasonState;
import dev.venatorvictor.equinox.api.Seasons;
import dev.venatorvictor.equinox.climate.ClimateBiome;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.minecraft.server.TickTask;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Seeds and fruit on tree leaves, picked by hand (right click), like berries: oak acorns, birch seeds.
 * <p>
 * Some leaf blocks bear (a fixed share, by position, so server and client agree without saving anything). The look
 * comes from the seasonal texture files ("fruit_textures", drawn over bearing leaves that weren't picked this year);
 * the year steps when the fruit is ripe are kept here and must match that file. A picked block remembers the year,
 * saved with the chunk and sent to the clients (they hide the fruit), so it bears again the next year.
 */
public final class TreeFruit {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Equinox.MOD_ID);
    public static final DeferredItem<Item> ACORN = ITEMS.register("acorn", () -> new SeedItem(Blocks.OAK_SAPLING, new Item.Properties()));
    public static final DeferredItem<Item> BIRCH_SEEDS = ITEMS.register("birch_seeds", () -> new SeedItem(Blocks.BIRCH_SAPLING, new Item.Properties()));
    public static final DeferredItem<Item> SPRUCE_CONE = ITEMS.register("spruce_cone", () -> new SeedItem(Blocks.SPRUCE_SAPLING, new Item.Properties()));

    /** One kind of tree: its leaves, the share of them that bear, the ripe weeks of the year (first..last), what a pick gives. */
    private record Kind(Block leaves, int percent, int ripeFrom, int ripeTo, Supplier<Item> item, int min, int max) {}

    // Ripe, as in the season_textures files: acorns summer week 15 to autumn week 6 (mid September to late October,
    // minecraft_oak_leaves.json); birch seed catkins brown autumn weeks 1 to 8 (late September to early November,
    // minecraft_birch_leaves.json); spruce cones brown summer week 15 to autumn week 6 (minecraft_spruce_leaves.json;
    // old cones stay over winter, but only the ripe ones can be picked).
    private static final Kind[] KINDS = {
            new Kind(Blocks.OAK_LEAVES, 30, SeasonState.week(Season.SUMMER, 15), SeasonState.week(Season.AUTUMN, 6), ACORN, 1, 3),
            new Kind(Blocks.BIRCH_LEAVES, 30, SeasonState.week(Season.AUTUMN, 1), SeasonState.week(Season.AUTUMN, 8), BIRCH_SEEDS, 1, 3),
            new Kind(Blocks.SPRUCE_LEAVES, 30, SeasonState.week(Season.SUMMER, 15), SeasonState.week(Season.AUTUMN, 6), SPRUCE_CONE, 1, 2)};

    private static final DeferredRegister<com.mojang.serialization.MapCodec<? extends IGlobalLootModifier>> LOOT_MODIFIERS =
            DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, Equinox.MOD_ID);
    static {
        LOOT_MODIFIERS.register("ripe_tree_fruit", () -> RipeFruitDrops.CODEC);
    }

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Equinox.MOD_ID);
    /** A chunk's picked leaves: packed local position -> year picked. Saved as pairs of ints. */
    private static final Codec<Int2IntOpenHashMap> CODEC = Codec.INT_STREAM.xmap(stream -> fromPairs(stream.toArray()),
            map -> java.util.Arrays.stream(pairs(map)));
    public static final Supplier<AttachmentType<Int2IntOpenHashMap>> PICKED = ATTACHMENTS.register("picked_fruit",
            () -> AttachmentType.builder(() -> new Int2IntOpenHashMap()).serialize(CODEC, map -> !map.isEmpty()).build());

    /** The client's copy, per chunk; read from the chunk drawing threads, so each chunk's map is replaced, never changed. */
    private static final Map<Long, Int2IntOpenHashMap> CLIENT = new ConcurrentHashMap<>();

    public static void init(IEventBus modBus) {
        ITEMS.register(modBus);
        LOOT_MODIFIERS.register(modBus);
        ATTACHMENTS.register(modBus);
        modBus.addListener(BuildCreativeModeTabContentsEvent.class, event -> {
            if (event.getTabKey() == CreativeModeTabs.NATURAL_BLOCKS) {
                event.accept(ACORN.get());
                event.accept(BIRCH_SEEDS.get());
                event.accept(SPRUCE_CONE.get());
            }
        });
        modBus.addListener(RegisterPayloadHandlersEvent.class, event ->
                event.registrar("1").optional().playToClient(Payload.TYPE, Payload.CODEC, (payload, context) -> context.enqueueWork(payload::receive)));
        NeoForge.EVENT_BUS.addListener(PlayerInteractEvent.RightClickBlock.class, TreeFruit::onUse);
        NeoForge.EVENT_BUS.addListener(ChunkWatchEvent.Sent.class, event -> {
            Int2IntOpenHashMap map = event.getChunk().getExistingDataOrNull(PICKED.get());
            if (map != null && !map.isEmpty())
                PacketDistributor.sendToPlayer(event.getPlayer(), new Payload(event.getPos().toLong(), true, pairs(map)));
        });
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Unload.class, event -> {
            if (event.getLevel().isClientSide()) CLIENT.remove(event.getChunk().getPos().toLong());
        });
    }

    private static Kind kind(BlockState state) {
        for (Kind kind : KINDS) if (state.is(kind.leaves)) return kind;
        return null;
    }

    /** Whether the leaf block at pos is one that bears (fixed by position). Any side. */
    public static boolean bears(BlockState state, BlockPos pos) {
        Kind kind = kind(state);
        return kind != null && !placed(state) && bears(kind, pos);
    }

    /** Leaves a player placed (vanilla's persistent flag): they never bear, so placing leaves can't farm fruit. */
    private static boolean placed(BlockState state) {
        return state.hasProperty(LeavesBlock.PERSISTENT) && state.getValue(LeavesBlock.PERSISTENT);
    }

    /** The share (percent) of this kind of leaves that bear, 0 for leaves without fruit. */
    public static int percent(BlockState state) {
        Kind kind = kind(state);
        return kind == null ? 0 : kind.percent;
    }

    private static boolean bears(Kind kind, BlockPos pos) {
        long hash = it.unimi.dsi.fastutil.HashCommon.mix(pos.asLong() ^ 0x5DEECE66DL * 31);
        return Math.floorMod(hash, 100) < kind.percent;
    }

    /** Client, chunk drawing: whether the bearing block at pos shows its fruit (not picked this year). */
    public static boolean showsFruit(Level level, BlockState state, BlockPos pos) {
        if (!bears(state, pos)) return false;
        SeasonState season = Seasons.get(level);
        return season == null || pickedYear(level, pos) != season.year();
    }

    /** Ripe and on the tree: bearing, in a seasonal biome, in the ripe steps, not picked this year. */
    private static boolean ripe(Level level, BlockPos pos, BlockState state) {
        Kind kind = kind(state);
        if (kind == null || !bears(state, pos)) return false;
        SeasonState season = Seasons.get(level);
        if (season == null) return false;
        int week = season.week(); // 0 = the first days of spring
        if (week < kind.ripeFrom || week > kind.ripeTo) return false;
        var biome = level.getNoiseBiome(QuartPos.fromBlock(pos.getX()), QuartPos.fromBlock(pos.getY()), QuartPos.fromBlock(pos.getZ()));
        ClimateBiome climate = (ClimateBiome) (Object) biome.value();
        // Warm zones: oaks are evergreen there but still make acorns (live oak); other trees have no fruit there.
        if (climate.equinox$climate() != ClimateBiome.SEASONAL || climate.equinox$zone().tropical && kind.leaves != Blocks.OAK_LEAVES) return false;
        return pickedYear(level, pos) != season.year();
    }

    private static void onUse(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        if (event.getEntity().isSecondaryUseActive() || !ripe(level, pos, state)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        if (!(level instanceof ServerLevel server)) return;
        Block.popResourceFromFace(server, pos, event.getFace() == null ? net.minecraft.core.Direction.UP : event.getFace(), pick(kind(state), server.random));
        server.playSound(null, pos, SoundEvents.SWEET_BERRY_BUSH_PICK_BERRIES, SoundSource.BLOCKS, 1.0F, 0.8F + server.random.nextFloat() * 0.4F);
        setPicked(server, pos, (int) Seasons.get(server).year());
    }

    private static ItemStack pick(Kind kind, RandomSource random) {
        return new ItemStack(kind.item.get(), kind.min + random.nextInt(kind.max - kind.min + 1));
    }

    /** RipeFruitDrops: what a breaking leaf block drops on top of its own loot (its ripe fruit), or empty. */
    static ItemStack ripeDrop(ServerLevel level, BlockPos pos, BlockState state, RandomSource random) {
        return ripe(level, pos, state) ? pick(kind(state), random) : ItemStack.EMPTY;
    }

    private static int pickedYear(Level level, BlockPos pos) {
        if (level.isClientSide) {
            Int2IntOpenHashMap map = CLIENT.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
            return map == null ? Integer.MIN_VALUE : map.getOrDefault(key(pos), Integer.MIN_VALUE);
        }
        LevelChunk chunk = level instanceof ServerLevel server ? server.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) : null;
        Int2IntOpenHashMap map = chunk == null ? null : chunk.getExistingDataOrNull(PICKED.get());
        return map == null ? Integer.MIN_VALUE : map.getOrDefault(key(pos), Integer.MIN_VALUE);
    }

    /** Remembers the pick (or forgets it with Integer.MIN_VALUE), saves it with the chunk and tells the clients. */
    private static void setPicked(ServerLevel level, BlockPos pos, int year) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return;
        boolean forget = year == Integer.MIN_VALUE;
        Int2IntOpenHashMap map = forget ? chunk.getExistingDataOrNull(PICKED.get()) : chunk.getData(PICKED.get());
        if (map == null) return;
        int key = key(pos);
        if (forget ? !map.containsKey(key) : map.getOrDefault(key, Integer.MIN_VALUE) == year) return;
        if (forget) map.remove(key);
        else map.put(key, year);
        chunk.setUnsaved(true);
        PacketDistributor.sendToPlayersTrackingChunk(level, chunk.getPos(), new Payload(chunk.getPos().toLong(), false, new int[]{key, year}));
    }

    /** LevelChunkMixin: the block at pos changed; a pick mark goes with the leaves. */
    public static void onBlockChanged(Level level, BlockPos pos, BlockState old, BlockState now) {
        if (!(level instanceof ServerLevel server) || old.getBlock() == now.getBlock() || kind(old) == null) return;
        // After this tick: a player's break removes the block before making its drops, which still need the mark
        // (a picked block drops no fruit, RipeFruitDrops).
        BlockPos at = pos.immutable();
        server.getServer().tell(new TickTask(server.getServer().getTickCount(), () -> {
            if (kind(server.getBlockState(at)) == null || server.getBlockState(at).getBlock() != old.getBlock()) setPicked(server, at, Integer.MIN_VALUE);
        }));
    }

    /** Client: logging out forgets everything. */
    public static void clearClient() {
        CLIENT.clear();
    }

    /** Local position in a chunk: x and z 4 bits each, y + 2048 above them. */
    private static int key(BlockPos pos) {
        return (pos.getY() + 2048) << 8 | (pos.getZ() & 15) << 4 | (pos.getX() & 15);
    }

    private static BlockPos posOf(ChunkPos chunk, int key) {
        return new BlockPos(chunk.getMinBlockX() + (key & 15), (key >>> 8) - 2048, chunk.getMinBlockZ() + (key >>> 4 & 15));
    }

    private static int[] pairs(Int2IntOpenHashMap map) {
        int[] out = new int[map.size() * 2];
        int i = 0;
        for (Int2IntMap.Entry entry : map.int2IntEntrySet()) {
            out[i++] = entry.getIntKey();
            out[i++] = entry.getIntValue();
        }
        return out;
    }

    private static Int2IntOpenHashMap fromPairs(int[] pairs) {
        Int2IntOpenHashMap map = new Int2IntOpenHashMap();
        for (int i = 0; i + 1 < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return map;
    }

    /** Server to client: a chunk's picked leaves (whole: replaces them) or changes (year Integer.MIN_VALUE: forgotten). */
    public record Payload(long chunk, boolean whole, int[] pairs) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Equinox.MOD_ID, "picked_fruit"));
        public static final StreamCodec<FriendlyByteBuf, Payload> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Payload::chunk,
                ByteBufCodecs.BOOL, Payload::whole,
                StreamCodec.of((buf, array) -> buf.writeVarIntArray(array), FriendlyByteBuf::readVarIntArray), Payload::pairs,
                Payload::new);

        @Override
        public Type<Payload> type() {
            return TYPE;
        }

        private void receive() {
            Int2IntOpenHashMap old = CLIENT.get(chunk);
            Int2IntOpenHashMap map = whole || old == null ? new Int2IntOpenHashMap() : new Int2IntOpenHashMap(old);
            IntArrayList changed = new IntArrayList();
            if (whole && old != null) changed.addAll(old.keySet());
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                if (pairs[i + 1] == Integer.MIN_VALUE) map.remove(pairs[i]);
                else map.put(pairs[i], pairs[i + 1]);
                changed.add(pairs[i]);
            }
            if (map.isEmpty()) CLIENT.remove(chunk);
            else CLIENT.put(chunk, map);
            ChunkPos pos = new ChunkPos(chunk);
            for (int i = 0; i < changed.size(); i++) dev.venatorvictor.equinox.client.SnowMapModel.redraw(posOf(pos, changed.getInt(i)));
        }
    }

    private TreeFruit() {}
}
