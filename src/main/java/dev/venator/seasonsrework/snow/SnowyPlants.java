package dev.venator.seasonsrework.snow;

import dev.venator.seasonsrework.SeasonsRework;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.SeagrassBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.TallSeagrassBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.WaterlilyBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.Map;

/**
 * Snow inside plants and on fences: a "snow" block state (layers) added to the blocks themselves (BlockSnowyMixin),
 * like the snowy crops. No extra blocks and no block entity: the plant stays itself (other mods, drops and Jade see
 * the real plant), and removing the mod just drops the state (the plants stay, the snow goes). Plants (every
 * BushBlock: grass, flowers, ferns, saplings, mushrooms, berry bushes, modded plants) hold 0..8 layers, a tall plant
 * in its lower half; fences, fence gates and walls hold 0..1 (not while waterlogged). Not: farmland crops
 * (SnowyCrops), water plants.
 * The rest lives in mixins: shape and collision, sound, melting on random ticks, breaking the snow first
 * (SnowStateMixin, BlockStateSnowBreakMixin), grass under snow (SnowyDirtMixin, SpreadingSnowyDirtMixin); the look in SnowyPlantModel.
 * Off while Snow! Real Magic is installed (it does this itself).
 */
public final class SnowyPlants {
    public static final IntegerProperty SNOW = IntegerProperty.create("snow", 0, 8);
    /** Fences, gates and walls: one layer for now. */
    public static final IntegerProperty SNOW_THIN = IntegerProperty.create("snow", 0, 1);
    /** Blocks that never get snow, though they have the state (a data pack can add plants that look wrong). */
    public static final TagKey<Block> NO_SNOW = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(SeasonsRework.MOD_ID, "no_snow"));

    /** Asked lazily: this class loads with the vanilla blocks (BlockSnowyMixin), before the mod list exists. */
    private static final class Srm {
        static final boolean LOADED = ModList.get().isLoaded("snowrealmagic");
    }
    /** Vanilla snow's collision: a layer less than it looks (so you sink a little), none for one layer. */
    private static final VoxelShape[] COLLISION = new VoxelShape[9];
    private static final VoxelShape[] SHAPE = new VoxelShape[9];

    static {
        for (int layers = 1; layers <= 8; layers++) {
            SHAPE[layers] = Block.box(0, 0, 0, 16, layers * 2, 16);
            COLLISION[layers] = Block.box(0, 0, 0, 16, (layers - 1) * 2, 16);
        }
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(PlayerInteractEvent.RightClickBlock.class, SnowyPlants::onUseSnow);
    }

    /** BlockSnowyMixin, while the block is built: the snow state it gets, or null. Only vanilla classes here. */
    public static IntegerProperty propertyFor(Block block, Map<String, Property<?>> properties) {
        if (properties.containsKey(SNOW.getName())) return null;
        if (block instanceof FenceBlock || block instanceof FenceGateBlock || block instanceof WallBlock) return SNOW_THIN;
        if (properties.containsKey(BlockStateProperties.WATERLOGGED.getName()) || !(block instanceof BushBlock) || block instanceof CropBlock || block instanceof StemBlock || block instanceof AttachedStemBlock
                || block instanceof WaterlilyBlock || block instanceof SeagrassBlock || block instanceof TallSeagrassBlock) return null;
        return SNOW;
    }

    /** Snow layers in a block (0 without the state). */
    public static int layers(BlockState state) {
        if (state.hasProperty(SNOW)) return state.getValue(SNOW);
        return state.hasProperty(SNOW_THIN) ? state.getValue(SNOW_THIN) : 0;
    }

    /** Most layers the block holds (0: none). */
    public static int maxLayers(BlockState state) {
        return state.hasProperty(SNOW) ? 8 : state.hasProperty(SNOW_THIN) ? 1 : 0;
    }

    public static BlockState withLayers(BlockState state, int layers) {
        if (state.hasProperty(SNOW)) return state.setValue(SNOW, Mth.clamp(layers, 0, 8));
        return state.hasProperty(SNOW_THIN) ? state.setValue(SNOW_THIN, Mth.clamp(layers, 0, 1)) : state;
    }

    /** All the snow gone. */
    public static BlockState unsnowed(BlockState state) {
        return withLayers(state, 0);
    }

    public static VoxelShape shape(int layers) {
        return SHAPE[layers];
    }

    public static VoxelShape collision(int layers) {
        return COLLISION[layers];
    }

    /** The random offset the plant would have without snow (a block with snow has none, SnowStateMixin). */
    public static Vec3 plantOffset(BlockState state, BlockGetter level, BlockPos pos) {
        return unsnowed(state).getOffset(level, pos);
    }

    /** Snow goes on plants now: config on and Snow! Real Magic not installed. */
    public static boolean enabled() {
        return !Srm.LOADED && SnowConfig.SNOWY_PLANTS.get();
    }

    /** Whether snow can go in this block: it has the state, isn't excluded, and isn't a tall plant's upper half. */
    public static boolean holds(BlockState state) {
        if (maxLayers(state) == 0 || state.is(NO_SNOW)) return false;
        if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED)) return false;
        return !state.hasProperty(DoublePlantBlock.HALF) || state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER;
    }

    /** Puts snow (layers, up to what the block holds) in the block at pos. False if it can't hold snow or snow can't lie there. */
    public static boolean cover(Level level, BlockPos pos, BlockState state, int layers, int flags) {
        if (!enabled() || !holds(state) || !Blocks.SNOW.defaultBlockState().canSurvive(level, pos)) return false;
        BlockState snowy = withLayers(state, Math.max(layers, 1));
        if (snowy == state) return false;
        level.setBlock(pos, snowy, flags);
        return true;
    }

    /** ServerLevelSnowfallMixin: vanilla snowfall reached a plant (it only snows on air or snow by itself). */
    public static void onSnowfall(ServerLevel level, BlockPos top) {
        if (!level.isRaining() || !enabled()) return;
        BlockState state = level.getBlockState(top);
        if (maxLayers(state) == 0 && maxLayers(level.getBlockState(top.below())) == 1) {
            // A fence or wall (the heightmap is above it): its snow is on the ground around it.
            top = top.below();
            state = level.getBlockState(top);
        }
        if (!holds(state) || layers(state) > 0) return;
        Holder<Biome> biome = level.getBiome(top);
        if (!biome.value().hasPrecipitation() || !SnowRules.lies(biome, top, Snowpack.warmest())
                || level.getBrightness(LightLayer.BLOCK, top) >= 10) return;
        cover(level, top, state, 1, Block.UPDATE_ALL);
    }

    /**
     * Random tick of a block with snow (SnowStateMixin): melts a layer in block light above 11 (as vanilla snow) or
     * where snow no longer lies (SnowRules.lies). True if it melted (the state changed).
     */
    public static boolean randomTick(BlockState state, ServerLevel level, BlockPos pos) {
        if (level.getBrightness(LightLayer.BLOCK, pos) <= 11 && SnowRules.lies(level.getBiome(pos), pos, Snowpack.warmest())) return false;
        level.setBlock(pos, withLayers(state, layers(state) - 1), Block.UPDATE_ALL);
        return true;
    }

    /**
     * A player breaks a block with snow (BlockSnowBreakMixin): the snow goes first (snowballs with a shovel, as vanilla
     * snow), the block stays. Both sides, so the client doesn't flash the block away. The break sound and particles
     * are vanilla's (playerWillDestroy), snow's for a snowy state (SnowStateMixin, SnowyPlantModel).
     */
    public static void breakSnow(BlockState state, Level level, BlockPos pos, Player player) {
        BlockState snow = Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers(state));
        if (!level.isClientSide && !player.isCreative() && player.hasCorrectToolForDrops(snow, level, pos))
            Block.dropResources(snow, level, pos, null, player, player.getMainHandItem());
        level.setBlock(pos, unsnowed(state), level.isClientSide ? 11 : 3);
    }

    /**
     * LevelChunkMixin, a block being set: a plant with snow that becomes another state of itself (grows, bonemeal,
     * a berry picked) keeps its snow. Only taking the snow off or turning into something else drops it.
     */
    public static BlockState keep(BlockState old, BlockState now) {
        if (old == now || old.getBlock() != now.getBlock()) return now;
        int layers = layers(old);
        if (layers == 0 || layers(now) != 0 || now == unsnowed(old)) return now;
        return withLayers(now, layers);
    }

    /** A player uses snow on a plant or fence: a layer goes in (vanilla would replace grass with snow). */
    private static void onUseSnow(PlayerInteractEvent.RightClickBlock event) {
        ItemStack stack = event.getItemStack();
        if (!stack.is(Items.SNOW) || !enabled()) return;
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        if (state.hasProperty(DoublePlantBlock.HALF) && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER && maxLayers(state) > 0) {
            pos = pos.below();
            state = level.getBlockState(pos);
        }
        if (!holds(state)) return;
        if (layers(state) < maxLayers(state) && Blocks.SNOW.defaultBlockState().canSurvive(level, pos)) {
            if (!level.isClientSide) {
                level.setBlock(pos, withLayers(state, layers(state) + 1), Block.UPDATE_ALL);
                SoundType sound = Blocks.SNOW.defaultBlockState().getSoundType();
                level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1) / 2, sound.getPitch() * 0.8F);
                if (!event.getEntity().getAbilities().instabuild) stack.shrink(1);
            }
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        } else if (maxLayers(state) == 8) {
            // Full: no vanilla replacing either.
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
        }
    }

    private SnowyPlants() {}
}
