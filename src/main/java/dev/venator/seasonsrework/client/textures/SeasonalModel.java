package dev.venator.seasonsrework.client.textures;

import dev.venator.seasonsrework.client.ClientConfig;
import dev.venator.seasonsrework.client.Outside;
import dev.venator.seasonsrework.crops.CropWarmth;
import dev.venator.seasonsrework.crops.SnowyCrops;
import dev.venator.seasonsrework.client.colors.ColorClock;
import dev.venator.seasonsrework.snow.ClientSnow;
import dev.venator.seasonsrework.snow.SnowRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A block model whose textures follow the season: picks the texture set for the block's position while the
 * chunk is meshed, then swaps the sprites on the quads. Items and anything without a position keep the original.
 */
public final class SeasonalModel extends BakedModelWrapper<BakedModel> {
    static final ModelProperty<SeasonalTextures.Variant> VARIANT = new ModelProperty<>();

    private final SeasonalTextures.Rule[] rules;

    SeasonalModel(BakedModel original, SeasonalTextures.Rule[] rules) {
        super(original);
        this.rules = rules;
    }

    /** Set while Snow! Real Magic draws a plant inside its snow layer (that path skips the plant's own model data). */
    public static final ThreadLocal<InSnow> IN_SNOW = new ThreadLocal<>();

    public record InSnow(BlockAndTintGetter region, BlockPos pos) {}

    @Override
    public @NotNull ModelData getModelData(@NotNull BlockAndTintGetter region, @NotNull BlockPos pos, @NotNull BlockState state, @NotNull ModelData modelData) {
        modelData = super.getModelData(region, pos, state, modelData);
        SeasonalTextures.Variant variant = pick(region, pos, state, false);
        if (variant == null) return modelData;
        return modelData == ModelData.EMPTY ? variant.data : modelData.derive().with(VARIANT, variant).build();
    }

    /** The texture set for a position, or null to keep the original. inSnow: drawn inside a snow layer. */
    private @Nullable SeasonalTextures.Variant pick(BlockAndTintGetter region, BlockPos pos, @Nullable BlockState state, boolean inSnow) {
        ClientLevel level = Minecraft.getInstance().level;
        if (!ColorClock.active || level == null || !ClientConfig.SEASONAL_TEXTURES.get()) return null;
        int term = ColorClock.step;
        Holder<Biome> biome = null;
        for (SeasonalTextures.Rule rule : rules) {
            SeasonalTextures.Variant[] variants = rule.byTerm()[term];
            SeasonalTextures.Variant[] snowVariants = rule.snowByTerm()[term];
            boolean hasNormal = variants != null && variants.length > 0, hasSnow = snowVariants != null && snowVariants.length > 0;
            if (!hasNormal && !hasSnow) continue;
            // The chunk region has no biomes, so read the level (as the biome color code does).
            if (biome == null) biome = level.getNoiseBiome(QuartPos.fromBlock(pos.getX()), QuartPos.fromBlock(pos.getY()), QuartPos.fromBlock(pos.getZ()));
            if (!rule.biomes().test(biome)) continue;
            if (hasSnow && snowy(rule, region, level, biome, pos, state, inSnow)) variants = snowVariants;
            else if (!hasNormal) continue;
            SeasonalTextures.Variant variant = variants.length == 1 ? variants[0] : variants[Math.floorMod(Mth.getSeed(pos), variants.length)];
            return variant.remap.isEmpty() ? null : variant;
        }
        return null;
    }

    private static boolean snowy(SeasonalTextures.Rule rule, BlockAndTintGetter region, ClientLevel level, Holder<Biome> biome, BlockPos pos,
                                 @Nullable BlockState state, boolean inSnow) {
        // Farmland crops carry their snow in the block state (set and melted by the server, SnowyCrops).
        if (state != null && state.hasProperty(SnowyCrops.SNOWY) && SnowyCrops.is(state.getBlock())) return state.getValue(SnowyCrops.SNOWY);
        if (rule.snowMode() == SeasonalTextures.SnowMode.SNOW_LAYER) return inSnow || upperHalfInSnow(region, pos, state);
        if (rule.snowMode() == SeasonalTextures.SnowMode.SNOW_ABOVE) return isSnow(region.getBlockState(pos.above()));
        if (!SnowRules.lies(biome, pos, ClientSnow.warmest())) return false;
        if (CropWarmth.heated(level, pos)) return false; // a campfire, lit furnace... melts it
        // Not under a roof (glass included; sky light passes through glass). SKY: not under leaves either.
        if (!Outside.outside(level, pos)) return false;
        return rule.snowMode() != SeasonalTextures.SnowMode.SKY || region.getBrightness(LightLayer.SKY, pos) >= 15;
    }

    /** Snow! Real Magic only takes in the bottom half of a tall plant; the top half stays a plain block above it. */
    private static boolean upperHalfInSnow(BlockAndTintGetter region, BlockPos pos, @Nullable BlockState state) {
        return state != null && state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
                && isSnow(region.getBlockState(pos.below()));
    }

    /** A snow layer, or any Snow! Real Magic block (snow holding a plant, snowy fences, slabs...). */
    private static boolean isSnow(BlockState state) {
        return state.getBlock() instanceof SnowLayerBlock || BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("snowrealmagic");
    }

    @Override
    public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand, @NotNull ModelData data, @Nullable RenderType renderType) {
        List<BakedQuad> quads = super.getQuads(state, side, rand, data, renderType);
        SeasonalTextures.Variant variant = data.get(VARIANT);
        InSnow inSnow = IN_SNOW.get();
        if (variant == null && inSnow != null) variant = pick(inSnow.region(), inSnow.pos(), state, true);
        if (variant == null || quads.isEmpty()) return quads;
        List<BakedQuad> swapped = new ArrayList<>(quads.size());
        for (BakedQuad quad : quads) swapped.add(variant.swap(quad));
        return swapped;
    }
}
