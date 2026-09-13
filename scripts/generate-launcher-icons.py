#!/usr/bin/env python3
"""
Draw the legacy launcher icons from the vector the adaptive icon already uses.

Android 8 and up use the adaptive icon in `mipmap-anydpi-v26`, which is a pair of
vectors and needs nothing generating. Android 7 has no adaptive icons at all, and
this app supports it (minSdk 24), so it needs a raster per density -- which is what
this writes.

    python3 scripts/generate-launcher-icons.py

The artwork is read out of `res/drawable/ic_launcher_foreground.xml` rather than
written down again here, so there is one drawing and not two. That is a change
from the first version of this script, which carried its own copy of the t-shirt's
outline and a comment asking the next person to keep the two in step. The monogram
that replaced the t-shirt is fifteen curves and a needle; a second hand-maintained
copy of it would have been wrong within a week.

What that costs is a path parser and a stroker, below. Both are small because they
only have to handle what this one file contains: absolute `M`, `C`, `L` and `Z`,
one stroke colour, round caps. Anything else raises rather than guessing, so a
drawing this cannot render fails here instead of silently coming out different
from the vector.

No dependencies on purpose. Pillow is not in this project's toolchain, and an
image library is a strange thing to add to a repository that draws its own icon.
"""

from __future__ import annotations

import math
import re
import struct
import xml.etree.ElementTree as ElementTree
import zlib
from pathlib import Path

RES = Path(__file__).resolve().parent.parent / 'app' / 'src' / 'main' / 'res'

ANDROID = '{http://schemas.android.com/apk/res/android}'

FOREGROUND = RES / 'drawable' / 'ic_launcher_foreground.xml'
BACKGROUND_COLOUR = RES / 'values' / 'ic_launcher_background.xml'

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

# How much of the square's half-width the corner radius takes on the plain icon.
# Enough that the corners are visibly transparent: a launcher icon that fills
# every pixel of its square reads as a coloured tile, and lint says so
# (IconLauncherShape).
CORNER_RADIUS = 0.18


# --------------------------------------------------------------------------
# Reading the drawing
# --------------------------------------------------------------------------


def parse_colour(value: str) -> tuple[int, int, int]:
    """`#RRGGBB` or `#AARRGGBB` as a triple. Alpha is read and dropped."""
    digits = value.strip().lstrip('#')
    if len(digits) == 8:
        digits = digits[2:]
    if len(digits) != 6:
        raise ValueError(f'not a colour this script can read: {value}')
    return tuple(int(digits[at:at + 2], 16) for at in (0, 2, 4))


def background_colour() -> tuple[int, int, int]:
    """The icon's background, from the colour resource the adaptive icon names."""
    for element in ElementTree.parse(BACKGROUND_COLOUR).getroot().iter('color'):
        if element.get('name') == 'ic_launcher_background':
            return parse_colour(element.text or '')
    raise ValueError(f'no ic_launcher_background in {BACKGROUND_COLOUR}')


def subpaths(path_data: str) -> list[list[tuple[float, float]]]:
    """
    A `pathData` string as polylines, one per subpath, curves flattened.

    Only the commands the foreground uses, and only in their absolute form: `M`
    starts a subpath, `L` and `C` extend it, `Z` closes it. An unsupported command
    raises -- see the module docstring.
    """
    tokens = re.findall(r'[A-Za-z]|-?\d*\.?\d+(?:[eE][-+]?\d+)?', path_data)

    shapes: list[list[tuple[float, float]]] = []
    points: list[tuple[float, float]] = []
    at = 0
    command = ''

    def number() -> float:
        nonlocal at
        value = float(tokens[at])
        at += 1
        return value

    while at < len(tokens):
        if re.fullmatch(r'[A-Za-z]', tokens[at]):
            command = tokens[at]
            at += 1
        elif not command:
            raise ValueError(f'pathData begins with a number: {path_data[:32]}')

        if command == 'M':
            if points:
                shapes.append(points)
            points = [(number(), number())]
            # A second pair after an M is an implicit lineto, which is what a
            # repeated command means everywhere in this syntax.
            command = 'L'
        elif command == 'L':
            points.append((number(), number()))
        elif command == 'C':
            control_one = (number(), number())
            control_two = (number(), number())
            end = (number(), number())
            points.extend(flatten(points[-1], control_one, control_two, end))
        elif command == 'Z':
            if points and points[0] != points[-1]:
                points.append(points[0])
            shapes.append(points)
            points = []
        else:
            raise ValueError(f'pathData command {command!r} is not supported')

    if points:
        shapes.append(points)
    return shapes


def flatten(
    start: tuple[float, float],
    control_one: tuple[float, float],
    control_two: tuple[float, float],
    end: tuple[float, float],
) -> list[tuple[float, float]]:
    """
    A cubic as points, the first of which is dropped because it is already there.

    The number of steps comes from the control polygon's length rather than being
    fixed: the monogram's curves range from a two-unit hook to a forty-unit sweep,
    and the same count for both is either coarse on one or pointless on the other.
    Half a viewport unit per step is under a pixel at every density here.
    """
    length = sum(
        math.dist(a, b)
        for a, b in zip((start, control_one, control_two), (control_one, control_two, end))
    )
    steps = max(8, min(96, int(length * 2)))

    points = []
    for step in range(1, steps + 1):
        t = step / steps
        inverse = 1 - t
        points.append((
            inverse ** 3 * start[0]
            + 3 * inverse ** 2 * t * control_one[0]
            + 3 * inverse * t ** 2 * control_two[0]
            + t ** 3 * end[0],
            inverse ** 3 * start[1]
            + 3 * inverse ** 2 * t * control_one[1]
            + 3 * inverse * t ** 2 * control_two[1]
            + t ** 3 * end[1],
        ))
    return points


def drawing() -> tuple[float, tuple[int, int, int], list[tuple[list[list[tuple[float, float]]], float]]]:
    """
    The foreground vector: its viewport, its colour, and its shapes.

    Each shape is its subpaths and the width to stroke them with -- zero meaning
    fill. One colour for the whole drawing, because the icon is a monogram in a
    single ink and a raster in two colours is all the background blend below can
    do; a second colour raises rather than being quietly painted in the first.
    """
    root = ElementTree.parse(FOREGROUND).getroot()
    viewport = float(root.get(f'{ANDROID}viewportWidth'))
    if float(root.get(f'{ANDROID}viewportHeight')) != viewport:
        raise ValueError('the foreground viewport is not square')

    colour: tuple[int, int, int] | None = None
    shapes = []

    for element in root.iter('path'):
        stroke = element.get(f'{ANDROID}strokeColor')
        fill = element.get(f'{ANDROID}fillColor')
        ink = stroke or fill
        if ink is None:
            raise ValueError('a <path> has neither a fill nor a stroke')

        ink = parse_colour(ink)
        if colour is not None and ink != colour:
            raise ValueError('the foreground uses more than one colour')
        colour = ink

        width = float(element.get(f'{ANDROID}strokeWidth', 0)) if stroke else 0.0
        shapes.append((subpaths(element.get(f'{ANDROID}pathData')), width))

    if colour is None:
        raise ValueError(f'no <path> in {FOREGROUND}')
    return viewport, colour, shapes


# --------------------------------------------------------------------------
# Turning it into pixels
# --------------------------------------------------------------------------


def fill_polygon(target: bytearray, width: int, polygon: list[tuple[float, float]]) -> None:
    """
    Scanline-fill a closed polygon into the coverage mask.

    One crossing list per row, sorted, filled in pairs -- the standard even-odd
    rule. Coverage is a union rather than a paint: every polygon here is the same
    ink, so a stroke that crosses itself (which this monogram does, twice) must
    come out solid rather than punching a hole where it overlaps.
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


# How many sides the discs at the joins get. Twelve is invisible from a segment of
# a circle at 192px and a third of the work of twenty-four.
DISC_SIDES = 12


def stroke_polyline(
    target: bytearray,
    width: int,
    polyline: list[tuple[float, float]],
    radius: float,
) -> None:
    """
    Draw a polyline with a round cap at each end and a round join at each vertex.

    A quadrilateral per segment, offset either side by the radius, plus a disc at
    every point. The discs are what make the joins and the caps: a stroker that
    mitres corners has to decide what to do with a spike, and this drawing is all
    curves -- every "corner" is a flattening artefact of a smooth line, where a
    round join is not an approximation but the right answer.
    """
    for point in polyline:
        fill_polygon(target, width, [
            (
                point[0] + radius * math.cos(math.tau * side / DISC_SIDES),
                point[1] + radius * math.sin(math.tau * side / DISC_SIDES),
            )
            for side in range(DISC_SIDES)
        ])

    for (x_start, y_start), (x_end, y_end) in zip(polyline, polyline[1:]):
        length = math.hypot(x_end - x_start, y_end - y_start)
        if length == 0:
            continue
        # The segment's normal, scaled to the radius.
        offset_x = -(y_end - y_start) / length * radius
        offset_y = (x_end - x_start) / length * radius
        fill_polygon(target, width, [
            (x_start + offset_x, y_start + offset_y),
            (x_end + offset_x, y_end + offset_y),
            (x_end - offset_x, y_end - offset_y),
            (x_start - offset_x, y_start - offset_y),
        ])


def render(size: int, mask: str, art: tuple, background: tuple[int, int, int]) -> bytes:
    """One icon, as RGBA rows. `mask` is 'rounded' or 'circle'."""
    viewport, glyph_colour, shapes = art
    scale = size * SUPERSAMPLE / viewport
    big = size * SUPERSAMPLE

    # Coverage of the glyph, 0 or 1 per supersampled pixel. A byte each rather than
    # a bitfield: this runs once, and clarity is worth more than the memory.
    glyph = bytearray(big * big)

    for polylines, stroke_width in shapes:
        for polyline in polylines:
            scaled = [(x * scale, y * scale) for x, y in polyline]
            if stroke_width:
                stroke_polyline(glyph, big, scaled, stroke_width * scale / 2)
            else:
                fill_polygon(glyph, big, scaled)

    # A circle for the round variant, so a launcher that asks for one gets a disc
    # rather than a square with rounded corners drawn on top of it.
    mask_radius = big / 2.0
    mask_radius_squared = mask_radius * mask_radius

    # And a rounded square for the plain one. `straight` is how far from the
    # centre the sides run flat; past that in both axes is a corner, measured
    # from the centre of the arc that turns it.
    corner = mask_radius * CORNER_RADIUS
    straight = mask_radius - corner
    corner_squared = corner * corner

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
                    dx = big_x + 0.5 - mask_radius
                    if mask == 'circle':
                        if dx * dx + dy * dy <= mask_radius_squared:
                            inside_hits += 1
                    else:
                        over_x = abs(dx) - straight
                        over_y = abs(dy) - straight
                        if over_x <= 0 or over_y <= 0:
                            inside_hits += 1
                        elif over_x * over_x + over_y * over_y <= corner_squared:
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
                round(glyph_colour[channel] * glyph_share + background[channel] * (1 - glyph_share))
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
    art = drawing()
    background = background_colour()

    for folder, size in DENSITIES.items():
        for name, mask in (('ic_launcher', 'rounded'), ('ic_launcher_round', 'circle')):
            path = RES / folder / f'{name}.png'
            write_png(path, size, render(size, mask, art, background))
            print(f'{path.relative_to(RES.parent.parent.parent.parent)}: {size}x{size}')


if __name__ == '__main__':
    main()
