package dev.venatorvictor.equinox.client;

import dev.venatorvictor.equinox.crops.Crops;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
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
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.state.BlockState;
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
 * Living farmland crops that don't grow show it (and the pumpkin or melon an attached stem holds, like its stem): dormant (cold) or wilted (heat) textures from DeadCropTextures, in 4
 * strengths (a hint while growing slowly near a limit, then stronger past it; Crops.look). Worked out on the client
 * from the same °C as the server, when the chunk section is meshed. Sections holding crops are redrawn every
 * REFRESH_TICKS, and each crop's look moves at most one strength per redraw toward its current one, both ways, so it
 * fades in and recovers gradually (a heater or a warm day helps growth at once; the look follows over half a day).
 */
public final class CropLookModel extends BakedModelWrapper<BakedModel> {
    private static final ModelProperty<DeadCropTextures.Kind> KIND = new ModelProperty<>();
    /** Ticks between redraws of the crop sections (the daily temperature drifts slowly). */
    private static final long REFRESH_TICKS = 3000;
    private static final LongOpenHashSet SECTIONS = new LongOpenHashSet();
    /** Per crop position: the look shown (Crops.look) and the redraw it was set in, packed by pack(). */
    private static final Long2LongOpenHashMap SHOWN = new Long2LongOpenHashMap();
    private static volatile long lastRefresh = Long.MIN_VALUE;

    @SuppressWarnings("unchecked")
    private final Map<TextureAtlasSprite, TextureAtlasSprite>[] sprites = new Map[DeadCropTextures.Kind.values().length];
    @SuppressWarnings("unchecked")
    private final Map<BakedQuad, BakedQuad>[] quads = new Map[DeadCropTextures.Kind.values().length];

    private CropLookModel(BakedModel original) {
        super(original);
        for (int i = 0; i < sprites.length; i++) {
            sprites[i] = new ConcurrentHashMap<>();
            quads[i] = new ConcurrentHashMap<>();
        }
    }

    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        java.util.Set<Block> fruits = Crops.fruits();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (!Crops.mortal(block) && !fruits.contains(block) && !(block instanceof SugarCaneBlock)) continue;
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                ModelResourceLocation location = BlockModelShaper.stateToModelLocation(state);
                BakedModel model = event.getModels().get(location);
                if (model != null && !(model instanceof CropLookModel)) event.getModels().put(location, new CropLookModel(model));
            }
        }
    }

    @Override
    public @NotNull ModelData getModelData(@NotNull BlockAndTintGetter region, @NotNull BlockPos pos, @NotNull BlockState state, @NotNull ModelData data) {
        data = super.getModelData(region, pos, state, data);
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return data;
        synchronized (SECTIONS) {
            SECTIONS.add(SectionPos.asLong(pos));
        }
        if (!ClientConfig.CROP_LOOKS.get()) return data;
        int look;
        if (state.getBlock() instanceof SugarCaneBlock) look = Crops.caneLook(region, pos); // by its water only
        else if (Crops.mortal(state.getBlock())) look = Crops.look(level, pos, state);
        else { // a pumpkin or melon: its stem's look, if a stem holds it
            BlockPos stem = Crops.stemOf(level, pos, state.getBlock());
            look = stem == null ? 0 : Crops.look(level, stem, level.getBlockState(stem));
        }
        DeadCropTextures.Kind kind = DeadCropTextures.Kind.ofLook(shown(pos.asLong(), look));
        return kind == null ? data : data.derive().with(KIND, kind).build();
    }

    @Override
    public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand, @NotNull ModelData data, @Nullable RenderType renderType) {
        List<BakedQuad> original = super.getQuads(state, side, rand, data, renderType);
        DeadCropTextures.Kind kind = data.get(KIND);
        if (kind == null || original.isEmpty()) return original;
        List<BakedQuad> swapped = new ArrayList<>(original.size());
        Map<BakedQuad, BakedQuad> cache = quads[kind.ordinal()];
        Map<TextureAtlasSprite, TextureAtlasSprite> spriteCache = sprites[kind.ordinal()];
        for (BakedQuad quad : original)
            swapped.add(cache.computeIfAbsent(quad, q -> retexture(q, sprite(spriteCache, q.getSprite(), kind), true)));
        return swapped;
    }

    /** The look to show: the target the first time a crop is seen, else one step from the last one per redraw. */
    private static int shown(long pos, int target) {
        long refresh = lastRefresh;
        synchronized (SHOWN) {
            if (!SHOWN.containsKey(pos)) {
                SHOWN.put(pos, pack(target, refresh));
                return target;
            }
            long entry = SHOWN.get(pos);
            int last = (int) (entry & 0xFF) - 8;
            if (entry >> 8 == refresh) return last;
            int look = last + Integer.signum(target - last);
            SHOWN.put(pos, pack(look, refresh));
            return look;
        }
    }

    private static long pack(int look, long refresh) {
        return refresh << 8 | (look + 8);
    }

    /** Client tick: every REFRESH_TICKS of day time, redraw the sections with crops (they re-register when meshed). */
    public static void tick(ClientLevel level) {
        long now = level.getDayTime() / REFRESH_TICKS;
        if (now == lastRefresh) return;
        lastRefresh = now;
        synchronized (SHOWN) { // forget crops not drawn for two redraws (unloaded, out of view, broken)
            SHOWN.values().removeIf(entry -> entry >> 8 < now - 2);
        }
        long[] sections;
        synchronized (SECTIONS) {
            sections = SECTIONS.toLongArray();
            SECTIONS.clear();
        }
        Minecraft mc = Minecraft.getInstance();
        for (long section : sections)
            mc.levelRenderer.setSectionDirty(SectionPos.x(section), SectionPos.y(section), SectionPos.z(section));
    }

    public static void reset() {
        synchronized (SECTIONS) {
            SECTIONS.clear();
        }
        synchronized (SHOWN) {
            SHOWN.clear();
        }
        lastRefresh = Long.MIN_VALUE;
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
}
