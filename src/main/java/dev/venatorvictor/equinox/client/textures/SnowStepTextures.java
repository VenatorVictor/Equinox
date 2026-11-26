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
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The snowy textures of the seasonal texture files cut for look steps 1..3: only their top quarter, half or three
 * quarters, the rest see-through (atlas source in assets/minecraft/atlases/blocks.json). Sprites:
 * equinox:snow_step/<ns>/<path>/<step>. The snow overlay on side faces uses them on a quad as tall as the face under it,
 * so shaders that wave plants by height move both the same way (a shorter quad would slide off the plant).
 */
public final class SnowStepTextures implements SpriteSource {
    private static final SnowStepTextures INSTANCE = new SnowStepTextures();
    private static final MapCodec<SnowStepTextures> CODEC = MapCodec.unit(INSTANCE);
    private static final String DIRECTORY = "season_textures";
    private static SpriteSourceType type;

    public static void register(RegisterSpriteSourceTypesEvent event) {
        type = event.register(ResourceLocation.fromNamespaceAndPath("equinox", "snow_steps"), CODEC);
    }

    /** The cut sprite of a snowy texture for step 1..3. */
    public static ResourceLocation id(ResourceLocation texture, int step) {
        return ResourceLocation.fromNamespaceAndPath("equinox", "snow_step/" + texture.getNamespace() + "/" + texture.getPath() + "/" + step);
    }

    @Override
    public SpriteSourceType type() {
        return type;
    }

    @Override
    public void run(ResourceManager resources, Output output) {
        Set<ResourceLocation> textures = new HashSet<>();
        for (Map.Entry<ResourceLocation, Resource> file : resources.listResources(DIRECTORY, path -> path.getPath().endsWith(".json")).entrySet()) {
            try (Reader reader = file.getValue().openAsReader()) {
                JsonObject json = GsonHelper.parse(reader);
                if (!json.has("weeks")) continue;
                for (JsonElement slice : GsonHelper.getAsJsonArray(json, "weeks"))
                    if (slice.isJsonObject() && slice.getAsJsonObject().has("snow_textures")) collect(slice.getAsJsonObject().get("snow_textures"), textures);
            } catch (Exception e) {
                Equinox.LOGGER.warn("Equinox: skipping {} for snow steps: {}", file.getKey(), e.toString());
            }
        }
        for (ResourceLocation texture : textures)
            for (int step = 1; step <= 3; step++) {
                ResourceLocation id = id(texture, step);
                int keep = step;
                output.add(id, loader -> make(resources, texture, id, keep));
            }
    }

    /** Every texture id in a textures value (a map, or a list of maps picked per position). */
    private static void collect(JsonElement element, Set<ResourceLocation> into) {
        if (element.isJsonArray()) for (JsonElement e : element.getAsJsonArray()) collect(e, into);
        else if (element.isJsonObject()) for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) collect(e.getValue(), into);
        else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String value = element.getAsString();
            if (value.startsWith("#")) return;
            ResourceLocation id = ResourceLocation.tryParse(value);
            if (id != null) into.add(id);
        }
    }

    private static SpriteContents make(ResourceManager resources, ResourceLocation texture, ResourceLocation id, int step) {
        Optional<Resource> resource = resources.getResource(TEXTURE_ID_CONVERTER.idToFile(texture));
        if (resource.isEmpty()) return null;
        try (InputStream in = resource.get().open(); NativeImage source = NativeImage.read(in)) {
            int width = source.getWidth(), height = source.getHeight();
            if (height != width) return null; // animated: the overlay keeps cutting the quad
            int keep = Math.round(height * step / 4F);
            NativeImage image = new NativeImage(width, height, true);
            for (int y = 0; y < height; y++)
                for (int x = 0; x < width; x++) image.setPixelRGBA(x, y, y < keep ? source.getPixelRGBA(x, y) : 0);
            return new SpriteContents(id, new FrameSize(width, height), image, ResourceMetadata.EMPTY);
        } catch (Exception e) {
            Equinox.LOGGER.warn("Equinox: couldn't make snow step texture {}: {}", id, e.toString());
            return null;
        }
    }
}
