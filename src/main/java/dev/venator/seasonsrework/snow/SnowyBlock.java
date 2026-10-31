package dev.venator.seasonsrework.snow;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * Snow storage option "block_entity" (SnowConfig.STORAGE): snow on a plant or fence as its own block, the plant or fence
 * kept inside in a block entity that never ticks (SnowyBlockEntity). Drawn by one baked model with the inner block's
 * quads and the snow's (SnowyBlockModel), so it renders like any block. Shape, collision, neighbour updates and drops
 * come from the inner block. The other option keeps the snow as a block state of the plant itself (SnowyPlants).
 */
public class SnowyBlock extends Block implements EntityBlock {
    public static final MapCodec<SnowyBlock> CODEC = simpleCodec(SnowyBlock::new);
    public static final IntegerProperty LAYERS = BlockStateProperties.LAYERS;
    /** Holds one layer (a fence, gate or wall inside). */
    public static final BooleanProperty THIN = BooleanProperty.create("thin");

    public SnowyBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(LAYERS, 1).setValue(THIN, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LAYERS, THIN);
    }

    /** Puts snow on the block at pos (inner), moving it inside. */
    static boolean place(Level level, BlockPos pos, BlockState inner, int layers) {
        boolean thin = SnowyPlants.maxLayers(inner) == 1;
        BlockState snowy = SnowBlocks.SNOWY.get().defaultBlockState().setValue(THIN, thin).setValue(LAYERS, Mth.clamp(layers, 1, thin ? 1 : 8));
        SnowyBlockEntity.PENDING.set(inner);
        try {
            return level.setBlock(pos, snowy, Block.UPDATE_ALL);
        } finally {
            SnowyBlockEntity.PENDING.remove();
        }
    }

    /** The block inside (air if unknown, e.g. on the client before the block entity's data came). */
    public static BlockState inner(BlockGetter level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof SnowyBlockEntity entity ? entity.inner() : Blocks.AIR.defaultBlockState();
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SnowyBlockEntity(pos, state);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.or(inner(level, pos).getShape(level, pos, context), SnowyPlants.shape(state.getValue(LAYERS)));
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape own = inner(level, pos).getCollisionShape(level, pos, context);
        int layers = state.getValue(LAYERS);
        return layers < 2 ? own : Shapes.or(own, SnowyPlants.collision(layers));
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        SnowyPlants.randomTick(state, level, pos);
    }

    /** The inner block follows its neighbours (fence connections, a plant losing its ground: then plain snow stays). */
    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (!(level.getBlockEntity(pos) instanceof SnowyBlockEntity entity) || entity.inner().isAir()) return state;
        BlockState updated = entity.inner().updateShape(direction, neighbor, level, pos, neighborPos);
        if (updated.isAir()) {
            BlockState snow = Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, state.getValue(LAYERS));
            return snow.canSurvive(level, pos) ? snow : Blocks.AIR.defaultBlockState();
        }
        if (updated != entity.inner()) entity.setInner(updated);
        return state;
    }

    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof SnowyBlockEntity entity && !entity.inner().isAir()
                ? entity.inner().getDrops(params) : List.of();
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        BlockState inner = inner(level, pos);
        return inner.isAir() ? new ItemStack(Blocks.SNOW) : inner.getBlock().getCloneItemStack(level, pos, inner);
    }
}
