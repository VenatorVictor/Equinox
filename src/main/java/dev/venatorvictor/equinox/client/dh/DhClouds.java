package dev.venatorvictor.equinox.client.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3d;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBoxGroupShading;
import dev.venatorvictor.equinox.Equinox;
import dev.venatorvictor.equinox.client.CloudRenderer;
import dev.venatorvictor.equinox.client.clouds.CloudMesher;
import dev.venatorvictor.equinox.client.clouds.CloudModel;
import dev.venatorvictor.equinox.weather.Weather;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * With Distant Horizons: our clouds go to its box renderer (its public API), so they sit right behind LOD terrain
 * (vanilla-path clouds would draw over far mountains), and its own clouds are switched off while ours are on.
 * Same sky as the vanilla path (CloudModel), rows of equal cells joined into one box.
 */
public final class DhClouds {
    private static final boolean LOADED = ModList.get().isLoaded("distanthorizons");

    /** Our clouds are being drawn by Distant Horizons now (the vanilla path draws none). */
    public static boolean drawing() {
        return LOADED && Impl.drawing;
    }

    /** Client tick: keeps the box group in step with the sky. */
    public static void tick(ClientLevel level) {
        if (!LOADED) return;
        try {
            Impl.tick(level);
        } catch (Throwable e) {
            Equinox.LOGGER.warn("Equinox: Distant Horizons clouds failed, back to the normal clouds", e);
            Impl.failed = true;
            Impl.remove();
        }
    }

    public static void clear() {
        if (LOADED) Impl.remove();
    }

    private static final class Impl {
        static boolean drawing, failed, dhCloudsOff;
        static IDhApiRenderableBoxGroup group;
        static IDhApiLevelWrapper wrapper;
        static CloudModel.Sky sky;
        static long filled = Long.MIN_VALUE;
        static final int FILL_TICKS = 200;
        static final DhApiRenderableBoxGroupShading SHADING = new DhApiRenderableBoxGroupShading();

        static {
            // Vanilla's cloud face shading.
            SHADING.top = 1F;
            SHADING.bottom = 0.7F;
            SHADING.east = SHADING.west = 0.9F;
            SHADING.north = SHADING.south = 0.8F;
        }

        static void tick(ClientLevel level) {
            IDhApiConfig configs = DhApi.Delayed.configs;
            boolean ready = !failed && level != null && CloudRenderer.active(level) && configs != null
                    && DhApi.Delayed.customRenderObjectFactory != null && DhApi.Delayed.worldProxy != null
                    && DhApi.Delayed.worldProxy.worldLoaded()
                    && Boolean.TRUE.equals(configs.graphics().renderingEnabled().getValue())
                    && Boolean.TRUE.equals(configs.graphics().genericRendering().renderingEnabled().getValue());
            if (!ready) {
                remove();
                return;
            }
            IDhApiLevelWrapper current = DhApi.Delayed.worldProxy.getSinglePlayerLevel();
            if (current == null) {
                remove();
                return;
            }
            if (current != wrapper) {
                remove();
                wrapper = current;
                group = DhApi.Delayed.customRenderObjectFactory.createRelativePositionedGroup(
                        Equinox.MOD_ID + ":clouds", new DhApiVec3d(0, 0, 0), new ArrayList<>());
                group.setSkyLight(15);
                group.setBlockLight(0);
                group.setSsaoEnabled(false);
                group.setShading(SHADING);
                group.setPreRenderFunc(param -> place(param.partialTicks));
                wrapper.getRenderRegister().add(group);
            }
            if (!dhCloudsOff) {
                configs.graphics().genericRendering().cloudRenderingEnabled().setValue(false);
                dhCloudsOff = true;
            }
            drawing = true;
            Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            CloudRenderer.schedule(level, camera.x, camera.z, false);
            CloudModel.Sky built = CloudRenderer.pollSky();
            // Boxes are remade every FILL_TICKS, or at once when the camera moved on (a new middle).
            if (built != null && (sky == null || built.time() - filled >= FILL_TICKS || built.time() < filled
                    || built.centerX() != sky.centerX() || built.centerZ() != sky.centerZ())) {
                sky = built;
                filled = built.time();
                fill(level, built);
            }
        }

        /** Moves the boxes with the wind (render thread, every frame). */
        static void place(float partialTick) {
            ClientLevel level = Minecraft.getInstance().level;
            CloudModel.Sky s = sky;
            Weather.Side side = Weather.client();
            if (level == null || s == null || side == null || group == null) return;
            long time = level.getGameTime();
            double[] now = side.field.offset(time), next = side.field.offset(time + 1);
            double offX = Mth.lerp(partialTick, now[0], next[0]), offZ = Mth.lerp(partialTick, now[1], next[1]);
            float height = level.effects().getCloudHeight();
            if (Float.isNaN(height)) height = 192;
            group.setOriginBlockPos(new DhApiVec3d(offX, height, offZ));
        }

        /** The sky as boxes (joined cells and heap cubes). Colour: vanilla's day cloud colour times the box's grey. */
        static void fill(ClientLevel level, CloudModel.Sky s) {
            float[] day = CloudRenderer.dayColor(level, 1);
            List<DhApiRenderableBox> boxes = new ArrayList<>();
            for (CloudMesher.Box box : CloudMesher.boxes(s)) {
                float g = box.grey();
                Color c = new Color(Mth.clamp(day[0] * g, 0, 1), Mth.clamp(day[1] * g, 0, 1), Mth.clamp(day[2] * g, 0, 1), box.alpha());
                boxes.add(new DhApiRenderableBox(new DhApiVec3d(box.x0(), box.bottom(), box.z0()),
                        new DhApiVec3d(box.x1(), box.top(), box.z1()), c, EDhApiBlockMaterial.UNKNOWN));
            }
            group.clear();
            group.addAll(boxes);
            group.triggerBoxChange();
        }

        static void remove() {
            if (group != null && wrapper != null) {
                try {
                    wrapper.getRenderRegister().remove(group.getId());
                } catch (Throwable ignored) {
                    // the level may already be gone
                }
            }
            group = null;
            wrapper = null;
            sky = null;
            drawing = false;
            if (dhCloudsOff) {
                IDhApiConfig configs = DhApi.Delayed.configs;
                if (configs != null) configs.graphics().genericRendering().cloudRenderingEnabled().clearValue();
                dhCloudsOff = false;
            }
        }
    }

    private DhClouds() {
    }
}
