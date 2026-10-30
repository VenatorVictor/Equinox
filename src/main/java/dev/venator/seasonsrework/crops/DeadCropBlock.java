package dev.venator.seasonsrework.crops;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
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
 * tinted blue (frost) or brown (heat), so modded crops need no art. Drops nothing, breaks instantly, and seeds can be
 * planted straight into it (replaceable). Stays on farmland only.
 */
public final class DeadCropBlock extends Block implements EntityBlock {
    public static final MapCodec<DeadCropBlock> CODEC = simpleCodec(DeadCropBlock::new);
    public static final EnumProperty<Cause> CAUSE = EnumProperty.create("cause", Cause.class);
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
        registerDefaultState(stateDefinition.any().setValue(CAUSE, Cause.FROST));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CAUSE);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
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

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DeadCropBlockEntity(pos, state);
    }
}
