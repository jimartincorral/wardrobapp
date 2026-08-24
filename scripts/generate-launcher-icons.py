#!/usr/bin/env python3
"""
Draw the port's legacy launcher icons.

Android 8 and up use the adaptive icon in `mipmap-anydpi-v26`, which is a pair of
vectors and needs nothing generating. Android 7 has no adaptive icons at all, and
this app supports it (minSdk 24), so it needs a raster per density -- which is what
this writes.

Kept as a script rather than as five PNGs with no history, because the artwork was
drawn rather than commissioned: without this the next person to touch the icon has
a binary and no idea what it is made of. Run it after changing
`res/drawable/ic_launcher_foreground.xml`, and keep the geometry below in step
with that file -- the two are the same drawing, and this is the copy that cannot
be a vector.

    python3 scripts/generate-launcher-icons.py

No dependencies on purpose. Pillow is not in this project's toolchain and adding an
image library to draw two lines and an arc would be a strange trade.
"""

from __future__ import annotations

import struct
import zlib
from pathlib import Path

RES = Path(__file__).resolve().parent.parent / 'native' / 'app' / 'src' / 'main' / 'res'

# The adaptive icon's canvas, so the geometry below is the same numbers as the
# vector's pathData.
VIEWPORT = 108.0

# The app's primary colour, from src/constants/theme.ts. Also
# `ic_launcher_background` in res/values.
BACKGROUND = (0x6C, 0x63, 0xFF)
GLYPH = (0xFF, 0xFF, 0xFF)

# The densities Android asks for, and the size each expects.
DENSITIES = {
    'mipmap-mdpi': 48,
    'mipmap-hdpi': 72,
    'mipmap-xhdpi': 96,
    'mipmap-xxhdpi': 144,
    'mipmap-xxxhdpi': 192,
}

# Rendered this many times over and averaged down, which is what gives the curves
# smooth edges without a rasterizer that understands them.
SUPERSAMPLE = 4


def shirt_outline() -> list[tuple[float, float]]:
    """
    The t-shirt, as one closed polygon.

    The same outline as `ic_launcher_foreground.xml`, with the collar's curve
    flattened into segments -- this renderer fills polygons and knows nothing about
    cubics, and a collar is the one place the vector needs one.
    """
    corners = [
        (42.0, 26.0),  # left of the collar
        (30.0, 30.0),  # left shoulder
        (22.0, 46.0),  # left cuff, outer
        (34.0, 52.0),  # left cuff, inner
        (34.0, 82.0),  # left hem
        (74.0, 82.0),  # right hem
        (74.0, 52.0),
        (86.0, 46.0),
        (78.0, 30.0),
        (66.0, 26.0),  # right of the collar
    ]

    # The collar: a cubic from the right of the neck back round to the left, dipping
    # to about y=32 in the middle. Twenty-four segments is past the point where more
    # changes a pixel at 192px.
    collar = []
    start, control_one, control_two, end = (66.0, 26.0), (62.0, 34.0), (46.0, 34.0), (42.0, 26.0)
    steps = 24
    for step in range(1, steps):
        t = step / steps
        inverse = 1 - t
        collar.append((
            inverse ** 3 * start[0]
            + 3 * inverse ** 2 * t * control_one[0]
            + 3 * inverse * t ** 2 * control_two[0]
            + t ** 3 * end[0],
            inverse ** 3 * start[1]
            + 3 * inverse ** 2 * t * control_one[1]
            + 3 * inverse * t ** 2 * control_two[1]
            + t ** 3 * end[1],
        ))

    return corners + collar


def fill_polygon(target: bytearray, width: int, polygon: list[tuple[float, float]]) -> None:
    """
    Scanline-fill a closed polygon.

    One crossing list per row, sorted, filled in pairs -- the standard even-odd
    rule. The shirt is a simple outline that never crosses itself, so even-odd and
    non-zero winding agree and the simpler one is enough.
    """
    top = max(0, int(min(y for _, y in polygon)))
    bottom = min(width - 1, int(max(y for _, y in polygon)) + 1)

    for y in range(top, bottom + 1):
        centre = y + 0.5
        crossings = []
        for index, (x_start, y_start) in enumerate(polygon):
            x_end, y_end = polygon[(index + 1) % len(polygon)]
            if y_start == y_end:
                continue
            # Half-open on purpose: a vertex exactly on the scanline must count for
            # one edge and not both, or the row fills inside out.
            if min(y_start, y_end) <= centre < max(y_start, y_end):
                fraction = (centre - y_start) / (y_end - y_start)
                crossings.append(x_start + (x_end - x_start) * fraction)

        crossings.sort()
        row = y * width
        for pair in range(0, len(crossings) - 1, 2):
            left = max(0, int(crossings[pair] + 0.5))
            right = min(width - 1, int(crossings[pair + 1] - 0.5))
            for x in range(left, right + 1):
                target[row + x] = 1


def render(size: int, round_mask: bool) -> bytes:
    """One icon, as RGBA rows."""
    scale = size * SUPERSAMPLE / VIEWPORT
    big = size * SUPERSAMPLE

    # Coverage of the glyph, 0 or 1 per supersampled pixel. A byte each rather than
    # a bitfield: this runs once, and clarity is worth more than the memory.
    glyph = bytearray(big * big)

    polygon = [(x * scale, y * scale) for x, y in shirt_outline()]
    fill_polygon(glyph, big, polygon)

    # A circle for the round variant, so a launcher that asks for one gets a disc
    # rather than a square with rounded corners drawn on top of it.
    mask_radius = big / 2.0
    mask_radius_squared = mask_radius * mask_radius

    rows = bytearray()
    block = SUPERSAMPLE * SUPERSAMPLE
    for y in range(size):
        rows.append(0)  # PNG filter: none.
        for x in range(size):
            glyph_hits = 0
            inside_hits = 0
            for sub_y in range(SUPERSAMPLE):
                big_y = y * SUPERSAMPLE + sub_y
                row = big_y * big
                dy = big_y + 0.5 - mask_radius
                for sub_x in range(SUPERSAMPLE):
                    big_x = x * SUPERSAMPLE + sub_x
                    glyph_hits += glyph[row + big_x]
                    if not round_mask:
                        inside_hits += 1
                    else:
                        dx = big_x + 0.5 - mask_radius
                        if dx * dx + dy * dy <= mask_radius_squared:
                            inside_hits += 1

            if inside_hits == 0:
                rows.extend((0, 0, 0, 0))
                continue

            # The glyph over the background, then the whole thing faded by however
            # much of the pixel is inside the mask. Averaging colour and alpha
            # separately like this is what keeps the edge of the circle smooth
            # instead of stepped.
            glyph_share = glyph_hits / block
            colour = tuple(
                round(GLYPH[channel] * glyph_share + BACKGROUND[channel] * (1 - glyph_share))
                for channel in range(3)
            )
            rows.extend(colour)
            rows.append(round(255 * inside_hits / block))

    return bytes(rows)


def write_png(path: Path, size: int, rows: bytes) -> None:
    def chunk(kind: bytes, payload: bytes) -> bytes:
        body = kind + payload
        return struct.pack('>I', len(payload)) + body + struct.pack('>I', zlib.crc32(body))

    header = struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0)  # 8-bit RGBA.
    png = (
        b'\x89PNG\r\n\x1a\n'
        + chunk(b'IHDR', header)
        + chunk(b'IDAT', zlib.compress(rows, 9))
        + chunk(b'IEND', b'')
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)


def main() -> None:
    for folder, size in DENSITIES.items():
        for name, round_mask in (('ic_launcher', False), ('ic_launcher_round', True)):
            path = RES / folder / f'{name}.png'
            write_png(path, size, render(size, round_mask))
            print(f'{path.relative_to(RES.parent.parent.parent.parent)}: {size}x{size}')


if __name__ == '__main__':
    main()
