package dev.venatorvictor.equinox.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.venatorvictor.equinox.snow.SnowyPlants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Snow that comes and goes near the player slides up out of the block below / sinks back into it, instead of popping.
 * Client only; the server's world is never changed by this, only when the client shows the server's change:
 * - more snow: the client keeps the old block until the slide ends, while a moving copy of the new snow rises out
 *   of the ground (the ground's top face hides the part below). Then the server's block is put in.
 * - less snow: the server's block is put in at once, and a copy of the old snow sinks into the ground; for the last
 *   40% it is water instead, fading away.
 * The chunk mesh always shows a real block, so the ground and the neighbours' sides are drawn as usual. The copy is
 * drawn a hair smaller than a block, so wherever it overlaps real snow the real snow wins.
 */
public final class SnowAnimation {
    private static final float LAYER = 1 / 8f;
    /** The copy stays this long after the new block is put in (ms), until the chunk is redrawn with it. */
    private static final long LINGER_MS = 400;
    private static final int MAX = 2048;
    private static final float INSET = 0.002f;
    /** Melting snow shows as water from this share of the animation on, fading out. */
    private static final float WATER_FROM = 0.6f;
    /** Client flags of a server block change (ClientPacketListener). */
    private static final int SERVER_FLAGS = 19;

    private static final Map<Long, Anim> ANIMS = new ConcurrentHashMap<>();
    /** Snow the player put in by hand changes at once: the clicked block and the one on the clicked face, until this time (ms). */
    private static final Map<Long, Long> PLACED = new ConcurrentHashMap<>();
    private static final long PLACED_MS = 1500;
    /** Set while we put a held-back block in, so the mixin lets it through. */
    private static boolean applying;

    // TEMPORARY measuring (SnowAnimBench).
    static long benchDrawNanos, benchMaxDrawNanos, benchActive, benchMaxActive, benchStarted, benchApplied;

    private static final class Anim {
        final BlockPos pos;
        final BlockState ghost;
        final float ghostHeight;
        /** Y offset of the copy at the start and the end (0: in place, -ghostHeight: all sunk). */
        final float from, to;
        final long start;
        /** The server's block, held back until the copy has risen (null: already in). */
        @Nullable BlockState pending;

        Anim(BlockPos pos, BlockState ghost, float from, float to, long start, @Nullable BlockState pending) {
            this.pos = pos;
            this.ghost = ghost;
            this.ghostHeight = ghost.getValue(SnowLayerBlock.LAYERS) * LAYER;
            this.from = from;
            this.to = to;
            this.start = start;
            this.pending = pending;
        }

        float offset(long now) {
            float t = Mth.clamp((now - start) / (float) duration(), 0, 1);
            t = t * t * (3 - 2 * t); // smoothstep: starts and ends softly
            return Mth.lerp(t, from, to);
        }

        /** Progress 0..1 (linear). */
        float progress(long now) {
            return Mth.clamp((now - start) / (float) duration(), 0, 1);
        }

        boolean melting() {
            return pending == null && to < from;
        }

        float shownHeight(long now) {
            return ghostHeight + offset(now);
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

    private static BlockState snow(int layers) {
        return Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, Mth.clamp(layers, 1, 8));
    }

    /**
     * ClientLevel.setServerVerifiedBlockState: the server changed a block (not the player's own predicted change).
     * Returns true when the change is held back (more snow: put in when the copy has risen).
     */
    public static boolean onServerChange(ClientLevel level, BlockPos pos, BlockState old, BlockState now) {
        if (applying) return false;
        long key = pos.asLong();
        Long placed = PLACED.get(key);
        if (placed != null && placed >= System.currentTimeMillis()) return false;
        Anim running = ANIMS.remove(key);
        long time = System.currentTimeMillis();
        // A newer change replaces a held-back one; the copy carries on from where it is.
        float shown = running != null ? running.shownHeight(time) : layers(old) * LAYER;
        if (old == now && running == null || !ClientConfig.SNOW_ANIMATION.get()) return false;
        // Only snow changing on the same block: a plant broken with its snow, snow turning into ice etc. just change.
        if (!base(old).equals(base(now))) return false;
        // Rising out of the ground only looks right on a full block (not leaves, glass...).
        BlockPos below = pos.below();
        if (!level.getBlockState(below).isSolidRender(level, below)) return false;
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        int distance = ClientConfig.SNOW_ANIMATION_DISTANCE.get();
        if (camera.distanceToSqr(Vec3.atCenterOf(pos)) > (double) distance * distance) return false;
        if (ANIMS.size() >= MAX) return false;

        float target = layers(now) * LAYER;
        if (Math.abs(target - shown) < 1e-4f) return false;
        if (target > shown) {
            // More snow: rise from the shown height to the new one, then put the server's block in.
            ANIMS.put(key, new Anim(pos.immutable(), snow(layers(now)), shown - target, 0, time, now));
            benchStarted++;
            return true;
        }
        // Less snow: the new block goes in now, a copy of what was shown sinks to the new height.
        int top = Mth.ceil(shown / LAYER - 1e-4f);
        float height = top * LAYER;
        ANIMS.put(key, new Anim(pos.immutable(), snow(top), shown - height, target - height, time, null));
        benchStarted++;
        return false;
    }

    /** The player uses snow (layers or a block) on a block: no animation for what that puts in. */
    public static void onUse(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide) return;
        net.minecraft.world.item.ItemStack stack = event.getItemStack();
        if (!stack.is(net.minecraft.world.item.Items.SNOW) && !stack.is(net.minecraft.world.item.Items.SNOW_BLOCK)) return;
        long until = System.currentTimeMillis() + PLACED_MS;
        BlockPos pos = event.getPos();
        PLACED.put(pos.asLong(), until);
        PLACED.put(pos.relative(event.getHitVec().getDirection()).asLong(), until);
        PLACED.put(pos.below().asLong(), until); // a tall plant's upper half: its snow is in the lower half
    }

    /** Client tick: put held-back blocks in once their copy has risen, drop finished copies. */
    public static void tick() {
        if (!PLACED.isEmpty()) {
            long now = System.currentTimeMillis();
            PLACED.values().removeIf(until -> until < now);
        }
        if (ANIMS.isEmpty()) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            ANIMS.clear();
            return;
        }
        long now = System.currentTimeMillis();
        long duration = duration();
        for (Iterator<Anim> it = ANIMS.values().iterator(); it.hasNext(); ) {
            Anim anim = it.next();
            long age = now - anim.start;
            if (age >= duration && anim.pending != null) {
                BlockState state = anim.pending;
                anim.pending = null;
                applying = true;
                try {
                    level.setServerVerifiedBlockState(anim.pos, state, SERVER_FLAGS);
                } finally {
                    applying = false;
                }
                benchApplied++;
            }
            if (age >= duration + LINGER_MS) it.remove();
        }
    }

    public static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || ANIMS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        long benchStart = System.nanoTime();
        benchActive += ANIMS.size();
        benchMaxActive = Math.max(benchMaxActive, ANIMS.size());
        long now = System.currentTimeMillis();
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        BlockRenderDispatcher blocks = mc.getBlockRenderer();
        RandomSource random = RandomSource.create();
        boolean drew = false, water = false;
        for (Anim anim : ANIMS.values()) {
            float offset = anim.offset(now);
            if (offset <= -anim.ghostHeight + 1e-4f) continue; // all sunk
            BlockPos p = anim.pos;
            float progress = anim.progress(now);
            if (anim.melting() && progress >= WATER_FROM) {
                // The last part of melting: the snow has turned to water, which soaks away.
                float height = Math.max(anim.ghostHeight + offset, 1 / 32f);
                float alpha = 0.8f * (1 - (progress - WATER_FROM) / (1 - WATER_FROM));
                if (alpha > 0.01f) {
                    drawWater(level, p, height, alpha, camera, pose, buffers.getBuffer(RenderType.translucent()));
                    water = true;
                }
                continue;
            }
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
        if (water) buffers.endBatch(RenderType.translucent());
        long took = System.nanoTime() - benchStart;
        benchDrawNanos += took;
        benchMaxDrawNanos = Math.max(benchMaxDrawNanos, took);
    }

    /** A box of still water from the ground up to height: top and sides, the biome's water color. */
    private static void drawWater(ClientLevel level, BlockPos p, float height, float alpha, Vec3 camera, PoseStack pose,
                                  com.mojang.blaze3d.vertex.VertexConsumer out) {
        net.minecraft.client.renderer.texture.TextureAtlasSprite sprite = Minecraft.getInstance()
                .getTextureAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS)
                .apply(net.minecraft.resources.ResourceLocation.withDefaultNamespace("block/water_still"));
        int color = net.minecraft.client.renderer.BiomeColors.getAverageWaterColor(level, p);
        float r = (color >> 16 & 0xFF) / 255f, g = (color >> 8 & 0xFF) / 255f, b = (color & 0xFF) / 255f;
        int light = net.minecraft.client.renderer.LevelRenderer.getLightColor(level, p);
        pose.pushPose();
        pose.translate(p.getX() - camera.x, p.getY() - camera.y, p.getZ() - camera.z);
        PoseStack.Pose last = pose.last();
        float e = INSET, h = height, u0 = sprite.getU0(), u1 = sprite.getU1(), v0 = sprite.getV0();
        float vh = sprite.getV(h); // side faces: only as much texture as the water is high
        // top
        quad(out, last, light, r, g, b, alpha, 0, 1, 0,
                e, h, e, u0, v0, e, h, 1 - e, u0, sprite.getV1(), 1 - e, h, 1 - e, u1, sprite.getV1(), 1 - e, h, e, u1, v0);
        // north, south, west, east
        both(out, last, light, r, g, b, alpha, 0, 0, -1, e, 0, e, u0, vh, e, h, e, u0, v0, 1 - e, h, e, u1, v0, 1 - e, 0, e, u1, vh);
        both(out, last, light, r, g, b, alpha, 0, 0, 1, 1 - e, 0, 1 - e, u0, vh, 1 - e, h, 1 - e, u0, v0, e, h, 1 - e, u1, v0, e, 0, 1 - e, u1, vh);
        both(out, last, light, r, g, b, alpha, -1, 0, 0, e, 0, 1 - e, u0, vh, e, h, 1 - e, u0, v0, e, h, e, u1, v0, e, 0, e, u1, vh);
        both(out, last, light, r, g, b, alpha, 1, 0, 0, 1 - e, 0, e, u0, vh, 1 - e, h, e, u0, v0, 1 - e, h, 1 - e, u1, v0, 1 - e, 0, 1 - e, u1, vh);
        pose.popPose();
    }

    /** A side face drawn from both sides (whichever way the corners turn, it shows from outside). */
    private static void both(com.mojang.blaze3d.vertex.VertexConsumer out, PoseStack.Pose pose, int light, float r, float g, float b, float a,
                             float nx, float ny, float nz, float... v) {
        quad(out, pose, light, r, g, b, a, nx, ny, nz, v);
        float[] back = new float[20];
        for (int i = 0; i < 4; i++) System.arraycopy(v, (3 - i) * 5, back, i * 5, 5);
        quad(out, pose, light, r, g, b, a, -nx, -ny, -nz, back);
    }

    private static void quad(com.mojang.blaze3d.vertex.VertexConsumer out, PoseStack.Pose pose, int light, float r, float g, float b, float a,
                             float nx, float ny, float nz, float... v) {
        for (int i = 0; i < 4; i++) {
            int k = i * 5;
            out.addVertex(pose, v[k], v[k + 1], v[k + 2]).setColor(r, g, b, a).setUv(v[k + 3], v[k + 4])
                    .setLight(light).setNormal(pose, nx, ny, nz);
        }
    }

    /** Leaving the world: held-back blocks don't matter any more (the chunks go too). */
    public static void clear() {
        ANIMS.clear();
    }

    private SnowAnimation() {}
}
