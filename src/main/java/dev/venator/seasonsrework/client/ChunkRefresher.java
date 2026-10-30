package dev.venator.seasonsrework.client;

import it.unimi.dsi.fastutil.ints.IntArraySet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Ecliptic's light re-color: clear the tint cache and rebuild only the sections holding each column's
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

    /** Rebuild every section of the loaded chunks (seasonal textures can be anywhere, e.g. flowers under trees). */
    public static void refreshAll() {
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

    /** Sections below a new or removed roof that are redrawn (snowy looks under it). */
    private static final int ROOF_SECTIONS = 2;

    /**
     * A heater or cooler (radius) or a roof (radius 0) changed at pos (CropWarmth, client): redraw the sections whose
     * snowy looks it can change. Vanilla only redraws the blocks right next to a change.
     */
    public static void snowChanged(BlockPos pos, int radius) {
        Minecraft mc = Minecraft.getInstance();
        int x0 = SectionPos.blockToSectionCoord(pos.getX() - radius), x1 = SectionPos.blockToSectionCoord(pos.getX() + radius);
        int z0 = SectionPos.blockToSectionCoord(pos.getZ() - radius), z1 = SectionPos.blockToSectionCoord(pos.getZ() + radius);
        int y1 = SectionPos.blockToSectionCoord(pos.getY() + radius);
        int y0 = radius > 0 ? SectionPos.blockToSectionCoord(pos.getY() - radius) : SectionPos.blockToSectionCoord(pos.getY()) - ROOF_SECTIONS;
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++) mc.levelRenderer.setSectionDirty(x, y, z);
    }

    private ChunkRefresher() {}
}
