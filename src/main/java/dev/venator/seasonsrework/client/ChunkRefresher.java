package dev.venator.seasonsrework.client;

import it.unimi.dsi.fastutil.ints.IntArraySet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Ecliptic's light re-color: clear the tint cache and rebuild only the sections holding each column's
 * surface, instead of a full renderer reload.
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
                        sections.add(SectionPos.blockToSectionCoord(top));
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

    private ChunkRefresher() {}
}
