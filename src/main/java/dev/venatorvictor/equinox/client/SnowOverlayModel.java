package dev.venatorvictor.equinox.client;

import dev.venatorvictor.equinox.client.colors.ColorClock;
import dev.venatorvictor.equinox.client.textures.SeasonalTextures;
import dev.venatorvictor.equinox.climate.Climate;
import dev.venatorvictor.equinox.crops.CropWarmth;
import dev.venatorvictor.equinox.snow.ClientSnow;
import dev.venatorvictor.equinox.snow.SnowMap;
import dev.venatorvictor.equinox.snow.SnowRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChainBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Snow drawn over blocks where snow lies (fences, gates, walls, stairs, slabs, chains; not the snowy grass block,
 * whose own snowy side already shows it):
 * the block's own faces again, a hair in front, with one shared see-through texture: a snow cap on side faces (the
 * face's own texture coordinates, so it sits at the top of each face), vanilla's snow on top faces. Any block of
 * those kinds works (modded ones too) with any texture pack, as the block's textures are never replaced.
 * Only where real snow lies: a snow layer on the block, or snow kept in a fence or wall (SnowMap).
 * Not leaves: they only show the real snow layer lying on them (and their own snowy textures under it).
 */
public final class SnowOverlayModel extends BakedModelWrapper<BakedModel> {
    /** Look step 1..4. */
    private static final ModelProperty<Integer> SNOW = new ModelProperty<>();
    private static final ChunkRenderTypeSet CUTOUT = ChunkRenderTypeSet.of(RenderType.cutout());
    private static final ResourceLocation CAP = ResourceLocation.fromNamespaceAndPath("equinox", "block/snow_cap");
    private static final ResourceLocation SNOW_TOP = ResourceLocation.withDefaultNamespace("block/snow");
    /** Distance in front of the face (blocks). */
    private static final float LIFT = 0.0015F;

    private enum Kind { FULL, HALF }

    private final Kind kind;
    /** Overlay quads per (base quad, step), made once. */
    private final Map<BakedQuad, Optional<BakedQuad>[]> made = new ConcurrentHashMap<>();
    private static TextureAtlasSprite cap, snowTop;

    private SnowOverlayModel(BakedModel own, Kind kind) {
        super(own);
        this.kind = kind;
    }

    private static @Nullable Kind kind(Block block) {
        if (block instanceof StairBlock || block instanceof SlabBlock) return Kind.HALF;
        if (block instanceof FenceBlock || block instanceof FenceGateBlock || block instanceof WallBlock || block instanceof ChainBlock) return Kind.FULL;
        return null;
    }

    /** Wraps those blocks' models (outermost: after the seasonal textures and the fence snow). */
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        cap = snowTop = null;
        for (Block block : BuiltInRegistries.BLOCK) {
            Kind kind = kind(block);
            if (kind == null) continue;
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                ModelResourceLocation location = BlockModelShaper.stateToModelLocation(state);
                BakedModel model = event.getModels().get(location);
                if (model != null && !(model instanceof SnowOverlayModel)) event.getModels().put(location, new SnowOverlayModel(model, kind));
            }
        }
        // Snow changes redraw chunks only when something shows snow.
        SeasonalTextures.anySnow = true;
    }

    private static TextureAtlasSprite sprite(ResourceLocation id) {
        return Minecraft.getInstance().getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS).getSprite(id);
    }

    @Override
    public @NotNull ModelData getModelData(@NotNull BlockAndTintGetter region, @NotNull BlockPos pos, @NotNull BlockState state, @NotNull ModelData data) {
        data = super.getModelData(region, pos, state, data);
        int step = step(region, pos, state);
        if (step == 0) return data;
        return data.derive().with(SNOW, step).build();
    }

    /** How snowy the block looks: 4 with real snow (a snow layer on it, or snow kept in a fence or wall), else 0. */
    private int step(BlockAndTintGetter region, BlockPos pos, BlockState state) {
        BlockState above = region.getBlockState(pos.above());
        if (above.getBlock() instanceof SnowLayerBlock || SnowMap.holds(state) && SnowMap.client(pos) > 0) return 4;
        return 0; // only real snow: nothing drawn where snow would lie but doesn't
    }

    @Override
    public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand, @NotNull ModelData data, @Nullable RenderType renderType) {
        Integer step = data.get(SNOW);
        if (step == null || state == null) return super.getQuads(state, side, rand, data, renderType);
        ChunkRenderTypeSet ownTypes = super.getRenderTypes(state, rand, data);
        List<BakedQuad> quads = renderType == null || ownTypes.contains(renderType)
                ? new ArrayList<>(super.getQuads(state, side, rand, data, renderType)) : new ArrayList<>();
        if (renderType != null && renderType != RenderType.cutout()) return quads;
        if (cap == null) {
            cap = sprite(CAP);
            snowTop = sprite(SNOW_TOP);
        }
        // The snow goes over the block's faces of every render type it has.
        for (RenderType type : ownTypes) {
            for (BakedQuad quad : super.getQuads(state, side, rand, data, type)) {
                if (quad.getSprite() == snowTop) continue; // the fence's own snow layer
                BakedQuad snow = overlay(quad, step);
                if (snow != null) quads.add(snow);
            }
        }
        return quads;
    }

    @Override
    public @NotNull ChunkRenderTypeSet getRenderTypes(@NotNull BlockState state, @NotNull RandomSource rand, @NotNull ModelData data) {
        ChunkRenderTypeSet own = super.getRenderTypes(state, rand, data);
        return data.get(SNOW) == null ? own : ChunkRenderTypeSet.union(own, CUTOUT);
    }

    @SuppressWarnings("unchecked")
    private @Nullable BakedQuad overlay(BakedQuad quad, int step) {
        Optional<BakedQuad>[] steps = made.computeIfAbsent(quad, q -> new Optional[4]);
        Optional<BakedQuad> snow = steps[step - 1];
        if (snow == null) steps[step - 1] = snow = Optional.ofNullable(make(quad, step));
        return snow.orElse(null);
    }

    /** The snow quad over a face: side faces their top part (step/4 of it) with the cap, top faces snow, bottom none. */
    private @Nullable BakedQuad make(BakedQuad quad, int step) {
        int[] v = quad.getVertices().clone();
        int stride = v.length / 4;
        float[] x = new float[4], y = new float[4], z = new float[4];
        for (int i = 0; i < 4; i++) {
            x[i] = Float.intBitsToFloat(v[i * stride]);
            y[i] = Float.intBitsToFloat(v[i * stride + 1]);
            z[i] = Float.intBitsToFloat(v[i * stride + 2]);
        }
        float ax = x[1] - x[0], ay = y[1] - y[0], az = z[1] - z[0];
        float bx = x[2] - x[0], by = y[2] - y[0], bz = z[2] - z[0];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1e-6F) return null;
        nx /= length;
        ny /= length;
        nz /= length;
        if (ny < -0.7F) return null;
        boolean top = ny >= 0.7F;
        // Stairs and slabs: only the faces at the top of the block, under the snow lying on it (not the lower step,
        // not a bottom slab's top halfway up the block).
        if (kind == Kind.HALF && Math.max(Math.max(y[0], y[1]), Math.max(y[2], y[3])) < 0.999F) return null;
        // One cap for every side face: only faces reaching the top of the block get snow, so the cap sits at the
        // top of the texture (the half cap, with a second cap halfway down, drew a snow band across full-height faces).
        TextureAtlasSprite to = top ? snowTop : cap;
        retexture(v, stride, quad.getSprite(), to);
        if (!top && step < 4) {
            // Keep the face's top part: each lower corner moves up its edge to the cut.
            float hi = Math.max(Math.max(y[0], y[1]), Math.max(y[2], y[3]));
            float lo = Math.min(Math.min(y[0], y[1]), Math.min(y[2], y[3]));
            if (hi - lo < 1e-4F) return null;
            float cut = hi - (hi - lo) * step / 4F;
            int[] source = v.clone();
            for (int i = 0; i < 4; i++) {
                if (y[i] >= cut - 1e-5F) continue;
                int prev = (i + 3) % 4, next = (i + 1) % 4;
                int partner = y[prev] - y[i] > y[next] - y[i] ? prev : next;
                if (y[partner] - y[i] < 1e-5F) continue;
                float t = (cut - y[i]) / (y[partner] - y[i]);
                for (int k : new int[]{0, 1, 2, 4, 5}) {
                    float a = Float.intBitsToFloat(source[i * stride + k]), b = Float.intBitsToFloat(source[partner * stride + k]);
                    v[i * stride + k] = Float.floatToRawIntBits(a + (b - a) * t);
                }
            }
        }
        for (int i = 0; i < 4; i++) {
            v[i * stride] = Float.floatToRawIntBits(Float.intBitsToFloat(v[i * stride]) + nx * LIFT);
            v[i * stride + 1] = Float.floatToRawIntBits(Float.intBitsToFloat(v[i * stride + 1]) + ny * LIFT);
            v[i * stride + 2] = Float.floatToRawIntBits(Float.intBitsToFloat(v[i * stride + 2]) + nz * LIFT);
        }
        return new BakedQuad(v, -1, quad.getDirection(), to, quad.isShade(), quad.hasAmbientOcclusion());
    }

    /** UVs moved from one sprite's place in the atlas to another's (same place inside the texture). */
    private static void retexture(int[] v, int stride, TextureAtlasSprite from, TextureAtlasSprite to) {
        for (int i = 0; i < 4; i++) {
            int uv = i * stride + 4;
            float u = (Float.intBitsToFloat(v[uv]) - from.getU0()) / (from.getU1() - from.getU0());
            float w = (Float.intBitsToFloat(v[uv + 1]) - from.getV0()) / (from.getV1() - from.getV0());
            v[uv] = Float.floatToRawIntBits(to.getU0() + u * (to.getU1() - to.getU0()));
            v[uv + 1] = Float.floatToRawIntBits(to.getV0() + w * (to.getV1() - to.getV0()));
        }
    }

}
