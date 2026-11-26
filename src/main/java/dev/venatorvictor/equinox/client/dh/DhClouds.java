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
import dev.venatorvictor.equinox.client.ClientConfig;
import dev.venatorvictor.equinox.client.clouds.CloudExtras;
import dev.venatorvictor.equinox.client.clouds.CloudField;
import dev.venatorvictor.equinox.client.clouds.CloudSky;
import dev.venatorvictor.equinox.weather.Weather;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.neoforged.fml.ModList;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * With Distant Horizons: the far clouds go to its box renderer (its public API), so they sit right behind LOD terrain
 * (clouds drawn the normal way would draw over far mountains); the near ones, within the game's own view, are drawn
 * the normal way (CloudRenderer). Its own clouds are switched off while ours are on. The far boxes come from the same
 * sky and density as the cubes (CloudField), in 16-block cells, remade every FILL_TICKS on our cloud thread.
 */
public final class DhClouds {
    private static final boolean LOADED = ModList.get().isLoaded("distanthorizons");

    /** Distant Horizons draws our far clouds now. */
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
        static CloudSky.Sky filledSky, shownSky;
        static long filled = Long.MIN_VALUE;
        static CompletableFuture<List<DhApiRenderableBox>> pending;
        static CloudSky.Sky pendingSky;
        static final int FILL_TICKS = 200, CELL = 16;
        static final float[] ALPHA = {1F, 0.85F, 0.55F};
        static final ExecutorService BUILDER = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "Equinox far clouds");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
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
            boolean ours = !failed && level != null && CloudRenderer.active(level) && configs != null;
            // Distant Horizons' own clouds stay off while ours are on
            if (ours && !dhCloudsOff) {
                configs.graphics().genericRendering().cloudRenderingEnabled().setValue(false);
                dhCloudsOff = true;
            }
            boolean ready = ours && ClientConfig.DH_CLOUDS.get()
                    && DhApi.Delayed.customRenderObjectFactory != null && DhApi.Delayed.worldProxy != null
                    && DhApi.Delayed.worldProxy.worldLoaded()
                    && Boolean.TRUE.equals(configs.graphics().renderingEnabled().getValue())
                    && Boolean.TRUE.equals(configs.graphics().genericRendering().renderingEnabled().getValue());
            if (!ready) {
                remove();
                if (!ours) restoreDh();
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
            drawing = true;
            // finished boxes go in; a new fill every FILL_TICKS (or when the sky's centre moved)
            if (pending != null && pending.isDone()) {
                List<DhApiRenderableBox> boxes = pending.getNow(null);
                pending = null;
                if (boxes != null) {
                    group.clear();
                    group.addAll(boxes);
                    group.triggerBoxChange();
                    shownSky = pendingSky;
                }
            }
            CloudSky.Sky sky = CloudRenderer.sky();
            if (pending == null && sky != null && (filledSky == null || sky.time() - filled >= FILL_TICKS || sky.time() < filled
                    || Math.abs(sky.centerX() - filledSky.centerX()) > 256 || Math.abs(sky.centerZ() - filledSky.centerZ()) > 256)) {
                filledSky = sky;
                filled = sky.time();
                float inner = CloudRenderer.nearReach() - CELL, outer = CloudRenderer.reach();
                pendingSky = sky;
                pending = CompletableFuture.supplyAsync(() -> boxes(sky, inner, outer), BUILDER);
            }
        }

        /** The far clouds as boxes, coloured from the palette (light, storm grey). */
        static List<DhApiRenderableBox> boxes(CloudSky.Sky sky, float inner, float outer) {
            List<DhApiRenderableBox> boxes = new ArrayList<>();
            for (CloudField.Box box : new CloudField(sky, CELL, (int) outer + CELL).boxes(inner, outer)) {
                float[] c = CloudExtras.colour(box.light(), box.storm());
                Color colour = new Color(Mth.clamp(c[0], 0, 1), Mth.clamp(c[1], 0, 1), Mth.clamp(c[2], 0, 1), ALPHA[box.kind()]);
                boxes.add(new DhApiRenderableBox(new DhApiVec3d(box.x0(), box.bottom(), box.z0()),
                        new DhApiVec3d(box.x1(), box.top(), box.z1()), colour, EDhApiBlockMaterial.UNKNOWN));
            }
            return boxes;
        }

        /** Moves the boxes with the wind (render thread, every frame): boxes are relative to their sky's centre. */
        static void place(float partialTick) {
            ClientLevel level = Minecraft.getInstance().level;
            CloudSky.Sky s = shownSky;
            Weather.Side side = Weather.client();
            if (level == null || s == null || side == null || group == null) return;
            long time = level.getGameTime();
            double[] now = side.field.offset(time), next = side.field.offset(time + 1);
            double offX = Mth.lerp(partialTick, now[0], next[0]), offZ = Mth.lerp(partialTick, now[1], next[1]);
            float height = level.effects().getCloudHeight();
            if (Float.isNaN(height)) height = 192;
            group.setOriginBlockPos(new DhApiVec3d(s.centerX() + offX, height, s.centerZ() + offZ));
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
            filledSky = shownSky = null;
            pending = null;
            drawing = false;
        }

        static void restoreDh() {
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
