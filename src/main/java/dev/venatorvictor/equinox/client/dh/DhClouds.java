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
import dev.venatorvictor.equinox.client.clouds.CloudModel;
import dev.venatorvictor.equinox.weather.Weather;
import net.minecraft.client.CloudStatus;
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
            CloudRenderer.schedule(level, camera.x, camera.z, CloudRenderer.radius(), false, CloudStatus.FANCY);
            CloudModel.Sky built = CloudRenderer.pollSky();
            if (built != null) {
                sky = built;
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
            group.setOriginBlockPos(new DhApiVec3d(s.originX() * (double) CloudModel.CELL + offX, height,
                    s.originZ() * (double) CloudModel.CELL + offZ));
        }

        /** The sky as boxes: per layer, runs of equal cells along z joined. Colour: vanilla's cloud colour times the cell's grey. */
        static void fill(ClientLevel level, CloudModel.Sky s) {
            Vec3 color = level.getCloudColor(1);
            int n = s.n(), radius = s.radius(), cell = CloudModel.CELL;
            List<DhApiRenderableBox> boxes = new ArrayList<>();
            Color[] greys = new Color[CloudModel.GREYS];
            for (CloudModel.Layer layer : s.layers()) {
                int alpha = Math.round(layer.alpha * 255);
                for (int i = 0; i < n; i++) {
                    int j = 0;
                    while (j < n) {
                        int index = i * n + j;
                        float b = layer.bottom[index], t = layer.top[index];
                        if (t <= b) {
                            j++;
                            continue;
                        }
                        byte g = layer.grey[index];
                        int end = j + 1;
                        while (end < n) {
                            int k = i * n + end;
                            if (layer.bottom[k] != b || layer.top[k] != t || layer.grey[k] != g) break;
                            end++;
                        }
                        int gi = g & 0xFF;
                        Color c = greys[gi];
                        if (c == null || c.getAlpha() != alpha) {
                            float f = gi / (float) (CloudModel.GREYS - 1);
                            c = greys[gi] = new Color(Mth.clamp((float) color.x * f, 0, 1), Mth.clamp((float) color.y * f, 0, 1),
                                    Mth.clamp((float) color.z * f, 0, 1), alpha / 255F);
                        }
                        double x0 = (i - radius) * (double) cell, z0 = (j - radius) * (double) cell;
                        boxes.add(new DhApiRenderableBox(new DhApiVec3d(x0, b, z0), new DhApiVec3d(x0 + cell, t, (end - radius) * (double) cell),
                                c, EDhApiBlockMaterial.UNKNOWN));
                        j = end;
                    }
                }
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
