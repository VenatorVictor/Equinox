package dev.venator.seasonsrework.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.serialization.MapCodec;
import dev.venator.seasonsrework.SeasonsRework;
import dev.venator.seasonsrework.crops.Crops;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.renderer.texture.atlas.SpriteSourceType;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.client.event.RegisterSpriteSourceTypesEvent;

import java.io.InputStream;
import java.io.Reader;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Dead and stopped crop textures, made when resources load (atlas source in assets/minecraft/atlases/blocks.json), so
 * no resource pack is needed: for every texture the models of farmland crops use, a frosted copy (icy blue), a frosted
 * copy with snow on its upper edges (outside), a withered one (dry brown), and for living crops that don't grow
 * (CropLookModel) dormant (cold) and wilted (heat) ones in 4 strengths, blended 25..100% over the crop's own colors.
 * Colors from the client config crop_colors: default greyish blue-green / dark yellow, or Ecliptic Seasons' winter
 * beige / dry orange (leaves only).
 * Sprite ids: seasonsrework:dead/<kind>/<namespace>/<path>. A resource pack can replace any of them with its own art at textures/dead/<kind>/<namespace>/<path>.png
 * (namespace seasonsrework).
 */
public final class DeadCropTextures implements SpriteSource {
    public enum Kind {
        FROST("frost", 0), FROST_SNOW("frost_snow", 0), HEAT("heat", 0),
        DORMANT_1("dormant_1", 1), DORMANT_2("dormant_2", 2), DORMANT_3("dormant_3", 3), DORMANT_4("dormant_4", 4),
        WILTED_1("wilted_1", 1), WILTED_2("wilted_2", 2), WILTED_3("wilted_3", 3), WILTED_4("wilted_4", 4);

        private static final Kind[] DORMANT = {DORMANT_1, DORMANT_2, DORMANT_3, DORMANT_4};
        private static final Kind[] WILTED = {WILTED_1, WILTED_2, WILTED_3, WILTED_4};

        final String id;
        /** Living crop looks: strength 1..4 (0 = dead crop). */
        final int strength;

        Kind(String id, int strength) {
            this.id = id;
            this.strength = strength;
        }

        boolean dormant() {
            return name().startsWith("DORMANT");
        }

        /** Crops.look: negative = dormant, positive = wilted, 0 = none. */
        public static Kind ofLook(int look) {
            if (look == 0) return null;
            int i = Math.min(4, Math.abs(look)) - 1;
            return look < 0 ? DORMANT[i] : WILTED[i];
        }

        public ResourceLocation sprite(ResourceLocation texture) {
            return ResourceLocation.fromNamespaceAndPath("seasonsrework", "dead/" + id + "/" + texture.getNamespace() + "/" + texture.getPath());
        }
    }

    private static final DeadCropTextures INSTANCE = new DeadCropTextures();
    private static final MapCodec<DeadCropTextures> CODEC = MapCodec.unit(INSTANCE);
    private static SpriteSourceType type;

    public static void register(RegisterSpriteSourceTypesEvent event) {
        type = event.register(ResourceLocation.fromNamespaceAndPath("seasonsrework", "dead_crops"), CODEC);
    }

    @Override
    public SpriteSourceType type() {
        return type;
    }

    @Override
    public void run(ResourceManager resources, Output output) {
        Set<Block> fruits = Crops.fruits();
        Set<ResourceLocation> fruitTextures = cropTextures(resources, fruits::contains);
        Set<ResourceLocation> textures = cropTextures(resources, Crops::mortal);
        textures.addAll(fruitTextures);
        for (ResourceLocation texture : textures) {
            boolean fruit = fruitTextures.contains(texture);
            for (Kind kind : Kind.values()) {
                if (kind == Kind.FROST_SNOW && fruit) continue; // dead fruit isn't drawn snowy (snow layers lie on it)
                ResourceLocation id = kind.sprite(texture);
                Optional<Resource> art = resources.getResource(TEXTURE_ID_CONVERTER.idToFile(id));
                if (art.isPresent()) output.add(id, art.get());
                else output.add(id, loader -> make(resources, texture, id, kind, fruit));
            }
        }
        SeasonsRework.LOGGER.info("Equinox: dead crop textures for {} crop textures", textures.size());
    }

    /** The textures named in the block models of the blocks given, crops or fruits (blockstates → models → parents). */
    private static Set<ResourceLocation> cropTextures(ResourceManager resources, java.util.function.Predicate<Block> blocks) {
        Set<ResourceLocation> models = new LinkedHashSet<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (!blocks.test(block)) continue;
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            JsonObject blockstate = json(resources, ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "blockstates/" + id.getPath() + ".json"));
            if (blockstate != null) collectModels(blockstate, models);
        }
        Set<ResourceLocation> textures = new LinkedHashSet<>();
        for (ResourceLocation model : models) {
            Set<ResourceLocation> seen = new HashSet<>();
            for (ResourceLocation current = model; current != null && seen.add(current); ) {
                JsonObject json = json(resources, ResourceLocation.fromNamespaceAndPath(current.getNamespace(), "models/" + current.getPath() + ".json"));
                if (json == null) break;
                if (json.has("textures"))
                    for (var entry : json.getAsJsonObject("textures").entrySet()) {
                        String value = entry.getValue().getAsString();
                        if (!value.startsWith("#") && !entry.getKey().equals("particle")) {
                            ResourceLocation texture = ResourceLocation.tryParse(value);
                            if (texture != null) textures.add(texture);
                        }
                    }
                current = json.has("parent") ? ResourceLocation.tryParse(json.get("parent").getAsString()) : null;
            }
        }
        return textures;
    }

    /** Every "model" in a blockstate file (variants or multipart, single or weighted lists). */
    private static void collectModels(JsonElement element, Set<ResourceLocation> models) {
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) collectModels(child, models);
        } else if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("model") && object.get("model").isJsonPrimitive()) {
                ResourceLocation model = ResourceLocation.tryParse(object.get("model").getAsString());
                if (model != null) models.add(model);
            }
            for (var entry : object.entrySet()) if (!entry.getKey().equals("model")) collectModels(entry.getValue(), models);
        }
    }

    private static JsonObject json(ResourceManager resources, ResourceLocation file) {
        Optional<Resource> resource = resources.getResource(file);
        if (resource.isEmpty()) return null;
        try (Reader reader = resource.get().openAsReader()) {
            JsonElement element = JsonParser.parseReader(reader);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** fruit: a pumpkin or melon texture, recolored all over (crops: only the leaves for the living looks). */
    private static SpriteContents make(ResourceManager resources, ResourceLocation texture, ResourceLocation id, Kind kind, boolean fruit) {
        Optional<Resource> resource = resources.getResource(TEXTURE_ID_CONVERTER.idToFile(texture));
        if (resource.isEmpty()) return null;
        boolean ecliptic = ClientConfig.CROP_COLORS.get() == ClientConfig.CropColors.ECLIPTIC;
        try (InputStream in = resource.get().open(); NativeImage source = NativeImage.read(in)) {
            int width = source.getWidth(), height = Math.min(source.getHeight(), width); // first frame of animations
            NativeImage image = new NativeImage(width, height, true);
            for (int y = 0; y < height; y++)
                for (int x = 0; x < width; x++) image.setPixelRGBA(x, y, look(source.getPixelRGBA(x, y), kind, ecliptic, fruit));
            if (kind == Kind.FROST_SNOW) snow(image);
            return new SpriteContents(id, new FrameSize(width, height), image, ResourceMetadata.EMPTY);
        } catch (Exception e) {
            SeasonsRework.LOGGER.warn("Equinox: couldn't make dead crop texture {}: {}", id, e.toString());
            return null;
        }
    }

    // Colors are ABGR (NativeImage's pixel order). Dark and light ends of each palette, by brightness.
    private static final int[] FROST_DARK = {38, 64, 112}, FROST_LIGHT = {196, 226, 255};
    private static final int[] HEAT_DARK = {64, 40, 18}, HEAT_LIGHT = {196, 152, 92};
    private static final int[] WILTED_DARK = {78, 60, 14}, WILTED_LIGHT = {214, 178, 64};

    /** Same shape and shading, new colors: brightness mapped onto an icy blue or a dry brown palette. */
    private static int recolor(int abgr, boolean heat) {
        int alpha = abgr >>> 24;
        if (alpha == 0) return abgr;
        int r = abgr & 0xFF, g = abgr >> 8 & 0xFF, b = abgr >> 16 & 0xFF;
        float light = (0.3F * r + 0.59F * g + 0.11F * b) / 255F;
        light = Math.min(1, light * 1.25F); // crop textures are fairly dark; spread them over the palette
        int[] dark = heat ? HEAT_DARK : FROST_DARK, bright = heat ? HEAT_LIGHT : FROST_LIGHT;
        int nr = (int) (dark[0] + (bright[0] - dark[0]) * light);
        int ng = (int) (dark[1] + (bright[1] - dark[1]) * light);
        int nb = (int) (dark[2] + (bright[2] - dark[2]) * light);
        return alpha << 24 | nb << 16 | ng << 8 | nr;
    }

    private static int look(int abgr, Kind kind, boolean ecliptic, boolean all) {
        if (kind.strength == 0) return recolor(abgr, kind == Kind.HEAT);
        int full = ecliptic ? (kind.dormant() ? palette(abgr, ECLIPTIC_WINTER, all) : palette(abgr, ECLIPTIC_DRY, all))
                : kind.dormant() ? dormant(abgr) : wilted(abgr, all);
        return blend(abgr, full, kind.strength / 4F);
    }

    // Ecliptic Seasons' base colors (RGB): winter #C1A173, dry #D1923F.
    private static final int[] ECLIPTIC_WINTER = {193, 161, 115}, ECLIPTIC_DRY = {209, 146, 63};

    /** Leaves only (not roots) unless all: the color, shaded by the pixel's brightness. */
    private static int palette(int abgr, int[] color, boolean all) {
        int alpha = abgr >>> 24;
        int r = abgr & 0xFF, g = abgr >> 8 & 0xFF, b = abgr >> 16 & 0xFF;
        if (alpha == 0 || !all && !leaf(r, g, b)) return abgr;
        float shade = 0.4F + 0.9F * Math.min(1, (0.3F * r + 0.59F * g + 0.11F * b) / 255F * 1.25F);
        int nr = Math.min(255, (int) (color[0] * shade)), ng = Math.min(255, (int) (color[1] * shade)), nb = Math.min(255, (int) (color[2] * shade));
        return alpha << 24 | nb << 16 | ng << 8 | nr;
    }

    /** Not a root or fruit: carrot orange, beetroot red. */
    private static boolean leaf(int r, int g, int b) {
        return g >= b && r <= g * 1.3F;
    }

    private static int blend(int from, int to, float f) {
        int out = from & 0xFF000000;
        for (int shift = 0; shift < 24; shift += 8) {
            int a = from >> shift & 0xFF, b = to >> shift & 0xFF;
            out |= Math.round(a + (b - a) * f) << shift;
        }
        return out;
    }

    /** Cold, not growing: most of the color gone, a little blue, a bit darker. */
    private static int dormant(int abgr) {
        int alpha = abgr >>> 24;
        if (alpha == 0) return abgr;
        float r = abgr & 0xFF, g = abgr >> 8 & 0xFF, b = abgr >> 16 & 0xFF, light = 0.3F * r + 0.59F * g + 0.11F * b;
        r = (light + (r - light) * 0.35F) * 0.85F;
        g = (light + (g - light) * 0.35F) * 0.88F;
        b = Math.min(255, (light + (b - light) * 0.35F) * 0.95F + 14);
        return alpha << 24 | (int) b << 16 | (int) g << 8 | (int) r;
    }

    /** Hot, not growing: leaves and ears go dark yellow (between the green and the dead brown); roots keep their color. */
    private static int wilted(int abgr, boolean all) {
        int alpha = abgr >>> 24;
        if (alpha == 0) return abgr;
        int r = abgr & 0xFF, g = abgr >> 8 & 0xFF, b = abgr >> 16 & 0xFF;
        if (!all && !leaf(r, g, b)) return abgr;
        float light = Math.min(1, (0.3F * r + 0.59F * g + 0.11F * b) / 255F * 1.25F);
        int nr = (int) (WILTED_DARK[0] + (WILTED_LIGHT[0] - WILTED_DARK[0]) * light);
        int ng = (int) (WILTED_DARK[1] + (WILTED_LIGHT[1] - WILTED_DARK[1]) * light);
        int nb = (int) (WILTED_DARK[2] + (WILTED_LIGHT[2] - WILTED_DARK[2]) * light);
        return alpha << 24 | nb << 16 | ng << 8 | nr;
    }

    /** Snow on the upper edges: every pixel with nothing above it turns white, the one below it half white. */
    private static void snow(NativeImage image) {
        int width = image.getWidth(), height = image.getHeight();
        boolean[] edge = new boolean[width * height];
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                edge[y * width + x] = opaque(image.getPixelRGBA(x, y)) && (y == 0 || !opaque(image.getPixelRGBA(x, y - 1)));
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) {
                if (edge[y * width + x]) image.setPixelRGBA(x, y, 0xFF << 24 | 0xFF << 16 | 0xF8 << 8 | 0xF0);
                else if (y > 0 && edge[(y - 1) * width + x] && opaque(image.getPixelRGBA(x, y)))
                    image.setPixelRGBA(x, y, blendWhite(image.getPixelRGBA(x, y)));
            }
    }

    private static boolean opaque(int abgr) {
        return abgr >>> 24 > 0;
    }

    private static int blendWhite(int abgr) {
        int r = ((abgr & 0xFF) + 0xF0) / 2, g = ((abgr >> 8 & 0xFF) + 0xF8) / 2, b = ((abgr >> 16 & 0xFF) + 0xFF) / 2;
        return abgr & 0xFF000000 | b << 16 | g << 8 | r;
    }
}
