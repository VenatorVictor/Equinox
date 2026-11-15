package dev.venatorvictor.equinox.client;

import it.unimi.dsi.fastutil.ints.IntArraySet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Light re-color: clear the tint cache and rebuild only the sections holding each column's
 * surface (the top block, and the ground under leaves), instead of a full renderer reload.
 */
public final class ChunkRefresher {
    public static void refreshVisible() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Entity camera = mc.getCameraEntity();
        if (level == null || camera == null) return;
        level.clearTintCaches();
        int cx = SectionPos.blockToSectionCoord(camera.getBlockX());
        int cz = SectionPos.blockToSectionCoord(camera.getBlockZ());
        int radius = mc.options.getEffectiveRenderDistance();
        for (int z = cz - radius; z <= cz + radius; z++) {
            for (int x = cx - radius; x <= cx + radius; x++) {
                LevelChunk chunk = level.getChunkSource().getChunk(x, z, false);
                if (chunk == null) continue;
                IntArraySet sections = new IntArraySet();
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
                        int ground = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lx, lz);
                        sections.add(SectionPos.blockToSectionCoord(top));
                        sections.add(SectionPos.blockToSectionCoord(ground)); // grass and plants under trees
                    }
                }
                for (int y : sections) mc.levelRenderer.setSectionDirty(x, y, z);
            }
        }
    }

    /** Sections still to redraw (packed x, y, z), nearest first; drained a share per tick (redraw_seconds). */
    private static final it.unimi.dsi.fastutil.longs.LongArrayList QUEUE = new it.unimi.dsi.fastutil.longs.LongArrayList();
    private static int queued, perTick;

    /**
     * Rebuild every section of the loaded chunks (seasonal textures can be anywhere, e.g. flowers under trees),
     * nearest first, spread over redraw_seconds instead of all in one frame. A new call starts over from the player.
     */
    public static void refreshAll() {
        int seconds = ClientConfig.REDRAW_SECONDS.get();
        if (seconds <= 0) {
            refreshAllNow();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Entity camera = mc.getCameraEntity();
        if (level == null || camera == null) return;
        level.clearTintCaches();
        int cx = SectionPos.blockToSectionCoord(camera.getBlockX());
        int cy = SectionPos.blockToSectionCoord(camera.getBlockY());
        int cz = SectionPos.blockToSectionCoord(camera.getBlockZ());
        int radius = mc.options.getEffectiveRenderDistance();
        QUEUE.clear();
        for (int z = cz - radius; z <= cz + radius; z++) {
            for (int x = cx - radius; x <= cx + radius; x++) {
                if (level.getChunkSource().getChunk(x, z, false) == null) continue;
                for (int y = level.getMinSection(); y < level.getMaxSection(); y++) QUEUE.add(SectionPos.asLong(x, y, z));
            }
        }
        // Nearest first (vertical distance counts less: the surface around the player matters most).
        QUEUE.sort((a, b) -> Integer.compare(distance(b, cx, cy, cz), distance(a, cx, cy, cz))); // farthest at the start: taken from the end
        queued = QUEUE.size();
        perTick = Math.max(1, Mth.ceil(queued / (seconds * 20.0)));
    }

    private static int distance(long section, int cx, int cy, int cz) {
        int dx = SectionPos.x(section) - cx, dy = SectionPos.y(section) - cy, dz = SectionPos.z(section) - cz;
        return dx * dx + dz * dz + dy * dy / 4;
    }

    /** Client tick: redraw the next share of the queued sections. */
    public static void tick() {
        redrawArrived();
        if (QUEUE.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            QUEUE.clear();
            return;
        }
        for (int i = 0; i < perTick && !QUEUE.isEmpty(); i++) {
            long section = QUEUE.removeLong(QUEUE.size() - 1);
            mc.levelRenderer.setSectionDirty(SectionPos.x(section), SectionPos.y(section), SectionPos.z(section));
        }
    }

    /** Rebuild every section of the loaded chunks at once. */
    public static void refreshAllNow() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Entity camera = mc.getCameraEntity();
        if (level == null || camera == null) return;
        level.clearTintCaches(); // or rebuilt sections keep the old colors
        int cx = SectionPos.blockToSectionCoord(camera.getBlockX());
        int cz = SectionPos.blockToSectionCoord(camera.getBlockZ());
        int radius = mc.options.getEffectiveRenderDistance();
        for (int z = cz - radius; z <= cz + radius; z++) {
            for (int x = cx - radius; x <= cx + radius; x++) {
                if (level.getChunkSource().getChunk(x, z, false) == null) continue;
                for (int y = level.getMinSection(); y < level.getMaxSection(); y++) mc.levelRenderer.setSectionDirty(x, y, z);
            }
        }
    }

    /** Chunks whose climate blend arrived this tick (packed ChunkPos). */
    private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet ARRIVED = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    /** A chunk's climate blend arrived (render thread): its colors and its neighbours' are redrawn next tick. */
    public static void blendArrived(long chunk) {
        if (ClientConfig.CLIMATE_COLOR_BLEND.get() > 0) ARRIVED.add(chunk);
    }

    /** Forget the colors of the chunks around arrived blends and redraw their surface (grass, plants, tree tops). */
    private static void redrawArrived() {
        if (ARRIVED.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            ARRIVED.clear();
            return;
        }
        it.unimi.dsi.fastutil.longs.LongOpenHashSet around = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (long chunk : ARRIVED) {
            int x = net.minecraft.world.level.ChunkPos.getX(chunk), z = net.minecraft.world.level.ChunkPos.getZ(chunk);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) around.add(net.minecraft.world.level.ChunkPos.asLong(x + dx, z + dz));
        }
        ARRIVED.clear();
        var caches = ((dev.venatorvictor.equinox.mixin.client.ClientLevelTintAccessor) level).equinox$tintCaches();
        for (long chunk : around) {
            int x = net.minecraft.world.level.ChunkPos.getX(chunk), z = net.minecraft.world.level.ChunkPos.getZ(chunk);
            LevelChunk loaded = level.getChunkSource().getChunk(x, z, false);
            if (loaded == null) continue;
            for (var cache : caches.values()) cache.invalidateForChunk(x, z);
            IntArraySet sections = new IntArraySet();
            for (int lx = 0; lx < 16; lx += 3) {
                for (int lz = 0; lz < 16; lz += 3) {
                    sections.add(SectionPos.blockToSectionCoord(loaded.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz)));
                    sections.add(SectionPos.blockToSectionCoord(loaded.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lx, lz)));
                }
            }
            for (int y : sections) mc.levelRenderer.setSectionDirty(x, y, z);
        }
    }

    private ChunkRefresher() {}
}
