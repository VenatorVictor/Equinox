"""Builds the "Seasons Rework Seasonal Plants" resource pack.

Usage: python make_plants.py <output folder> <jar> [<jar> ...] [--vanilla | --addon] [--preview <png>]
The first jar must be the Minecraft client jar. --vanilla: only plants whose block model is in it (the pack built
into the mod, src/main/resources/packs/seasonal_plants). --addon: only the others (a separate resource pack).
Jars: the Minecraft client resources jar, plus Vanilla Backport, Blooming Nature and Immersive Weathering (Renewed)
if their plants should be covered (plants whose texture isn't in any jar are skipped). Needs Pillow.

Flowers get one bloom window per year (real-world seasons, see BLOOM): a green bud, then a bud showing its color
before it, the petals opening in its first term, seed head after it, green leaves
the rest of the year (winter too: like grass, their green parts take the color of the block they stand on, which
the season colors turn olive-brown in winter). Grass and ferns get snow on top where real snow lies.
Every stage also has a snowy version, shown where real snow lies. Seasonal biomes only (tropical ones keep blooming).
All textures are made from the jars' own textures, so the style matches whatever is installed.

Flowers become two layers: the green parts in grey, tinted with the biome's grass color (Seasons Rework colors them,
so they follow the season colors), and the petals untinted on top. Their block models are replaced to draw both.
"""
import copy
import colorsys
import json
import sys
import zipfile
from io import BytesIO
from pathlib import Path

from PIL import Image, ImageDraw

TERMS = ["beginning_of_spring", "rain_water", "insects_awakening", "spring_equinox", "fresh_green", "grain_rain",
         "beginning_of_summer", "lesser_fullness", "grain_in_ear", "summer_solstice", "lesser_heat", "greater_heat",
         "beginning_of_autumn", "end_of_heat", "white_dew", "autumnal_equinox", "cold_dew", "first_frost",
         "beginning_of_winter", "light_snow", "heavy_snow", "winter_solstice", "lesser_cold", "greater_cold"]
# Term = 1/24 of the year; 2 per season part (0-1 early spring ... 22-23 late winter).
BIOMES = "#eclipticseasons:rain/seasonal"

SNOW = (240, 250, 252)
SNOW_SHADE = (205, 222, 230)
DRY = [(66, 50, 30), (118, 92, 56), (166, 138, 88)]       # dead stems and leaves, dark to light
SEED = [(96, 74, 46), (150, 120, 76), (196, 170, 120)]    # dried flower heads
PUFF = [(176, 176, 168), (214, 214, 206), (244, 244, 238)]  # dandelion clock

# (first term, last term) of the bloom, inclusive; may wrap past the end of the year.
# Models listed together share the window (tall plants: both halves).
# Real-world bloom seasons of a temperate climate (term 0 starts Feb 4, ~15 days each, term 3 = March equinox):
# the window is the season a patch of them is in flower, not one flower's life. Made-up and tropical plants keep
# rough windows.
BLOOM = {
    # vanilla
    "minecraft:block/dandelion": (3, 16),
    "minecraft:block/pink_petals_1 2 3 4": (3, 5),
    "minecraft:block/red_tulip": (3, 6), "minecraft:block/orange_tulip": (3, 6),
    "minecraft:block/white_tulip": (3, 6), "minecraft:block/pink_tulip": (3, 6),
    "minecraft:block/lily_of_the_valley": (5, 7),
    "minecraft:block/lilac_top lilac_bottom": (5, 7),
    "minecraft:block/azure_bluet": (4, 9),
    "minecraft:block/allium": (6, 8),
    "minecraft:block/peony_top peony_bottom": (6, 8),
    "minecraft:block/poppy": (6, 12),
    "minecraft:block/oxeye_daisy": (6, 14),
    "minecraft:block/cornflower": (7, 14),
    "minecraft:block/rose_bush_top rose_bush_bottom": (7, 14),
    "minecraft:block/blue_orchid": (7, 12),
    "minecraft:block/torchflower": (8, 13),
    "minecraft:block/sunflower_top sunflower_bottom": (10, 15),
    # Vanilla Backport
    "minecraft:block/wildflowers_1 2 3 4": (4, 12),
    # Blooming Nature
    "bloomingnature:block/daphne": (0, 4),
    "bloomingnature:block/forsythia": (2, 4),
    "bloomingnature:block/anemone": (2, 6),
    "bloomingnature:block/bluebell": (4, 6),
    "bloomingnature:block/mountain_snowbell": (4, 8),
    "bloomingnature:block/freesia_pink": (2, 6), "bloomingnature:block/freesia_yellow": (2, 6),
    "bloomingnature:block/myosotis": (4, 8),
    "bloomingnature:block/amaryllis": (3, 6),
    "bloomingnature:block/desert_lily": (2, 6),
    "bloomingnature:block/genisteae": (4, 8),
    "bloomingnature:block/bird_of_paradise": (4, 12),
    "bloomingnature:block/lupine_blue": (6, 10), "bloomingnature:block/lupine_purple": (6, 10),
    "bloomingnature:block/tall_lupine_blue_top tall_lupine_blue_bottom": (6, 10),
    "bloomingnature:block/tall_lupine_purple_top tall_lupine_purple_bottom": (6, 10),
    "bloomingnature:block/mountain_laurel": (6, 8),
    "bloomingnature:block/tall_mountain_laurel_top tall_mountain_laurel_bottom": (6, 8),
    "bloomingnature:block/bottlebrushes": (5, 10),
    "bloomingnature:block/foxglove_pink": (7, 12), "bloomingnature:block/foxglove_white": (7, 12),
    "bloomingnature:block/goatsbeard": (7, 10),
    "bloomingnature:block/white_orchid": (6, 10),
    "bloomingnature:block/munstead": (7, 12),
    "bloomingnature:block/begonie": (7, 16),
    "bloomingnature:block/gladiolus": (10, 15),
    "bloomingnature:block/hyssop": (10, 15),
    "bloomingnature:block/cardinal_top cardinal_bottom": (10, 15),
    "bloomingnature:block/joe_pye": (10, 15),
    "bloomingnature:block/wild_sunflower_top wild_sunflower_bottom": (10, 15),
    "bloomingnature:block/sunflower_bottom": (10, 15),
    "bloomingnature:block/golden_rod": (12, 16),
    # Immersive Weathering
    "immersive_weathering:block/weeds_0 1 2 3 4 5 6 7": (6, 13),
}

GRASS = [
    "minecraft:block/short_grass", "minecraft:block/tall_grass_bottom", "minecraft:block/tall_grass_top",
    "minecraft:block/fern", "minecraft:block/large_fern_bottom", "minecraft:block/large_fern_top",
    "minecraft:block/bush",
    "bloomingnature:block/bluff_grass_0", "bloomingnature:block/bluff_grass_1", "bloomingnature:block/bluff_grass_2",
    "bloomingnature:block/tall_bluff_grass_bottom_0", "bloomingnature:block/tall_bluff_grass_bottom_1",
    "bloomingnature:block/tall_bluff_grass_bottom_2", "bloomingnature:block/tall_bluff_grass_top_0",
    "bloomingnature:block/tall_bluff_grass_top_1", "bloomingnature:block/tall_bluff_grass_top_2",
    "bloomingnature:block/red_oat_grass_1", "bloomingnature:block/red_oat_grass_2", "bloomingnature:block/red_oat_grass_3",
    "bloomingnature:block/tall_red_oat_grass_1_bottom", "bloomingnature:block/tall_red_oat_grass_1_top",
    "bloomingnature:block/tall_red_oat_grass_2_bottom", "bloomingnature:block/tall_red_oat_grass_2_top",
    "bloomingnature:block/silkgrass_1", "bloomingnature:block/silkgrass_2", "bloomingnature:block/silkgrass_3",
    "bloomingnature:block/tall_silkgrass_1_bottom", "bloomingnature:block/tall_silkgrass_1_top",
    "bloomingnature:block/tall_silkgrass_2_bottom", "bloomingnature:block/tall_silkgrass_2_top",
    "bloomingnature:block/sungrass_1", "bloomingnature:block/sungrass_2", "bloomingnature:block/sungrass_3",
    "bloomingnature:block/sungrass_4", "bloomingnature:block/sungrass_5",
    "bloomingnature:block/tall_sungrass_1_bottom", "bloomingnature:block/tall_sungrass_1_top",
    "bloomingnature:block/tall_sungrass_2_bottom", "bloomingnature:block/tall_sungrass_2_top",
]

# Crops: green parts in the grass color, the rest (ripe wheat, carrots) as it is, snow on top.
# Snow: crops get it on top whenever it lies there and they're under open sky; every other plant only when it sits
# inside a Snow! Real Magic snow layer (otherwise it just keeps its color).
CROPS = ["minecraft:block/wheat_stage0 1 2 3 4 5 6 7", "minecraft:block/carrots_stage0 1 2 3",
         "minecraft:block/potatoes_stage0 1 2 3", "minecraft:block/beetroots_stage0 1 2 3",
         "farmersdelight:block/cabbages_stage0 1 2 3 4 5 6 7", "farmersdelight:block/onions_stage0 1 2 3",
         "farmersdelight:block/budding_tomatoes_stage0 1 2 3", "farmersdelight:block/tomatoes_stage0 1 2 3",
         "farmersdelight:block/tomatoes_on_rope_stage0 1 2 3", "farmersdelight:block/tomatoes_old_stage0 1 2 3",
         "farmersdelight:block/rice_stage0 1 2 3", "farmersdelight:block/rice_panicles_stage0 1 2 3"]
# Farmer's Delight's wild crops: plants like any other (Seasons Rework puts them in Snow! Real Magic's plants tag).
WILD = ["farmersdelight:block/wild_cabbages", "farmersdelight:block/wild_onions", "farmersdelight:block/wild_tomatoes",
        "farmersdelight:block/wild_carrots", "farmersdelight:block/wild_potatoes", "farmersdelight:block/wild_beetroots",
        "farmersdelight:block/wild_rice_bottom", "farmersdelight:block/wild_rice_top"]
# Pumpkin and melon stems: crops too, but their texture is already grey (tinted; Seasons Rework gives them the grass
# color instead of vanilla's ripening green-to-yellow) and each growth stage shows only the top rows of it.
STEMS = ["minecraft:block/pumpkin_stem_stage0 1 2 3 4 5 6 7", "minecraft:block/melon_stem_stage0 1 2 3 4 5 6 7",
         "minecraft:block/attached_pumpkin_stem", "minecraft:block/attached_melon_stem"]
# Evergreen shrubs (like lingonberry), saplings and dead bushes: their own look all year, snow only inside a snow layer.
# Saplings: every "*_sapling" cross model in the jars.
EVERGREEN = ["minecraft:block/sweet_berry_bush_stage0 1 2 3", "minecraft:block/dead_bush"]
# Bamboo: a crop too (snow under open sky): leaves and shoot keep their look, the stalk gets snow on its top face only.
BAMBOO_PARTS = [("minecraft:block/bamboo_small_leaves", "texture"), ("minecraft:block/bamboo_large_leaves", "texture"),
                ("minecraft:block/bamboo_sapling", "cross")]
BAMBOO_STALK = "minecraft:block/bamboo1_age0"
BAMBOO_TOP = (13, 0, 16, 3)  # the stalk's up face in bamboo_stalk.png (2 px thin stalks use the corner of it)

FLOWER_STAGES = ["bud_green", "bud_color", "opening", "bloom", "seed", "leaves"]
# Opening, like a real flower: a small green bud (closed sepals), then a bigger bud with the petal color showing,
# then the petals opening from the middle out. (part of the flower kept from its center, share still green)
OPENING = {"bud_green": (0.30, 0.80), "bud_color": (0.60, 0.25), "opening": (0.80, 0.08)}
# Green parts are levelled to the grass block top's grey (average 0.58, from 0.44 to 0.76), so with the same grass
# tint every plant shows the same green as the ground.
GRASS_TOP_GREY, GREY_MIN, GREY_MAX = 0.58, 0.44, 0.76
PLAINS = (145, 189, 89)  # previews only


class Jars:
    """Reads models and textures from the jars; later jars win, like resource packs stacked on top."""

    def __init__(self, paths):
        self.zips = [zipfile.ZipFile(p) for p in paths]

    def read(self, path):
        for z in reversed(self.zips):
            if path in z.namelist():
                return z.read(path)
        return None

    def vanilla(self, model_id):
        """True when the block model comes with Minecraft itself (the first jar)."""
        ns, path = model_id.split(":")
        return f"assets/{ns}/models/{path}.json" in self.zips[0].namelist()

    def model(self, model_id):
        ns, path = model_id.split(":")
        data = self.read(f"assets/{ns}/models/{path}.json")
        return json.loads(data) if data else None

    def textures(self, model_id):
        """Texture variables of a block model, following its parents."""
        out, seen = {}, set()
        while model_id and model_id not in seen:
            seen.add(model_id)
            model = self.model(model_id)
            if model is None:
                break
            for key, value in model.get("textures", {}).items():
                out.setdefault(key, value)
            parent = model.get("parent")
            model_id = None if parent is None else parent if ":" in parent else "minecraft:" + parent
        return {k: (v if ":" in v else "minecraft:" + v) for k, v in out.items()
                if not v.startswith("#") and k != "particle"}

    def tinted(self, model_id):
        """True when the model already has tinted faces (grass, ferns: grey textures colored by the game)."""
        seen = set()
        while model_id and model_id not in seen:
            seen.add(model_id)
            model = self.model(model_id)
            if model is None:
                return False
            if "elements" in model:
                return any("tintindex" in face for element in model["elements"] for face in element.get("faces", {}).values())
            parent = model.get("parent")
            model_id = None if parent is None else parent if ":" in parent else "minecraft:" + parent
        return False

    def saplings(self):
        """Every '<ns>:block/*_sapling' model drawn as a cross."""
        found = set()
        for z in self.zips:
            for name in z.namelist():
                parts = name.split("/")
                if len(parts) == 5 and parts[0] == "assets" and parts[2:4] == ["models", "block"] and parts[4].endswith("_sapling.json"):
                    model_id = f"{parts[1]}:block/{parts[4][:-5]}"
                    if "cross" in self.textures(model_id):
                        found.add(model_id)
        return sorted(found)

    def image(self, texture_id):
        ns, path = texture_id.split(":")
        data = self.read(f"assets/{ns}/textures/{path}.png")
        if data is None:
            return None
        image = Image.open(BytesIO(data)).convert("RGBA")
        # Animated strips: first frame only.
        return image.crop((0, 0, image.width, image.width)) if image.height > image.width else image


def expand(key):
    """'ns:block/a b c' -> ['ns:block/a', 'ns:block/b', 'ns:block/c']; bare numbers reuse the first name's stem."""
    ns_path, *rest = key.split(" ")
    ns, path = ns_path.split(":")
    folder, first = path.rsplit("/", 1)
    names = [first]
    for part in rest:
        names.append(first.rstrip("0123456789") + part if part.isdigit() else part)
    return [f"{ns}:{folder}/{name}" for name in names]


def luma(rgb):
    r, g, b = rgb[:3]
    return (0.299 * r + 0.587 * g + 0.114 * b) / 255


def ramp(palette, t):
    t = min(max(t, 0.0), 1.0) * (len(palette) - 1)
    i = min(int(t), len(palette) - 2)
    f = t - i
    return tuple(round(palette[i][c] + (palette[i + 1][c] - palette[i][c]) * f) for c in range(3))


def blend(a, b, f):
    return tuple(round(a[c] + (b[c] - a[c]) * f) for c in range(3))


def is_foliage(rgb):
    h, s, v = colorsys.rgb_to_hsv(*(c / 255 for c in rgb[:3]))
    # Pale pixels are petals even when slightly green or blue (white flowers).
    if v > 0.8 and s < 0.35:
        return False
    return v < 0.22 or (0.17 <= h <= 0.47 and s >= 0.12)


def petal_groups(image):
    """Connected groups of non-green opaque pixels (flower heads)."""
    px = image.load()
    w, h = image.size
    petals = {(x, y) for y in range(h) for x in range(w) if px[x, y][3] and not is_foliage(px[x, y])}
    groups = []
    while petals:
        stack = [petals.pop()]
        group = []
        while stack:
            x, y = stack.pop()
            group.append((x, y))
            for n in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                if n in petals:
                    petals.remove(n)
                    stack.append(n)
        groups.append(group)
    return groups


def foliage_color(image):
    px = image.load()
    pixels = [px[x, y] for y in range(image.height) for x in range(image.width) if px[x, y][3] and is_foliage(px[x, y]) and luma(px[x, y]) > 0.2]
    if not pixels:
        return (62, 110, 44)
    return tuple(sum(p[c] for p in pixels) // len(pixels) for c in range(3))


def shrink(group, keep):
    """Pixels of a group within `keep` (0..1) of its radius from its center; never empty."""
    cx = sum(x for x, _ in group) / len(group)
    cy = sum(y for _, y in group) / len(group)
    dist = {p: ((p[0] - cx) ** 2 + (p[1] - cy) ** 2) ** 0.5 for p in group}
    radius = max(dist.values()) or 1
    kept = [p for p in group if dist[p] <= keep * radius]
    return kept or [min(group, key=dist.get)]


def flower_stage(image, stage, puff=False, head=False):
    """head: a flower face that stays on the plant (sunflower front): after blooming it keeps its dried seed head."""
    return stage_layers(image, stage, puff, head)[0]


def neighbours(x, y):
    return [(x + dx, y + dy) for dx in (-1, 0, 1) for dy in (-1, 0, 1) if dx or dy]


def ground(out, kept_groups, petals, green):
    """Buds and seed heads that lost the petals holding them get a stem down to the plant; then tiny leftover
    specks (3 pixels or less, not joined to the lowest part of the plant) are removed. Bigger floating parts stay:
    bushy plants (lilac, rose bush) have leaf sprays that only touch the rest through the flowers."""
    px = out.load()
    w, h = out.size
    opaque = lambda p: 0 <= p[0] < w and 0 <= p[1] < h and px[p][3] > 0
    for kept in kept_groups:
        if not kept or any(opaque(n) and n not in kept for p in kept for n in neighbours(*p)):
            continue
        bottom = max(y for _, y in kept)
        xs = [x for x, y in kept if y == bottom]
        x, path = round(sum(xs) / len(xs)), []
        for y in range(bottom + 1, h):
            if opaque((x, y)):
                break
            path.append((x, y))
        else:
            continue
        for p in path:
            px[p] = green + (255,)
    pixels = {(x, y) for y in range(h) for x in range(w) if px[x, y][3]}
    if not pixels:
        return
    lowest = max(y for _, y in pixels)
    while pixels:
        stack = [pixels.pop()]
        piece = set(stack)
        while stack:
            for n in neighbours(*stack.pop()):
                if n in pixels:
                    pixels.remove(n)
                    piece.add(n)
                    stack.append(n)
        if len(piece) <= 3 and all(y != lowest for _, y in piece):
            for p in piece:
                px[p] = (0, 0, 0, 0)
                petals.discard(p)


def stage_layers(image, stage, puff=False, head=False, grounded=False):
    """The plant at a stage, and which of its pixels are petals (untinted). grounded: no floating pieces."""
    if stage == "bloom":
        return image, petal_mask(image)
    if head and stage in ("leaves", "bare"):
        stage = "seed"
    out = image.copy()
    px = out.load()
    green = foliage_color(image)
    petals, kept_groups = set(), []
    for group in petal_groups(image):
        kept = set()
        if stage in OPENING:
            kept = set(shrink(group, OPENING[stage][0]))
        elif stage == "seed":
            kept = set(group) if puff else set(shrink(group, 0.65))
        petals |= kept
        kept_groups.append(kept)
        for x, y in group:
            r, g, b, a = px[x, y]
            if (x, y) not in kept:
                px[x, y] = (0, 0, 0, 0)
            elif stage in OPENING:
                px[x, y] = blend((r, g, b), green, OPENING[stage][1]) + (a,)
            else:
                px[x, y] = ramp(PUFF if puff else SEED, luma((r, g, b))) + (a,)
    if grounded:
        ground(out, kept_groups, petals, green)
    if stage == "bare":
        for y in range(out.height):
            for x in range(out.width):
                if px[x, y][3]:
                    px[x, y] = ramp(DRY, luma(px[x, y]) * 1.4) + (px[x, y][3],)
    return out, petals


def snowy(image, top=True):
    """Snow on every pixel that is open to the sky above it (top=False: the top edge is covered, e.g. a tall plant's bottom)."""
    out = image.copy()
    px = out.load()
    src = image.load()
    for y in range(out.height):
        for x in range(out.width):
            if src[x, y][3] and (top if y == 0 else not src[x, y - 1][3]):
                px[x, y] = SNOW + (255,)
                if y + 1 < out.height and src[x, y + 1][3] and (y + 2 >= out.height or src[x, y + 2][3]):
                    px[x, y + 1] = SNOW_SHADE + (255,)
    return out


def petal_mask(image):
    return {p for group in petal_groups(image) for p in group}


def levelled(image, mask=(), ref=None):
    """Opaque pixels outside `mask` in grey, at the grass block top's brightness (for the grass tint).
    ref: the brightness that becomes the grass top's average (default: this image's own average)."""
    out = Image.new("RGBA", image.size)
    src, px = image.load(), out.load()
    pixels = [luma(src[x, y]) for y in range(image.height) for x in range(image.width) if src[x, y][3] and (x, y) not in mask]
    if ref is None:
        ref = (sum(pixels) / len(pixels)) if pixels else 0.5
    for y in range(image.height):
        for x in range(image.width):
            if src[x, y][3] and (x, y) not in mask:
                g = round(min(max(luma(src[x, y]) / ref * GRASS_TOP_GREY, GREY_MIN), GREY_MAX) * 255)
                px[x, y] = (g, g, g, src[x, y][3])
    return out


def grey_stem(image, mask):
    """Green parts in grey (for the grass tint); petals left out."""
    return levelled(image, mask)


def only(image, mask):
    out = Image.new("RGBA", image.size)
    src, px = image.load(), out.load()
    for x, y in mask:
        px[x, y] = src[x, y]
    return out


def tint(image, color):
    out = image.copy()
    px = out.load()
    for y in range(out.height):
        for x in range(out.width):
            if px[x, y][3]:
                px[x, y] = tuple(round(px[x, y][c] * color[c] / 255) for c in range(3)) + (px[x, y][3],)
    return out


def same(a, b):
    return a.tobytes() == b.tobytes()


def flower_term(window, term):
    first, last = window
    length = (last - first) % 24
    offset = (term - first) % 24
    if offset <= length:
        # the first bloom term shows the petals still opening (windows of 3 terms or more)
        return "opening" if offset == 0 and length >= 2 else "bloom"
    if (first - term) % 24 == 2:
        return "bud_green"
    if (first - term) % 24 == 1:
        return "bud_color"
    if (term - last) % 24 in (1, 2):
        return "seed"
    return "leaves"


def slices_for(states):
    """Group a 24-term list of stage names into start/end slices (term 0 joins a run that wraps around)."""
    runs = []
    for term, state in enumerate(states):
        if runs and runs[-1][2] == state:
            runs[-1][1] = term
        else:
            runs.append([term, term, state])
    if len(runs) > 1 and runs[0][2] == runs[-1][2]:
        runs[0][0] = runs[-1][0]
        runs.pop()
    return runs


class Pack:
    def __init__(self, out, jars):
        self.out, self.jars = out, jars
        self.textures = out / "assets/seasonsrework/textures/block/plants"
        self.rules = out / "assets/seasonsrework/eclipticseasons/season_textures"
        self.textures.mkdir(parents=True, exist_ok=True)
        self.rules.mkdir(parents=True, exist_ok=True)
        self.preview = []  # (label, [images])

    def save(self, texture_id, suffix, image):
        ns, path = texture_id.split(":")
        name = f"{ns}_{path.rsplit('/', 1)[1]}_{suffix}"
        image.save(self.textures / f"{name}.png")
        return f"seasonsrework:block/plants/{name}"

    def rule(self, model_id, runs, textures_for, snow_for, tint=None, untinted=None, biomes=BIOMES, snow_mode="snow_only_in_snow_layer"):
        """untinted(state): texture variables to draw without tint in that state (default: all swapped ones)."""
        slices = []
        for first, last, state in runs:
            textures, snow = textures_for(state), snow_for(state)
            if not textures and not snow:
                continue
            piece = {"start": TERMS[first], "end": TERMS[last]}
            if textures:
                piece["textures"] = textures
                if tint is not None:
                    piece["tint"] = {k: tint for k in textures}
                elif untinted and untinted(state):
                    piece["tint"] = {k: -1 for k in untinted(state)}
            if snow:
                piece["snow_textures"] = snow
                piece["snow_tint"] = {k: -1 for k in (untinted(state) if untinted else snow)}
                if not piece["snow_tint"]:
                    del piece["snow_tint"]
            slices.append(piece)
        if not slices:
            return
        ns, path = model_id.split(":")
        # snow_mode: "snow_needs_sky" (crops) or "snow_only_in_snow_layer" (other plants); see CROPS.
        data = {"target": [model_id]} | ({"biomes": biomes} if biomes else {}) | {snow_mode: True, "slices": slices}
        (self.rules / f"{ns}_{path.rsplit('/', 1)[1]}.json").write_text(json.dumps(data, indent=2))

    def tinted_flower(self, model_id, window):
        """A flower whose "cross" becomes two layers: tinted grey stem + untinted petals ("flower")."""
        variables = self.jars.textures(model_id)
        cross_id = variables.get("cross")
        image = self.jars.image(cross_id) if cross_id else None
        if image is None:
            return False
        puff = "dandelion" in model_id
        top = not model_id.endswith("bottom") and "_bottom_" not in model_id
        mask = petal_mask(image)
        src = image.load()
        foliage = [luma(src[x, y]) for y in range(image.height) for x in range(image.width)
                   if src[x, y][3] and (x, y) not in mask]
        ref = sum(foliage) / len(foliage) if foliage else 0.5
        base_stem = levelled(image, mask, ref)
        stem = self.save(cross_id, "stem", base_stem)
        empty = self.save(cross_id, "empty", Image.new("RGBA", image.size))
        petals, petals_snow, stems, preview = {}, {}, {}, []
        for stage in FLOWER_STAGES:
            staged, kept = stage_layers(image, stage, puff, grounded=True)
            layer = only(staged, kept)
            stage_stem = levelled(staged, kept, ref)
            # A stem changed by grounding (new stalk under a bud, floating bits removed) gets its own texture.
            stems[stage] = None if same(stage_stem, base_stem) else self.save(cross_id, f"{stage}_stem", stage_stem)
            petals[stage] = self.save(cross_id, f"{stage}_petals", layer)
            # Snow goes on the petal layer (untinted, drawn on top of the stem).
            snowed, overlay = snowy(staged, top), layer.copy()
            spx, opx, tpx = snowed.load(), overlay.load(), staged.load()
            for y in range(image.height):
                for x in range(image.width):
                    if spx[x, y] != tpx[x, y]:
                        opx[x, y] = spx[x, y]
            petals_snow[stage] = self.save(cross_id, f"{stage}_petals_snow", overlay)
            if stage == "leaves":
                snow_preview = tint(stage_stem, PLAINS)
                snow_preview.alpha_composite(overlay)
            shown = tint(stage_stem, PLAINS)
            shown.alpha_composite(layer)
            preview.append(shown)
        preview.append(snow_preview)
        self.write_two_layer_model(model_id, stem, petals["bloom"], cross_id)

        # Other texture variables (the sunflower's face) change as single untinted layers.
        others = {}
        for var, texture_id in variables.items():
            other = self.jars.image(texture_id) if var != "cross" else None
            if other is None:
                continue
            others[var] = {}
            for stage in FLOWER_STAGES:
                staged = flower_stage(other, stage, puff, var == "front")
                others[var][stage] = (None if same(staged, other) else self.save(texture_id, stage, staged),
                                      self.save(texture_id, stage + "_snow", snowy(staged, top)))

        def own_stem(state):
            return {"cross": stems[state]} if stems.get(state) else {}

        def textures(state):
            out = {} if state == "bloom" else {"flower": petals[state]} | own_stem(state)
            out.update({v: others[v][state][0] for v in others if others[v][state][0]})
            return out

        def snow(state):
            out = {"flower": petals_snow[state]} | own_stem(state)
            out.update({v: others[v][state][1] for v in others})
            return out

        def untinted(state):
            return list(others)

        states = [flower_term(window, t) for t in range(24)]
        self.rule(model_id, slices_for(states), textures, snow, untinted=untinted)
        self.preview.append((f"{model_id.split(':')[1].split('/')[-1]} {window}", preview))
        return True

    def write_two_layer_model(self, model_id, stem, petals, particle, var="cross", more=None):
        """Replaces the block model: its `var` faces get the grass tint, and are drawn again with "flower" on top.
        more: other texture variables done the same way, {var: (texture, overlay var, overlay texture)}."""
        layers = {var: (stem, "flower", petals)} | (more or {})
        chain, model, data = [], model_id, None
        while model:
            data = self.jars.model(model)
            if data is None:
                break
            chain.append(data)
            parent = data.get("parent")
            model = None if parent is None else parent if ":" in parent else "minecraft:" + parent
        out = {}
        for data in reversed(chain):
            out.update({k: v for k, v in data.items() if k not in ("parent", "textures")})
        elements = copy.deepcopy(out.get("elements", []))
        overlay = []
        for element in elements:
            faces = {side: face for side, face in element.get("faces", {}).items() if face.get("texture", "#")[1:] in layers}
            if not faces:
                continue
            for face in faces.values():
                face["tintindex"] = 0
            top = copy.deepcopy(element)
            top["faces"] = {side: {k: v for k, v in face.items() if k != "tintindex"}
                            | {"texture": "#" + layers[face["texture"][1:]][1]} for side, face in faces.items()}
            overlay.append(top)
        out["elements"] = elements + overlay
        out["textures"] = self.jars.textures(model_id) | {"particle": particle}
        for name, (base, top_var, top_texture) in layers.items():
            out["textures"] |= {name: base, top_var: top_texture}
        ns, path = model_id.split(":")
        target = self.out / f"assets/{ns}/models/{path}.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(out, indent=2))

    def flower(self, model_id, window):
        variables = self.jars.textures(model_id)
        if not variables:
            return False
        if "cross" in variables:
            return self.tinted_flower(model_id, window)
        puff = "dandelion" in model_id
        top = not model_id.endswith("bottom") and "_bottom_" not in model_id
        made = {}  # variable -> {stage: texture id or None (unchanged)}, snow: {stage: id}
        snow_made = {}
        previews = {}
        for var, texture_id in variables.items():
            image = self.jars.image(texture_id)
            # Flowerbed stems are shared green stalks; they stay as they are.
            if image is None or var == "stem":
                continue
            made[var], snow_made[var] = {}, {}
            for stage in FLOWER_STAGES:
                staged = flower_stage(image, stage, puff, var == "front")
                made[var][stage] = None if same(staged, image) else self.save(texture_id, stage, staged)
                snowed = snowy(staged, top)
                snow_made[var][stage] = self.save(texture_id, stage + "_snow", snowed)
                previews.setdefault(var, []).append(staged)
            previews[var].append(snowy(flower_stage(image, "leaves", puff, var == "front"), top))
        if not made:
            return False
        states = [flower_term(window, t) for t in range(24)]
        self.rule(model_id, slices_for(states),
                  lambda s: {v: made[v][s] for v in made if made[v][s]},
                  lambda s: {v: snow_made[v][s] for v in snow_made})
        for var, images in previews.items():
            self.preview.append((f"{model_id.split(':')[1].split('/')[-1]} [{var}] {window}", images))
        return True

    def grass(self, model_id):
        """Grass, ferns and crops: green parts levelled to the grass block top's grey, so with the grass
        tint they show the same color as the block they stand on, all year (the season colors make it olive-brown in
        winter). Anything not green (ripe wheat, carrots) is a second, untinted layer on top, and so is the
        snow, shown only where real snow lies."""
        variables = self.jars.textures(model_id)
        var = next((v for v in ("cross", "crop") if v in variables), None)
        texture_id = variables.get(var)
        image = self.jars.image(texture_id) if texture_id else None
        if image is None:
            return False
        # Already tinted (grey texture): all of it takes the grass color; its pale greys aren't petals.
        mask = set() if self.jars.tinted(model_id) else petal_mask(image)
        top = not model_id.endswith("bottom") and "_bottom_" not in model_id
        level, rest = levelled(image, mask), only(image, mask)
        snowed, overlay = snowy(image, top), rest.copy()
        spx, ipx, opx = snowed.load(), image.load(), overlay.load()
        for y in range(image.height):
            for x in range(image.width):
                if spx[x, y] != ipx[x, y]:
                    opx[x, y] = spx[x, y]
        if mask:
            stem = self.save(texture_id, "stem", level)
            layer = self.save(texture_id, "rest", rest)
        else:
            # Only green: the plant keeps its own texture id (levelled in place); the top layer starts empty.
            ns, path = texture_id.split(":")
            own = self.out / f"assets/{ns}/textures/{path}.png"
            own.parent.mkdir(parents=True, exist_ok=True)
            level.save(own)
            stem, layer = texture_id, self.save(texture_id, "empty", Image.new("RGBA", image.size))
        snow = self.save(texture_id, "snow", overlay)
        self.write_two_layer_model(model_id, stem, layer, texture_id, var)
        # Every biome: snow only shows where it lies anyway (snowy biomes and peaks included).
        crop = any(model_id in expand(key) for key in CROPS)  # WILD: snow-layer mode like grass
        self.rule(model_id, [[0, 23, "all"]], lambda s: {}, lambda s: {"flower": snow}, biomes=None,
                  snow_mode="snow_needs_sky" if crop else "snow_only_in_snow_layer")
        shown = tint(level, PLAINS)
        shown.alpha_composite(rest)
        winter = shown.copy()
        winter.alpha_composite(overlay)
        self.preview.append((f"{model_id.split(':')[1].split('/')[-1]}", [shown, winter]))
        return True

    def stem(self, model_id):
        """Pumpkin/melon stems: grey texture levelled in place (shared by every stage), snow on the top of the rows
        each stage shows (its faces' UV box), as an untinted overlay."""
        model, elements = model_id, None
        while model and elements is None:
            data = self.jars.model(model)
            if data is None:
                return False
            elements = data.get("elements")
            parent = data.get("parent")
            model = None if parent is None else parent if ":" in parent else "minecraft:" + parent
        variables = self.jars.textures(model_id)
        boxes = {}
        for element in elements or []:
            for face in element.get("faces", {}).values():
                u0, v0, u1, v1 = face["uv"]
                box = (min(u0, u1), min(v0, v1), max(u0, u1), max(v0, v1))
                old = boxes.get(face["texture"][1:], box)
                boxes[face["texture"][1:]] = (min(old[0], box[0]), min(old[1], box[1]), max(old[2], box[2]), max(old[3], box[3]))
        layers, snows, shown, winter = {}, {}, None, None
        for i, (var, box) in enumerate(boxes.items()):
            texture_id = variables.get(var)
            image = self.jars.image(texture_id) if texture_id else None
            if image is None:
                return False
            ns, path = texture_id.split(":")
            own = self.out / f"assets/{ns}/textures/{path}.png"
            own.parent.mkdir(parents=True, exist_ok=True)
            level = levelled(image)
            level.save(own)
            visible = Image.new("RGBA", image.size)
            visible.paste(image.crop(box), box[:2])
            snowed, overlay = snowy(visible), Image.new("RGBA", image.size)
            spx, vpx, opx = snowed.load(), visible.load(), overlay.load()
            for y in range(image.height):
                for x in range(image.width):
                    if spx[x, y] != vpx[x, y]:
                        opx[x, y] = spx[x, y]
            stage = model_id.split("/")[-1]
            top_var = "flower" if i == 0 else f"flower_{var}"
            # Every stage its own empty overlay: the rules are matched by sprite, so a shared one would mix stages up.
            layers[var] = (texture_id, top_var, self.save(texture_id, f"{stage}_empty", Image.new("RGBA", image.size)))
            snows[top_var] = self.save(texture_id, f"{stage}_snow", overlay)
            if shown is None:
                shown = tint(only(level, {(x, y) for x in range(box[0], box[2]) for y in range(box[1], box[3])}), PLAINS)
                winter = shown.copy()
                winter.alpha_composite(overlay)
        (var, (base, _, top)), *rest = layers.items()
        self.write_two_layer_model(model_id, base, top, base, var, dict(rest))
        self.rule(model_id, [[0, 23, "all"]], lambda s: {}, lambda s: snows, biomes=None, snow_mode="snow_needs_sky")
        self.preview.append((model_id.split("/")[-1], [shown, winter]))
        return True

    def evergreen(self, model_id, var="cross", snow_mode="snow_only_in_snow_layer"):
        """Own texture and color all year; snow on top only inside a snow layer (or under open sky: snow_mode)."""
        texture_id = self.jars.textures(model_id).get(var)
        image = self.jars.image(texture_id) if texture_id else None
        if image is None:
            return False
        snowed = snowy(image)
        snow = self.save(texture_id, "snowy", snowed)
        self.rule(model_id, [[0, 23, "all"]], lambda s: {}, lambda s: {var: snow}, biomes=None, snow_mode=snow_mode)
        self.preview.append((model_id.split("/")[-1], [image, snowed]))
        return True

    def bamboo_stalk(self):
        texture_id = self.jars.textures(BAMBOO_STALK).get("all")
        image = self.jars.image(texture_id) if texture_id else None
        if image is None:
            return False
        snowed = image.copy()
        x0, y0, x1, y1 = BAMBOO_TOP
        for y in range(y0, y1):
            for x in range(x0, x1):
                snowed.putpixel((x, y), (SNOW if y < y1 - 1 else SNOW_SHADE) + (255,))
        snow = self.save(texture_id, "snowy", snowed)
        # One target is enough: every bamboo model using the stalk sprite gets it.
        self.rule(BAMBOO_STALK, [[0, 23, "all"]], lambda s: {}, lambda s: {"all": snow}, biomes=None, snow_mode="snow_needs_sky")
        self.preview.append(("bamboo_stalk", [image, snowed]))
        return True

    def write_preview(self, path, columns):
        scale, pad, label = 4, 4, 150
        cell = 16 * scale + pad
        width = label + cell * columns
        sheet = Image.new("RGBA", (width, cell * len(self.preview) + pad), (120, 128, 120, 255))
        draw = ImageDraw.Draw(sheet)
        for row, (name, images) in enumerate(self.preview):
            y = pad + row * cell
            draw.text((4, y + 24), name[:24], fill=(255, 255, 255, 255))
            for col, image in enumerate(images):
                sheet.alpha_composite(image.resize((16 * scale, 16 * scale), Image.NEAREST), (label + col * cell, y))
        sheet.save(path)


def main():
    args = sys.argv[1:]
    preview, mode = None, None
    for flag in ("--vanilla", "--addon"):
        if flag in args:
            mode = flag[2:]
            args.remove(flag)
    if "--preview" in args:
        i = args.index("--preview")
        preview = Path(args[i + 1])
        del args[i:i + 2]
    out, jars = Path(args[0]), Jars(args[1:])
    pack = Pack(out, jars)
    done, skipped = 0, []
    wanted = lambda m: mode is None or (mode == "vanilla") == jars.vanilla(m)
    for key, window in BLOOM.items():
        for model_id in filter(wanted, expand(key)):
            if pack.flower(model_id, window):
                done += 1
            else:
                skipped.append(model_id)
    for make, keys in ((pack.grass, GRASS + CROPS + WILD), (pack.stem, STEMS), (pack.evergreen, EVERGREEN + jars.saplings())):
        for model_id in [m for key in keys for m in expand(key) if wanted(m)]:
            if make(model_id):
                done += 1
            else:
                skipped.append(model_id)
    for model_id, var in [(m, v) for m, v in BAMBOO_PARTS if wanted(m)]:
        if pack.evergreen(model_id, var, "snow_needs_sky"):
            done += 1
        else:
            skipped.append(model_id)
    if not wanted(BAMBOO_STALK):
        pass
    elif pack.bamboo_stalk():
        done += 1
    else:
        skipped.append(BAMBOO_STALK)
    (out / "pack.mcmeta").write_text(json.dumps({"pack": {
        "pack_format": 34,
        "description": "Seasons Rework: seasonal looks for modded flowers and plants" if mode == "addon"
        else "Seasons Rework: flowers bud, bloom, seed and die back; grass dries in winter",
    }}, indent=2))
    print(f"{done} plants, skipped (not in the jars): {', '.join(skipped) or 'none'}")
    if preview:
        pack.write_preview(preview, 7)


if __name__ == "__main__":
    main()
