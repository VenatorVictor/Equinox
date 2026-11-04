package dev.venatorvictor.equinox.crops;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import dev.venatorvictor.equinox.snow.SnowRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
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
public final class DeadCropBlock extends Block implements EntityBlock, SimpleWaterloggedBlock {
    public static final MapCodec<DeadCropBlock> CODEC = simpleCodec(DeadCropBlock::new);
    public static final EnumProperty<Cause> CAUSE = EnumProperty.create("cause", Cause.class);
    /** Snow lies on a frozen crop (server side, its random tick): cold, after the first snowfall, outside, no heater. */
    public static final BooleanProperty SNOWY = BlockStateProperties.SNOWY;
    /** Dead rice stays in its water. */
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    /** Its water froze (SnowCover or its random tick, with the water around it): the whole dead plant sits in ice. */
    public static final BooleanProperty ICED = BooleanProperty.create("iced");
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
        registerDefaultState(stateDefinition.any().setValue(CAUSE, Cause.FROST).setValue(SNOWY, false).setValue(WATERLOGGED, false).setValue(ICED, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CAUSE, SNOWY, WATERLOGGED, ICED);
    }

    /** The crop it was: a dead young stem is as small as the living one. */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (state.getValue(ICED)) return Shapes.block();
        if (level.getBlockEntity(pos) instanceof DeadCropBlockEntity entity) {
            BlockState original = entity.original();
            if (original != null && !(original.getBlock() instanceof DeadCropBlock)) return original.getShape(level, pos, context);
        }
        return SHAPE;
    }

    /** In ice: solid like ice; else walked through. */
    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(ICED) ? Shapes.block() : Shapes.empty();
    }

    @Override
    protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
        return !state.getValue(ICED) && super.canBeReplaced(state, context);
    }

    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        return state.getValue(ICED) ? Blocks.ICE.defaultBlockState().getDestroyProgress(player, level, pos) : super.getDestroyProgress(state, player, level, pos);
    }

    @Override
    public float getFriction(BlockState state, LevelReader level, BlockPos pos, @Nullable Entity entity) {
        return state.getValue(ICED) ? Blocks.ICE.getFriction() : super.getFriction(state, level, pos, entity);
    }

    /** Broken in ice: the water comes back at the plant's base, as with ice. */
    @Override
    public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state, @Nullable BlockEntity entity, ItemStack tool) {
        super.playerDestroy(level, player, pos, state, entity, tool);
        if (state.getValue(ICED) && base(level, pos)) level.setBlockAndUpdate(pos, IceBlock.meltsInto());
    }

    /** In ice: no face between it and ice next to it (as between two ice blocks). */
    @Override
    protected boolean skipRendering(BlockState state, BlockState adjacent, Direction direction) {
        return state.getValue(ICED) && (adjacent.is(Blocks.ICE) || adjacent.getBlock() instanceof DeadCropBlock && adjacent.getValue(ICED))
                || super.skipRendering(state, adjacent, direction);
    }

    /**
     * The water at the base of a dead plant (rice) froze (true) or thawed: the whole dead plant goes into the ice or
     * comes out of it, its base back in water. pos: any block of the plant. False if it has no water there or nothing
     * changes.
     */
    public static boolean setIced(Level level, BlockPos pos, boolean iced) {
        if (!(level.getBlockState(pos).getBlock() instanceof DeadCropBlock)) return false;
        BlockPos base = pos;
        while (level.getBlockState(base.below()).getBlock() instanceof DeadCropBlock) base = base.below();
        BlockState state = level.getBlockState(base);
        if (state.getValue(ICED) == iced || !state.getValue(ICED) && !state.getValue(WATERLOGGED)) return false;
        BlockPos top = base;
        while (level.getBlockState(top.above()).getBlock() instanceof DeadCropBlock) top = top.above();
        for (BlockPos p = top; p.getY() >= base.getY(); p = p.below()) {
            BlockState part = level.getBlockState(p);
            level.setBlock(p, part.setValue(ICED, iced).setValue(WATERLOGGED, !iced && p.equals(base)), Block.UPDATE_ALL);
        }
        return true;
    }

    /** The base of a dead plant (nothing dead below it): the block that holds its water. */
    private static boolean base(LevelReader level, BlockPos pos) {
        return !(level.getBlockState(pos.below()).getBlock() instanceof DeadCropBlock);
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        // Farmland, the ground rice grows in, or the rest of a dead (or still living) plant below it.
        BlockState below = level.getBlockState(pos.below());
        return below.getBlock() instanceof FarmBlock || below.getBlock() instanceof DeadCropBlock || Crops.mortal(below.getBlock())
                || below.isFaceSturdy(level, pos.below(), Direction.UP);
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        return direction == Direction.DOWN && !canSurvive(state, level, pos) ? Blocks.AIR.defaultBlockState()
                : super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }

    /** Snow settles and melts as on living crops (SnowyCrops); only on frozen ones. */
    private static boolean snowy(BlockState state, ServerLevel level, BlockPos pos) {
        return state.getValue(CAUSE) == Cause.FROST && SnowyCrops.snowy(state, level, pos);
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        // Its water freezes and thaws over time like the water around it (the snow cover's sweeps are rare: rice that
        // dies after the paddy froze would stay in open water).
        if ((state.getValue(ICED) || state.getValue(WATERLOGGED)) && base(level, pos)
                && setIced(level, pos, state.getValue(ICED) ? !thaws(level, pos) : freezes(level, pos))) return;
        boolean snowy = snowy(state, level, pos);
        if (snowy != state.getValue(SNOWY)) level.setBlock(pos, state.setValue(SNOWY, snowy), Block.UPDATE_CLIENTS);
    }

    /** Its water freezes as vanilla water: cold, open to the sky (leaves aside), block light below 10. */
    private static boolean freezes(ServerLevel level, BlockPos pos) {
        BlockPos above = pos.above();
        return SnowRules.cold(level.getBiome(above), above) && level.getBrightness(LightLayer.BLOCK, pos) < 10
                && pos.getY() >= level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ()) - 1;
    }

    /** Its ice melts as vanilla ice: warm, or block light above 11. */
    private static boolean thaws(ServerLevel level, BlockPos pos) {
        BlockPos above = pos.above();
        return !SnowRules.cold(level.getBiome(above), above) || level.getBrightness(LightLayer.BLOCK, pos) > 11;
    }

    /** Frozen or in ice: breaks like ice. Dried by heat: the crop's own rustle. */
    @Override
    public SoundType getSoundType(BlockState state, LevelReader level, BlockPos pos, @Nullable Entity entity) {
        return state.getValue(CAUSE) == Cause.FROST || state.getValue(ICED) ? SoundType.GLASS : SoundType.CROP;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DeadCropBlockEntity(pos, state);
    }
}
