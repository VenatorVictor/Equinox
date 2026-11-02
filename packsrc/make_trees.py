"""Builds the "Equinox Seasonal Trees" resource pack.

Usage: python make_trees.py <minecraft client resources jar> <output folder>
Needs Pillow. The vanilla jar is read for the leaf textures: the snowy spruce is drawn on top of spruce_leaves.png,
and the bare branches of oak, birch and dark oak follow the gaps and shadows of their own leaf texture.
"""
import heapq
import json
import random
import sys
import zipfile
from io import BytesIO
from pathlib import Path

from PIL import Image

SIZE = 16
TRANSPARENT = 0

# Bare branches: limbs from the middle of the block to the middle of each edge (so they carry on into the
# neighbouring leaf blocks), each one taking the path through the darkest gaps of the tree's own leaf texture, as if
# the branches were what showed between the leaf clumps. Twigs reach out to other dark spots, half of them all the
# way to the edge of the block. The limbs taper: thick where they leave the knot in the middle, 1 px where they reach
# the edge (so they meet the neighbouring block's limbs at the same width), twigs always 1 px. Every tree gets
# VARIANTS versions, picked per block position: each one mirrors/turns the leaf texture before the paths are found,
# moves the knot a little and picks its twigs at random among the dark spots, so neighbouring blocks differ.
CENTER = (7, 8)
# Per tree: how far (px) the knot may move from CENTER, its shape, how many twigs, and the limb taper: (share of
# the limb from the knot, width) pairs, 1 px after the last one.
SMALL_KNOT = [(0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)]
STYLES = {
    "oak": {"shift": 2, "knot": None, "twigs": 10, "taper": [(0.4, 2)]},  # knot: None = KNOT
    "birch": {"shift": 1, "knot": SMALL_KNOT, "twigs": 12, "taper": [(0.3, 2)]},  # near the middle, fine twigs
    "dark_oak": {"shift": 2, "knot": None, "twigs": 5, "taper": [(0.3, 3), (0.6, 2)]},  # heavy limbs
}
EXITS = [(0, 7), (15, 7), (8, 0), (8, 15)]
KNOT = [(dx, dy) for dx in (-1, 0, 1) for dy in (-1, 0, 1)] + [(0, -2), (0, 2), (-2, 0), (2, 0)]  # thick middle
VARIANTS = 8
TWIG_CHOICES = 6  # each twig goes to one of this many darkest free spots, at random

# light, mid, dark: taken from each wood's log texture
PALETTES = {
    "oak": [(128, 100, 60), (95, 74, 43), (60, 46, 26)],
    "birch": [(216, 216, 206), (166, 164, 150), (96, 94, 84)],
    "dark_oak": [(88, 68, 40), (63, 49, 29), (41, 32, 17)],
}
SNOW = (240, 250, 252)
SNOW_SHADE = (205, 222, 230)
# Spruce leaves color in winter (vanilla spruce green mixed toward the winter spruce color).
SPRUCE_WINTER = (79, 125, 79)


def darkness(leaves):
    """Cost of a branch passing each pixel: cheap through holes and dark leaf shadows, dear through bright leaves."""
    leaves = leaves.convert("RGBA")
    cost = {}
    for y in range(SIZE):
        for x in range(SIZE):
            r, g, b, a = leaves.getpixel((x, y))
            cost[x, y] = 0.15 if a == 0 else 0.15 + (0.299 * r + 0.587 * g + 0.114 * b) / 255
    return cost


def path(cost, start, goals):
    """Cheapest 8-way path from start to the nearest of goals (pixel cost times step length)."""
    goals = set(goals)
    best, prev, queue = {start: 0.0}, {}, [(0.0, start)]
    while queue:
        d, p = heapq.heappop(queue)
        if p in goals:
            out = [p]
            while p in prev:
                p = prev[p]
                out.append(p)
            return out[::-1]
        if d > best[p]:
            continue
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                n = (p[0] + dx, p[1] + dy)
                if (dx or dy) and n in cost:
                    nd = d + cost[n] * (1.41 if dx and dy else 1.0)
                    if nd < best.get(n, 1e9):
                        best[n], prev[n] = nd, p
                        heapq.heappush(queue, (nd, n))
    return []


def sign(v):
    return (v > 0) - (v < 0)


def tapered(limb, taper):
    """The limb widened beside its path, by the taper's width for each share of its length from the start."""
    out = set(limb)
    for i, (x, y) in enumerate(limb):
        share = i / max(len(limb) - 1, 1)
        width = next((w for upto, w in taper if share < upto), 1)
        if width < 2:
            continue
        a, b = limb[max(i - 1, 0)], limb[min(i + 1, len(limb) - 1)]
        px, py = -sign(b[1] - a[1]), sign(b[0] - a[0])  # across the limb
        sides = [(px, 0), (0, py)] if px and py else [(px, py)]
        for sx, sy in sides:
            out.add((x + sx, y + sy))
            if width >= 3:
                out.add((x - sx, y - sy))
    return out


def branch_mask(leaves, variant, seed, style):
    # Mirror/turn the leaf texture so each variant finds different paths; centre and exits stay where they are.
    image = leaves.convert("RGBA")
    image = [image, image.transpose(Image.FLIP_LEFT_RIGHT), image.transpose(Image.FLIP_TOP_BOTTOM),
             image.transpose(Image.ROTATE_180)][variant % 4]
    cost = darkness(image)
    rng = random.Random(seed)
    shift = style["shift"]
    center = (CENTER[0] + rng.randint(-shift, shift), CENTER[1] + rng.randint(-shift, shift))
    mask = {(center[0] + dx, center[1] + dy) for dx, dy in (style["knot"] or KNOT)}
    for exit_ in EXITS:
        mask |= tapered(path(cost, center, [exit_]), style["taper"])
    # Twigs: from the branches to the darkest spots not yet reached; every other one ends on the block's edge.
    edge = {p for p in cost if p[0] in (0, SIZE - 1) or p[1] in (0, SIZE - 1)}
    for i in range(style["twigs"]):
        spots = edge if i % 2 else set(cost) - edge
        far = [p for p in spots if min(max(abs(p[0] - m[0]), abs(p[1] - m[1])) for m in mask) >= 3]
        if not far:
            continue
        target = rng.choice(sorted(far, key=lambda p: (cost[p], p))[:TWIG_CHOICES])
        mask |= set(path(cost, target, mask))
    return {(x, y) for x, y in mask if 0 <= x < SIZE and 0 <= y < SIZE}


def branches(palette, snowy, mask):
    light, mid, dark = palette
    # Transparent pixels keep a dark color: Fast graphics draws leaves opaque.
    image = Image.new("RGBA", (SIZE, SIZE), dark + (TRANSPARENT,))
    for x, y in mask:
        lit = (x - 1, y - 1) not in mask  # light from the top left
        shaded = (x + 1, y + 1) not in mask and (x, y + 1) not in mask
        color = light if lit else dark if shaded else mid
        # Snow settles on the flatter parts: open above and a branch pixel beside it.
        if snowy and (x, y - 1) not in mask and ((x - 1, y) in mask or (x + 1, y) in mask):
            color = SNOW if lit else SNOW_SHADE
        image.putpixel((x, y), color + (255,))
    return image


def snowy_spruce(spruce_leaves):
    source = spruce_leaves.convert("RGBA")
    image = Image.new("RGBA", (SIZE, SIZE))
    for y in range(SIZE):
        for x in range(SIZE):
            g, _, _, a = source.getpixel((x, y))
            # Bake the winter green in: the snow must not be tinted, so the whole texture is untinted.
            color = tuple(round(c * g / 255) for c in SPRUCE_WINTER)
            # Snow on needles with an open gap above them (the top row has the block above it, so no rim there).
            # Only on the lighter needles, so some green shows between the snow.
            if a > 0 and y > 0 and g >= 100 and source.getpixel((x, y - 1))[3] == 0:
                color = SNOW
            image.putpixel((x, y), color + (a,))
    return image


def season_file(target, slices, biomes=None):
    data = {"target": [target]}
    if biomes:
        data["biomes"] = biomes
    data["slices"] = slices
    return data


def main():
    jar, out = Path(sys.argv[1]), Path(sys.argv[2])
    textures = out / "assets/equinox/textures/block"
    seasons = out / "assets/equinox/eclipticseasons/season_textures"
    textures.mkdir(parents=True, exist_ok=True)
    seasons.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(jar) as z:
        leaves = {name: Image.open(BytesIO(z.read(f"assets/minecraft/textures/block/{name}_leaves.png")))
                  for name in ["spruce"] + list(PALETTES)}
    spruce = leaves["spruce"]
    snowy_spruce(spruce).save(textures / "spruce_leaves_snowy.png")
    # Snowy spruce wherever real snow lies, all year.
    (seasons / "spruce_leaves.json").write_text(json.dumps(season_file("minecraft:block/spruce_leaves", [{
        "snow_textures": {"all": "equinox:block/spruce_leaves_snowy"},
        "snow_tint": {"all": -1},
    }]), indent=2))

    for wood, palette in PALETTES.items():
        plain, snowy = [], []
        for variant in range(VARIANTS):
            mask = branch_mask(leaves[wood], variant, f"{wood}{variant}", STYLES[wood])
            branches(palette, False, mask).save(textures / f"{wood}_branches_{variant}.png")
            branches(palette, True, mask).save(textures / f"{wood}_branches_{variant}_snowy.png")
            plain.append({"all": f"equinox:block/{wood}_branches_{variant}"})
            snowy.append({"all": f"equinox:block/{wood}_branches_{variant}_snowy"})
        # Bare from Beginning of Winter (early winter) to Rain Water (end of early spring),
        # in seasonal biomes only (jungles, savannas, deserts... keep their leaves). Snowy only under a snow layer
        # (the leaf blocks snow lies on), the ones below stay bare branches.
        rule = season_file(f"minecraft:block/{wood}_leaves", [{
            "start": "beginning_of_winter",
            "end": "rain_water",
            "textures": plain,
            "tint": {"all": -1},
            "snow_textures": snowy,
            "snow_tint": {"all": -1},
        }], "#eclipticseasons:rain/seasonal")
        rule["snow_needs_snow_above"] = True
        (seasons / f"{wood}_leaves.json").write_text(json.dumps(rule, indent=2))

    (out / "pack.mcmeta").write_text(json.dumps({"pack": {
        "pack_format": 34,
        "description": "Equinox: snowy spruce, bare oak/birch/dark oak in winter",
    }}, indent=2))


if __name__ == "__main__":
    main()
