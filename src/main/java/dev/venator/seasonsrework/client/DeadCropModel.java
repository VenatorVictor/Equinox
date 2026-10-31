package dev.venator.seasonsrework.client;

import dev.venator.seasonsrework.crops.CropBlocks;
import dev.venator.seasonsrework.crops.DeadCropBlock;
import dev.venator.seasonsrework.crops.DeadCropBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Blocks;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws a dead crop (or dead fruit) as the crop it was (from its block entity) with its textures swapped for the dead ones
 * (DeadCropTextures): frosted, frosted with snow (block state snowy, set by the server), or withered.
 * Without the crop known yet it shows the plain dead_crop model.
 */
public final class DeadCropModel extends BakedModelWrapper<BakedModel> {
    private static final ModelProperty<DeadCropTextures.Kind> KIND = new ModelProperty<>();

    // Per resource load (a new model each time): dead sprite per original sprite and kind, and swapped quads.
    @SuppressWarnings("unchecked")
    private final Map<TextureAtlasSprite, TextureAtlasSprite>[] sprites = new Map[DeadCropTextures.Kind.values().length];
    @SuppressWarnings("unchecked")
    private final Map<BakedQuad, BakedQuad>[] quads = new Map[DeadCropTextures.Kind.values().length];

    private DeadCropModel(BakedModel original) {
        super(original);
        for (int i = 0; i < sprites.length; i++) {
            sprites[i] = new ConcurrentHashMap<>();
            quads[i] = new ConcurrentHashMap<>();
        }
    }

    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        List<BlockState> states = new ArrayList<>(CropBlocks.DEAD_CROP.get().getStateDefinition().getPossibleStates());
        states.addAll(CropBlocks.DEAD_FRUIT.get().getStateDefinition().getPossibleStates());
        for (BlockState state : states) {
            ModelResourceLocation location = BlockModelShaper.stateToModelLocation(state);
            BakedModel model = event.getModels().get(location);
            if (model != null) event.getModels().put(location, new DeadCropModel(model));
        }
        clearIcons();
    }

    @Override
    public @NotNull ModelData getModelData(@NotNull BlockAndTintGetter region, @NotNull BlockPos pos, @NotNull BlockState state, @NotNull ModelData data) {
        data = super.getModelData(region, pos, state, data);
        return data.derive().with(KIND, kind(state)).build();
    }

    private static BakedModel model(BlockState crop) {
        return Minecraft.getInstance().getBlockRenderer().getBlockModel(crop);
    }

    @Override
    public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand, @NotNull ModelData data, @Nullable RenderType renderType) {
        if (state == null || !iced(state)) return plantQuads(state, side, rand, data, renderType);
        // Dead rice in frozen water: an ice block around it. Each part only in its own render types (models give
        // their quads whatever type is asked).
        BlockState ice = Blocks.ICE.defaultBlockState();
        BakedModel iceModel = model(ice);
        List<BakedQuad> quads = new ArrayList<>();
        if (renderType == null || plantTypes(state, rand, data).contains(renderType)) quads.addAll(plantQuads(state, side, rand, data, renderType));
        if (renderType == null || iceModel.getRenderTypes(ice, rand, ModelData.EMPTY).contains(renderType))
            quads.addAll(iceModel.getQuads(ice, side, rand, ModelData.EMPTY, renderType));
        return quads;
    }

    private List<BakedQuad> plantQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand, @NotNull ModelData data, @Nullable RenderType renderType) {
        BlockState crop = data.get(DeadCropBlockEntity.ORIGINAL);
        DeadCropTextures.Kind kind = data.get(KIND);
        if (crop == null || kind == null) return super.getQuads(state, side, rand, data, renderType);
        List<BakedQuad> original = model(crop).getQuads(crop, side, rand, ModelData.EMPTY, renderType);
        List<BakedQuad> swapped = new ArrayList<>(original.size());
        Map<BakedQuad, BakedQuad> cache = quads[kind.ordinal()];
        for (BakedQuad quad : original) swapped.add(cache.computeIfAbsent(quad, q -> retexture(q, deadSprite(q.getSprite(), kind), false)));
        return swapped;
    }

    private TextureAtlasSprite deadSprite(TextureAtlasSprite sprite, DeadCropTextures.Kind kind) {
        return sprite(sprites[kind.ordinal()], sprite, kind);
    }

    @SuppressWarnings("unchecked")
    private static final Map<TextureAtlasSprite, TextureAtlasSprite>[] ICONS = new Map[DeadCropTextures.Kind.values().length];

    static {
        for (int i = 0; i < ICONS.length; i++) ICONS[i] = new ConcurrentHashMap<>();
    }

    /** The dead look of a crop's main texture, for a dead block state (Jade's icon); null if the atlas has none. */
    public static @Nullable TextureAtlasSprite deadIcon(BlockState crop, BlockState dead) {
        DeadCropTextures.Kind kind = kind(dead);
        TextureAtlasSprite sprite = model(crop).getParticleIcon(ModelData.EMPTY);
        TextureAtlasSprite icon = sprite(ICONS[kind.ordinal()], sprite, kind);
        return icon == sprite ? null : icon;
    }

    /** Clears the icon sprites (the atlas changed). */
    public static void clearIcons() {
        for (Map<TextureAtlasSprite, TextureAtlasSprite> icons : ICONS) icons.clear();
    }

    private static DeadCropTextures.Kind kind(BlockState state) {
        return state.getValue(DeadCropBlock.CAUSE) == DeadCropBlock.Cause.HEAT ? DeadCropTextures.Kind.HEAT
                : state.hasProperty(DeadCropBlock.SNOWY) && state.getValue(DeadCropBlock.SNOWY) ? DeadCropTextures.Kind.FROST_SNOW : DeadCropTextures.Kind.FROST;
    }

    /** The kind's sprite for a crop sprite (cached in cache), or the same sprite if there is none. */
    static TextureAtlasSprite sprite(Map<TextureAtlasSprite, TextureAtlasSprite> cache, TextureAtlasSprite sprite, DeadCropTextures.Kind kind) {
        return cache.computeIfAbsent(sprite, s -> {
            TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS);
            TextureAtlasSprite dead = atlas.getSprite(kind.sprite(s.contents().name()));
            return dead.contents().name().equals(MissingTextureAtlasSprite.getLocation()) ? s : dead;
        });
    }

    /** Same quad, its UVs moved to the new sprite's place in the atlas; tinted only if keepTint. */
    static BakedQuad retexture(BakedQuad quad, TextureAtlasSprite to, boolean keepTint) {
        TextureAtlasSprite from = quad.getSprite();
        if (to == from) return quad;
        int[] vertices = quad.getVertices().clone();
        int stride = vertices.length / 4;
        for (int i = 0; i < 4; i++) {
            int uv = i * stride + 4; // block vertex format: position (3), color (1), then UV
            float u = (Float.intBitsToFloat(vertices[uv]) - from.getU0()) / (from.getU1() - from.getU0());
            float v = (Float.intBitsToFloat(vertices[uv + 1]) - from.getV0()) / (from.getV1() - from.getV0());
            vertices[uv] = Float.floatToRawIntBits(to.getU0() + u * (to.getU1() - to.getU0()));
            vertices[uv + 1] = Float.floatToRawIntBits(to.getV0() + v * (to.getV1() - to.getV0()));
        }
        return new BakedQuad(vertices, keepTint ? quad.getTintIndex() : -1, quad.getDirection(), to, quad.isShade(), quad.hasAmbientOcclusion());
    }

    @Override
    public @NotNull ChunkRenderTypeSet getRenderTypes(@NotNull BlockState state, @NotNull RandomSource rand, @NotNull ModelData data) {
        ChunkRenderTypeSet types = plantTypes(state, rand, data);
        if (!iced(state)) return types;
        BlockState ice = Blocks.ICE.defaultBlockState();
        return ChunkRenderTypeSet.union(types, model(ice).getRenderTypes(ice, rand, ModelData.EMPTY));
    }

    private ChunkRenderTypeSet plantTypes(BlockState state, RandomSource rand, ModelData data) {
        BlockState crop = data.get(DeadCropBlockEntity.ORIGINAL);
        return crop == null ? super.getRenderTypes(state, rand, data) : model(crop).getRenderTypes(crop, rand, ModelData.EMPTY);
    }

    private static boolean iced(BlockState state) {
        return state.hasProperty(DeadCropBlock.ICED) && state.getValue(DeadCropBlock.ICED);
    }

    @Override
    public @NotNull TextureAtlasSprite getParticleIcon(@NotNull ModelData data) {
        BlockState crop = data.get(DeadCropBlockEntity.ORIGINAL);
        DeadCropTextures.Kind kind = data.get(KIND);
        if (crop == null || kind == null) return super.getParticleIcon(data);
        return deadSprite(model(crop).getParticleIcon(ModelData.EMPTY), kind);
    }
}
