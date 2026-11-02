package dev.venatorvictor.equinox.client.textures;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.venatorvictor.equinox.Equinox;
import dev.venatorvictor.equinox.client.colors.ColorTables;
import dev.venatorvictor.equinox.climate.ClimateBiome;
import dev.venatorvictor.equinox.climate.ColorProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.tags.TagKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.io.Reader;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Reads seasonal texture files (assets/<ns>/eclipticseasons/season_textures/*.json), so
 * resource packs in that format work here. Each file swaps texture variables of block models per year step
 * (solar terms: 24 a year, 2 per season part), in the biomes it names. Supported: target, biomes, slices with start/end, solar_term, season,
 * start_season/end_season, textures (one map, or a list picked per position), tint (texture variable to tint
 * index, -1 = untinted), and snow_textures/snow_tint, used where real snow would lie (same rule as the snow cover).
 * Our own additions: "snow_needs_sky": true = snow textures only on blocks open to the sky (full sky light), so plants
 * under trees or roofs stay snow-free; "snow_only_in_snow_layer": true = snow textures only on plants a snow mod
 * draws inside its snow layers (and there always, as the snow is right there; the top half of a two-block plant counts
 * when its bottom half is in the snow); "snow_needs_snow_above": true = snow textures only under a snow layer (leaves
 * with snow lying on them). Not supported: climate, transition_textures.
 */
public final class SeasonalTextures {
    private static final String DIRECTORY = "eclipticseasons/season_textures";
    private static final List<String> SEASONS = List.of("spring", "summer", "autumn", "winter");

    /** Whether any file was loaded; when false nothing is wrapped and term changes need no rebuild. */
    public static volatile boolean any = false;
    /** Whether any file has snow textures, which also change when the first snow falls. */
    public static volatile boolean anySnow = false;

    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        List<Rule> rules = new ArrayList<>();
        Function<Material, TextureAtlasSprite> sprites = event.getTextureGetter();
        for (Map.Entry<ResourceLocation, Resource> file : Minecraft.getInstance().getResourceManager()
                .listResources(DIRECTORY, path -> path.getPath().endsWith(".json")).entrySet()) {
            try (Reader reader = file.getValue().openAsReader()) {
                Rule rule = parse(file.getKey(), GsonHelper.parse(reader), event, sprites);
                if (rule != null) rules.add(rule);
            } catch (Exception e) {
                Equinox.LOGGER.warn("Equinox: skipping seasonal texture file {}: {}", file.getKey(), e.toString());
            }
        }
        any = !rules.isEmpty();
        anySnow = rules.stream().anyMatch(rule -> rule.hasSnow);
        if (!any) return;

        Set<TextureAtlasSprite> changed = new HashSet<>();
        for (Rule rule : rules) changed.addAll(rule.sprites);
        RandomSource random = RandomSource.create();
        int wrapped = 0;
        for (Map.Entry<ModelResourceLocation, BakedModel> entry : event.getModels().entrySet()) {
            if (entry.getKey().variant().equals("inventory")) continue; // items keep their texture
            Set<TextureAtlasSprite> used = usedSprites(entry.getValue(), random);
            List<Rule> modelRules = new ArrayList<>();
            for (Rule rule : rules) {
                for (TextureAtlasSprite sprite : rule.sprites) {
                    if (used.contains(sprite)) {
                        modelRules.add(rule);
                        break;
                    }
                }
            }
            if (modelRules.isEmpty()) continue;
            entry.setValue(new SeasonalModel(entry.getValue(), modelRules.toArray(Rule[]::new)));
            wrapped++;
        }
        Equinox.LOGGER.info("Equinox: {} seasonal texture files, {} block models", rules.size(), wrapped);
    }

    private static Set<TextureAtlasSprite> usedSprites(BakedModel model, RandomSource random) {
        Set<TextureAtlasSprite> used = new HashSet<>();
        for (Direction side : Direction.values()) {
            random.setSeed(42L);
            for (BakedQuad quad : model.getQuads(null, side, random)) used.add(quad.getSprite());
        }
        random.setSeed(42L);
        for (BakedQuad quad : model.getQuads(null, null, random)) used.add(quad.getSprite());
        return used;
    }

    private static Rule parse(ResourceLocation file, JsonObject json, ModelEvent.ModifyBakingResult event, Function<Material, TextureAtlasSprite> sprites) {
        String name = file.getPath().substring(DIRECTORY.length() + 1, file.getPath().length() - ".json".length());
        List<ResourceLocation> targets = new ArrayList<>();
        for (String target : strings(json.get("target"))) targets.add(ResourceLocation.parse(target));
        // Without a target, the file name is the block model: <ns>/.../season_textures/poppy.json -> <ns>:block/poppy.
        if (targets.isEmpty()) targets.add(ResourceLocation.fromNamespaceAndPath(file.getNamespace(), "block/" + name));

        List<BlockModel> models = new ArrayList<>();
        for (ResourceLocation target : targets) {
            // Packs may cover plants of mods that aren't installed; asking for those models only logs warnings.
            if (!target.getNamespace().equals("minecraft") && !ModList.get().isLoaded(target.getNamespace())) continue;
            UnbakedModel model = event.getModelBakery().getModel(target);
            if (model instanceof BlockModel blockModel) models.add(blockModel);
        }
        if (models.isEmpty()) return null;

        Variant[][] byTerm = new Variant[ColorTables.STEPS][];
        Variant[][] snowByTerm = new Variant[ColorTables.STEPS][];
        Set<TextureAtlasSprite> used = new HashSet<>();
        for (JsonElement element : GsonHelper.getAsJsonArray(json, "slices")) {
            JsonObject slice = element.getAsJsonObject();
            int[] range = range(slice);
            if (range == null) continue;
            Variant[] variants = variants(slice.get("textures"), slice.get("tint"), models, sprites, used);
            Variant[] snowVariants = variants(slice.get("snow_textures"), slice.get("snow_tint"), models, sprites, used);
            // Later slices win.
            for (int term = range[0]; ; term = (term + 1) % ColorTables.STEPS) {
                byTerm[term] = variants;
                snowByTerm[term] = snowVariants;
                if (term == range[1]) break;
            }
        }
        if (used.isEmpty()) return null;
        SnowMode snowMode = GsonHelper.getAsBoolean(json, "snow_only_in_snow_layer", false) ? SnowMode.SNOW_LAYER
                : GsonHelper.getAsBoolean(json, "snow_needs_snow_above", false) ? SnowMode.SNOW_ABOVE
                : GsonHelper.getAsBoolean(json, "snow_needs_sky", false) ? SnowMode.SKY : SnowMode.COLD;
        return new Rule(biomes(json.get("biomes")), byTerm, snowByTerm, used, snowMode);
    }

    private static Variant[] variants(JsonElement texturesJson, JsonElement tintJson, List<BlockModel> models,
                                      Function<Material, TextureAtlasSprite> sprites, Set<TextureAtlasSprite> used) {
        Map<String, Integer> tints = new HashMap<>();
        if (tintJson != null) for (Map.Entry<String, JsonElement> tint : tintJson.getAsJsonObject().entrySet()) tints.put(tint.getKey(), tint.getValue().getAsInt());
        List<Variant> variants = new ArrayList<>();
        for (JsonObject textures : objects(texturesJson)) {
            Map<TextureAtlasSprite, Swap> remap = new IdentityHashMap<>();
            for (Map.Entry<String, JsonElement> texture : textures.entrySet()) {
                TextureAtlasSprite to = sprites.apply(new Material(InventoryMenu.BLOCK_ATLAS, ResourceLocation.parse(texture.getValue().getAsString())));
                if (missing(to)) continue;
                Integer tint = tints.get(texture.getKey());
                for (BlockModel model : models) {
                    TextureAtlasSprite from = sprites.apply(model.getMaterial(texture.getKey()));
                    if (!missing(from) && (from != to || tint != null)) remap.put(from, new Swap(to, tint == null ? KEEP_TINT : tint));
                }
            }
            used.addAll(remap.keySet());
            variants.add(new Variant(remap));
        }
        return variants.toArray(Variant[]::new);
    }

    /** {first, last} year step (inclusive, may wrap past the end of the year), or null if unreadable. */
    private static int[] range(JsonObject slice) {
        int start = term(slice, "start"), end = term(slice, "end");
        int single = term(slice, "solar_term");
        int season = season(slice, "season");
        if (start < 0) start = single >= 0 ? single : season >= 0 ? season * 6 : season(slice, "start_season") * 6;
        if (end < 0) end = single >= 0 ? single : season >= 0 ? season * 6 + 5 : season(slice, "end_season") * 6 + 5;
        boolean noStart = start < 0, noEnd = end < 0;
        if (noStart && noEnd) return new int[]{0, ColorTables.STEPS - 1};
        if (noStart || noEnd) return null;
        return new int[]{start, end};
    }

    private static int term(JsonObject slice, String key) {
        return slice.has(key) ? YearSteps.index(GsonHelper.getAsString(slice, key)) : -1;
    }

    private static int season(JsonObject slice, String key) {
        if (!slice.has(key)) return -1;
        String season = GsonHelper.getAsString(slice, key).toLowerCase();
        return season.equals("fall") ? 2 : SEASONS.indexOf(season);
    }

    /**
     * Biome filter: a tag ("#ns:path") or a list of biome ids; none = everywhere. The format's own tags are
     * filled in by its mod at runtime, so without it they're empty: those fall back to the same biome profiles
     * the colors use.
     */
    private static Predicate<Holder<Biome>> biomes(JsonElement json) {
        if (json == null) return biome -> true;
        if (json.isJsonPrimitive() && json.getAsString().startsWith("#")) {
            ResourceLocation id = ResourceLocation.parse(json.getAsString().substring(1));
            TagKey<Biome> tag = TagKey.create(Registries.BIOME, id);
            Set<ColorProfile> fallback = id.getNamespace().equals("eclipticseasons") ? eclipticProfiles(id.getPath()) : Set.of();
            return biome -> biome.is(tag) || fallback.contains(((ClimateBiome) (Object) biome.value()).equinox$colorProfile());
        }
        Set<ResourceLocation> ids = new HashSet<>();
        for (String biome : strings(json)) ids.add(ResourceLocation.parse(biome));
        return biome -> biome.unwrapKey().map(key -> ids.contains(key.location())).orElse(false);
    }

    private static Set<ColorProfile> eclipticProfiles(String path) {
        String type = path.startsWith("rain/") ? path.substring(5) : path;
        return switch (type) {
            case "seasonal" -> EnumSet.of(ColorProfile.SEASONAL, ColorProfile.SEASONAL_HOT, ColorProfile.SEASONAL_COLD);
            case "seasonal/hot" -> EnumSet.of(ColorProfile.SEASONAL_HOT);
            case "seasonal/cold" -> EnumSet.of(ColorProfile.SEASONAL_COLD);
            case "monsoonal" -> EnumSet.of(ColorProfile.MONSOONAL);
            case "slightly" -> EnumSet.of(ColorProfile.SLIGHTLY);
            case "stable" -> EnumSet.of(ColorProfile.STABLE);
            default -> EnumSet.noneOf(ColorProfile.class);
        };
    }

    private static List<String> strings(JsonElement json) {
        List<String> list = new ArrayList<>();
        if (json == null) return list;
        if (json.isJsonArray()) for (JsonElement element : json.getAsJsonArray()) list.add(element.getAsString());
        else list.add(json.getAsString());
        return list;
    }

    private static List<JsonObject> objects(JsonElement json) {
        List<JsonObject> list = new ArrayList<>();
        if (json == null) return list;
        if (json instanceof JsonArray array) for (JsonElement element : array) list.add(element.getAsJsonObject());
        else list.add(json.getAsJsonObject());
        return list;
    }

    private static boolean missing(TextureAtlasSprite sprite) {
        return sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation());
    }

    /** When the snow textures show: where snow lies, there and under open sky, only inside a snow mod's snow, or only under a snow layer. */
    enum SnowMode { COLD, SKY, SNOW_LAYER, SNOW_ABOVE }

    /** One file. snowByTerm: textures used instead where snow lies (empty = none). */
    record Rule(Predicate<Holder<Biome>> biomes, Variant[][] byTerm, Variant[][] snowByTerm, Set<TextureAtlasSprite> sprites,
                SnowMode snowMode, boolean hasSnow) {
        Rule(Predicate<Holder<Biome>> biomes, Variant[][] byTerm, Variant[][] snowByTerm, Set<TextureAtlasSprite> sprites, SnowMode snowMode) {
            this(biomes, byTerm, snowByTerm, sprites, snowMode, java.util.Arrays.stream(snowByTerm).anyMatch(v -> v != null && v.length > 0));
        }
    }

    private static final int KEEP_TINT = Integer.MIN_VALUE;

    /** New sprite and tint index for quads using one sprite (KEEP_TINT = the model's own tint). */
    record Swap(TextureAtlasSprite to, int tint) {}

    /** One texture set: sprite swaps, plus the ModelData that carries it and its swapped quads. */
    static final class Variant {
        final Map<TextureAtlasSprite, Swap> remap;
        final ModelData data;
        // Filled from chunk-meshing threads.
        private final Map<BakedQuad, BakedQuad> quads = new ConcurrentHashMap<>();

        Variant(Map<TextureAtlasSprite, Swap> remap) {
            this.remap = remap.isEmpty() ? Map.of() : new HashMap<>(remap);
            this.data = ModelData.builder().with(SeasonalModel.VARIANT, this).build();
        }

        private final Overlay[] overlays = new Overlay[3];

        /** This (snowy) set drawn over the normal one for look step 1..3. */
        Overlay overlay(int step) {
            Overlay overlay = overlays[step - 1];
            if (overlay == null) overlays[step - 1] = overlay = new Overlay(this, step);
            return overlay;
        }

        BakedQuad swap(BakedQuad quad) {
            Swap swap = remap.get(quad.getSprite());
            return swap == null ? quad : quads.computeIfAbsent(quad, q -> retexture(q, swap));
        }

        /** Same quad, with its UVs moved from the old sprite's place in the atlas to the new one's. */
        private static BakedQuad retexture(BakedQuad quad, Swap swap) {
            TextureAtlasSprite to = swap.to();
            TextureAtlasSprite from = quad.getSprite();
            int[] vertices = quad.getVertices().clone();
            int stride = vertices.length / 4;
            for (int i = 0; i < 4; i++) {
                int uv = i * stride + 4; // block vertex format: position (3), color (1), then UV
                float u = (Float.intBitsToFloat(vertices[uv]) - from.getU0()) / (from.getU1() - from.getU0());
                float v = (Float.intBitsToFloat(vertices[uv + 1]) - from.getV0()) / (from.getV1() - from.getV0());
                vertices[uv] = Float.floatToRawIntBits(to.getU0() + u * (to.getU1() - to.getU0()));
                vertices[uv + 1] = Float.floatToRawIntBits(to.getV0() + v * (to.getV1() - to.getV0()));
            }
            int tint = swap.tint() == KEEP_TINT ? quad.getTintIndex() : swap.tint();
            return new BakedQuad(vertices, tint, quad.getDirection(), to, quad.isShade(), quad.hasAmbientOcclusion());
        }
    }

    /**
     * The snowy texture drawn over the normal one for look steps 1..3: on side faces only the top part (a quarter,
     * half, three quarters: snow settles from the top down), on top faces all of it from step 1, on bottom faces none.
     * A hair in front of the face so it always wins over the texture under it. Its own tint (snow_tint).
     */
    static final class Overlay {
        /** Distance in front of the face (blocks). */
        private static final float LIFT = 0.0015F;
        private final Variant snow;
        private final int step;
        private final Map<BakedQuad, Optional<BakedQuad>> quads = new ConcurrentHashMap<>();

        Overlay(Variant snow, int step) {
            this.snow = snow;
            this.step = step;
        }

        /** The snow quad drawn over this (original, not yet swapped) quad, or null. */
        @Nullable BakedQuad quad(BakedQuad quad) {
            return quads.computeIfAbsent(quad, q -> Optional.ofNullable(make(q))).orElse(null);
        }

        private @Nullable BakedQuad make(BakedQuad quad) {
            if (!snow.remap.containsKey(quad.getSprite())) return null;
            BakedQuad swapped = snow.swap(quad);
            int[] v = swapped.getVertices().clone();
            int stride = v.length / 4;
            float[] x = new float[4], y = new float[4], z = new float[4];
            for (int i = 0; i < 4; i++) {
                x[i] = Float.intBitsToFloat(v[i * stride]);
                y[i] = Float.intBitsToFloat(v[i * stride + 1]);
                z[i] = Float.intBitsToFloat(v[i * stride + 2]);
            }
            // The face's normal, from its corners.
            float ax = x[1] - x[0], ay = y[1] - y[0], az = z[1] - z[0];
            float bx = x[2] - x[0], by = y[2] - y[0], bz = z[2] - z[0];
            float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length < 1e-6F) return null;
            nx /= length;
            ny /= length;
            nz /= length;
            if (ny < -0.7F) return null; // bottom faces: no snow until fully snowy
            if (ny < 0.7F) {
                // A side face: keep its top part. Each lower corner moves up its edge to the cut.
                float top = Math.max(Math.max(y[0], y[1]), Math.max(y[2], y[3]));
                float bottom = Math.min(Math.min(y[0], y[1]), Math.min(y[2], y[3]));
                if (top - bottom < 1e-4F) return null;
                float cut = top - (top - bottom) * step / 4F;
                int[] source = v.clone();
                for (int i = 0; i < 4; i++) {
                    if (y[i] >= cut - 1e-5F) continue;
                    // Its partner: the neighbouring corner higher up (the other end of the side edge).
                    int prev = (i + 3) % 4, next = (i + 1) % 4;
                    int partner = y[prev] - y[i] > y[next] - y[i] ? prev : next;
                    if (y[partner] - y[i] < 1e-5F) continue;
                    float t = (cut - y[i]) / (y[partner] - y[i]);
                    for (int k : new int[]{0, 1, 2, 4, 5}) { // position and texture coordinates
                        float a = Float.intBitsToFloat(source[i * stride + k]), b = Float.intBitsToFloat(source[partner * stride + k]);
                        v[i * stride + k] = Float.floatToRawIntBits(a + (b - a) * t);
                    }
                }
            }
            for (int i = 0; i < 4; i++) {
                v[i * stride] = Float.floatToRawIntBits(Float.intBitsToFloat(v[i * stride]) + nx * LIFT);
                v[i * stride + 1] = Float.floatToRawIntBits(Float.intBitsToFloat(v[i * stride + 1]) + ny * LIFT);
                v[i * stride + 2] = Float.floatToRawIntBits(Float.intBitsToFloat(v[i * stride + 2]) + nz * LIFT);
            }
            return new BakedQuad(v, swapped.getTintIndex(), swapped.getDirection(), swapped.getSprite(), swapped.isShade(), swapped.hasAmbientOcclusion());
        }
    }

    private SeasonalTextures() {}
}
