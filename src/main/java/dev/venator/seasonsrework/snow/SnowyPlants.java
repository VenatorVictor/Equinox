package dev.venator.seasonsrework.snow;

import dev.venator.seasonsrework.SeasonsRework;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Snow on grass, ferns and flowers (SnowyPlantBlock): which plants, turning them snowy (snow cover, snowfall) and
 * back, snow depth by climate, and the clean-removal option. Off while Snow! Real Magic is installed (it does this
 * itself).
 */
public final class SnowyPlants {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(SeasonsRework.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, SeasonsRework.MOD_ID);

    /** Plants with their own snowy block (no block entity): the common ones, in every snowy field. */
    private static final List<Block> OWN_BLOCK = List.of(Blocks.SHORT_GRASS, Blocks.FERN, Blocks.DEAD_BUSH, Blocks.DANDELION, Blocks.POPPY,
            Blocks.BLUE_ORCHID, Blocks.ALLIUM, Blocks.AZURE_BLUET, Blocks.RED_TULIP, Blocks.ORANGE_TULIP, Blocks.WHITE_TULIP, Blocks.PINK_TULIP,
            Blocks.OXEYE_DAISY, Blocks.CORNFLOWER, Blocks.LILY_OF_THE_VALLEY, Blocks.TORCHFLOWER,
            // tall plants: the lower half goes in the snow, the upper half stays above (DoublePlantSnowMixin)
            Blocks.TALL_GRASS, Blocks.LARGE_FERN, Blocks.SUNFLOWER, Blocks.LILAC, Blocks.ROSE_BUSH, Blocks.PEONY);
    /** Other plants that snow may cover (the generic block keeps them in a block entity). */
    public static final TagKey<Block> PLANTS = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(SeasonsRework.MOD_ID, "snowy_plants"));

    private static final Map<Block, DeferredBlock<SnowyPlantBlock>> BY_PLANT = new LinkedHashMap<>();
    public static final DeferredBlock<SnowyPlantBlock> GENERIC = BLOCKS.register("snowy_plant", () -> new Generic(properties()));
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SnowyPlantBlockEntity>> GENERIC_ENTITY = BLOCK_ENTITIES.register("snowy_plant",
            () -> BlockEntityType.Builder.of(SnowyPlantBlockEntity::new, GENERIC.get()).build(null));

    static {
        for (Block plant : OWN_BLOCK) {
            String name = "snowy_" + BuiltInRegistries.BLOCK.getKey(plant).getPath();
            BY_PLANT.put(plant, BLOCKS.register(name, () -> new SnowyPlantBlock(properties(), plant)));
        }
    }

    private static final boolean SNOW_REAL_MAGIC = ModList.get().isLoaded("snowrealmagic");
    private static final Map<Block, SnowyPlantBlock> OWN = new IdentityHashMap<>();
    /** Clean removal: snowy plants found in loaded chunks, put back on the next tick. */
    private static final ConcurrentLinkedQueue<Pending> RESTORE = new ConcurrentLinkedQueue<>();

    private record Pending(ServerLevel level, long[] positions) {}

    private static BlockBehaviour.Properties properties() {
        return BlockBehaviour.Properties.ofFullCopy(Blocks.SNOW).noLootTable();
    }

    public static void init(IEventBus modBus) {
        BLOCKS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Load.class, SnowyPlants::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(LevelTickEvent.Post.class, SnowyPlants::onLevelTick);
        NeoForge.EVENT_BUS.addListener(PlayerInteractEvent.RightClickBlock.class, SnowyPlants::onUseSnow);
    }

    /** Every snowy plant block (client: models and colors). */
    public static List<SnowyPlantBlock> blocks() {
        List<SnowyPlantBlock> blocks = new ArrayList<>();
        for (DeferredBlock<SnowyPlantBlock> block : BY_PLANT.values()) blocks.add(block.get());
        blocks.add(GENERIC.get());
        return blocks;
    }

    /** Snow goes on plants now: config on, not removing them, and Snow! Real Magic not installed. */
    public static boolean enabled() {
        return !SNOW_REAL_MAGIC && SnowConfig.SNOWY_PLANTS.get() && !SnowConfig.RESTORE_PLANTS.get();
    }

    private static @Nullable SnowyPlantBlock ownBlock(Block plant) {
        if (OWN.isEmpty()) for (Map.Entry<Block, DeferredBlock<SnowyPlantBlock>> entry : BY_PLANT.entrySet()) OWN.put(entry.getKey(), entry.getValue().get());
        return OWN.get(plant);
    }

    /** The snowy block for a plant state, or null if snow can't hold it. */
    public static @Nullable SnowyPlantBlock blockFor(BlockState plant) {
        SnowyPlantBlock own = ownBlock(plant.getBlock());
        if (own != null && plant == plant.getBlock().defaultBlockState()) return own;
        if (!plant.is(PLANTS) || plant.hasBlockEntity() || !plant.getFluidState().isEmpty() || plant.getBlock() instanceof DoublePlantBlock
                || plant.getBlock() instanceof SnowLayerBlock) return null;
        return GENERIC.get();
    }

    /** Puts snow (layers) on the plant at pos. False if it can't: not a plant snow holds, or snow can't lie there. */
    public static boolean cover(Level level, BlockPos pos, BlockState plant, int layers, int flags) {
        if (!enabled()) return false;
        SnowyPlantBlock block = blockFor(plant);
        if (block == null) return false;
        BlockState snowy = block.defaultBlockState().setValue(SnowLayerBlock.LAYERS, Mth.clamp(layers, 1, 8));
        if (!Blocks.SNOW.defaultBlockState().canSurvive(level, pos)) return false;
        level.setBlock(pos, snowy, flags);
        if (block == GENERIC.get() && level.getBlockEntity(pos) instanceof SnowyPlantBlockEntity entity) entity.setPlant(plant);
        return true;
    }

    /** A player uses snow on grass, a fern or a flower: the plant goes inside the snow (vanilla would replace grass). */
    private static void onUseSnow(PlayerInteractEvent.RightClickBlock event) {
        ItemStack stack = event.getItemStack();
        if (!stack.is(Items.SNOW) || !enabled()) return;
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState plant = level.getBlockState(pos);
        if (blockFor(plant) == null || !Blocks.SNOW.defaultBlockState().canSurvive(level, pos)) return;
        if (!level.isClientSide && cover(level, pos, plant, 1, Block.UPDATE_ALL)) {
            SoundType sound = Blocks.SNOW.defaultBlockState().getSoundType();
            level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1) / 2, sound.getPitch() * 0.8F);
            if (!event.getEntity().getAbilities().instabuild) stack.shrink(1);
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
    }

    /** ServerLevelSnowfallMixin: vanilla snowfall reached a plant (it only snows on air or snow by itself). */
    public static void onSnowfall(ServerLevel level, BlockPos top) {
        if (!level.isRaining() || !enabled()) return;
        BlockState state = level.getBlockState(top);
        if (blockFor(state) == null) return;
        Holder<Biome> biome = level.getBiome(top);
        if (!biome.value().hasPrecipitation() || !SnowRules.lies(biome, top, Snowpack.warmest())
                || level.getBrightness(LightLayer.BLOCK, top) >= 10) return;
        cover(level, top, state, 1, Block.UPDATE_ALL);
    }

    // Clean removal: with restore_plants on, snowy plants in chunks that load turn back into their plants.

    private static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !SnowConfig.RESTORE_PLANTS.get() || !(event.getChunk() instanceof LevelChunk chunk)) return;
        LongArrayList found = new LongArrayList();
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section.hasOnlyAir() || !section.maybeHas(state -> state.getBlock() instanceof SnowyPlantBlock)) continue;
            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++)
                        if (section.getBlockState(x, y, z).getBlock() instanceof SnowyPlantBlock)
                            found.add(BlockPos.asLong(chunk.getPos().getMinBlockX() + x, baseY + y, chunk.getPos().getMinBlockZ() + z));
        }
        if (!found.isEmpty()) RESTORE.add(new Pending(level, found.toLongArray()));
    }

    private static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || RESTORE.isEmpty()) return;
        for (var it = RESTORE.iterator(); it.hasNext(); ) {
            Pending pending = it.next();
            if (pending.level() != level) continue;
            it.remove();
            for (long packed : pending.positions()) {
                BlockPos pos = BlockPos.of(packed);
                if (!level.isLoaded(pos)) continue;
                BlockState state = level.getBlockState(pos);
                if (state.getBlock() instanceof SnowyPlantBlock snowy) level.setBlock(pos, snowy.unsnowed(state, level, pos), Block.UPDATE_ALL);
            }
        }
    }

    /** Plants without their own block: the plant is kept in a block entity. */
    private static final class Generic extends SnowyPlantBlock implements EntityBlock {
        Generic(Properties properties) {
            super(properties, null);
        }

        @Override
        public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new SnowyPlantBlockEntity(pos, state);
        }
    }

    private SnowyPlants() {}
}
