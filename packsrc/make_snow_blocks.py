"""Builds the snow cap textures drawn over blocks where snow lies (client/SnowOverlayModel.java).

Usage: python make_snow_blocks.py <src/main/resources folder>
Writes assets/equinox/textures/block/snow_cap.png (cap at the top edge: fences, walls, gates, chains, leaves,
grass) and snow_cap_half.png (also at the half-block line: stairs and slabs, whose lower faces use the texture's lower
half). Snow pattern: the plants' rule, thicker: a cap that drifts 2-4 pixels deep per column, then a shade pixel.
The overlay uses each face's own texture coordinates, so the cap sits at the top of every face.
"""
import sys
from pathlib import Path

from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from make_plants import SNOW, SNOW_SHADE  # noqa: E402

DEPTH = [2, 3, 2, 2, 3, 4, 3, 2, 2, 3, 2, 3, 3, 4, 3, 2]  # cap depth per column, a fixed drift


def cap(rows):
    image = Image.new("RGBA", (16, 16))
    px = image.load()
    for x in range(16):
        for row in rows:
            for k in range(DEPTH[x]):
                px[x, row + k] = SNOW + (255,)
            px[x, row + DEPTH[x]] = SNOW_SHADE + (255,)
    return image


def main():
    out = Path(sys.argv[1]) / "assets/equinox/textures/block"
    out.mkdir(parents=True, exist_ok=True)
    cap((0,)).save(out / "snow_cap.png")
    cap((0, 8)).save(out / "snow_cap_half.png")


if __name__ == "__main__":
    main()
