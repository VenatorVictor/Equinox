package dev.venatorvictor.equinox.client.textures;

import dev.venatorvictor.equinox.client.ClientConfig;
import dev.venatorvictor.equinox.climate.Climate;
import dev.venatorvictor.equinox.client.Outside;
import dev.venatorvictor.equinox.crops.CropWarmth;
import dev.venatorvictor.equinox.crops.SnowyCrops;
import dev.venatorvictor.equinox.client.colors.ColorClock;
import dev.venatorvictor.equinox.snow.ClientSnow;
import dev.venatorvictor.equinox.snow.SnowRules;
import dev.venatorvictor.equinox.snow.SnowyPlants;
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
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
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
    /** Set on bearing leaf blocks that weren't picked this year: their fruit_textures are drawn in front. */
    static final ModelProperty<Boolean> FRUIT = new ModelProperty<>();

    private final SeasonalTextures.Rule[] rules;

    /** Every seasonal model by its model location, filled while models bake (to ask about a block outside meshing). */
    static final java.util.Map<net.minecraft.client.resources.model.ModelResourceLocation, SeasonalModel> BY_LOCATION = new java.util.concurrent.ConcurrentHashMap<>();

    /** Whether the block at pos shows one of its seasonal textures right now instead of its own (client). */
    public static boolean changed(ClientLevel level, BlockPos pos, BlockState state) {
        SeasonalModel model = BY_LOCATION.get(net.minecraft.client.renderer.block.BlockModelShaper.stateToModelLocation(state));
        if (model == null) return false;
        Pick pick = model.pick(level, pos, state, false);
        return pick != null && pick.variant != null;
    }

    SeasonalModel(BakedModel original, SeasonalTextures.Rule[] rules) {
        super(original);
        this.rules = rules;
    }

    /** Set while the snow mod draws a plant inside its snow layer (that path skips the plant's own model data). */
    public static final ThreadLocal<InSnow> IN_SNOW = new ThreadLocal<>();

    public record InSnow(BlockAndTintGetter region, BlockPos pos) {}

    @Override
    public @NotNull ModelData getModelData(@NotNull BlockAndTintGetter region, @NotNull BlockPos pos, @NotNull BlockState state, @NotNull ModelData modelData) {
        modelData = super.getModelData(region, pos, state, modelData);
        Pick pick = pick(region, pos, state, false);
        if (pick == null) return modelData;
        if (pick.overlay == null && modelData == ModelData.EMPTY) return pick.fruit ? pick.variant.fruitData : pick.variant.data;
        ModelData.Builder builder = modelData.derive();
        if (pick.variant != null) builder.with(VARIANT, pick.variant);
        if (pick.overlay != null) builder.with(OVERLAY, pick.overlay);
        if (pick.fruit) builder.with(FRUIT, true);
        return builder.build();
    }

    /** What a position shows: a texture set (null: the original), snow drawn over it for the steps between, and whether its fruit shows. */
    private record Pick(@Nullable SeasonalTextures.Variant variant, @Nullable SeasonalTextures.Overlay overlay, boolean fruit) {}

    /** The texture set for a position, or null to keep the original. inSnow: drawn inside a snow layer. */
    private @Nullable Pick pick(BlockAndTintGetter region, BlockPos pos, @Nullable BlockState state, boolean inSnow) {
        ClientLevel level = Minecraft.getInstance().level;
        if (!ColorClock.active || level == null || !ClientConfig.SEASONAL_TEXTURES.get()) return null;
        Holder<Biome> biome = null;
        float warm = Float.NaN;
        for (SeasonalTextures.Rule rule : rules) {
            int term = ColorClock.week;
            if (rule.fallWeeks() != null) {   // near warm zones the leaves fall less (blended like the colors)
                if (warm != warm) warm = dev.venatorvictor.equinox.climate.ClimateBlend.warmShare(pos.getX(), pos.getZ());
                int limit = fallLimit(rule.maxFall(), warm);
                if (limit < rule.maxFall()) term = rule.fallWeeks()[limit][term];
            }
            SeasonalTextures.Variant[] variants = rule.byTerm()[term];
            SeasonalTextures.Variant[] snowVariants = rule.snowByTerm()[term];
            boolean hasNormal = variants != null && variants.length > 0, hasSnow = snowVariants != null && snowVariants.length > 0;
            if (!hasNormal && !hasSnow) continue;
            // The chunk region has no biomes, so read the level (as the biome color code does).
            if (biome == null) biome = level.getNoiseBiome(QuartPos.fromBlock(pos.getX()), QuartPos.fromBlock(pos.getY()), QuartPos.fromBlock(pos.getZ()));
            if (!rule.biomes().test(biome)) continue;
            int step = hasSnow ? snowStep(rule, region, level, biome, pos, state, inSnow) : 0;
            SeasonalTextures.Variant normal = hasNormal ? choose(variants, pos) : null;
            boolean fruit = normal != null && normal.fruit != null && state != null
                    && dev.venatorvictor.equinox.trees.TreeFruit.showsFruit(level, state, pos);
            if (!fruit && normal != null && normal.noOver != null && !showsOver(normal, state, pos)) normal = normal.noOver;
            SeasonalTextures.Variant frontSnow = step > 0 ? choose(snowVariants, pos) : null;
            if (step >= 4 || frontSnow != null && frontSnow.front) {
                SeasonalTextures.Variant snow = choose(snowVariants, pos);
                return pick(snow, null, snow != null && snow.fruit != null && state != null
                        && dev.venatorvictor.equinox.trees.TreeFruit.showsFruit(level, state, pos));
            }
            if (step > 0) {
                SeasonalTextures.Variant snow = choose(snowVariants, pos);
                return pick(normal, snow == null ? null : snow.overlay(step), fruit);
            }
            if (!hasNormal) continue;
            return pick(normal, null, fruit);
        }
        return null;
    }

    /**
     * How far leaves may fall (0 = none, max = bare) at a warm zone share around the column (ClimateBlend.warmShare):
     * all the way with no warm zone around, less and less toward a warm zone, none where it is half of the mix (the
     * border); the warm zones' own files have no leaf fall. -1 (no mix known): no limit.
     */
    static int fallLimit(int max, float warm) {
        if (warm <= 0) return max;
        return Math.round(Math.max(0F, 1F - 2F * warm) * max);
    }

    /**
     * Whether a block without fruit shows the over_textures (catkins), so that overPercent of all blocks do: where the
     * slice has fruit, the bearing share already shows fruit instead, so the rest take a bigger share. By position.
     */
    private static boolean showsOver(SeasonalTextures.Variant variant, @Nullable BlockState state, BlockPos pos) {
        int bearing = variant.fruit != null && state != null ? dev.venatorvictor.equinox.trees.TreeFruit.percent(state) : 0;
        if (bearing >= 100) return true;
        long hash = it.unimi.dsi.fastutil.HashCommon.mix(pos.asLong() * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L);
        return Math.floorMod(hash, 10000) < variant.overPercent * 10000L / (100 - bearing);
    }

    private static @Nullable Pick pick(@Nullable SeasonalTextures.Variant variant, @Nullable SeasonalTextures.Overlay overlay, boolean fruit) {
        if (variant != null && variant.empty()) variant = null;
        return variant == null && overlay == null ? null : new Pick(variant, overlay, fruit && variant != null);
    }

    private static @Nullable SeasonalTextures.Variant choose(SeasonalTextures.Variant[] variants, BlockPos pos) {
        SeasonalTextures.Variant variant = variants.length == 1 ? variants[0] : variants[Math.floorMod(Mth.getSeed(pos), variants.length)];
        return variant.empty() ? null : variant;
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

    /** Snow only goes in the bottom half of a tall plant (ours and the snow mod's); the top half looks at it. */
    private static boolean upperHalfInSnow(BlockAndTintGetter region, BlockPos pos, @Nullable BlockState state) {
        return state != null && state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
                && (isSnow(region.getBlockState(pos.below())) || SnowyPlants.layers(region.getBlockState(pos.below())) > 0);
    }

    /** A snow layer, or any of the snow mod's blocks (snow holding a plant, snowy fences, slabs...). */
    private static boolean isSnow(BlockState state) {
        return state.getBlock() instanceof SnowLayerBlock || BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("snowrealmagic");
    }

    /** The see-through layer flowers and fruit go into when the block itself is solid (leaves on Fast graphics). */
    private static final RenderType FRONT_LAYER = RenderType.cutoutMipped();

    /** Whether the block draws solid only (no see-through layer of its own), so its flowers and fruit need FRONT_LAYER. */
    private boolean solidOnly(@Nullable BlockState state, RandomSource rand, ModelData data) {
        if (state == null) return false;
        ChunkRenderTypeSet own = super.getRenderTypes(state, rand, data);
        return own.contains(RenderType.solid()) && !own.contains(FRONT_LAYER);
    }

    /** Whether the front layer is drawn on the side faces only: snow in front, and flowers / fruit on leaves. */
    private static boolean sidesOnly(SeasonalTextures.Variant variant, @Nullable BlockState state) {
        return variant.front || state != null && state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock;
    }

    private List<BakedQuad> frontOnly(@Nullable BlockState state, @Nullable Direction side, RandomSource rand, ModelData data) {
        SeasonalTextures.Variant variant = data.get(VARIANT);
        if (variant == null) return List.of();
        SeasonalTextures.Variant front = Boolean.TRUE.equals(data.get(FRUIT)) && variant.fruit != null ? variant.fruit : variant.over;
        if (front == null) return List.of();
        List<BakedQuad> out = new ArrayList<>();
        for (BakedQuad quad : super.getQuads(state, side, rand, data, RenderType.solid())) {
            if (sidesOnly(variant, state) && quad.getDirection().getAxis().isVertical()) continue; // snow, leaf flowers/fruit: side faces only
            BakedQuad lifted = front.inFront(quad);
            if (lifted != null) out.add(lifted);
        }
        return out;
    }

    @Override
    public @NotNull ChunkRenderTypeSet getRenderTypes(@NotNull BlockState state, @NotNull RandomSource rand, @NotNull ModelData data) {
        ChunkRenderTypeSet own = super.getRenderTypes(state, rand, data);
        SeasonalTextures.Variant variant = data.get(VARIANT);
        if (variant == null || (variant.over == null && variant.fruit == null) || !solidOnly(state, rand, data)) return own;
        return ChunkRenderTypeSet.union(own, ChunkRenderTypeSet.of(FRONT_LAYER));
    }

    @Override
    public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand, @NotNull ModelData data, @Nullable RenderType renderType) {
        // Leaves on Fast graphics: the block is drawn solid; its flowers and fruit go alone into the see-through layer.
        if (renderType == FRONT_LAYER && solidOnly(state, rand, data)) return frontOnly(state, side, rand, data);
        List<BakedQuad> quads = super.getQuads(state, side, rand, data, renderType);
        if (quads.isEmpty()) return quads;
        SeasonalTextures.Variant variant = data.get(VARIANT);
        SeasonalTextures.Overlay overlay = data.get(OVERLAY);
        boolean fruit = Boolean.TRUE.equals(data.get(FRUIT));
        InSnow inSnow = IN_SNOW.get();
        if (variant == null && overlay == null && inSnow != null) {
            Pick pick = pick(inSnow.region(), inSnow.pos(), state, true);
            if (pick != null) {
                variant = pick.variant;
                overlay = pick.overlay;
                fruit = pick.fruit;
            }
        }
        if (variant == null && overlay == null) return quads;
        // The snow drawn over needs see-through pixels: not in solid (cutout plants and leaves have them).
        if (renderType == RenderType.solid()) overlay = null;
        List<BakedQuad> swapped = new ArrayList<>(overlay == null ? quads.size() : quads.size() * 2);
        for (BakedQuad quad : quads) swapped.add(variant == null ? quad : variant.swap(quad));
        if (variant != null && variant.under != null) {
            for (BakedQuad quad : quads) {
                BakedQuad behind = variant.behind(quad);
                if (behind != null) swapped.add(behind);
            }
        }
        // Flowers and fruit in front: need see-through pixels too.
        SeasonalTextures.Variant front = variant == null || renderType == RenderType.solid() ? null
                : fruit && variant.fruit != null ? variant.fruit : variant.over;
        if (front != null) {
            for (BakedQuad quad : quads) {
                // Snow in front (snow_over_textures): only on the side faces, as the snow layer lies on the top; flowers
                // and fruit on leaves too (on the top and bottom they looked pasted on).
                if (sidesOnly(variant, state) && quad.getDirection().getAxis().isVertical()) continue;
                BakedQuad lifted = front.inFront(quad);
                if (lifted != null) swapped.add(lifted);
            }
        }
        if (overlay != null) {
            for (BakedQuad quad : quads) {
                BakedQuad snow = overlay.quad(quad);
                if (snow != null) swapped.add(snow);
            }
        }
        return swapped;
    }
}
