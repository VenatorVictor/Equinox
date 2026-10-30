package dev.venator.seasonsrework.crops;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.levelgen.Heightmap;
import dev.venator.seasonsrework.snow.FirstSnow;
import dev.venator.seasonsrework.snow.SnowRules;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * A crop killed by frost or heat. Its block entity keeps the crop it was, and the client draws that crop's model
 * with frosted (snowy when snow lies on it) or withered textures (client DeadCropModel / DeadCropTextures). Drops nothing, breaks instantly, and seeds can be
 * planted straight into it (replaceable). Stays on farmland only.
 */
public final class DeadCropBlock extends Block implements EntityBlock {
    public static final MapCodec<DeadCropBlock> CODEC = simpleCodec(DeadCropBlock::new);
    public static final EnumProperty<Cause> CAUSE = EnumProperty.create("cause", Cause.class);
    /** Snow lies on a frozen crop (server side, its random tick): cold, after the first snowfall, outside, no heater. */
    public static final BooleanProperty SNOWY = BlockStateProperties.SNOWY;
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 8, 16);

    public enum Cause implements StringRepresentable {
        FROST, HEAT;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public DeadCropBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(CAUSE, Cause.FROST).setValue(SNOWY, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CAUSE, SNOWY);
    }

    /** The crop it was: a dead young stem is as small as the living one. */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (level.getBlockEntity(pos) instanceof DeadCropBlockEntity entity) {
            BlockState original = entity.original();
            if (original != null && !(original.getBlock() instanceof DeadCropBlock)) return original.getShape(level, pos, context);
        }
        return SHAPE;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return level.getBlockState(pos.below()).getBlock() instanceof FarmBlock;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return direction == Direction.DOWN && !canSurvive(state, level, pos) ? Blocks.AIR.defaultBlockState()
                : super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }

    /** Whether snow lies on it now; the glass of a greenhouse is a roof (motion-blocking height map), leaves aren't. */
    public static boolean snowy(BlockState state, ServerLevel level, BlockPos pos) {
        if (state.getValue(CAUSE) != Cause.FROST) return false;
        Holder<Biome> biome = level.getBiome(pos);
        return SnowRules.cold(biome, pos) && SnowRules.snowAllowed(biome, pos, FirstSnow.coverage)
                && pos.getY() >= level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ())
                && !CropWarmth.heated(level, pos);
    }

    /** Roof and heater changes waiting for the end of the tick (server thread; not while a block is being set). */
    private static final java.util.Map<Level, java.util.List<long[]>> PENDING = new java.util.WeakHashMap<>();
    /** How far below a new or removed roof dead crops are checked. */
    private static final int ROOF_DEPTH = 32;

    /** CropWarmth: a heater or cooler (radius) or a roof (radius 0) changed at pos. */
    static void snowChanged(Level level, BlockPos pos, int radius) {
        PENDING.computeIfAbsent(level, l -> new java.util.ArrayList<>()).add(new long[]{pos.asLong(), radius});
    }

    /** End of the level tick: dead crops around the changes get their snow right now instead of on a random tick. */
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        java.util.List<long[]> changes = PENDING.remove(level);
        if (changes == null) return;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (long[] change : changes) {
            BlockPos pos = BlockPos.of(change[0]);
            int r = (int) change[1];
            if (r > 0) {
                for (BlockPos p : BlockPos.betweenClosed(pos.offset(-r, -r, -r), pos.offset(r, r, r))) update(level, p);
            } else {
                for (int y = pos.getY() - 1; y >= pos.getY() - ROOF_DEPTH && y >= level.getMinBuildHeight(); y--)
                    update(level, cursor.set(pos.getX(), y, pos.getZ()));
            }
        }
    }

    private static void update(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return;
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof DeadCropBlock)) return;
        boolean snowy = snowy(state, level, pos);
        if (snowy != state.getValue(SNOWY)) level.setBlock(pos, state.setValue(SNOWY, snowy), Block.UPDATE_CLIENTS);
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        boolean snowy = snowy(state, level, pos);
        if (snowy != state.getValue(SNOWY)) level.setBlock(pos, state.setValue(SNOWY, snowy), Block.UPDATE_CLIENTS);
    }

    /** Frozen: breaks like ice. Dried by heat: the crop's own rustle. */
    @Override
    public SoundType getSoundType(BlockState state, LevelReader level, BlockPos pos, @Nullable Entity entity) {
        return state.getValue(CAUSE) == Cause.FROST ? SoundType.GLASS : SoundType.CROP;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DeadCropBlockEntity(pos, state);
    }
}
