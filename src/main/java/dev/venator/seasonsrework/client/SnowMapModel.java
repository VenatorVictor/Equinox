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
 */
public final class SnowMapModel extends BakedModelWrapper<BakedModel> {
    private static final ChunkRenderTypeSet SOLID = ChunkRenderTypeSet.of(RenderType.solid());
    private static final ModelProperty<Integer> LAYERS = new ModelProperty<>();

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

    /** The snow at pos changed: draw its section again. */
    public static void redraw(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.levelRenderer != null)
            minecraft.levelRenderer.setBlocksDirty(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public @NotNull ModelData getModelData(@NotNull BlockAndTintGetter region, @NotNull BlockPos pos, @NotNull BlockState state, @NotNull ModelData data) {
        data = super.getModelData(region, pos, state, data);
        int layers = SnowMap.holds(state) ? SnowMap.client(pos) : 0;
        return layers == 0 ? data : data.derive().with(LAYERS, layers).build();
    }

    @Override
    public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand, @NotNull ModelData data, @Nullable RenderType renderType) {
        List<BakedQuad> own = super.getQuads(state, side, rand, data, renderType);
        Integer layers = data.get(LAYERS);
        if (layers == null || renderType != null && renderType != RenderType.solid()) return own;
        BlockState snow = Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers);
        List<BakedQuad> quads = new ArrayList<>(own);
        quads.addAll(Minecraft.getInstance().getBlockRenderer().getBlockModel(snow).getQuads(snow, side, rand, ModelData.EMPTY, renderType));
        return quads;
    }

    @Override
    public @NotNull ChunkRenderTypeSet getRenderTypes(@NotNull BlockState state, @NotNull RandomSource rand, @NotNull ModelData data) {
        ChunkRenderTypeSet own = super.getRenderTypes(state, rand, data);
        return data.get(LAYERS) == null ? own : ChunkRenderTypeSet.union(own, SOLID);
    }
}
