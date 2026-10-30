package dev.venator.seasonsrework.client;

import dev.venator.seasonsrework.crops.Crops;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
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
 * Living farmland crops that don't grow show it: dormant (cold) or wilted (heat) textures from DeadCropTextures, in 4
 * strengths (a hint while growing slowly near a limit, then stronger past it; Crops.look). Worked out on the client
 * from the same °C as the server, when the
 * chunk section is meshed. Sections holding crops are remembered and redrawn a few times a day (refresh), and around
 * a heater or cooler when it's placed, broken, lit or put out.
 */
public final class CropLookModel extends BakedModelWrapper<BakedModel> {
    private static final ModelProperty<DeadCropTextures.Kind> KIND = new ModelProperty<>();
    /** Ticks between redraws of the crop sections (the daily temperature drifts slowly). */
    private static final long REFRESH_TICKS = 3000;
    private static final LongOpenHashSet SECTIONS = new LongOpenHashSet();
    private static long lastRefresh = Long.MIN_VALUE;

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
        for (Block block : BuiltInRegistries.BLOCK) {
            if (!Crops.mortal(block)) continue;
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
        DeadCropTextures.Kind kind = DeadCropTextures.Kind.ofLook(Crops.look(level, pos, state));
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
            swapped.add(cache.computeIfAbsent(quad, q -> DeadCropModel.retexture(q, DeadCropModel.sprite(spriteCache, q.getSprite(), kind), true)));
        return swapped;
    }

    /** Client tick: every REFRESH_TICKS of day time, redraw the sections with crops (they re-register when meshed). */
    public static void tick(ClientLevel level) {
        long now = level.getDayTime() / REFRESH_TICKS;
        if (now == lastRefresh) return;
        lastRefresh = now;
        long[] sections;
        synchronized (SECTIONS) {
            sections = SECTIONS.toLongArray();
            SECTIONS.clear();
        }
        Minecraft mc = Minecraft.getInstance();
        for (long section : sections)
            mc.levelRenderer.setSectionDirty(SectionPos.x(section), SectionPos.y(section), SectionPos.z(section));
    }

    /** A heater or cooler changed at pos: redraw the crop sections within its radius. */
    public static void heaterChanged(BlockPos pos, int radius) {
        Minecraft mc = Minecraft.getInstance();
        int minX = SectionPos.blockToSectionCoord(pos.getX() - radius), maxX = SectionPos.blockToSectionCoord(pos.getX() + radius);
        int minY = SectionPos.blockToSectionCoord(pos.getY() - radius), maxY = SectionPos.blockToSectionCoord(pos.getY() + radius);
        int minZ = SectionPos.blockToSectionCoord(pos.getZ() - radius), maxZ = SectionPos.blockToSectionCoord(pos.getZ() + radius);
        synchronized (SECTIONS) {
            for (int x = minX; x <= maxX; x++)
                for (int y = minY; y <= maxY; y++)
                    for (int z = minZ; z <= maxZ; z++)
                        if (SECTIONS.contains(SectionPos.asLong(x, y, z))) mc.levelRenderer.setSectionDirty(x, y, z);
        }
    }

    public static void reset() {
        synchronized (SECTIONS) {
            SECTIONS.clear();
        }
        lastRefresh = Long.MIN_VALUE;
    }
}
