package dev.venatorvictor.equinox.client.textures;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.venatorvictor.equinox.Equinox;
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
 * Reads seasonal texture files (assets/<ns>/season_textures/*.json). Each file swaps texture variables of block models per week
 * (14 a season, 56 a year, whole days each, on the calendar: week 0 = the first days of spring), in the biomes it
 * names. Weeks are named <season>_week_<1..14> (YearSteps). Supported: target, biomes, slices with start/end, season,
 * start_season/end_season, textures (one map, or a list picked per position), tint (texture variable to tint
 * index, -1 = untinted), and snow_textures/snow_tint, used where real snow would lie (same rule as the snow cover).
 * Our own additions: "snow_needs_sky": true = snow textures only on blocks open to the sky (full sky light), so plants
 * under trees or roofs stay snow-free; "snow_only_in_snow_layer": true = snow textures only on plants a snow mod
 * draws inside its snow layers (and there always, as the snow is right there; the top half of a two-block plant counts
 * when its bottom half is in the snow); "snow_needs_snow_above": true = snow textures only under a snow layer (leaves
 * with snow lying on them); "under_textures"/"under_tint" in a slice: a second texture set drawn a hair behind the
 * first (twigs behind falling leaves, untinted with -1); "over_textures"/"over_tint" in a slice: a texture set drawn a
 * hair in front (flowers on leaves; may stand alone in a slice, the block's own texture under it); "fruit_textures"/
 * "fruit_tint": drawn in front instead on leaf blocks that bear fruit and weren't picked this year (TreeFruit);
 * "snow_over_textures"/"snow_over_tint" (and "snow_fruit_textures" for bearing leaves): instead of snow_textures, snow
 * drawn in front of the slice's own textures, which keep their look and tint (leaves that keep their leaves); on
 * the side faces only (the snow layer lies on the top).
 * Not supported: climate, transition_textures.
 */
public final class SeasonalTextures {
    private static final int WEEKS = dev.venatorvictor.equinox.api.SeasonState.WEEKS;
    private static final int PER = dev.venatorvictor.equinox.api.SeasonState.WEEKS_PER_SEASON;
    /** Weeks when some loaded file changes its textures from the week before (a texture redraw is only needed then). */
    public static final boolean[] CHANGES = new boolean[WEEKS];
    private static final String DIRECTORY = "season_textures";
    private static final List<String> SEASONS = List.of("spring", "summer", "autumn", "winter");

    /** Whether any file was loaded; when false nothing is wrapped and week changes need no rebuild. */
    public static volatile boolean any = false;
    /** Whether any file has snow textures, which also change when the first snow falls. */
    public static volatile boolean anySnow = false;

    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        SeasonalModel.BY_LOCATION.clear();
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
        java.util.Arrays.fill(CHANGES, false);
        for (Rule rule : rules) {
            for (int w = 0; w < WEEKS; w++) {
                int prev = (w + WEEKS - 1) % WEEKS;
                if (rule.byWeek()[w] != rule.byWeek()[prev] || rule.snowByWeek()[w] != rule.snowByWeek()[prev]) CHANGES[w] = true;
                // Where leaf fall is held back (near warm zones) the week shown instead can change on its own.
                if (rule.fallWeeks() != null) for (int c = 0; c < rule.fallWeeks().length; c++)
                    if (rule.fallWeeks()[c][w] != rule.fallWeeks()[c][prev] || rule.held()[c][w] != rule.held()[c][prev]) CHANGES[w] = true;
            }
        }
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
            SeasonalModel seasonal = new SeasonalModel(entry.getValue(), modelRules.toArray(Rule[]::new));
            entry.setValue(seasonal);
            SeasonalModel.BY_LOCATION.put(entry.getKey(), seasonal);
            wrapped++;
        }
        Equinox.LOGGER.info("Equinox: {} seasonal texture files, {} block models", rules.size(), wrapped);
    }

    private static Set<TextureAtlasSprite> usedSprites(BakedModel model, RandomSource random) {
        Set<TextureAtlasSprite> used = new HashSet<>();
        // Some models need a block state and fail without one: they keep their textures.
        try {
            for (Direction side : Direction.values()) {
                random.setSeed(42L);
                for (BakedQuad quad : model.getQuads(null, side, random)) used.add(quad.getSprite());
            }
            random.setSeed(42L);
            for (BakedQuad quad : model.getQuads(null, null, random)) used.add(quad.getSprite());
        } catch (RuntimeException e) {
            return Set.of();
        }
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

        Variant[][] byWeek = new Variant[WEEKS][];
        Variant[][] snowByWeek = new Variant[WEEKS][];
        // fall: how far the slice's leaves have fallen (0 = all on, 1.. = leaf fall stages, highest = bare twigs).
        int[] fall = new int[WEEKS];
        // held_over_textures: drawn in front when the week's leaf fall is held back (default: its over_textures).
        Variant[][] heldOver = new Variant[WEEKS][];
        Set<TextureAtlasSprite> used = new HashSet<>();
        for (JsonElement element : GsonHelper.getAsJsonArray(json, "slices")) {
            JsonObject slice = element.getAsJsonObject();
            int[] range = range(slice);
            if (range == null) continue;
            Variant[] variants = variants(slice.get("textures"), slice.get("tint"), models, sprites, used);
            Variant[] snowVariants = variants(slice.get("snow_textures"), slice.get("snow_tint"), models, sprites, used);
            Variant[] under = variants(slice.get("under_textures"), slice.get("under_tint"), models, sprites, new HashSet<>());
            Variant[] over = variants(slice.get("over_textures"), slice.get("over_tint"), models, sprites, used);
            Variant[] fruit = variants(slice.get("fruit_textures"), slice.get("fruit_tint"), models, sprites, used);
            // over_percent: the share of all blocks (in percent) that show the over_textures (catkins); default all.
            int overPercent = GsonHelper.getAsInt(slice, "over_percent", 100);
            // Only drawn in front: the block keeps its own texture under it.
            if (variants.length == 0 && (over.length > 0 || fruit.length > 0)) variants = new Variant[]{new Variant(Map.of())};
            for (int i = 0; i < variants.length; i++) {
                if (under.length > 0) variants[i].under = under[i % under.length];
                if (over.length > 0) variants[i].over = over[i % over.length];
                if (fruit.length > 0) variants[i].fruit = fruit[i % fruit.length];
                if (over.length > 0 && overPercent < 100) {   // the same set without the over_textures, for the rest
                    Variant bare = new Variant(variants[i].remap);
                    bare.under = variants[i].under;
                    bare.fruit = variants[i].fruit;
                    variants[i].noOver = bare;
                    variants[i].overPercent = overPercent;
                }
            }
            // The snow set keeps what is drawn in front (catkins and seeds on snowy bare twigs).
            for (int i = 0; i < snowVariants.length; i++) {
                if (over.length > 0) snowVariants[i].over = over[i % over.length];
                if (fruit.length > 0) snowVariants[i].fruit = fruit[i % fruit.length];
            }
            // Snow drawn in front of leaves that keep their own look (and season color) under it.
            Variant[] snowOver = variants(slice.get("snow_over_textures"), slice.get("snow_over_tint"), models, sprites, used);
            Variant[] snowFruit = variants(slice.get("snow_fruit_textures"), slice.get("snow_fruit_tint"), models, sprites, used);
            if (snowVariants.length == 0 && snowOver.length > 0) {
                Variant[] base = variants.length > 0 ? variants : new Variant[]{new Variant(Map.of())};
                snowVariants = new Variant[base.length];
                for (int i = 0; i < base.length; i++) {
                    Variant snow = new Variant(base[i].remap);
                    snow.under = base[i].under;
                    snow.over = snowOver[i % snowOver.length];
                    // Only one set is drawn in front: without snow_fruit_textures bearing leaves show the snow too.
                    snow.fruit = snowFruit.length > 0 ? snowFruit[i % snowFruit.length] : null;
                    snow.front = true;
                    snowVariants[i] = snow;
                }
            }
            int sliceFall = GsonHelper.getAsInt(slice, "fall", 0);
            Variant[] sliceHeld = variants(slice.get("held_over_textures"), slice.get("held_over_tint"), models, sprites, used);
            // Later slices win.
            for (int week = range[0]; ; week = (week + 1) % WEEKS) {
                fall[week] = sliceFall;
                heldOver[week] = sliceHeld.length > 0 ? sliceHeld : null;
                byWeek[week] = variants;
                snowByWeek[week] = snowVariants;
                if (week == range[1]) break;
            }
        }
        if (used.isEmpty()) return null;
        SnowMode snowMode = GsonHelper.getAsBoolean(json, "snow_only_in_snow_layer", false) ? SnowMode.SNOW_LAYER
                : GsonHelper.getAsBoolean(json, "snow_needs_snow_above", false) ? SnowMode.SNOW_ABOVE
                : GsonHelper.getAsBoolean(json, "snow_needs_sky", false) ? SnowMode.SKY : SnowMode.COLD;
        int[][] fallWeeks = fallWeeks(fall);
        return new Rule(biomes(json.get("biomes")), byWeek, snowByWeek, used, snowMode, fallWeeks, held(fallWeeks, byWeek, heldOver));
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
        int start = weekOf(slice, "start"), end = weekOf(slice, "end");
        int season = season(slice, "season"), startSeason = season(slice, "start_season"), endSeason = season(slice, "end_season");
        if (start < 0) start = season >= 0 ? season * PER : startSeason >= 0 ? startSeason * PER : -1;
        if (end < 0) end = season >= 0 ? season * PER + PER - 1 : endSeason >= 0 ? endSeason * PER + PER - 1 : -1;
        boolean noStart = start < 0, noEnd = end < 0;
        if (noStart && noEnd) return new int[]{0, WEEKS - 1};
        if (noStart || noEnd) return null;
        return new int[]{start, end};
    }

    /** The week a slice's start or end names, -1 if missing or unknown. */
    private static int weekOf(JsonObject slice, String key) {
        return slice.has(key) ? YearSteps.week(GsonHelper.getAsString(slice, key)) : -1;
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
        if (json.isJsonPrimitive() && json.getAsString().equals("#equinox:tropical_zones")) {
            // Not a real tag: every land biome in a tropical climate zone (Zone.tropical: savanna, rainforest, monsoon,
            // untagged hot biomes), the biomes the "seasonal" files leave out.
            return biome -> ((ClimateBiome) (Object) biome.value()).equinox$climate() == ClimateBiome.SEASONAL
                    && ((ClimateBiome) (Object) biome.value()).equinox$zone().tropical;
        }
        if (json.isJsonPrimitive() && json.getAsString().startsWith("#")) {
            ResourceLocation id = ResourceLocation.parse(json.getAsString().substring(1));
            TagKey<Biome> tag = TagKey.create(Registries.BIOME, id);
            boolean format = id.getNamespace().equals("equinox");
            Set<ColorProfile> fallback = format ? profilesFor(id.getPath()) : Set.of();
            // "seasonal" also takes every biome with a seasonal climate (beaches, rivers: their colors barely change,
            // but they have winters), so a tree standing on a biome border loses its leaves as a whole.
            boolean climate = format && (id.getPath().equals("seasonal") || id.getPath().equals("rain/seasonal"));
            // Warm zones (Zone.tropical) never take the "seasonal" files, even when their color profile is a seasonal
            // one (sparse / bamboo jungle: SEASONAL_HOT), so their leaves don't fall; they have their own files.
            return biome -> {
                ClimateBiome c = (ClimateBiome) (Object) biome.value();
                if (climate && c.equinox$zone().tropical) return false;
                return biome.is(tag) || fallback.contains(c.equinox$colorProfile())
                        || climate && c.equinox$climate() == ClimateBiome.SEASONAL;
            };
        }
        Set<ResourceLocation> ids = new HashSet<>();
        for (String biome : strings(json)) ids.add(ResourceLocation.parse(biome));
        return biome -> biome.unwrapKey().map(key -> ids.contains(key.location())).orElse(false);
    }

    private static Set<ColorProfile> profilesFor(String path) {
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

    /**
     * For each leaf fall limit c (0 .. the file's highest fall - 1) and week: the week to show instead when that week's
     * leaves have fallen further than c: the last week before it whose fall is c (else the last one below c), so the
     * leaves stay as they were when they stopped falling, and nothing from spring shows in winter. Null when the file
     * has no leaf fall.
     */
    private static int[][] fallWeeks(int[] fall) {
        int max = 0;
        for (int f : fall) max = Math.max(max, f);
        if (max == 0) return null;
        int[][] weeks = new int[max][WEEKS];
        for (int c = 0; c < max; c++) {
            for (int w = 0; w < WEEKS; w++) {
                weeks[c][w] = w;
                if (fall[w] <= c) continue;
                int best = -1, bestFall = -1;
                for (int d = 1; d < WEEKS && bestFall != c; d++) {
                    int t = Math.floorMod(w - d, WEEKS);
                    if (fall[t] <= c && fall[t] > bestFall) {
                        best = t;
                        bestFall = fall[t];
                    }
                }
                if (best >= 0) weeks[c][w] = best;
            }
        }
        return weeks;
    }

    /**
     * For each leaf fall limit and week shown instead (fallWeeks): the earlier week's leaves with this week's front
     * (held_over_textures, else its catkins: over_textures, and its fruit), so flowers and catkins still come on time.
     */
    private static Variant[][][] held(int[][] fallWeeks, Variant[][] byWeek, Variant[][] heldOver) {
        if (fallWeeks == null) return null;
        Variant[][][] held = new Variant[fallWeeks.length][WEEKS][];
        Map<List<Object>, Variant[]> made = new HashMap<>();
        for (int c = 0; c < fallWeeks.length; c++) {
            for (int w = 0; w < WEEKS; w++) {
                int shown = fallWeeks[c][w];
                if (shown == w) continue;
                Variant[] base = byWeek[shown], now = byWeek[w], front = heldOver[w];
                Variant first = now != null && now.length > 0 ? now[0] : null;
                Variant over = front != null ? front[0] : first != null ? first.over : null;
                int percent = front != null ? 100 : first != null ? first.overPercent : 100;
                Variant fruit = first != null ? first.fruit : null;
                held[c][w] = made.computeIfAbsent(java.util.Arrays.asList(base, now, front), k -> {
                    Variant[] from = base != null && base.length > 0 ? base : new Variant[]{new Variant(Map.of())};
                    Variant[] out = new Variant[from.length];
                    for (int i = 0; i < from.length; i++) {
                        Variant b = from[i], v = new Variant(b.remap);
                        v.under = b.under;
                        v.over = over != null ? over : b.over;
                        v.fruit = fruit != null ? fruit : b.fruit;
                        v.overPercent = over != null ? percent : b.overPercent;
                        if (v.over != null && v.overPercent < 100) {
                            Variant bare = new Variant(b.remap);
                            bare.under = b.under;
                            bare.fruit = v.fruit;
                            v.noOver = bare;
                        }
                        out[i] = v;
                    }
                    return out;
                });
            }
        }
        return held;
    }

    /** When the snow textures show: where snow lies, there and under open sky, only inside a snow mod's snow, or only under a snow layer. */
    enum SnowMode { COLD, SKY, SNOW_LAYER, SNOW_ABOVE }

    /** One file. snowByWeek: textures used instead where snow lies (empty = none). */
    /** fallWeeks: see fallWeeks(), null without leaf fall; held: see held(), by limit and week. */
    record Rule(Predicate<Holder<Biome>> biomes, Variant[][] byWeek, Variant[][] snowByWeek, Set<TextureAtlasSprite> sprites,
                SnowMode snowMode, boolean hasSnow, int[][] fallWeeks, Variant[][][] held) {
        Rule(Predicate<Holder<Biome>> biomes, Variant[][] byWeek, Variant[][] snowByWeek, Set<TextureAtlasSprite> sprites, SnowMode snowMode,
             int[][] fallWeeks, Variant[][][] held) {
            this(biomes, byWeek, snowByWeek, sprites, snowMode, java.util.Arrays.stream(snowByWeek).anyMatch(v -> v != null && v.length > 0), fallWeeks, held);
        }

        /** The file's highest fall (bare), 0 without leaf fall. */
        int maxFall() {
            return fallWeeks == null ? 0 : fallWeeks.length;
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

        /** The same, for a bearing leaf block (its fruit drawn in front). */
        final ModelData fruitData;

        Variant(Map<TextureAtlasSprite, Swap> remap) {
            this.remap = remap.isEmpty() ? Map.of() : new HashMap<>(remap);
            this.data = ModelData.builder().with(SeasonalModel.VARIANT, this).build();
            this.fruitData = ModelData.builder().with(SeasonalModel.VARIANT, this).with(SeasonalModel.FRUIT, true).build();
        }

        /** Drawn a hair in front of this set (over_textures), and instead of it on bearing leaves (fruit_textures), or null. */
        @Nullable Variant over, fruit;
        /** With over_percent below 100: this set without over_textures, for the blocks that don't show them. */
        @Nullable Variant noOver;
        /** The share of all blocks (percent) showing over_textures (SeasonalModel picks which, by position). */
        int overPercent = 100;
        /** A snow set whose snow is drawn in front (snow_over_textures): shown whole at any snow step. */
        boolean front;
        private final Map<BakedQuad, Optional<BakedQuad>> lifted = new ConcurrentHashMap<>();

        /** Changes nothing: no swaps, nothing behind or in front. */
        boolean empty() {
            return remap.isEmpty() && under == null && over == null && fruit == null;
        }

        /** This (front) set's quad for an original, not yet swapped quad, a hair in front of it, or null. */
        @Nullable BakedQuad inFront(BakedQuad quad) {
            return lifted.computeIfAbsent(quad, q -> remap.containsKey(q.getSprite())
                    ? Optional.ofNullable(Overlay.lift(swap(q), Overlay.LIFT)) : Optional.empty()).orElse(null);
        }

        private final Overlay[] overlays = new Overlay[3];
        /** Drawn a hair behind this set (under_textures), or null. */
        @Nullable Variant under;
        private final Map<BakedQuad, Optional<BakedQuad>> behind = new ConcurrentHashMap<>();

        /** The under set's quad for this (original, not yet swapped) quad, a hair behind it, or null. */
        @Nullable BakedQuad behind(BakedQuad quad) {
            if (under == null) return null;
            return behind.computeIfAbsent(quad, q -> under.remap.containsKey(q.getSprite())
                    ? Optional.ofNullable(Overlay.lift(under.swap(q), -Overlay.LIFT)) : Optional.empty()).orElse(null);
        }

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
        static final float LIFT = 0.0015F;
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
                // A side face: as tall as the face, with the cut texture (shaders that wave plants move both alike).
                BakedQuad full = fullHeight(swapped, v, stride, y, nx, ny, nz);
                if (full != null) return full;
                // Otherwise keep its top part. Each lower corner moves up its edge to the cut.
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
            move(v, stride, nx * LIFT, ny * LIFT, nz * LIFT);
            return new BakedQuad(v, swapped.getTintIndex(), swapped.getDirection(), swapped.getSprite(), swapped.isShade(), swapped.hasAmbientOcclusion());
        }

        /**
         * The whole face, a hair in front, with the cut snow texture of this step (SnowStepTextures), or null when there
         * is none or the face doesn't show the whole texture upright (top of the face = top of the texture).
         */
        private @Nullable BakedQuad fullHeight(BakedQuad swapped, int[] v, int stride, float[] y, float nx, float ny, float nz) {
            TextureAtlasSprite from = swapped.getSprite();
            float top = Math.max(Math.max(y[0], y[1]), Math.max(y[2], y[3]));
            float bottom = Math.min(Math.min(y[0], y[1]), Math.min(y[2], y[3]));
            float height = from.getV1() - from.getV0();
            if (top - bottom < 1e-4F || height <= 0) return null;
            for (int i = 0; i < 4; i++) {
                float rel = (Float.intBitsToFloat(v[i * stride + 5]) - from.getV0()) / height;
                float expected = (top - y[i]) / (top - bottom); // 0 at the top, 1 at the bottom
                if (Math.abs(rel - expected) > 0.02F) return null;
            }
            net.minecraft.resources.ResourceLocation id = SnowStepTextures.id(from.contents().name(), step);
            TextureAtlasSprite to = net.minecraft.client.Minecraft.getInstance().getModelManager()
                    .getAtlas(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS).getSprite(id);
            if (!to.contents().name().equals(id)) return null;
            int[] out = v.clone();
            for (int i = 0; i < 4; i++) {
                int uv = i * stride + 4;
                float u = (Float.intBitsToFloat(out[uv]) - from.getU0()) / (from.getU1() - from.getU0());
                float w = (Float.intBitsToFloat(out[uv + 1]) - from.getV0()) / height;
                out[uv] = Float.floatToRawIntBits(to.getU0() + u * (to.getU1() - to.getU0()));
                out[uv + 1] = Float.floatToRawIntBits(to.getV0() + w * (to.getV1() - to.getV0()));
            }
            move(out, stride, nx * LIFT, ny * LIFT, nz * LIFT);
            return new BakedQuad(out, swapped.getTintIndex(), swapped.getDirection(), to, swapped.isShade(), swapped.hasAmbientOcclusion());
        }

        private static void move(int[] v, int stride, float dx, float dy, float dz) {
            for (int i = 0; i < 4; i++) {
                v[i * stride] = Float.floatToRawIntBits(Float.intBitsToFloat(v[i * stride]) + dx);
                v[i * stride + 1] = Float.floatToRawIntBits(Float.intBitsToFloat(v[i * stride + 1]) + dy);
                v[i * stride + 2] = Float.floatToRawIntBits(Float.intBitsToFloat(v[i * stride + 2]) + dz);
            }
        }

        /** The quad moved along its normal (negative: behind the face), or null if it has no area. */
        static @Nullable BakedQuad lift(BakedQuad quad, float distance) {
            int[] v = quad.getVertices().clone();
            int stride = v.length / 4;
            float ax = Float.intBitsToFloat(v[stride]) - Float.intBitsToFloat(v[0]);
            float ay = Float.intBitsToFloat(v[stride + 1]) - Float.intBitsToFloat(v[1]);
            float az = Float.intBitsToFloat(v[stride + 2]) - Float.intBitsToFloat(v[2]);
            float bx = Float.intBitsToFloat(v[2 * stride]) - Float.intBitsToFloat(v[0]);
            float by = Float.intBitsToFloat(v[2 * stride + 1]) - Float.intBitsToFloat(v[1]);
            float bz = Float.intBitsToFloat(v[2 * stride + 2]) - Float.intBitsToFloat(v[2]);
            float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length < 1e-6F) return null;
            move(v, stride, nx / length * distance, ny / length * distance, nz / length * distance);
            return new BakedQuad(v, quad.getTintIndex(), quad.getDirection(), quad.getSprite(), quad.isShade(), quad.hasAmbientOcclusion());
        }
    }

    private SeasonalTextures() {}
}
