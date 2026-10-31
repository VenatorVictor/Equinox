package dev.venator.seasonsrework.snow;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Snow layers with a plant inside (grass, fern, a flower), like Snow! Real Magic's but without a block entity: one
 * block per plant (SnowyPlants), so chunks store them as plainly as snow. Plants without their own block use
 * SnowyPlants.GENERIC, which keeps the plant in a block entity. Breaking takes what the player aims at: the snow
 * (the plant stays) or the plant (the snow stays); melting down to no layers gives the plant back.
 */
public class SnowyPlantBlock extends SnowLayerBlock {
    /** The plant inside; null for the generic block (the plant is in its block entity). */
    private final @Nullable Block plant;

    public SnowyPlantBlock(Properties properties, @Nullable Block plant) {
        super(properties);
        this.plant = plant;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public MapCodec<SnowLayerBlock> codec() {
        return (MapCodec) simpleCodec(properties -> new SnowyPlantBlock(properties, plant));
    }

    /** The plant inside (air if a generic block lost it). */
    public BlockState plant(BlockState state, BlockGetter level, BlockPos pos) {
        if (plant != null) return plant.defaultBlockState();
        return level.getBlockEntity(pos) instanceof SnowyPlantBlockEntity entity && entity.plant() != null ? entity.plant() : Blocks.AIR.defaultBlockState();
    }

    public @Nullable Block plantBlock() {
        return plant;
    }

    /** One layer less, or the plant back (air if it can't stand there any more). */
    public BlockState melted(BlockState state, LevelReader level, BlockPos pos) {
        int layers = state.getValue(LAYERS);
        if (layers > 1) return state.setValue(LAYERS, layers - 1);
        return unsnowed(state, level, pos);
    }

    /** All the snow gone: the plant. */
    public BlockState unsnowed(BlockState state, LevelReader level, BlockPos pos) {
        BlockState inside = plant(state, level, pos);
        return inside.canSurvive(level, pos) ? inside : Blocks.AIR.defaultBlockState();
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.or(super.getShape(state, level, pos, context), plant(state, level, pos).getShape(level, pos, context));
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return super.canSurvive(state, level, pos) && plant(state, level, pos).canSurvive(level, pos);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (direction == Direction.DOWN && !canSurvive(state, level, pos)) return unsnowed(state, level, pos);
        // A tall plant whose upper half went (broken): only the snow is left.
        if (direction == Direction.UP && plant instanceof DoublePlantBlock
                && !(neighbor.is(plant) && neighbor.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER)) return snow(state);
        return state;
    }

    /** Melts as vanilla snow does (block light above 11), and where snow no longer lies (SnowRules.lies); a layer at a time. */
    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (level.getBrightness(LightLayer.BLOCK, pos) > 11 || !SnowRules.lies(level.getBiome(pos), pos, Snowpack.warmest()))
            level.setBlock(pos, melted(state, level, pos), Block.UPDATE_ALL);
    }

    /** More snow piles on (the snow item); a block placed on a single layer replaces it, as with snow. */
    @Override
    protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
        int layers = state.getValue(LAYERS);
        if (context.getItemInHand().is(Items.SNOW)) return layers < 8 && (!context.replacingClickedOnBlock() || context.getClickedFace() == Direction.UP);
        return layers == 1;
    }

    /** Breaks as fast as the part aimed at: the plant (instant for grass) or the snow. */
    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        BlockState inside = plant(state, level, pos);
        if (inside.isAir() || aimsAtSnow(state, pos, player)) return snow(state).getDestroyProgress(player, level, pos);
        return inside.getDestroyProgress(player, level, pos);
    }

    /** A player breaks the part aimed at, with its own drops: the snow (the plant stays) or the plant (the snow stays). */
    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, boolean willHarvest, FluidState fluid) {
        BlockState inside = plant(state, level, pos);
        if (inside.isAir()) return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
        boolean snowPart = aimsAtSnow(state, pos, player);
        BlockState broken = snowPart ? snow(state) : inside;
        if (!level.isClientSide && !player.isCreative() && (!snowPart || player.hasCorrectToolForDrops(broken, level, pos)))
            Block.dropResources(broken, level, pos, null, player, player.getMainHandItem());
        level.levelEvent(player, 2001, pos, Block.getId(broken));
        // Flag 3: a tall plant's upper half follows (DoublePlantBlock drops it when its lower half is gone).
        level.setBlock(pos, snowPart ? unsnowed(state, level, pos) : snow(state), level.isClientSide ? 11 : 3);
        return false;
    }

    /** Plain snow with the same layers. */
    public static BlockState snow(BlockState state) {
        return Blocks.SNOW.defaultBlockState().setValue(LAYERS, state.getValue(LAYERS));
    }

    /** The player looks at the snow (up to its top surface) rather than the plant sticking out of it. */
    private static boolean aimsAtSnow(BlockState state, BlockPos pos, Player player) {
        HitResult hit = player.pick(player.blockInteractionRange(), 1.0F, false);
        if (!(hit instanceof BlockHitResult block) || !block.getBlockPos().equals(pos)) return true;
        return hit.getLocation().y - pos.getY() <= state.getValue(LAYERS) * 2 / 16.0 + 1.0E-3;
    }

    /** Broken some other way (explosion, piston): the snow's drops and the plant's. */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = new ArrayList<>(Blocks.SNOW.defaultBlockState().setValue(LAYERS, state.getValue(LAYERS)).getDrops(params));
        if (plant != null) drops.addAll(plant.defaultBlockState().getDrops(params));
        else if (params.getOptionalParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY) instanceof SnowyPlantBlockEntity entity && entity.plant() != null)
            drops.addAll(entity.plant().getDrops(params));
        return drops;
    }

    /** Pick block (and Jade's name): the plant. */
    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        BlockState inside = plant(state, level, pos);
        return inside.isAir() ? new ItemStack(Items.SNOW) : inside.getBlock().getCloneItemStack(level, pos, inside);
    }
}
