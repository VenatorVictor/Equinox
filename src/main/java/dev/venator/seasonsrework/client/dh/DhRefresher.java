package dev.venator.seasonsrework.client.dh;

import com.seibel.distanthorizons.common.wrappers.block.AbstractDhTintGetter_neoforge;
import com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper_neoforge;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.level.ClientLevelModule;
import com.seibel.distanthorizons.core.level.DhClientLevel;
import com.seibel.distanthorizons.core.level.DhClientServerLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.QuadTree.LodQuadTree;
import com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection;
import com.seibel.distanthorizons.core.world.IDhClientWorld;
import dev.venator.seasonsrework.SeasonsRework;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Comparator;

/**
 * Re-colors Distant Horizons LODs: DH caches one tint per (block, biome) and keeps built LOD meshes,
 * so both the cache and the visible sections must be redone. Only loaded when DH is installed.
 * Uses DH 3.3.3 internals; on any mismatch it logs once and turns itself off.
 */
public final class DhRefresher {
    private static boolean broken = false;

    public static void clearCache() {
        if (broken) return;
        try {
            AbstractDhTintGetter_neoforge.clear();
            DhSnow.clearCache();
        } catch (Throwable t) {
            fail(t);
        }
    }

    public static void refresh(ClientLevel level) {
        if (broken) return;
        try {
            AbstractDhTintGetter_neoforge.clear();
            LodQuadTree quadtree = quadtree(level);
            if (quadtree == null) return;
            ArrayList<LodRenderSection> sections = new ArrayList<>();
            quadtree.populateListWithEnabledRenderSections(sections);
            Entity camera = Minecraft.getInstance().getCameraEntity();
            int px = camera == null ? 0 : camera.getBlockX();
            int pz = camera == null ? 0 : camera.getBlockZ();
            // Nearest first; at equal distance, the coarser section first (as Ecliptic does).
            sections.sort(Comparator.<LodRenderSection>comparingLong(s -> distanceSq(s.pos, px, pz))
                    .thenComparing(s -> -DhSectionPos.getDetailLevel(s.pos)));
            // queuePosToReload also queues the 4 neighbours, so skip positions already covered.
            LongOpenHashSet queued = new LongOpenHashSet();
            for (LodRenderSection section : sections) {
                if (!queued.add(section.pos)) continue;
                quadtree.queuePosToReload(section.pos);
                for (EDhDirection direction : EDhDirection.CARDINAL_COMPASS) queued.add(DhSectionPos.getAdjacentPos(section.pos, direction));
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static LodQuadTree quadtree(ClientLevel level) {
        IDhClientWorld world = SharedApi.tryGetDhClientWorld();
        if (world == null) return null;
        IDhLevel dhLevel = world.getLevel(ClientLevelWrapper_neoforge.getWrapper(level));
        ClientLevelModule module = dhLevel instanceof DhClientLevel client ? client.clientside
                : dhLevel instanceof DhClientServerLevel clientServer ? clientServer.clientside : null;
        if (module == null) return null;
        ClientLevelModule.ClientRenderState state = module.ClientRenderStateRef.get();
        return state == null ? null : state.quadtree;
    }

    private static long distanceSq(long pos, int px, int pz) {
        long dx = DhSectionPos.getCenterBlockPosX(pos) - px;
        long dz = DhSectionPos.getCenterBlockPosZ(pos) - pz;
        return dx * dx + dz * dz;
    }

    private static void fail(Throwable t) {
        broken = true;
        SeasonsRework.LOGGER.warn("Seasons Rework: Distant Horizons LOD refresh disabled (DH version not supported?)", t);
    }

    private DhRefresher() {}
}
