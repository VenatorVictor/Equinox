package dev.venator.seasonsrework.client.textures;

import dev.venator.seasonsrework.client.ClientConfig;
import dev.venator.seasonsrework.climate.Climate;
import dev.venator.seasonsrework.client.Outside;
import dev.venator.seasonsrework.crops.CropWarmth;
import dev.venator.seasonsrework.crops.SnowyCrops;
import dev.venator.seasonsrework.client.colors.ColorClock;
import dev.venator.seasonsrework.snow.ClientSnow;
import dev.venator.seasonsrework.snow.SnowRules;
import dev.venator.seasonsrework.snow.SnowyPlants;
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
    static final ModelProperty<SeasonalTextures.Overlay> OVERLAY = new ModelProperty<>();

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
        Pick pick = pick(region, pos, state, false);
        if (pick == null) return modelData;
        if (pick.overlay == null && modelData == ModelData.EMPTY) return pick.variant.data;
        ModelData.Builder builder = modelData.derive();
        if (pick.variant != null) builder.with(VARIANT, pick.variant);
        if (pick.overlay != null) builder.with(OVERLAY, pick.overlay);
        return builder.build();
    }

    /** What a position shows: a texture set (null: the original), and snow drawn over it for the steps between. */
    private record Pick(@Nullable SeasonalTextures.Variant variant, @Nullable SeasonalTextures.Overlay overlay) {}

    /** The texture set for a position, or null to keep the original. inSnow: drawn inside a snow layer. */
    private @Nullable Pick pick(BlockAndTintGetter region, BlockPos pos, @Nullable BlockState state, boolean inSnow) {
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
            int step = hasSnow ? snowStep(rule, region, level, biome, pos, state, inSnow) : 0;
            if (rule.full() && step > 0) step = 4;
            SeasonalTextures.Variant normal = hasNormal ? choose(variants, pos) : null;
            if (step >= 4) return pick(choose(snowVariants, pos), null);
            if (step > 0) {
                SeasonalTextures.Variant snow = choose(snowVariants, pos);
                return pick(normal, snow == null ? null : snow.overlay(step));
            }
            if (!hasNormal) continue;
            return pick(normal, null);
        }
        return null;
    }

    private static @Nullable Pick pick(@Nullable SeasonalTextures.Variant variant, @Nullable SeasonalTextures.Overlay overlay) {
        if (variant != null && variant.isEmpty()) variant = null;
        return variant == null && overlay == null ? null : new Pick(variant, overlay);
    }

    private static @Nullable SeasonalTextures.Variant choose(SeasonalTextures.Variant[] variants, BlockPos pos) {
        SeasonalTextures.Variant variant = variants.length == 1 ? variants[0] : variants[Math.floorMod(Mth.getSeed(pos), variants.length)];
        return variant.isEmpty() ? null : variant;
    }

    /** How snowy the position looks, 0 (not) .. 4 (the snowy texture), per the client's texture_mode. */
    private static int snowStep(SeasonalTextures.Rule rule, BlockAndTintGetter region, ClientLevel level, Holder<Biome> biome, BlockPos pos,
                                @Nullable BlockState state, boolean inSnow) {
        float here = Climate.temperature(biome.value(), pos);
        boolean crop = state != null && state.hasProperty(SnowyCrops.SNOWY) && SnowyCrops.is(state.getBlock());
        if (ClientConfig.SNOW_LOOK.get() == ClientConfig.SnowLook.CALENDAR) {
            if (CropWarmth.heated(level, pos) || !Outside.outside(level, pos)) return 0;
            if (rule.snowMode() == SeasonalTextures.SnowMode.SKY && region.getBrightness(LightLayer.SKY, pos) < 15) return 0;
            return SnowRules.calendarStep(here);
        }
        // GROUND: snow has to be there. How much it shows follows how deep into the cold the column is.
        int ground = Math.max(1, SnowRules.groundStep(biome, pos, ClientSnow.warmest(), here));
        // Farmland crops carry their snow in the block state (it comes with the snowfall, SnowyCrops).
        if (crop) return state.getValue(SnowyCrops.SNOWY) ? ground : 0;
        if (rule.snowMode() == SeasonalTextures.SnowMode.SNOW_LAYER) {
            if (inSnow) return 4;
            int layers = state == null ? 0 : SnowyPlants.layers(state);
            if (layers == 0 && upperHalfInSnow(region, pos, state)) layers = SnowyPlants.layers(region.getBlockState(pos.below())) + 1;
            return layers == 0 ? 0 : Math.max(ground, Math.min(4, layers));
        }
        if (rule.snowMode() == SeasonalTextures.SnowMode.SNOW_ABOVE) {
            BlockState above = region.getBlockState(pos.above());
            if (!isSnow(above)) return 0;
            int layers = above.getBlock() instanceof SnowLayerBlock ? above.getValue(SnowLayerBlock.LAYERS) : 4;
            return Math.max(ground, Math.min(4, layers));
        }
        if (!SnowRules.lies(biome, pos, ClientSnow.warmest(), here)) return 0;
        if (CropWarmth.heated(level, pos)) return 0; // a campfire, lit furnace... melts it
        // Not under a roof (glass included; sky light passes through glass). SKY: not under leaves either.
        if (!Outside.outside(level, pos)) return 0;
        if (rule.snowMode() == SeasonalTextures.SnowMode.SKY && region.getBrightness(LightLayer.SKY, pos) < 15) return 0;
        return ground;
    }

    /** Snow only goes in the bottom half of a tall plant (ours and Snow! Real Magic's); the top half looks at it. */
    private static boolean upperHalfInSnow(BlockAndTintGetter region, BlockPos pos, @Nullable BlockState state) {
        return state != null && state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
                && (isSnow(region.getBlockState(pos.below())) || SnowyPlants.layers(region.getBlockState(pos.below())) > 0);
    }

    /** A snow layer, or any Snow! Real Magic block (snow holding a plant, snowy fences, slabs...). */
    private static boolean isSnow(BlockState state) {
        return state.getBlock() instanceof SnowLayerBlock || BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("snowrealmagic");
    }

    @Override
    public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand, @NotNull ModelData data, @Nullable RenderType renderType) {
        List<BakedQuad> quads = super.getQuads(state, side, rand, data, renderType);
        if (quads.isEmpty()) return quads;
        SeasonalTextures.Variant variant = data.get(VARIANT);
        SeasonalTextures.Overlay overlay = data.get(OVERLAY);
        InSnow inSnow = IN_SNOW.get();
        if (variant == null && overlay == null && inSnow != null) {
            Pick pick = pick(inSnow.region(), inSnow.pos(), state, true);
            if (pick != null) {
                variant = pick.variant;
                overlay = pick.overlay;
            }
        }
        if (variant == null && overlay == null) return quads;
        // The snow drawn over needs see-through pixels: not in solid (cutout plants and leaves have them).
        if (renderType == RenderType.solid()) overlay = null;
        List<BakedQuad> swapped = new ArrayList<>(overlay == null ? quads.size() : quads.size() * 2);
        for (BakedQuad quad : quads) swapped.add(variant == null ? quad : variant.swap(quad));
        if (overlay != null) {
            for (BakedQuad quad : quads) {
                BakedQuad snow = overlay.quad(quad);
                if (snow != null) swapped.add(snow);
            }
        }
        return swapped;
    }
}
