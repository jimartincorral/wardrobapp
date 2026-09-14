#!/usr/bin/env python3
"""
Cut the launcher icons out of the logo.

The logo is `art/logo.png`: the monogram and the wordmark, letterpressed into a
cream card. This takes the monogram out of it and writes every icon the app
ships, so what a phone shows is the artwork itself rather than a redrawing of it.

    python3 scripts/generate-launcher-icons.py

What it writes, all under `app/src/main/res`:

    mipmap-*/ic_launcher_foreground.png   the adaptive icon's top layer
    mipmap-*/ic_launcher_monochrome.png   the same shape, flat, for themed icons
    mipmap-*/ic_launcher.png              Android 7's icon: the two composited
    mipmap-*/ic_launcher_round.png        the same under a circular mask
    values/ic_launcher_background.xml     the cream, sampled from the card

The hard part is separating the mark from the paper. The card is not one flat
colour -- it is lit, so it shades from about 240 at the top to 253 in the middle
-- and the letterpress is both darker and lighter than the paper around it: a
pressed edge and a lit one. A single threshold gets the strokes and the shading
and cannot tell them apart, which is why the paper is estimated locally here
instead, and the mark is whatever differs from it.

No dependencies, which for this script now includes decoding a PNG as well as
writing one. Pillow would do both, and would be the sensible choice in a project
that already had it; adding an image library to a repository whose only Python is
this file is the trade this script has refused twice.
"""

from __future__ import annotations

import math
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / 'art' / 'logo.png'
RES = ROOT / 'app' / 'src' / 'main' / 'res'

# The adaptive icon's layers are 108dp; the legacy rasters are 48dp. Both are
# listed per density because a bitmap, unlike a vector, has one.
DENSITIES = {
    'mipmap-mdpi': 1,
    'mipmap-hdpi': 1.5,
    'mipmap-xhdpi': 2,
    'mipmap-xxhdpi': 3,
    'mipmap-xxxhdpi': 4,
}

LAYER_DP = 108
LEGACY_DP = 48

# How far from the centre of the 108dp canvas the artwork may reach, in those
# same units. Launchers mask the canvas to a shape of their own -- circle,
# squircle, teardrop -- and each of those fits inside the circle of radius 36, so
# that circle rather than the usually-quoted 72x72 square is the real boundary.
# A little under it, because the edge of a letterpress is a gradient and clipping
# one looks like a printing fault.
SAFE_RADIUS = 34.5

# How much of the legacy icon's half-width its corner radius takes. Enough that
# the corners are visibly transparent: an icon that fills every pixel of its
# square reads as a coloured tile, and lint says so (IconLauncherShape).
CORNER_RADIUS = 0.18

# Supersampling for the legacy icons' masks only. The artwork is resampled by
# averaging whole source pixels, which needs no help; it is the circle's edge
# that would otherwise be a staircase.
SUPERSAMPLE = 4


# --------------------------------------------------------------------------
# PNG
# --------------------------------------------------------------------------


def read_png(path: Path) -> tuple[int, int, bytearray]:
    """An 8-bit RGBA PNG as (width, height, pixels). Only what the logo is."""
    data = path.read_bytes()
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError(f'{path} is not a PNG')

    at, compressed, width, height = 8, bytearray(), 0, 0
    while at < len(data):
        length = struct.unpack('>I', data[at:at + 4])[0]
        kind = data[at + 4:at + 8]
        if kind == b'IHDR':
            width, height, depth, colour, _, _, interlace = struct.unpack(
                '>IIBBBBB', data[at + 8:at + 21])
            if (depth, colour, interlace) != (8, 6, 0):
                raise ValueError(
                    f'{path} is depth {depth}, colour type {colour}, interlace '
                    f'{interlace}; this reader only does 8-bit RGBA, not interlaced')
        elif kind == b'IDAT':
            compressed += data[at + 8:at + 8 + length]
        at += 12 + length

    raw = zlib.decompress(compressed)
    stride = width * 4
    pixels = bytearray(height * stride)
    previous = bytearray(stride)
    at = 0

    # Undo the per-row filters. Five of them, and a PNG may use a different one
    # on every row, so all five have to be here whatever this particular file
    # happens to contain.
    for y in range(height):
        method = raw[at]
        at += 1
        line = bytearray(raw[at:at + stride])
        at += stride

        if method == 1:
            for i in range(4, stride):
                line[i] = (line[i] + line[i - 4]) & 0xFF
        elif method == 2:
            for i in range(stride):
                line[i] = (line[i] + previous[i]) & 0xFF
        elif method == 3:
            for i in range(stride):
                left = line[i - 4] if i >= 4 else 0
                line[i] = (line[i] + ((left + previous[i]) >> 1)) & 0xFF
        elif method == 4:
            for i in range(stride):
                left = line[i - 4] if i >= 4 else 0
                up = previous[i]
                corner = previous[i - 4] if i >= 4 else 0
                pa, pb, pc = (abs(up - corner), abs(left - corner),
                              abs(left + up - 2 * corner))
                predicted = left if (pa <= pb and pa <= pc) else (up if pb <= pc else corner)
                line[i] = (line[i] + predicted) & 0xFF
        elif method != 0:
            raise ValueError(f'unknown PNG filter {method} on row {y}')

        pixels[y * stride:(y + 1) * stride] = line
        previous = line

    return width, height, pixels


def write_png(path: Path, width: int, height: int, rows: bytes) -> None:
    def chunk(kind: bytes, payload: bytes) -> bytes:
        body = kind + payload
        return struct.pack('>I', len(payload)) + body + struct.pack('>I', zlib.crc32(body))

    header = struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)  # 8-bit RGBA.
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(
        b'\x89PNG\r\n\x1a\n'
        + chunk(b'IHDR', header)
        + chunk(b'IDAT', zlib.compress(rows, 9))
        + chunk(b'IEND', b'')
    )


# --------------------------------------------------------------------------
# Finding the mark
# --------------------------------------------------------------------------


class Logo:
    """The source image, and what has been worked out about it."""

    # A pixel this dark is the frame the render sits in, not the card.
    FRAME = 60

    # How far into the card to step before believing a pixel. The card's own
    # edge is antialiased against the frame, and that ramp is as dark as a
    # stroke.
    RIM = 7

    # Wide enough that the window always contains paper -- the widest stroke in
    # the mark is about a third of this.
    PAPER_WINDOW = 22

    # Where a difference from the paper starts counting, and where it is the
    # whole way. Below the first is the card's own grain.
    SOFT, HARD = 4.0, 26.0

    def __init__(self, path: Path) -> None:
        self.width, self.height, self.pixels = read_png(path)
        count = self.width * self.height

        self.luma = [
            0.299 * self.pixels[i * 4] + 0.587 * self.pixels[i * 4 + 1]
            + 0.114 * self.pixels[i * 4 + 2]
            for i in range(count)
        ]

        self.off_card = self._off_card()
        paper = self._blur(self._window_max(self.luma, self.PAPER_WINDOW), 12)

        # Two mattes, because two things want different shapes.
        #
        # `mark` is the absolute difference, so it keeps the lit edge of the
        # letterpress as well as the pressed one -- that pair is what makes the
        # logo look stamped rather than drawn, and dropping the light half would
        # flatten it.
        #
        # `silhouette` is only what is darker than the paper, which is the stroke
        # itself. That is the one to hand a launcher that intends to tint it: the
        # highlight, tinted, would fatten every stroke by the width of its own
        # shadow.
        self.mark = [0.0] * count
        self.silhouette = [0.0] * count
        for i in range(count):
            if self.off_card[i]:
                continue
            pressed = paper[i] - self.luma[i]
            self.mark[i] = self._ramp(abs(pressed))
            self.silhouette[i] = self._ramp(pressed)

        self.paper_colour = self._paper_colour()
        self.monogram = self._isolate_monogram()

    def _ramp(self, difference: float) -> float:
        return min(1.0, max(0.0, (difference - self.SOFT) / (self.HARD - self.SOFT)))

    def _off_card(self) -> bytearray:
        """The frame, grown inwards far enough to swallow the card's own edge."""
        mask = bytearray(1 if value < self.FRAME else 0 for value in self.luma)
        width, height = self.width, self.height
        for _ in range(self.RIM):
            grown = bytearray(mask)
            for y in range(height):
                row = y * width
                for x in range(width):
                    i = row + x
                    if mask[i]:
                        continue
                    if (x == 0 or y == 0 or x == width - 1 or y == height - 1
                            or mask[i - 1] or mask[i + 1] or mask[i - width] or mask[i + width]):
                        grown[i] = 1
            mask = grown
        return mask

    def _window_max(self, values: list[float], radius: int) -> list[float]:
        """Separable maximum filter, over card pixels only."""
        width, height = self.width, self.height
        across = [0.0] * (width * height)
        for y in range(height):
            row = y * width
            for x in range(width):
                best = 0.0
                for k in range(max(0, x - radius), min(width, x + radius + 1)):
                    if not self.off_card[row + k] and values[row + k] > best:
                        best = values[row + k]
                across[row + x] = best

        out = [0.0] * (width * height)
        for x in range(width):
            for y in range(height):
                best = 0.0
                for k in range(max(0, y - radius), min(height, y + radius + 1)):
                    value = across[k * width + x]
                    if value > best:
                        best = value
                out[y * width + x] = best
        return out

    def _blur(self, values: list[float], radius: int) -> list[float]:
        """Separable box blur, so the paper estimate has no steps in it."""
        width, height = self.width, self.height
        across = [0.0] * (width * height)
        for y in range(height):
            row = y * width
            for x in range(width):
                lo, hi = max(0, x - radius), min(width - 1, x + radius)
                across[row + x] = sum(values[row + lo:row + hi + 1]) / (hi - lo + 1)

        out = [0.0] * (width * height)
        for x in range(width):
            column = [across[y * width + x] for y in range(height)]
            for y in range(height):
                lo, hi = max(0, y - radius), min(height - 1, y + radius)
                out[y * width + x] = sum(column[lo:hi + 1]) / (hi - lo + 1)
        return out

    def _paper_colour(self) -> tuple[int, int, int]:
        """
        The card's colour, as one value.

        The median of the pixels the mark does not touch, rather than the mean:
        the card carries a vignette and a sparkle, and a mean would take both of
        them into account.
        """
        channels = []
        for channel in range(3):
            values = sorted(
                self.pixels[i * 4 + channel]
                for i in range(self.width * self.height)
                if not self.off_card[i] and self.mark[i] < 0.02
            )
            channels.append(values[len(values) // 2])
        return tuple(channels)

    # Where a pixel is the core of a stroke rather than the halo around it.
    #
    # High, and that is the point. At the matte's own threshold the letterpress
    # haloes merge the wordmark's letters into one bar and join that bar to the
    # monogram above it, leaving nothing to separate. Only well inside the
    # strokes do the two come apart, and this is where: the monogram's lowest
    # core pixel is thirty rows clear of the wordmark's highest.
    SOLID = 0.75

    # An island smaller than this is a speck of grain or the sparkle in the
    # corner, not a piece of the logo.
    SPECK = 300

    # How far past that the halo is allowed to reach. The letterpress fades out
    # over about this many pixels, and everything inside it belongs to the mark.
    HALO = 12

    def _isolate_monogram(self) -> tuple[int, int, int, int]:
        """
        Throw away everything in the logo except the monogram.

        Both mattes are cleared outside it, which is what stops the wordmark
        appearing along the bottom of the icon: the canvas is wider than the mark
        and would otherwise sample whatever the logo has underneath it.

        The monogram is whatever sits above the gap: the islands of solid ink are
        found, the widest run of rows containing none of them is the space
        between the mark and the wordmark, and everything above it is the mark.
        Not "the largest island", which was the first thing tried here and is
        wrong -- the wordmark's letters run together into a blob half again the
        size of the monogram, and the icon came out as the word ARDROB.

        What survives is then grown by the width of its own halo, so that what is
        kept is the whole letterpress rather than a hard-edged cut through the
        middle of one.
        """
        width, height = self.width, self.height
        solid = bytearray(
            1 if (self.mark[i] > self.SOLID and not self.off_card[i]) else 0
            for i in range(width * height)
        )

        # Flood filled iteratively rather than recursively: these islands run to
        # thousands of pixels and Python's stack does not.
        seen = bytearray(width * height)
        islands = []
        for start in range(width * height):
            if not solid[start] or seen[start]:
                continue
            island, queue, seen[start] = [], [start], 1
            while queue:
                i = queue.pop()
                island.append(i)
                x, y = i % width, i // width
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1),
                               (1, 1), (1, -1), (-1, 1), (-1, -1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < width and 0 <= ny < height:
                        j = ny * width + nx
                        if solid[j] and not seen[j]:
                            seen[j] = 1
                            queue.append(j)
            if len(island) >= self.SPECK:
                islands.append(island)
        if not islands:
            raise ValueError('no artwork found in the logo')

        # Which rows any island occupies, and the widest run of rows where none
        # of them do.
        occupied = bytearray(height)
        for island in islands:
            for i in island:
                occupied[i // width] = 1
        first = occupied.index(1)
        last = height - 1 - occupied[::-1].index(1)

        gap, run = (0, -1), None
        for y in range(first, last + 2):
            if y <= last and not occupied[y]:
                run = y if run is None else run
            elif run is not None:
                if y - 1 - run > gap[1] - gap[0]:
                    gap = (run, y - 1)
                run = None
        if gap[1] < gap[0]:
            raise ValueError('the monogram and the wordmark are not separated by a gap')
        split = (gap[0] + gap[1]) // 2

        keep = bytearray(width * height)
        for island in islands:
            if max(i // width for i in island) < split:
                for i in island:
                    keep[i] = 1
        if not any(keep):
            raise ValueError('nothing above the gap; is the mark still above the wordmark?')
        for _ in range(self.HALO):
            grown = bytearray(keep)
            for y in range(height):
                row = y * width
                for x in range(width):
                    i = row + x
                    if keep[i]:
                        continue
                    if ((x and keep[i - 1]) or (x < width - 1 and keep[i + 1])
                            or (y and keep[i - width])
                            or (y < height - 1 and keep[i + width])):
                        grown[i] = 1
            keep = grown

        for i in range(width * height):
            if not keep[i]:
                self.mark[i] = 0.0
                self.silhouette[i] = 0.0

        present = [(i % width, i // width) for i in range(width * height)
                   if self.mark[i] > 0.15]
        xs = [x for x, _ in present]
        ys = [y for _, y in present]
        return min(xs), min(ys), max(xs), max(ys)

    def enclosing_circle(self) -> tuple[float, float, float]:
        """
        The smallest circle round the monogram's ink, as (x, y, radius).

        This, rather than the bounding box, is what the artwork has to be fitted
        by: the mask a launcher applies is round, and a box fitted to a circle
        wastes the corners it does not have.
        """
        left, top, right, bottom = self.monogram
        points = [
            (x, y)
            for y in range(top, bottom + 1)
            for x in range(left, right + 1)
            if self.mark[y * self.width + x] > 0.15
        ]

        # Move towards whatever is furthest, by less each time. Converges on the
        # minimum enclosing circle closely enough for a drawing.
        cx = sum(x for x, _ in points) / len(points)
        cy = sum(y for _, y in points) / len(points)
        step = 40.0
        for _ in range(4000):
            far, distance = max(
                ((p, math.hypot(p[0] - cx, p[1] - cy)) for p in points),
                key=lambda pair: pair[1],
            )
            cx += (far[0] - cx) * step / distance
            cy += (far[1] - cy) * step / distance
            step *= 0.997

        radius = max(math.hypot(x - cx, y - cy) for x, y in points)
        return cx, cy, radius


# --------------------------------------------------------------------------
# Drawing the icons
# --------------------------------------------------------------------------


def sample(logo: Logo, matte: list[float], flatten: tuple[int, int, int] | None,
           size: int, centre: tuple[float, float], scale: float) -> list[tuple]:
    """
    The artwork, resampled onto a `size` square canvas.

    `scale` is source pixels per destination pixel, and is always greater than
    one here -- the monogram is larger in the logo than in any icon drawn from it
    -- so every destination pixel is the average of a block of source pixels.
    That is the whole of the filtering, and it is the right one for shrinking: a
    bilinear tap would read four pixels out of the forty it is standing in for
    and alias the rest.

    `flatten` replaces the artwork's own colour, for the themed layer, which is
    going to be tinted and wants a shape rather than a picture.
    """
    out = []
    half = size / 2.0
    for y in range(size):
        for x in range(size):
            # The source block this destination pixel covers.
            sx0 = centre[0] + (x - half) * scale
            sy0 = centre[1] + (y - half) * scale
            sx1, sy1 = sx0 + scale, sy0 + scale

            red = green = blue = alpha = count = 0.0
            for sy in range(int(math.floor(sy0)), int(math.ceil(sy1))):
                if not 0 <= sy < logo.height:
                    continue
                for sx in range(int(math.floor(sx0)), int(math.ceil(sx1))):
                    if not 0 <= sx < logo.width:
                        continue
                    i = sy * logo.width + sx
                    a = matte[i]
                    count += 1
                    alpha += a
                    # Weighted by alpha, so the colour of a pixel that is mostly
                    # paper does not dilute the stroke it sits beside.
                    red += logo.pixels[i * 4] * a
                    green += logo.pixels[i * 4 + 1] * a
                    blue += logo.pixels[i * 4 + 2] * a

            if not count or alpha <= 0:
                out.append((0, 0, 0, 0.0))
                continue

            if flatten is not None:
                out.append((flatten[0], flatten[1], flatten[2], alpha / count))
            else:
                out.append((round(red / alpha), round(green / alpha),
                            round(blue / alpha), alpha / count))
    return out


def rows_of(pixels: list[tuple], size: int, mask=None) -> bytes:
    """RGBA rows with the PNG filter byte in front of each, optionally masked."""
    rows = bytearray()
    for y in range(size):
        rows.append(0)
        for x in range(size):
            red, green, blue, alpha = pixels[y * size + x]
            if mask is not None:
                alpha *= mask(x, y)
            rows.extend((red, green, blue, round(255 * min(1.0, max(0.0, alpha)))))
    return bytes(rows)


def over(pixels: list[tuple], background: tuple[int, int, int]) -> list[tuple]:
    """The artwork composited onto the card's colour, opaque."""
    out = []
    for red, green, blue, alpha in pixels:
        out.append((
            round(red * alpha + background[0] * (1 - alpha)),
            round(green * alpha + background[1] * (1 - alpha)),
            round(blue * alpha + background[2] * (1 - alpha)),
            1.0,
        ))
    return out


def shape_mask(size: int, shape: str):
    """
    How much of each pixel is inside the legacy icon's outline.

    Supersampled, because this is the one edge in these icons that is not
    already softened by the resampling above.
    """
    radius = size / 2.0
    corner = radius * CORNER_RADIUS
    straight = radius - corner
    coverage = [0.0] * (size * size)

    for y in range(size):
        for x in range(size):
            inside = 0
            for sub_y in range(SUPERSAMPLE):
                dy = y + (sub_y + 0.5) / SUPERSAMPLE - radius
                for sub_x in range(SUPERSAMPLE):
                    dx = x + (sub_x + 0.5) / SUPERSAMPLE - radius
                    if shape == 'circle':
                        if dx * dx + dy * dy <= radius * radius:
                            inside += 1
                    else:
                        over_x, over_y = abs(dx) - straight, abs(dy) - straight
                        if over_x <= 0 or over_y <= 0:
                            inside += 1
                        elif over_x * over_x + over_y * over_y <= corner * corner:
                            inside += 1
            coverage[y * size + x] = inside / (SUPERSAMPLE * SUPERSAMPLE)

    return lambda x, y: coverage[y * size + x]


def write_background(colour: tuple[int, int, int]) -> None:
    value = '#%02X%02X%02X' % colour
    (RES / 'values' / 'ic_launcher_background.xml').write_text(
        '<?xml version="1.0" encoding="utf-8" ?>\n'
        '<resources>\n'
        '    <!--\n'
        '        The card the logo is printed on, sampled from art/logo.png.\n'
        '\n'
        '        Written by scripts/generate-launcher-icons.py, which takes the\n'
        '        median of every pixel of the card the mark does not touch — edit\n'
        '        the logo rather than this number. It is the adaptive icon\'s\n'
        '        background layer, and what the monogram was drawn to sit on: the\n'
        '        artwork is letterpressed, so its highlights are lighter than the\n'
        '        paper and only read as highlights against this exact colour.\n'
        '\n'
        '        Not a night variant, and it must not become one. An icon that\n'
        '        changed with the system theme would be a different icon on the\n'
        '        same launcher twice a day. The splash screen, which does want to\n'
        '        follow the theme, has its own colour in splash_background.xml.\n'
        '    -->\n'
        f'    <color name="ic_launcher_background">{value}</color>\n'
        '</resources>\n'
    )
    print(f'values/ic_launcher_background.xml: {value}')


def main() -> None:
    logo = Logo(SOURCE)
    cx, cy, radius = logo.enclosing_circle()
    left, top, right, bottom = logo.monogram
    print(f'monogram: {right - left + 1}x{bottom - top + 1}px at ({left}, {top}), '
          f'within {radius:.0f}px of ({cx:.0f}, {cy:.0f})')

    write_background(logo.paper_colour)

    for folder, density in DENSITIES.items():
        layer = round(LAYER_DP * density)
        legacy = round(LEGACY_DP * density)

        # Source pixels per destination pixel, set so the monogram's own circle
        # lands exactly on the safe one.
        layer_scale = radius / (SAFE_RADIUS / LAYER_DP * layer)
        legacy_scale = radius / (SAFE_RADIUS / LAYER_DP * legacy)

        artwork = sample(logo, logo.mark, None, layer, (cx, cy), layer_scale)
        write_png(RES / folder / 'ic_launcher_foreground.png', layer, layer,
                  rows_of(artwork, layer))

        flat = sample(logo, logo.silhouette, (0, 0, 0), layer, (cx, cy), layer_scale)
        write_png(RES / folder / 'ic_launcher_monochrome.png', layer, layer,
                  rows_of(flat, layer))

        small = over(sample(logo, logo.mark, None, legacy, (cx, cy), legacy_scale),
                     logo.paper_colour)
        for name, shape in (('ic_launcher', 'rounded'), ('ic_launcher_round', 'circle')):
            write_png(RES / folder / f'{name}.png', legacy, legacy,
                      rows_of(small, legacy, shape_mask(legacy, shape)))

        print(f'{folder}: layers {layer}x{layer}, icons {legacy}x{legacy}')


if __name__ == '__main__':
    main()
