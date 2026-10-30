package dev.venator.seasonsrework.client;

import dev.venator.seasonsrework.crops.CropBlocks;
import dev.venator.seasonsrework.crops.DeadCropBlock;
import dev.venator.seasonsrework.crops.DeadCropBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a dead crop as the crop it was (from its block entity), with every quad tinted: blue-ish for frost, brown for
 * heat (block colors multiply the texture). Baked into the chunk mesh like grass color. Without the crop known yet it
 * shows the plain dead_crop model (a dead bush).
 */
public final class DeadCropModel extends BakedModelWrapper<BakedModel> {
    private static final int FROST = 0x9DB8E0, HEAT = 0x8E6B3E;

    private DeadCropModel(BakedModel original) {
        super(original);
    }

    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        for (BlockState state : CropBlocks.DEAD_CROP.get().getStateDefinition().getPossibleStates()) {
            ModelResourceLocation location = BlockModelShaper.stateToModelLocation(state);
            BakedModel model = event.getModels().get(location);
            if (model != null) event.getModels().put(location, new DeadCropModel(model));
        }
    }

    public static void registerColors(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tint) -> state.getValue(DeadCropBlock.CAUSE) == DeadCropBlock.Cause.FROST ? FROST : HEAT,
                CropBlocks.DEAD_CROP.get());
    }

    private static @Nullable BlockState crop(ModelData data) {
        return data.get(DeadCropBlockEntity.ORIGINAL);
    }

    private static BakedModel model(BlockState crop) {
        return Minecraft.getInstance().getBlockRenderer().getBlockModel(crop);
    }

    @Override
    public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand, @NotNull ModelData data, @Nullable RenderType renderType) {
        BlockState crop = crop(data);
        if (crop == null) return super.getQuads(state, side, rand, data, renderType);
        List<BakedQuad> quads = model(crop).getQuads(crop, side, rand, ModelData.EMPTY, renderType);
        List<BakedQuad> tinted = new ArrayList<>(quads.size());
        for (BakedQuad quad : quads)
            tinted.add(new BakedQuad(quad.getVertices(), 0, quad.getDirection(), quad.getSprite(), quad.isShade(), quad.hasAmbientOcclusion()));
        return tinted;
    }

    @Override
    public @NotNull ChunkRenderTypeSet getRenderTypes(@NotNull BlockState state, @NotNull RandomSource rand, @NotNull ModelData data) {
        BlockState crop = crop(data);
        return crop == null ? super.getRenderTypes(state, rand, data) : model(crop).getRenderTypes(crop, rand, ModelData.EMPTY);
    }

    @Override
    public @NotNull TextureAtlasSprite getParticleIcon(@NotNull ModelData data) {
        BlockState crop = crop(data);
        return crop == null ? super.getParticleIcon(data) : model(crop).getParticleIcon(ModelData.EMPTY);
    }
}
