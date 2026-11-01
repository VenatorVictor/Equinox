package dev.venator.seasonsrework.client;

import dev.venator.seasonsrework.snow.SnowyPlants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import com.mojang.math.Transformation;
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
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.QuadTransformers;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import net.neoforged.neoforge.common.util.TriState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a plant or fence with snow in it (SnowyPlants): its own model plus vanilla's snow layer model, each in its own
 * render types. The seasonal textures see the snow state themselves (frosted grass, SeasonalModel). Breaking
 * particles are snow's (the snow breaks first). NeoForge models only, no Fabric renderer.
 */
public final class SnowyPlantModel extends BakedModelWrapper<BakedModel> {
    private static final ChunkRenderTypeSet SOLID = ChunkRenderTypeSet.of(RenderType.solid());
    /**
     * The plant's random offset (grass, flowers), applied to the plant's quads only: a block with snow has no offset
     * itself (SnowStateMixin), so the snow sits like a vanilla layer and smooth lighting works on it.
     */
    private static final ModelProperty<IQuadTransformer> OFFSET = new ModelProperty<>();

    private final int layers;
    /** Whether the block's own model draws in solid too (fences, walls), found on first use. */
    private volatile Boolean ownSolid;

    private SnowyPlantModel(BakedModel own, int layers) {
        super(own);
        this.layers = layers;
    }

    /** After the other wrappers (seasonal textures), so this is the outer one. */
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        for (Block block : BuiltInRegistries.BLOCK) {
            if (SnowyPlants.stateMaxLayers(block.defaultBlockState()) == 0) continue;
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                int layers = SnowyPlants.stateLayers(state);
                if (layers == 0) continue;
                ModelResourceLocation location = BlockModelShaper.stateToModelLocation(state);
                BakedModel model = event.getModels().get(location);
                if (model != null && !(model instanceof SnowyPlantModel)) event.getModels().put(location, new SnowyPlantModel(model, layers));
            }
        }
    }

    private BlockState snow() {
        return Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers);
    }

    private static BakedModel model(BlockState state) {
        return Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
    }

    @Override
    public @NotNull ModelData getModelData(@NotNull BlockAndTintGetter region, @NotNull BlockPos pos, @NotNull BlockState state, @NotNull ModelData data) {
        data = super.getModelData(region, pos, state, data);
        if (!state.hasOffsetFunction()) return data;
        Vec3 offset = SnowyPlants.plantOffset(state, region, pos);
        if (offset.equals(Vec3.ZERO)) return data;
        IQuadTransformer move = QuadTransformers.applying(new Transformation(
                new Matrix4f().translation((float) offset.x, (float) offset.y, (float) offset.z)));
        return data.derive().with(OFFSET, move).build();
    }

    @Override
    public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand, @NotNull ModelData data, @Nullable RenderType renderType) {
        // Models give their quads for whatever type is asked, so each part only in its own render types.
        boolean own = renderType == null || super.getRenderTypes(state == null ? snow() : state, rand, data).contains(renderType);
        boolean snow = renderType == null || renderType == RenderType.solid();
        List<BakedQuad> ownQuads = own ? super.getQuads(state, side, rand, data, renderType) : List.of();
        IQuadTransformer move = data.get(OFFSET);
        if (move != null && !ownQuads.isEmpty()) ownQuads = move.process(ownQuads);
        if (!snow) return ownQuads;
        BlockState snowState = snow();
        List<BakedQuad> quads = new ArrayList<>(ownQuads);
        quads.addAll(model(snowState).getQuads(snowState, side, rand, ModelData.EMPTY, renderType));
        return quads;
    }


    @Override
    public @NotNull ChunkRenderTypeSet getRenderTypes(@NotNull BlockState state, @NotNull RandomSource rand, @NotNull ModelData data) {
        return ChunkRenderTypeSet.union(super.getRenderTypes(state, rand, data), SOLID);
    }

    /** Plants turn smooth lighting off; the snow keeps it, as a snow layer (its own render type, solid). */
    @Override
    public @NotNull TriState useAmbientOcclusion(@NotNull BlockState state, @NotNull ModelData data, @NotNull RenderType renderType) {
        if (renderType != RenderType.solid()) return super.useAmbientOcclusion(state, data, renderType);
        Boolean own = ownSolid;
        if (own == null) ownSolid = own = super.getRenderTypes(state, RandomSource.create(42), data).contains(renderType);
        return own ? super.useAmbientOcclusion(state, data, renderType) : TriState.DEFAULT;
    }

    @Override
    public @NotNull TextureAtlasSprite getParticleIcon(@NotNull ModelData data) {
        return model(snow()).getParticleIcon(ModelData.EMPTY);
    }

    @Override
    public @NotNull TextureAtlasSprite getParticleIcon() {
        return model(snow()).getParticleIcon();
    }
}
