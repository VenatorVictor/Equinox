package dev.venator.seasonsrework.crops;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import org.jetbrains.annotations.Nullable;

/**
 * A pumpkin or melon killed with its stem by frost or heat. Like a dead crop, its block entity keeps the fruit it was
 * and the client draws that fruit frosted or rotten (DeadCropModel). Drops nothing.
 */
public final class DeadFruitBlock extends Block implements EntityBlock {
    public static final MapCodec<DeadFruitBlock> CODEC = simpleCodec(DeadFruitBlock::new);

    public DeadFruitBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(DeadCropBlock.CAUSE, DeadCropBlock.Cause.FROST));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(DeadCropBlock.CAUSE);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DeadCropBlockEntity(pos, state);
    }
}
