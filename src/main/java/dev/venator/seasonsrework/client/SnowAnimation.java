package dev.venator.seasonsrework.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.venator.seasonsrework.snow.SnowyPlants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Snow that comes and goes near the player slides up out of the block below / sinks back into it, instead of popping.
 * Client only. When the server changes a block's snow (a snow layer, or the snow state of a plant or fence), the chunk
 * mesh draws that block without its snow while a moving copy of the snow is drawn here; the part below the surface is
 * hidden by the ground block. At the end the mesh gets its snow back and the copy stays a moment (drawn a hair smaller,
 * so the mesh covers it) until the chunk is redrawn, so there is no blink.
 */
public final class SnowAnimation {
    /** Read by the models: hide the snow at this block (it's being animated). */
    static final ModelProperty<Boolean> HIDDEN = new ModelProperty<>();
    private static final float LAYER = 1 / 8f;
    /** The copy stays this long after the end (ms), until the mesh has the snow again. */
    private static final long LINGER_MS = 400;
    private static final int MAX = 2048;
    private static final float INSET = 0.002f;

    private static final Map<Long, Anim> ANIMS = new ConcurrentHashMap<>();

    private static final class Anim {
        final BlockPos pos;
        final BlockState ghost;
        final float ghostHeight;
        /** Y offset of the copy at the start and the end (0: in place, -ghostHeight: all sunk). */
        final float from, to;
        final long start;
        volatile boolean done;

        Anim(BlockPos pos, BlockState ghost, float from, float to, long start) {
            this.pos = pos;
            this.ghost = ghost;
            this.ghostHeight = ghost.getValue(SnowLayerBlock.LAYERS) * LAYER;
            this.from = from;
            this.to = to;
            this.start = start;
        }

        float offset(long now) {
            float t = Mth.clamp((now - start) / (float) duration(), 0, 1);
            t = t * t * (3 - 2 * t); // smoothstep: starts and ends softly
            return Mth.lerp(t, from, to);
        }
    }

    private static long duration() {
        return ClientConfig.SNOW_ANIMATION_MS.get();
    }

    /** Snow layers at a block: a snow layer's, or a plant's or fence's snow state. */
    private static int layers(BlockState state) {
        if (state.is(Blocks.SNOW)) return state.getValue(SnowLayerBlock.LAYERS);
        return SnowyPlants.stateLayers(state);
    }

    /** The block without its snow. */
    private static BlockState base(BlockState state) {
        if (state.is(Blocks.SNOW)) return Blocks.AIR.defaultBlockState();
        return SnowyPlants.stateMaxLayers(state) > 0 ? SnowyPlants.withLayers(state, 0) : state;
    }

    /** ClientLevel: the server changed a block (not the player's own predicted change). */
    public static void onServerChange(ClientLevel level, BlockPos pos, BlockState old, BlockState now) {
        if (old == now || !ClientConfig.SNOW_ANIMATION.get()) return;
        int a = layers(old), b = layers(now);
        if (a == b) return;
        // Only snow changing on the same block: a plant broken with its snow, snow turning into ice etc. just change.
        if (!base(old).equals(base(now))) return;
        // Rising out of the ground only looks right on a full block (not leaves, glass...).
        BlockPos below = pos.below();
        if (!level.getBlockState(below).isSolidRender(level, below)) return;
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        int distance = ClientConfig.SNOW_ANIMATION_DISTANCE.get();
        if (camera.distanceToSqr(Vec3.atCenterOf(pos)) > (double) distance * distance) return;
        long key = pos.asLong();
        if (ANIMS.size() >= MAX && !ANIMS.containsKey(key)) return;

        long now_ = System.currentTimeMillis();
        int top = Math.max(a, b);
        BlockState ghost = Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, top);
        float height = top * LAYER;
        // Interrupted animation: carry on from where the copy is now.
        Anim running = ANIMS.get(key);
        float shownHeight = running != null && !running.done ? running.ghostHeight + running.offset(now_) : a * LAYER;
        ANIMS.put(key, new Anim(pos.immutable(), ghost, shownHeight - height, b * LAYER - height, now_));
    }

    /** Models (chunk meshing, any thread): leave out the snow at this block. */
    public static boolean hidden(BlockPos pos) {
        if (ANIMS.isEmpty()) return false;
        Anim anim = ANIMS.get(pos.asLong());
        return anim != null && !anim.done;
    }

    static boolean hidden(ModelData data) {
        return Boolean.TRUE.equals(data.get(HIDDEN));
    }

    public static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || ANIMS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        long now = System.currentTimeMillis();
        long duration = duration();
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        BlockRenderDispatcher blocks = mc.getBlockRenderer();
        RandomSource random = RandomSource.create();
        boolean drew = false;
        for (Iterator<Anim> it = ANIMS.values().iterator(); it.hasNext(); ) {
            Anim anim = it.next();
            long age = now - anim.start;
            if (age >= duration + LINGER_MS) {
                it.remove();
                continue;
            }
            if (age >= duration && !anim.done) {
                anim.done = true; // the mesh draws the snow again
                BlockPos p = anim.pos;
                mc.levelRenderer.setBlocksDirty(p.getX(), p.getY(), p.getZ(), p.getX(), p.getY(), p.getZ());
            }
            float offset = anim.offset(now);
            if (offset <= -anim.ghostHeight + 1e-4f) continue; // all sunk
            BlockPos p = anim.pos;
            pose.pushPose();
            pose.translate(p.getX() - camera.x + 0.5, p.getY() - camera.y + offset - INSET, p.getZ() - camera.z + 0.5);
            pose.scale(1 - INSET, 1, 1 - INSET);
            pose.translate(-0.5, 0, -0.5);
            BakedModel model = blocks.getBlockModel(anim.ghost);
            blocks.getModelRenderer().tesselateBlock(level, model, anim.ghost, p, pose, buffers.getBuffer(RenderType.solid()),
                    false, random, anim.ghost.getSeed(p), OverlayTexture.NO_OVERLAY, ModelData.EMPTY, RenderType.solid());
            pose.popPose();
            drew = true;
        }
        if (drew) buffers.endBatch(RenderType.solid());
    }

    public static void clear() {
        ANIMS.clear();
    }

    /** Wraps vanilla's snow layer models so an animated block's layer can be left out of the mesh. */
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        for (BlockState state : Blocks.SNOW.getStateDefinition().getPossibleStates()) {
            ModelResourceLocation location = BlockModelShaper.stateToModelLocation(state);
            BakedModel model = event.getModels().get(location);
            if (model != null && !(model instanceof LayerModel)) event.getModels().put(location, new LayerModel(model));
        }
    }

    private static final class LayerModel extends BakedModelWrapper<BakedModel> {
        LayerModel(BakedModel model) {
            super(model);
        }

        @Override
        public @NotNull ModelData getModelData(@NotNull BlockAndTintGetter region, @NotNull BlockPos pos, @NotNull BlockState state, @NotNull ModelData data) {
            data = super.getModelData(region, pos, state, data);
            return hidden(pos) ? data.derive().with(HIDDEN, true).build() : data;
        }

        @Override
        public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand, @NotNull ModelData data, @Nullable RenderType renderType) {
            return hidden(data) ? List.of() : super.getQuads(state, side, rand, data, renderType);
        }
    }

    private SnowAnimation() {}
}
