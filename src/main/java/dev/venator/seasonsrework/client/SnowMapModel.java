package dev.venator.seasonsrework.client;

import dev.venator.seasonsrework.snow.SnowMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a fence with the snow around it (SnowMap): the fence's own model plus vanilla's snow layer model. The layers
 * come from the client's snow map when the chunk is drawn (getModelData gets the position, in vanilla and in
 * renderers that replace chunk drawing), so the fence needs no snow state.
 * The snow's faces are hidden by the snow's own rules, not the fence's: the game hides a block's culled faces by the
 * block's shape (a fence arm next to a fence arm counts as covered), which would hide the snow's sides too. So all
 * snow faces are given unculled, minus those covered here: by a full block, or snow at least as deep next to it.
 */
public final class SnowMapModel extends BakedModelWrapper<BakedModel> {
    private static final ChunkRenderTypeSet SOLID = ChunkRenderTypeSet.of(RenderType.solid());
    /** Layers (low 4 bits) and the hidden faces (a bit per Direction, from bit 4). */
    private static final ModelProperty<Integer> SNOW = new ModelProperty<>();
    private static final Direction[] DIRECTIONS = Direction.values();

    private SnowMapModel(BakedModel own) {
        super(own);
    }

    /** Wraps every fence state's model, after the other wrappers (seasonal textures). */
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        for (Block block : BuiltInRegistries.BLOCK) {
            if (!SnowMap.uses(block.defaultBlockState())) continue;
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                ModelResourceLocation location = BlockModelShaper.stateToModelLocation(state);
                BakedModel model = event.getModels().get(location);
                if (model != null && !(model instanceof SnowMapModel)) event.getModels().put(location, new SnowMapModel(model));
            }
        }
    }

    /** The snow at pos changed: draw its section (and the neighbours' sections) again. */
    public static void redraw(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.levelRenderer != null)
            // The neighbours too: their snow's hidden faces depend on this snow.
            minecraft.levelRenderer.setBlocksDirty(pos.getX() - 1, pos.getY() - 1, pos.getZ() - 1, pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
    }

    @Override
    public @NotNull ModelData getModelData(@NotNull BlockAndTintGetter region, @NotNull BlockPos pos, @NotNull BlockState state, @NotNull ModelData data) {
        data = super.getModelData(region, pos, state, data);
        int layers = SnowMap.holds(state) ? SnowMap.client(pos) : 0;
        if (layers == 0) return data;
        int hidden = 0;
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        for (Direction direction : DIRECTIONS) {
            if (direction == Direction.UP && layers < 8) continue; // a lower top is never covered
            next.setWithOffset(pos, direction);
            if (covers(region, next, region.getBlockState(next), direction, layers)) hidden |= 1 << direction.ordinal();
        }
        return data.derive().with(SNOW, layers | hidden << 4).build();
    }

    /** Whether the block next to the snow covers the snow's face toward it. */
    private static boolean covers(BlockAndTintGetter region, BlockPos next, BlockState neighbour, Direction direction, int layers) {
        if (neighbour.isSolidRender(region, next)) return true;
        if (direction.getAxis().isVertical()) return false;
        int theirs = neighbour.getBlock() instanceof SnowLayerBlock ? neighbour.getValue(SnowLayerBlock.LAYERS)
                : SnowMap.holds(neighbour) ? SnowMap.client(next) : dev.venator.seasonsrework.snow.SnowyPlants.stateLayers(neighbour);
        return theirs >= layers;
    }

    @Override
    public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand, @NotNull ModelData data, @Nullable RenderType renderType) {
        List<BakedQuad> own = super.getQuads(state, side, rand, data, renderType);
        Integer packed = data.get(SNOW);
        // All the snow's faces come with the unculled ones (side null), minus the hidden ones.
        if (packed == null || side != null || renderType != null && renderType != RenderType.solid()) return own;
        int layers = packed & 15, hidden = packed >> 4;
        BlockState snow = Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers);
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(snow);
        List<BakedQuad> quads = new ArrayList<>(own);
        quads.addAll(model.getQuads(snow, null, rand, ModelData.EMPTY, renderType));
        for (Direction direction : DIRECTIONS)
            if ((hidden & 1 << direction.ordinal()) == 0) quads.addAll(model.getQuads(snow, direction, rand, ModelData.EMPTY, renderType));
        return quads;
    }

    @Override
    public @NotNull ChunkRenderTypeSet getRenderTypes(@NotNull BlockState state, @NotNull RandomSource rand, @NotNull ModelData data) {
        ChunkRenderTypeSet own = super.getRenderTypes(state, rand, data);
        return data.get(SNOW) == null ? own : ChunkRenderTypeSet.union(own, SOLID);
    }
}
