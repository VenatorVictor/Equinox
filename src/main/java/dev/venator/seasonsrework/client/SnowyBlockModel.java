package dev.venator.seasonsrework.client;

import com.mojang.math.Transformation;
import dev.venator.seasonsrework.snow.SnowBlocks;
import dev.venator.seasonsrework.snow.SnowyBlock;
import dev.venator.seasonsrework.snow.SnowyBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
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
 * Draws a SnowyBlock (snow storage "block_entity"): the inner block's model (from the block entity's model data,
 * with the plant's random offset) plus vanilla's snow layer, baked into the chunk like any block. Tints come from the
 * inner block (colors()).
 */
public final class SnowyBlockModel extends BakedModelWrapper<BakedModel> {
    private static final ChunkRenderTypeSet SOLID = ChunkRenderTypeSet.of(RenderType.solid());
    private static final ModelProperty<ModelData> INNER_DATA = new ModelProperty<>();
    private static final ModelProperty<IQuadTransformer> OFFSET = new ModelProperty<>();

    private final int layers;

    private SnowyBlockModel(BakedModel snow, int layers) {
        super(snow);
        this.layers = layers;
    }

    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        for (BlockState state : SnowBlocks.SNOWY.get().getStateDefinition().getPossibleStates()) {
            ModelResourceLocation location = BlockModelShaper.stateToModelLocation(state);
            BakedModel model = event.getModels().get(location);
            if (model != null) event.getModels().put(location, new SnowyBlockModel(model, state.getValue(SnowyBlock.LAYERS)));
        }
    }

    /** The inner block's colors (grass, leaves); the snow has no tint. */
    public static void colors(RegisterColorHandlersEvent.Block event) {
        var colors = event.getBlockColors();
        event.register((state, level, pos, tint) -> {
            if (level == null || pos == null) return -1;
            BlockState inner = SnowyBlock.inner(level, pos);
            return inner.isAir() ? -1 : colors.getColor(inner, level, pos, tint);
        }, SnowBlocks.SNOWY.get());
    }

    private BlockState snow() {
        return Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers);
    }

    private static BakedModel model(BlockState state) {
        return Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
    }

    @Override
    public @NotNull ModelData getModelData(@NotNull BlockAndTintGetter region, @NotNull BlockPos pos, @NotNull BlockState state, @NotNull ModelData data) {
        BlockState inner = data.get(SnowyBlockEntity.INNER);
        if (inner == null || inner.isAir()) return data;
        ModelData.Builder builder = data.derive().with(INNER_DATA, model(inner).getModelData(region, pos, inner, ModelData.EMPTY));
        Vec3 offset = inner.getOffset(region, pos);
        if (!offset.equals(Vec3.ZERO))
            builder.with(OFFSET, QuadTransformers.applying(new Transformation(new Matrix4f().translation((float) offset.x, (float) offset.y, (float) offset.z))));
        return builder.build();
    }

    @Override
    public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand, @NotNull ModelData data, @Nullable RenderType renderType) {
        BlockState inner = data.get(SnowyBlockEntity.INNER);
        List<BakedQuad> quads = new ArrayList<>();
        if (inner != null && !inner.isAir()) {
            BakedModel model = model(inner);
            ModelData innerData = data.get(INNER_DATA);
            if (innerData == null) innerData = ModelData.EMPTY;
            if (renderType == null || model.getRenderTypes(inner, rand, innerData).contains(renderType)) {
                List<BakedQuad> own = model.getQuads(inner, side, rand, innerData, renderType);
                IQuadTransformer move = data.get(OFFSET);
                quads.addAll(move != null && !own.isEmpty() ? move.process(own) : own);
            }
        }
        if (renderType == null || renderType == RenderType.solid()) {
            BlockState snow = snow();
            quads.addAll(model(snow).getQuads(snow, side, rand, ModelData.EMPTY, renderType));
        }
        return quads;
    }

    @Override
    public @NotNull ChunkRenderTypeSet getRenderTypes(@NotNull BlockState state, @NotNull RandomSource rand, @NotNull ModelData data) {
        BlockState inner = data.get(SnowyBlockEntity.INNER);
        if (inner == null || inner.isAir()) return SOLID;
        ModelData innerData = data.get(INNER_DATA);
        return ChunkRenderTypeSet.union(model(inner).getRenderTypes(inner, rand, innerData == null ? ModelData.EMPTY : innerData), SOLID);
    }

    /** As SnowyPlantModel: the inner block's smooth lighting in its own types, the snow's (on) in solid unless the inner block draws there. */
    @Override
    public @NotNull TriState useAmbientOcclusion(@NotNull BlockState state, @NotNull ModelData data, @NotNull RenderType renderType) {
        BlockState inner = data.get(SnowyBlockEntity.INNER);
        if (inner == null || inner.isAir()) return TriState.DEFAULT;
        ModelData innerData = data.get(INNER_DATA);
        if (innerData == null) innerData = ModelData.EMPTY;
        BakedModel model = model(inner);
        if (renderType == RenderType.solid() && !model.getRenderTypes(inner, RandomSource.create(42), innerData).contains(renderType)) return TriState.DEFAULT;
        return model.useAmbientOcclusion(inner, innerData, renderType);
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
