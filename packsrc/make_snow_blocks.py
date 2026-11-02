"""Builds the built-in snowy block packs of Seasons Rework (src/main/resources/packs/...).

Usage: python make_snow_blocks.py <resources/packs folder> <Minecraft client jar> [--preview <png>]
Writes three packs:
  snowy_blocks       fences, fence gates, walls, stairs, slabs, chains, the snowy grass block side (always on)
  snowy_leaves_new   leaves with a snow layer on top (new model)           } one of the two, picked by the client
  snowy_leaves_flat  leaves with the snow drawn on the leaf texture         } config [textures] snow_models
Snow pattern: the plants' rule (snow on every pixel open to the sky, a shade pixel under it), with a thicker cap on
the top edge that drifts 2-4 pixels per column. The textures are swapped in where snow lies (seasonal texture rules,
"snow_full_texture": solid blocks change all at once instead of fading in).
"""
import copy
import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).parent))
from make_plants import Jars, SNOW, SNOW_SHADE  # noqa: E402

DEPTH = [2, 3, 2, 2, 3, 4, 3, 2, 2, 3, 2, 3, 3, 4, 3, 2]  # cap depth per column, a fixed drift
SNOW_TOP = "minecraft:block/snow"
ALL_TERMS = [[0, 23, "all"]]
LEAF_COLOR = (119, 171, 47)  # previews only


def cap(image, rows=(0,), depth=DEPTH, extra=None, inner=True):
    """Snow from each of `rows` (a top edge: row 0, or row 8 for the lower half of stairs and slabs) `depth` pixels
    down (+extra), then a shade pixel; inner edges open to the sky get the plants' single snow pixel (inner=True)."""
    out = image.copy()
    src, px = image.load(), out.load()
    w, h = image.size
    for x in range(w):
        y = 0
        while y < h:
            edge = y in rows
            if src[x, y][3] and (edge or (inner and y > 0 and not src[x, y - 1][3])):
                d = depth[x % 16] + (extra[x] if extra else 0) if edge else 1
                k = 0
                while k < d and y + k < h and src[x, y + k][3]:
                    px[x, y + k] = SNOW + (255,)
                    k += 1
                if y + k < h and src[x, y + k][3]:
                    px[x, y + k] = SNOW_SHADE + (255,)
                y += k + 1
            else:
                y += 1
    return out


def only_snow(snowed, image):
    """The pixels `snowed` changed, alone (an untinted layer drawn over the tinted leaves)."""
    out = Image.new("RGBA", image.size)
    a, b, px = snowed.load(), image.load(), out.load()
    for y in range(image.height):
        for x in range(image.width):
            if a[x, y] != b[x, y]:
                px[x, y] = a[x, y]
    return out


class SnowPack:
    def __init__(self, out, name, jars, description):
        self.root, self.jars = out / name, jars
        self.textures = self.root / "assets/seasonsrework/textures/block/snow"
        self.rules = self.root / "assets/seasonsrework/eclipticseasons/season_textures"
        self.textures.mkdir(parents=True, exist_ok=True)
        self.rules.mkdir(parents=True, exist_ok=True)
        (self.root / "pack.mcmeta").write_text(json.dumps({"pack": {"pack_format": 34, "description": description}}, indent=2))
        self.saved = {}

    def save(self, texture_id, suffix, image):
        ns, path = texture_id.split(":")
        name = f"{ns}_{path.rsplit('/', 1)[1]}_{suffix}"
        if name not in self.saved:
            image.save(self.textures / f"{name}.png")
            self.saved[name] = f"seasonsrework:block/snow/{name}"
        return self.saved[name]

    def rule(self, model_id, textures=None, snow=None):
        """textures: swapped all year; snow: swapped where snow lies (all at once)."""
        piece = {"start": "beginning_of_spring", "end": "greater_cold"}
        if textures:
            piece["textures"] = textures
        if snow:
            piece["snow_textures"] = snow
            piece["snow_tint"] = {k: -1 for k in snow}
        ns, path = model_id.split(":")
        data = {"target": [model_id], "snow_full_texture": True, "slices": [piece]}
        (self.rules / f"{ns}_{path.rsplit('/', 1)[1]}.json").write_text(json.dumps(data, indent=2))

    def write_model(self, model_id, data):
        ns, path = model_id.split(":")
        target = self.root / f"assets/{ns}/models/{path}.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(data, indent=2))


def block_models(jars):
    names = [n for n in jars.zips[0].namelist() if n.startswith("assets/minecraft/models/block/") and n.endswith(".json")]
    return sorted("minecraft:block/" + n.rsplit("/", 1)[1][:-5] for n in names)


def kind(model_id):
    name = model_id.rsplit("/", 1)[1]
    if name.startswith("template_") or name.endswith("_inventory"):
        return None
    if name.endswith(("_fence_post", "_fence_side")):
        return "fence"
    if "_fence_gate" in name:
        return "gate"
    if name.endswith(("_wall_post", "_wall_side", "_wall_side_tall")):
        return "wall"
    if name.endswith(("_stairs", "_stairs_inner", "_stairs_outer")):
        return "stairs"
    if name.endswith(("_slab", "_slab_top")):
        return "slab"
    if name == "chain":
        return "chain"
    if name == "grass_block_snow":
        return "grass"
    if name.endswith("_leaves"):
        return "leaves"
    return None


def flattened(jars, model_id):
    """The model with its parents merged (elements, display, ...), own texture variables resolved."""
    chain, model = [], model_id
    while model:
        data = jars.model(model)
        if data is None:
            break
        chain.append(data)
        parent = data.get("parent")
        model = None if parent is None else parent if ":" in parent else "minecraft:" + parent
    out, textures = {}, {}
    for data in reversed(chain):
        out.update({k: v for k, v in data.items() if k not in ("parent", "textures")})
        textures.update(data.get("textures", {}))
    # References (#all) stay as they are: the parents' faces use them (cube_all: down -> #all ...).
    out["textures"] = {k: v if v.startswith("#") or ":" in v else "minecraft:" + v for k, v in textures.items()}
    return out


def leaves_model(jars, model_id, new, empty_side, empty_top):
    """Leaves drawn again with untinted snow on top: `snow` (sides, transparent until snow lies) and `snow_top`.
    new=True: a snow layer 2 pixels thick on top of the block instead of snow on the top face."""
    data = flattened(jars, model_id)
    elements = copy.deepcopy(data.get("elements", []))
    extra = []
    for element in elements:
        faces = element.get("faces", {})
        over = {}
        for side, face in faces.items():
            if side == "down" or (new and side == "up"):
                continue
            over[side] = {k: v for k, v in face.items() if k not in ("tintindex", "texture")} | {
                "texture": "#snow_top" if side == "up" else "#snow"}
        if over:
            extra.append(copy.deepcopy(element) | {"faces": over})
    if new:
        layer = {"from": [0, 16, 0], "to": [16, 18, 16], "faces": {
            "up": {"uv": [0, 0, 16, 16], "texture": "#snow_top"},
            "north": {"uv": [0, 14, 16, 16], "texture": "#snow_top"}, "south": {"uv": [0, 14, 16, 16], "texture": "#snow_top"},
            "east": {"uv": [0, 14, 16, 16], "texture": "#snow_top"}, "west": {"uv": [0, 14, 16, 16], "texture": "#snow_top"}}}
        extra.append(layer)
    data["elements"] = elements + extra
    data["textures"] = data["textures"] | {"snow": empty_side, "snow_top": empty_top}
    # Fast graphics draws leaves solid, which would turn the empty snow faces black: always cutout (holes show).
    data["render_type"] = "minecraft:cutout_mipped"
    return data


def main():
    args = sys.argv[1:]
    preview = None
    if "--preview" in args:
        i = args.index("--preview")
        preview = Path(args[i + 1])
        del args[i:i + 2]
    out, jars = Path(args[0]), Jars(args[1:2])
    blocks = SnowPack(out, "snowy_blocks", jars, "Seasons Rework: snow on fences, walls, stairs, slabs, chains and grass blocks")
    leaf_packs = {
        True: SnowPack(out, "snowy_leaves_new", jars, "Seasons Rework: snow layer on leaves"),
        False: SnowPack(out, "snowy_leaves_flat", jars, "Seasons Rework: snow drawn on leaves"),
    }
    # Two different empty textures: swaps go by sprite, so the side snow and the top snow need their own.
    empty = {}
    for new, pack in leaf_packs.items():
        empty[new] = (pack.save("seasonsrework:block/none", "empty_side", Image.new("RGBA", (16, 16))),
                      pack.save("seasonsrework:block/none", "empty_top", Image.new("RGBA", (16, 16))))
    shown, counts = [], {}
    for model_id in block_models(jars):
        k = kind(model_id)
        if k is None:
            continue
        variables = jars.textures(model_id)
        if k in ("fence", "gate", "wall", "chain"):
            var = {"fence": "texture", "gate": "texture", "wall": "wall", "chain": "all"}[k]
            tid = variables.get(var)
            image = jars.image(tid) if tid else None
            if image is None:
                continue
            snowed = cap(image)
            blocks.rule(model_id, snow={var: blocks.save(tid, "snowy", snowed)})
            shown.append((k, image, snowed))
        elif k in ("stairs", "slab"):
            tid = variables.get("side")
            image = jars.image(tid) if tid else None
            if image is None:
                continue
            snowed = cap(image, rows=(0, 8))
            # One texture for top, bottom and sides: the faces pointing up become snow, the bottom keeps its own.
            blocks.rule(model_id, snow={"side": blocks.save(tid, "snowy_half", snowed), "up:top": SNOW_TOP,
                                        "down:bottom": variables.get("bottom", tid)})
            shown.append((k, image, snowed))
        elif k == "grass":
            dirt = jars.image("minecraft:block/grass_block_side")
            overlay = jars.image("minecraft:block/grass_block_side_overlay")
            if dirt is None or overlay is None:
                continue
            o = overlay.load()
            fringe = [max([y + 1 for y in range(16) if o[x, y][3]] or [0]) for x in range(16)]
            snowed = cap(dirt, depth=[1] * 16, extra=fringe)
            blocks.rule(model_id, textures={"side": blocks.save("minecraft:block/grass_block_side", "snowy", snowed)})
            shown.append((k, jars.image("minecraft:block/grass_block_snow"), snowed))
        elif k == "leaves":
            tid = variables.get("all")
            image = jars.image(tid) if tid else None
            if image is None:
                continue
            for new, pack in leaf_packs.items():
                snowed = cap(image, inner=not new)
                side = pack.save(tid, "snow", only_snow(snowed, image))
                pack.write_model(model_id, leaves_model(jars, model_id, new, *empty[new]))
                pack.rule(model_id, snow={"snow": side, "snow_top": SNOW_TOP})
                shown.append((f"leaves {'new' if new else 'flat'}", image, snowed))
        counts[k] = counts.get(k, 0) + 1
    print(", ".join(f"{v} {k}" for k, v in counts.items()))
    if preview:
        seen, rows = set(), []
        for label, a, b in shown:
            if label not in seen or len([r for r in rows if r[0] == label]) < 3:
                seen.add(label)
                rows.append((label, a, b))
        scale, pad, lab = 4, 4, 110
        cell = 16 * scale + pad
        sheet = Image.new("RGBA", (lab + cell * 2, pad + cell * len(rows)), (120, 128, 120, 255))
        draw = ImageDraw.Draw(sheet)
        for r, (label, a, b) in enumerate(rows):
            draw.text((4, pad + r * cell + 24), label, fill=(255, 255, 255, 255))
            for c, im in enumerate((a, b)):
                sheet.alpha_composite(im.resize((16 * scale, 16 * scale), Image.NEAREST), (lab + c * cell, pad + r * cell))
        sheet.save(preview)


if __name__ == "__main__":
    main()
