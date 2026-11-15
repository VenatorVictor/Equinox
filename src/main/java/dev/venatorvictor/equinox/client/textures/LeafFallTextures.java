package dev.venatorvictor.equinox.client.textures;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.serialization.MapCodec;
import dev.venatorvictor.equinox.Equinox;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.renderer.texture.atlas.SpriteSourceType;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.minecraft.util.GsonHelper;
import net.neoforged.neoforge.client.event.RegisterSpriteSourceTypesEvent;

import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Leaf fall textures, made when resources load (atlas source in assets/minecraft/atlases/blocks.json) from whatever leaf
 * texture is active, so resource packs with the same leaf shapes keep their colors.
 * Each assets/<ns>/leaf_fall/<name>.json lists the leaves of one texture (their pixels, in the order they fall) and how
 * many have fallen at stages 1, 2, 3 ... (as many as listed). Sprites: <ns>:leaf_fall/<name>/stage_<n>, the leaves still there (the twigs are
 * their own texture, drawn behind: under_textures in the seasonal texture files).
 * If the active texture doesn't have the same pixels as the leaf map (another pack's leaves), no stages are made and
 * the seasonal texture files leave those steps as they are.
 */
public final class LeafFallTextures implements SpriteSource {
    private static final LeafFallTextures INSTANCE = new LeafFallTextures();
    private static final MapCodec<LeafFallTextures> CODEC = MapCodec.unit(INSTANCE);
    private static SpriteSourceType type;

    public static void register(RegisterSpriteSourceTypesEvent event) {
        type = event.register(ResourceLocation.fromNamespaceAndPath("equinox", "leaf_fall"), CODEC);
    }

    @Override
    public SpriteSourceType type() {
        return type;
    }

    @Override
    public void run(ResourceManager resources, Output output) {
        for (Map.Entry<ResourceLocation, Resource> file : resources.listResources("leaf_fall", path -> path.getPath().endsWith(".json")).entrySet()) {
            ResourceLocation key = file.getKey();
            String name = key.getPath().substring("leaf_fall/".length(), key.getPath().length() - ".json".length());
            try (Reader reader = file.getValue().openAsReader()) {
                JsonObject json = GsonHelper.parse(reader);
                ResourceLocation texture = ResourceLocation.parse(GsonHelper.getAsString(json, "texture"));
                List<int[][]> leaves = new ArrayList<>();
                for (JsonElement leaf : GsonHelper.getAsJsonArray(json, "leaves")) {
                    String[] pixels = leaf.getAsString().trim().split("\\s+");
                    int[][] list = new int[pixels.length][];
                    for (int i = 0; i < pixels.length; i++) {
                        String[] xy = pixels[i].split(",");
                        list[i] = new int[]{Integer.parseInt(xy[0]), Integer.parseInt(xy[1])};
                    }
                    leaves.add(list);
                }
                var stages = GsonHelper.getAsJsonArray(json, "stages");
                for (int stage = 1; stage <= stages.size(); stage++) {   // as many stages as the map lists
                    int fallen = stages.get(stage - 1).getAsInt();
                    ResourceLocation id = ResourceLocation.fromNamespaceAndPath(key.getNamespace(), "leaf_fall/" + name + "/stage_" + stage);
                    output.add(id, loader -> make(resources, texture, id, leaves, fallen));
                }
            } catch (Exception e) {
                Equinox.LOGGER.warn("Equinox: skipping leaf fall file {}: {}", key, e.toString());
            }
        }
    }

    private static SpriteContents make(ResourceManager resources, ResourceLocation texture, ResourceLocation id, List<int[][]> leaves, int fallen) {
        Optional<Resource> resource = resources.getResource(TEXTURE_ID_CONVERTER.idToFile(texture));
        if (resource.isEmpty()) return null;
        try (InputStream in = resource.get().open(); NativeImage source = NativeImage.read(in)) {
            int size = source.getWidth();
            if (size % 16 != 0 || source.getHeight() < size) return null;
            int scale = size / 16; // higher resolution packs: each map pixel is a scale x scale block
            // Same leaf shapes? Every map pixel opaque, and nothing else.
            boolean[] inMap = new boolean[256];
            for (int[][] leaf : leaves) for (int[] p : leaf) inMap[p[1] * 16 + p[0]] = true;
            for (int y = 0; y < 16; y++)
                for (int x = 0; x < 16; x++)
                    if (inMap[y * 16 + x] != (source.getPixelRGBA(x * scale, y * scale) >>> 24 > 0)) {
                        Equinox.LOGGER.info("Equinox: {} has other leaf shapes than its leaf map, no leaf fall for it", texture);
                        return null;
                    }
            NativeImage image = new NativeImage(size, size, true);
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) image.setPixelRGBA(x, y, source.getPixelRGBA(x, y));
            for (int i = 0; i < fallen && i < leaves.size(); i++)
                for (int[] p : leaves.get(i))
                    for (int dy = 0; dy < scale; dy++)
                        for (int dx = 0; dx < scale; dx++) image.setPixelRGBA(p[0] * scale + dx, p[1] * scale + dy, 0);
            return new SpriteContents(id, new FrameSize(size, size), image, ResourceMetadata.EMPTY);
        } catch (Exception e) {
            Equinox.LOGGER.warn("Equinox: couldn't make leaf fall texture {}: {}", id, e.toString());
            return null;
        }
    }
}
