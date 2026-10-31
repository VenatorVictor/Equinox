package dev.venator.seasonsrework.client;

import dev.venator.seasonsrework.client.textures.SeasonalModel;
import dev.venator.seasonsrework.snow.SnowyPlantBlock;
import dev.venator.seasonsrework.snow.SnowyPlantBlockEntity;
import dev.venator.seasonsrework.snow.SnowyPlants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a snowy plant: its snow layers (the block's own model, vanilla's snow) plus the plant's model, the plant
 * drawn as "in snow" for the seasonal textures (frosted grass from the Seasonal Plants pack). NeoForge models only,
 * no Fabric renderer.
 */
public final class SnowyPlantModel extends BakedModelWrapper<BakedModel> {
    private static final ModelProperty<Inside> INSIDE = new ModelProperty<>();

    private record Inside(BlockState plant, BlockAndTintGetter region, BlockPos pos) {}

    private SnowyPlantModel(BakedModel snow) {
        super(snow);
    }

    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        for (SnowyPlantBlock block : SnowyPlants.blocks())
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                ModelResourceLocation location = BlockModelShaper.stateToModelLocation(state);
                BakedModel model = event.getModels().get(location);
                if (model != null && !(model instanceof SnowyPlantModel)) event.getModels().put(location, new SnowyPlantModel(model));
            }
    }

    /** The plant's tint (grass and fern colors, seasonal ones included) for the plant's quads. */
    public static void registerColors(RegisterColorHandlersEvent.Block event) {
        BlockColors colors = event.getBlockColors();
        event.register((state, level, pos, tint) -> {
            if (level == null || pos == null || !(state.getBlock() instanceof SnowyPlantBlock snowy)) return -1;
            BlockState plant = snowy.plant(state, level, pos);
            return plant.isAir() ? -1 : colors.getColor(plant, level, pos, tint);
        }, SnowyPlants.blocks().toArray(SnowyPlantBlock[]::new));
    }

    private static BakedModel model(BlockState state) {
        return Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
    }

    private static @Nullable BlockState plant(BlockState state, ModelData data) {
        if (state.getBlock() instanceof SnowyPlantBlock snowy && snowy.plantBlock() != null) return snowy.plantBlock().defaultBlockState();
        return data.get(SnowyPlantBlockEntity.PLANT);
    }

    @Override
    public @NotNull ModelData getModelData(@NotNull BlockAndTintGetter region, @NotNull BlockPos pos, @NotNull BlockState state, @NotNull ModelData data) {
        data = super.getModelData(region, pos, state, data);
        BlockState plant = plant(state, data);
        return plant == null ? data : data.derive().with(INSIDE, new Inside(plant, region, pos.immutable())).build();
    }

    @Override
    public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand, @NotNull ModelData data, @Nullable RenderType renderType) {
        Inside inside = data.get(INSIDE);
        if (state == null || inside == null) return super.getQuads(state, side, rand, data, renderType);
        // Models give their quads for whatever type is asked, so each part only in its own render types.
        List<BakedQuad> quads = new ArrayList<>();
        if (renderType == null || super.getRenderTypes(state, rand, data).contains(renderType)) quads.addAll(super.getQuads(state, side, rand, data, renderType));
        BakedModel plantModel = model(inside.plant());
        if (renderType == null || plantModel.getRenderTypes(inside.plant(), rand, ModelData.EMPTY).contains(renderType)) {
            SeasonalModel.IN_SNOW.set(new SeasonalModel.InSnow(inside.region(), inside.pos()));
            try {
                quads.addAll(plantModel.getQuads(inside.plant(), side, rand, ModelData.EMPTY, renderType));
            } finally {
                SeasonalModel.IN_SNOW.remove();
            }
        }
        return quads;
    }

    @Override
    public @NotNull ChunkRenderTypeSet getRenderTypes(@NotNull BlockState state, @NotNull RandomSource rand, @NotNull ModelData data) {
        ChunkRenderTypeSet snow = super.getRenderTypes(state, rand, data);
        Inside inside = data.get(INSIDE);
        return inside == null ? snow : ChunkRenderTypeSet.union(snow, model(inside.plant()).getRenderTypes(inside.plant(), rand, ModelData.EMPTY));
    }
}
