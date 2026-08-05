#!/usr/bin/env python3
"""Generate NeroColonies' placeholder block, item and entity textures.

Run it directly or through the Gradle `genAssets` task:

    python tools/gen_textures.py                # write missing textures
    python tools/gen_textures.py --multiloader  # same (flag kept for the shared gradle task)
    python tools/gen_textures.py --force        # rewrite every generated texture
    python tools/gen_textures.py --list         # print what would be written, write nothing

ADDITIVE-ONLY: a texture that already exists on disk is never overwritten, so hand-drawn
replacements survive every rerun and this script only fills gaps. `--force` replaces the whole
generated set in one shot.

No third-party dependency: PNGs are encoded here with `zlib` + `struct` (ported from
NeroCreatures' generator), so the script runs on a bare Python 3 install and `genAssets` stays
green on machines without Pillow. The painting recipes are ported from NeroTech's 32x generator
and scaled back to 16x: noise-filled hull plate, 1px bevel, corner rivets, a recessed face panel
and an accent glyph or lens per machine family.

Palette contract — the art matches the GUI palette in `client/screen/NeroColoniesScreen.java` and
its subclasses, so a block and its screen read as the same machine:

    hull            0x141C26 panel / 0x2A3A4D edge   (dark blue-grey plate)
    colony cyan     0x4FB3D9  beacon, outpost
    oxygen cyan     0x6FD3E8  oxygen generator
    work green      0x8FD96F  job stations (family LED strip)
    depot amber     0xD9A64F  colony depot, refinery glyph
    research violet 0x9F7FE0  research station, fabricator glyph
    habitat panels  lighter grey-blue plate, tiered brighter per housing tier

Sizes: blocks and items are 16x16 RGBA; the colonist entity sheet is 64x64 and is painted against
the real UV map of `client/renderer/ColonistModel.java` (vanilla biped offsets plus a 6x8x3 suit
pack at texOffs(0, 32)), not as an abstract field.

Nothing is written into `textures/gui/` — every NeroColonies screen paints procedurally and
references no GUI sheet.

Coverage is checked at the end of every run: the script scans the mod's own model and item
definition JSONs for `nerocolonies:block/...` / `nerocolonies:item/...` references and the
colonist renderer for its entity path, then reports referenced vs painted and flags both
directions of mismatch (a reference with no painter, a painter no model asks for, a stray PNG).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import random
import re
import struct
import sys
import zlib

SIZE = 16
ENTITY_SIZE = 64

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_RESOURCES = os.path.join(REPO_ROOT, "common", "src", "main", "resources")
_ASSETS = os.path.join(_RESOURCES, "assets", "nerocolonies")
_TEXTURES = os.path.join(_ASSETS, "textures")
BLOCK_DIR = os.path.join(_TEXTURES, "block")
ITEM_DIR = os.path.join(_TEXTURES, "item")
ENTITY_DIR = os.path.join(_TEXTURES, "entity")
RENDERER_JAVA = os.path.join(
    REPO_ROOT, "common", "src", "main", "java", "za", "co", "neroland", "nerocolonies",
    "client", "renderer", "ColonistRenderer.java")

DIRS = {"block": BLOCK_DIR, "item": ITEM_DIR, "entity": ENTITY_DIR}

# Coverage ledger: every save() records its name, so check_coverage() can compare what the
# painters own against what the models actually reference.
PAINTED = {"block": set(), "item": set(), "entity": set()}

Colour = tuple  # (r, g, b, a)

CLEAR: Colour = (0, 0, 0, 0)

# ---- hull palette (GUI panel 0x141C26 / edge 0x2A3A4D, spread into a noise ramp) ----
H_DARK: Colour = (14, 20, 28, 255)
HULL = [(26, 36, 50, 255), (31, 43, 58, 255), (22, 31, 43, 255), (37, 50, 67, 255)]
H_MID: Colour = (52, 70, 92, 255)
H_LIGHT: Colour = (98, 126, 158, 255)
INK: Colour = (5, 8, 13, 255)          # GUI INK — outlines
TROUGH: Colour = (11, 17, 25, 255)     # GUI TROUGH — recessed panel fill

# ---- habitat palette: the same plate, lit and painted for people rather than machines ----
HAB = [(74, 89, 105, 255), (83, 99, 116, 255), (66, 80, 95, 255), (93, 110, 128, 255)]
HAB_DARK: Colour = (38, 47, 58, 255)
HAB_LIGHT: Colour = (162, 180, 198, 255)

# ---- accents, straight off the screens ----
CYAN: Colour = (79, 179, 217, 255)      # 0xFF4FB3D9 colony cyan
OXY: Colour = (111, 211, 232, 255)      # 0xFF6FD3E8 oxygen cyan
GREEN: Colour = (143, 217, 111, 255)    # 0xFF8FD96F work green
AMBER: Colour = (217, 166, 79, 255)     # 0xFFD9A64F depot amber
VIOLET: Colour = (159, 127, 224, 255)   # 0xFF9F7FE0 research violet
GLASS: Colour = (126, 196, 224, 255)    # habitat window glass


def _mix(a: Colour, b: Colour, t: float) -> Colour:
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3)) + (255,)


def _lighten(colour: Colour, amount: int) -> Colour:
    r, g, b, a = colour
    return (min(255, r + amount), min(255, g + amount), min(255, b + amount), a)


def deep(accent: Colour) -> Colour:
    """The unlit socket shade of an accent — dark enough to read as 'off'."""
    return _mix(accent, H_DARK, 0.74)


def glow(accent: Colour) -> Colour:
    """The hot core of an accent."""
    return _lighten(accent, 62)


def rng_for(name: str) -> random.Random:
    """Deterministic per-name seed — stable across runs and machines."""
    return random.Random(int(hashlib.md5(name.encode()).hexdigest(), 16) & 0xFFFFFFFF)


class Canvas:
    """A tiny RGBA raster with just enough primitives for pixel motifs."""

    def __init__(self, size: int = SIZE) -> None:
        self.size = size
        self.px = [[CLEAR for _ in range(size)] for _ in range(size)]

    # --- primitives ---

    def set(self, x: int, y: int, colour: Colour) -> None:
        if 0 <= x < self.size and 0 <= y < self.size:
            self.px[y][x] = colour

    def get(self, x: int, y: int) -> Colour:
        return self.px[y][x]

    def rect(self, x0: int, y0: int, x1: int, y1: int, colour: Colour) -> None:
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.set(x, y, colour)

    def outline(self, x0: int, y0: int, x1: int, y1: int, colour: Colour) -> None:
        for x in range(x0, x1 + 1):
            self.set(x, y0, colour)
            self.set(x, y1, colour)
        for y in range(y0, y1 + 1):
            self.set(x0, y, colour)
            self.set(x1, y, colour)

    def disc(self, cx: float, cy: float, radius: float, colour: Colour) -> None:
        for y in range(self.size):
            for x in range(self.size):
                if (x - cx) ** 2 + (y - cy) ** 2 <= radius * radius:
                    self.set(x, y, colour)

    def diamond(self, cx: float, cy: float, radius: float, colour: Colour) -> None:
        for y in range(self.size):
            for x in range(self.size):
                if abs(x - cx) + abs(y - cy) <= radius:
                    self.set(x, y, colour)

    def speckle(self, rng: random.Random, count: int, colour: Colour, region) -> None:
        x0, y0, x1, y1 = region
        for _ in range(count):
            self.set(rng.randint(x0, x1), rng.randint(y0, y1), colour)

    # --- machine recipes (NeroTech's, at 16x) ---

    def noise_fill(self, palette, rng: random.Random, region=None) -> None:
        x0, y0, x1, y1 = region or (0, 0, self.size - 1, self.size - 1)
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.set(x, y, rng.choice(palette))

    def bevel(self, light: Colour, dark: Colour) -> None:
        """1px bevel: highlight top/left, shadow bottom/right."""
        n = self.size
        for i in range(n):
            self.set(i, 0, light)
            self.set(0, i, light)
            self.set(i, n - 1, dark)
            self.set(n - 1, i, dark)
        self.set(n - 1, 0, _mix(light, dark, 0.5))
        self.set(0, n - 1, _mix(light, dark, 0.5))

    def rivets(self, light: Colour, dark: Colour,
               pts=((2, 2), (13, 2), (2, 13), (13, 13))) -> None:
        for (rx, ry) in pts:
            self.set(rx, ry, light)
            self.set(rx, ry + 1, dark)

    def recess(self, x0: int, y0: int, x1: int, y1: int,
               fill: Colour = TROUGH, lip: Colour = H_MID) -> None:
        """Sunken panel: fill, inner shadow top/left, catch-light on the lower lip."""
        self.rect(x0, y0, x1, y1, fill)
        for x in range(x0, x1 + 1):
            self.set(x, y0, INK)
        for y in range(y0, y1 + 1):
            self.set(x0, y, INK)
        for x in range(x0 + 1, x1 + 1):
            self.set(x, y1, lip)
        for y in range(y0 + 1, y1 + 1):
            self.set(x1, y, lip)

    def led(self, x: int, y: int, accent: Colour, core: Colour = None) -> None:
        """2x2 dark socket with a single emissive pixel."""
        self.rect(x, y, x + 1, y + 1, deep(accent))
        self.set(x, y, accent)
        if core:
            self.set(x, y, core)

    # --- encoding ---

    def to_png(self) -> bytes:
        raw = bytearray()
        for row in self.px:
            raw.append(0)  # filter type 0 (None)
            for (r, g, b, a) in row:
                raw += bytes((r, g, b, a))

        def chunk(tag: bytes, data: bytes) -> bytes:
            return (struct.pack(">I", len(data)) + tag + data
                    + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

        header = struct.pack(">2I5B", self.size, self.size, 8, 6, 0, 0, 0)
        return (b"\x89PNG\r\n\x1a\n"
                + chunk(b"IHDR", header)
                + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
                + chunk(b"IEND", b""))


# --- shared bases ---------------------------------------------------------


def hull_base(name: str) -> Canvas:
    """The shared machine-face recipe: hull noise + 1px bevel + corner rivets."""
    c = Canvas(SIZE)
    c.noise_fill(HULL, rng_for(name))
    c.bevel(H_MID, H_DARK)
    c.rivets(H_LIGHT, H_DARK)
    return c


def habitat_base(name: str, tier: int) -> Canvas:
    """Habitat plate: lighter than machine hull, and one shade brighter per tier."""
    lift = (tier - 1) * 10
    palette = [_lighten(col, lift) for col in HAB]
    c = Canvas(SIZE)
    c.noise_fill(palette, rng_for(name))
    c.bevel(_lighten(HAB_LIGHT, lift), HAB_DARK)
    c.rivets(_lighten(HAB_LIGHT, lift), HAB_DARK)
    return c


def _job_strip(c: Canvas) -> None:
    """The work-green status strip every job station carries along its lower edge."""
    c.rect(3, 13, 12, 13, deep(GREEN))
    for x in (4, 6, 8, 10):
        c.set(x, 13, GREEN)
    c.set(6, 13, glow(GREEN))


# --- block painters -------------------------------------------------------


def gen_colony_beacon() -> Canvas:
    """The colony's heart: a broad emitter lens ringed by cyan status LEDs."""
    c = hull_base("colony_beacon")
    c.recess(3, 3, 12, 12)
    c.diamond(7.5, 7.5, 4.4, deep(CYAN))
    c.diamond(7.5, 7.5, 3.2, _mix(CYAN, H_DARK, 0.35))
    c.diamond(7.5, 7.5, 1.8, CYAN)
    c.rect(7, 7, 8, 8, glow(CYAN))
    for (lx, ly) in ((4, 4), (11, 4), (4, 11), (11, 11)):
        c.set(lx, ly, CYAN)
    return c


def gen_outpost_beacon() -> Canvas:
    """The outpost: the beacon's smaller sibling — one lens, a mast, no crown."""
    c = hull_base("outpost_beacon")
    c.recess(3, 5, 12, 12)
    c.disc(7.5, 8.5, 3.0, deep(CYAN))
    c.disc(7.5, 8.5, 2.0, _mix(CYAN, H_DARK, 0.25))
    c.rect(7, 8, 8, 9, CYAN)
    c.set(7, 8, glow(CYAN))
    c.rect(7, 2, 8, 4, H_MID)                        # mast
    c.rect(5, 2, 10, 2, H_LIGHT)                     # cross-arm
    c.set(5, 3, CYAN)
    c.set(10, 3, CYAN)
    return c


def gen_oxygen_generator() -> Canvas:
    """Louvred intake with the gas glow behind it, and a three-segment pressure tick."""
    c = hull_base("oxygen_generator")
    c.recess(2, 4, 13, 12)
    for y in (5, 7, 9, 11):
        c.rect(3, y, 12, y, deep(OXY))
        c.rect(3, y + 1, 12, y + 1, H_DARK)
    for y in (5, 7, 9, 11):
        c.rect(6, y, 9, y, OXY)
    c.set(7, 7, glow(OXY))
    c.set(8, 9, glow(OXY))
    for x in (3, 5, 7):                      # pressure tick along the top rail
        c.set(x, 2, OXY)
    return c


def gen_colony_depot() -> Canvas:
    """A shared locker: banded crate face, amber handle band, corner brackets."""
    c = hull_base("colony_depot")
    c.recess(2, 2, 13, 13)
    c.rect(3, 3, 12, 12, _mix(HULL[3], AMBER, 0.12))
    for x in (3, 12):                        # vertical crate ribs
        c.rect(x, 3, x, 12, H_DARK)
    c.rect(3, 7, 12, 8, deep(AMBER))
    c.rect(5, 7, 10, 7, AMBER)
    c.rect(6, 8, 9, 8, glow(AMBER))
    for (bx, by) in ((4, 4), (11, 4), (4, 11), (11, 11)):
        c.set(bx, by, AMBER)
        c.set(bx, by + 1 if by < 8 else by - 1, deep(AMBER))
    return c


def gen_research_station() -> Canvas:
    """A data screen: violet readout rows over a dark panel, with a progress rail."""
    c = hull_base("research_station")
    c.recess(2, 2, 13, 11)
    rng = rng_for("research_rows")
    for i, y in enumerate((4, 6, 8)):
        width = rng.randint(4, 9)
        c.rect(3, y, 3 + width, y, _mix(VIOLET, H_DARK, 0.45))
        c.set(3, y, VIOLET)
    c.rect(3, 10, 12, 10, deep(VIOLET))
    c.rect(3, 10, 8, 10, VIOLET)
    c.set(8, 10, glow(VIOLET))
    c.rect(3, 13, 12, 13, H_DARK)
    for x in (4, 7, 10):
        c.set(x, 13, _mix(VIOLET, H_DARK, 0.5))
    return c


def gen_farm_station() -> Canvas:
    """Soil bed under a work light: a green sprout in the till rows."""
    c = hull_base("farm_station")
    c.recess(2, 3, 13, 12)
    c.rect(3, 9, 12, 12, (58, 44, 32, 255))          # tilled soil
    for x in range(3, 13, 3):
        c.set(x, 10, (78, 60, 44, 255))
    c.rect(7, 6, 7, 9, _mix(GREEN, H_DARK, 0.2))     # stem
    for (lx, ly) in ((5, 8), (6, 7), (8, 7), (9, 8), (4, 7), (10, 7)):
        c.set(lx, ly, GREEN)                         # drooping leaf pairs
    for (lx, ly) in ((5, 7), (9, 7)):
        c.set(lx, ly, _lighten(GREEN, 30))
    c.rect(6, 4, 8, 5, deep(GREEN))                  # grow light housing
    c.rect(6, 5, 8, 5, glow(GREEN))
    _job_strip(c)
    return c


def gen_hydroponics_station() -> Canvas:
    """Nutrient columns: three lit tubes with a cyan meniscus in each."""
    c = hull_base("hydroponics_station")
    c.recess(2, 2, 13, 12)
    for x in (4, 7, 10):
        c.rect(x, 3, x + 1, 11, deep(OXY))
        c.rect(x, 6, x + 1, 11, _mix(OXY, H_DARK, 0.35))
        c.rect(x, 6, x + 1, 6, OXY)
        c.set(x, 4, glow(OXY))
    for x in (3, 6, 9, 12):                          # feed manifold
        c.set(x, 2, _mix(OXY, H_DARK, 0.5))
    _job_strip(c)
    return c


def gen_refinery_station() -> Canvas:
    """A smelt window: amber melt behind a hazard-free viewport, pipes down each side."""
    c = hull_base("refinery_station")
    c.recess(3, 3, 12, 11)
    c.rect(4, 7, 11, 10, deep(AMBER))
    c.rect(4, 8, 11, 10, _mix(AMBER, H_DARK, 0.3))
    c.rect(4, 9, 11, 10, AMBER)
    for x in (5, 8, 11):
        c.set(x, 9, glow(AMBER))
    for x in (4, 11):                                 # riser pipes
        c.rect(x, 4, x, 6, H_MID)
        c.set(x, 5, H_LIGHT)
    c.rect(6, 4, 9, 5, H_DARK)                        # burner hood
    _job_strip(c)
    return c


def gen_fabricator_station() -> Canvas:
    """A print bed: violet head over a gridded platen, sparks on the rail."""
    c = hull_base("fabricator_station")
    c.recess(2, 3, 13, 12)
    for y in range(9, 12):                            # platen, lit from the head
        for x in range(3, 13):
            c.set(x, y, _mix(HULL[3], VIOLET, 0.10 if (x + y) % 2 else 0.20))
    c.rect(3, 11, 12, 11, H_MID)                      # platen lip
    c.rect(3, 4, 12, 4, H_MID)                        # gantry rail
    for x in (3, 12):
        c.set(x, 4, H_LIGHT)
    c.rect(5, 5, 10, 7, deep(VIOLET))                 # print head
    c.rect(6, 5, 9, 6, VIOLET)
    c.rect(7, 7, 8, 8, glow(VIOLET))                  # extrusion
    c.rect(7, 9, 8, 10, _mix(VIOLET, HULL[3], 0.45))  # the part being printed
    _job_strip(c)
    return c


def gen_habitat_pod() -> Canvas:
    """Tier 1: a cramped pod — one porthole, one hatch handle, and not much else."""
    c = habitat_base("habitat_pod", 1)
    c.disc(7.5, 7.5, 4.6, HAB_DARK)                   # porthole frame
    c.disc(7.5, 7.5, 3.6, HAB_LIGHT)
    c.disc(7.5, 7.5, 3.0, _mix(GLASS, HAB_DARK, 0.4))
    c.rect(5, 7, 10, 7, _mix(GLASS, HAB_DARK, 0.1))   # sill glint across the pane
    c.disc(6.6, 6.6, 1.3, GLASS)                      # catch-light
    c.rect(3, 12, 6, 12, HAB_DARK)                    # hatch handle
    c.set(3, 12, HAB_LIGHT)
    return c


def gen_habitat_module() -> Canvas:
    """Tier 2: a pressurised module — twin windows, a seam, and a lit door strip."""
    c = habitat_base("habitat_module", 2)
    for x0 in (2, 9):
        c.rect(x0, 3, x0 + 4, 7, HAB_DARK)
        c.rect(x0 + 1, 4, x0 + 3, 6, _mix(GLASS, HAB_DARK, 0.35))
        c.set(x0 + 1, 4, GLASS)
    c.rect(1, 9, 14, 9, HAB_DARK)                     # deck seam
    c.rect(5, 11, 10, 13, _mix(HAB[2], HAB_DARK, 0.5))
    c.rect(6, 12, 9, 12, deep(CYAN))
    c.set(7, 12, CYAN)
    return c


def gen_habitat_block() -> Canvas:
    """Tier 3: a residential block — a full window band, trim, and warm interior light."""
    c = habitat_base("habitat_block", 3)
    c.rect(1, 4, 14, 9, HAB_DARK)
    for x0 in (2, 6, 10):
        c.rect(x0, 5, x0 + 2, 8, _mix(GLASS, HAB_DARK, 0.25))
        c.rect(x0, 5, x0 + 2, 5, GLASS)
        c.set(x0 + 2, 8, _mix(AMBER, HAB_DARK, 0.45))
    c.rect(1, 3, 14, 3, HAB_LIGHT)                    # roof trim
    c.rect(1, 10, 14, 10, HAB_DARK)
    c.rect(2, 12, 13, 12, _mix(HAB[3], CYAN, 0.25))   # ground-floor light rail
    for x in (4, 8, 12):
        c.set(x, 12, CYAN)
    return c


# --- item painters --------------------------------------------------------
# Upgrade modules share one casing so they read as a family, and differ only in glyph and hue —
# both chosen to survive a 16x16 slot on the 0x232F3F slot fill the screens paint.


def _module_casing(name: str, accent: Colour) -> Canvas:
    c = Canvas(SIZE)
    c.rect(3, 2, 12, 13, HULL[1])
    c.noise_fill(HULL, rng_for(name), (4, 3, 11, 12))
    c.outline(3, 2, 12, 13, INK)
    for x in range(4, 12):                            # top highlight / bottom shadow
        c.set(x, 3, _mix(H_LIGHT, HULL[1], 0.4))
        c.set(x, 12, H_DARK)
    for y in range(3, 13):
        c.set(4, y, _mix(H_MID, HULL[1], 0.5))
        c.set(11, y, H_DARK)
    for (px, py) in ((4, 3), (11, 3), (4, 12), (11, 12)):
        c.set(px, py, H_LIGHT)
    c.rect(5, 1, 10, 1, deep(accent))                 # keyed edge connector
    c.rect(6, 1, 9, 1, accent)
    c.rect(5, 14, 10, 14, H_DARK)
    return c


def gen_speed_module() -> Canvas:
    """Speed: two stacked chevrons driving upward, in colony cyan."""
    c = _module_casing("speed_module", CYAN)
    c.recess(5, 3, 10, 12, INK, deep(CYAN))
    for apex in (5, 9):
        for i in range(3):                            # legs spread down and out: chevron up
            c.set(7 - i, apex + i, CYAN)
            c.set(8 + i, apex + i, CYAN)
        c.set(7, apex, glow(CYAN))
        c.set(8, apex, glow(CYAN))
        c.set(5, apex + 2, deep(CYAN))
        c.set(10, apex + 2, deep(CYAN))
    return c


def gen_efficiency_module() -> Canvas:
    """Efficiency: a bolt through the casing, in work green."""
    c = _module_casing("efficiency_module", GREEN)
    c.recess(5, 3, 10, 12, INK, deep(GREEN))
    bolt = ((8, 4), (8, 5), (7, 5), (7, 6), (6, 7), (7, 7), (8, 7), (9, 7),
            (8, 8), (8, 9), (7, 9), (7, 10), (7, 11))
    for (x, y) in bolt:
        c.set(x, y, GREEN)
    for (x, y) in ((8, 4), (8, 7), (7, 10)):
        c.set(x, y, glow(GREEN))
    for (x, y) in ((6, 6), (9, 8)):
        c.set(x, y, deep(GREEN))
    return c


def gen_range_module() -> Canvas:
    """Range: a claim marker throwing three rings, in research violet."""
    c = _module_casing("range_module", VIOLET)
    c.recess(5, 3, 10, 12, INK, deep(VIOLET))
    c.diamond(7.5, 8.5, 4.2, VIOLET)
    c.diamond(7.5, 8.5, 3.2, INK)
    c.diamond(7.5, 8.5, 2.4, VIOLET)
    c.diamond(7.5, 8.5, 1.4, INK)
    c.rect(7, 8, 8, 9, glow(VIOLET))
    c.rect(5, 3, 10, 3, deep(VIOLET))                 # mast base the rings leave from
    c.rect(7, 3, 8, 4, _lighten(VIOLET, 20))
    return c


def gen_capacity_module() -> Canvas:
    """Capacity: a stack of filling bars, in depot amber."""
    c = _module_casing("capacity_module", AMBER)
    c.recess(5, 3, 10, 12, INK, deep(AMBER))
    for i, y in enumerate((5, 7, 9, 11)):
        c.rect(6, y, 9, y, deep(AMBER))
        c.rect(6, y, 6 + min(3, i + 1), y, AMBER)
        c.set(6, y, glow(AMBER))
    c.rect(6, 4, 9, 4, _mix(AMBER, INK, 0.5))         # crate lid
    return c


# --- entity sheet ---------------------------------------------------------
# Painted against the REAL UV map of ColonistModel (LayerDefinition.create(mesh, 64, 64)):
#   head  texOffs(0, 0)   8x8x8   — vanilla biped head block
#   body  texOffs(16, 16) 8x12x4  — vanilla biped body block
#   pack  texOffs(0, 32)  6x8x3   — the suit backpack, the mod's own addition
#   arms  texOffs(40, 16) 4x12x4  — both arms share one block (vanilla right-arm slot)
#   legs  texOffs(0, 16)  4x12x4  — both legs share one block (vanilla leg slot)

SUIT = [(54, 72, 96, 255), (60, 80, 106, 255), (46, 62, 84, 255)]
SUIT_DARK: Colour = (28, 38, 52, 255)
SUIT_LIGHT: Colour = (112, 138, 168, 255)
HELMET = [(150, 162, 176, 255), (136, 148, 162, 255), (162, 174, 188, 255)]
VISOR_DARK: Colour = (16, 30, 40, 255)


def _faces(u: int, v: int, w: int, h: int, d: int) -> dict:
    """The six UV rects of a Minecraft cube at texOffs(u, v) with size (w, h, d)."""
    return {
        "right": (u, v + d, d, h),
        "front": (u + d, v + d, w, h),
        "left": (u + d + w, v + d, d, h),
        "back": (u + d + w + d, v + d, w, h),
        "top": (u + d, v, w, d),
        "bottom": (u + d + w, v, w, d),
    }


def _fill_face(c: Canvas, face, palette, rng: random.Random) -> None:
    x, y, w, h = face
    c.noise_fill(palette, rng, (x, y, x + w - 1, y + h - 1))


def _face_rect(c: Canvas, face, x0: int, y0: int, x1: int, y1: int, colour: Colour) -> None:
    """Rect in face-local coordinates."""
    fx, fy, _, _ = face
    c.rect(fx + x0, fy + y0, fx + x1, fy + y1, colour)


def gen_colonist() -> Canvas:
    c = Canvas(ENTITY_SIZE)
    rng = rng_for("colonist")

    head = _faces(0, 0, 8, 8, 8)
    body = _faces(16, 16, 8, 12, 4)
    pack = _faces(0, 32, 6, 8, 3)
    arm = _faces(40, 16, 4, 12, 4)
    leg = _faces(0, 16, 4, 12, 4)

    # Helmet: hard shell everywhere, glass at the front.
    for key in ("right", "left", "back", "top", "bottom"):
        _fill_face(c, head[key], HELMET, rng)
    _fill_face(c, head["front"], HELMET, rng)
    _face_rect(c, head["front"], 1, 2, 6, 6, VISOR_DARK)
    _face_rect(c, head["front"], 1, 3, 6, 5, _mix(CYAN, VISOR_DARK, 0.55))
    _face_rect(c, head["front"], 1, 3, 3, 3, CYAN)                 # reflection sweep
    _face_rect(c, head["front"], 0, 7, 7, 7, SUIT_DARK)            # neck seal
    _face_rect(c, head["top"], 3, 0, 4, 1, CYAN)                   # lamp on the crown
    for key in ("right", "left"):
        _face_rect(c, head[key], 2, 6, 5, 6, SUIT_DARK)

    # Body: suit with a chest control panel.
    for key, palette in (("right", SUIT), ("left", SUIT), ("back", SUIT),
                         ("top", SUIT), ("bottom", SUIT)):
        _fill_face(c, body[key], palette, rng)
    _fill_face(c, body["front"], SUIT, rng)
    _face_rect(c, body["front"], 2, 1, 5, 4, SUIT_DARK)            # chest panel
    _face_rect(c, body["front"], 3, 2, 4, 2, CYAN)
    _face_rect(c, body["front"], 3, 3, 4, 3, deep(GREEN))
    c.set(body["front"][0] + 3, body["front"][1] + 3, GREEN)
    _face_rect(c, body["front"], 0, 6, 7, 6, AMBER)                # utility belt
    _face_rect(c, body["front"], 0, 7, 7, 7, deep(AMBER))
    _face_rect(c, body["back"], 0, 6, 7, 7, SUIT_DARK)
    _face_rect(c, body["top"], 0, 0, 7, 0, SUIT_LIGHT)

    # Pack: the silhouette cue — tanks, gauge, warning tick.
    for key in pack:
        _fill_face(c, pack[key], [SUIT_DARK, (30, 40, 54, 255), (26, 34, 46, 255)], rng)
    for face in (pack["back"], pack["front"]):
        _face_rect(c, face, 1, 1, 1, 6, H_MID)                     # tank bodies
        _face_rect(c, face, 4, 1, 4, 6, H_MID)
        _face_rect(c, face, 1, 1, 1, 2, OXY)                       # charge windows
        _face_rect(c, face, 4, 1, 4, 2, OXY)
        _face_rect(c, face, 2, 4, 3, 4, deep(CYAN))
        c.set(face[0] + 2, face[1] + 4, CYAN)
    _face_rect(c, pack["top"], 1, 0, 4, 0, H_MID)

    # Arms: suit sleeve, bright cuff so the walk cycle reads at distance.
    for key in arm:
        _fill_face(c, arm[key], SUIT, rng)
    for key in ("front", "back", "left", "right"):
        _face_rect(c, arm[key], 0, 8, 3, 8, deep(CYAN))
        _face_rect(c, arm[key], 0, 9, 3, 11, SUIT_DARK)            # glove
        _face_rect(c, arm[key], 0, 2, 3, 2, SUIT_LIGHT)            # shoulder seam
    _face_rect(c, arm["front"], 1, 8, 2, 8, CYAN)

    # Legs: suit, sealed boot.
    for key in leg:
        _fill_face(c, leg[key], [SUIT[2], SUIT[0], SUIT_DARK], rng)
    for key in ("front", "back", "left", "right"):
        _face_rect(c, leg[key], 0, 9, 3, 11, SUIT_DARK)            # boot
        _face_rect(c, leg[key], 0, 9, 3, 9, H_MID)                 # boot lip
        _face_rect(c, leg[key], 0, 5, 3, 5, _mix(SUIT[1], AMBER, 0.35))  # knee band
    return c


# --- registry of painters -------------------------------------------------

BLOCK_MOTIFS = {
    "colony_beacon": gen_colony_beacon,
    "outpost_beacon": gen_outpost_beacon,
    "oxygen_generator": gen_oxygen_generator,
    "colony_depot": gen_colony_depot,
    "research_station": gen_research_station,
    "farm_station": gen_farm_station,
    "hydroponics_station": gen_hydroponics_station,
    "refinery_station": gen_refinery_station,
    "fabricator_station": gen_fabricator_station,
    "habitat_pod": gen_habitat_pod,
    "habitat_module": gen_habitat_module,
    "habitat_block": gen_habitat_block,
}

ITEM_MOTIFS = {
    "speed_module": gen_speed_module,
    "efficiency_module": gen_efficiency_module,
    "range_module": gen_range_module,
    "capacity_module": gen_capacity_module,
}

ENTITY_MOTIFS = {
    "colonist": gen_colonist,
}


# --- writing + coverage ---------------------------------------------------


def _write_set(motifs, folder: str, args) -> tuple:
    directory = DIRS[folder]
    os.makedirs(directory, exist_ok=True)
    written = 0
    skipped = 0
    for name, motif in sorted(motifs.items()):
        PAINTED[folder].add(name)
        path = os.path.join(directory, name + ".png")
        if os.path.exists(path) and not args.force:
            skipped += 1
            continue
        if args.list:
            print("would write " + os.path.relpath(path, REPO_ROOT))
            written += 1
            continue
        data = motif().to_png()
        with open(path, "wb") as handle:
            handle.write(data)
        print("wrote " + os.path.relpath(path, REPO_ROOT).replace("\\", "/"))
        written += 1
    return written, skipped


_TEXTURE_REF = re.compile(r"^nerocolonies:(block|item)/([a-z0-9_/]+)$")
_ENTITY_REF = re.compile(r'"textures/entity/([a-z0-9_/]+)\.png"')


def _scan_json_refs() -> tuple:
    """Returns (texture refs, model refs) declared by the mod's own JSON assets.

    Texture refs are (folder, name) pairs from every `textures` block; model refs are the
    `nerocolonies:...` model ids named by blockstates and item definitions, so a model file that
    was never written is caught here rather than in game.
    """
    textures = set()
    models = set()

    def walk(node, in_textures: bool):
        if isinstance(node, dict):
            for key, value in node.items():
                walk(value, in_textures or key == "textures")
        elif isinstance(node, list):
            for value in node:
                walk(value, in_textures)
        elif isinstance(node, str):
            match = _TEXTURE_REF.match(node)
            if match and in_textures:
                textures.add((match.group(1), match.group(2)))

    for sub in ("models", "blockstates", "items"):
        base = os.path.join(_ASSETS, sub)
        for root, _dirs, files in os.walk(base):
            for filename in sorted(files):
                if not filename.endswith(".json"):
                    continue
                path = os.path.join(root, filename)
                with open(path, "r", encoding="utf-8") as handle:
                    data = json.load(handle)
                walk(data, False)
                if sub in ("blockstates", "items"):
                    _collect_models(data, models)
                elif isinstance(data, dict) and isinstance(data.get("parent"), str):
                    if data["parent"].startswith("nerocolonies:"):
                        models.add(data["parent"].split(":", 1)[1])
    return textures, models


def _collect_models(node, out: set) -> None:
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "model" and isinstance(value, str) and value.startswith("nerocolonies:"):
                out.add(value.split(":", 1)[1])
            else:
                _collect_models(value, out)
    elif isinstance(node, list):
        for value in node:
            _collect_models(value, out)


def _scan_entity_refs() -> set:
    if not os.path.exists(RENDERER_JAVA):
        return set()
    with open(RENDERER_JAVA, "r", encoding="utf-8") as handle:
        return set(_ENTITY_REF.findall(handle.read()))


def check_coverage(args) -> int:
    """Referenced vs painted vs on disk, in both directions. Returns a process exit code."""
    referenced, model_refs = _scan_json_refs()
    referenced |= {("entity", name) for name in _scan_entity_refs()}
    painted = {(folder, name) for folder, names in PAINTED.items() for name in names}

    problems = 0

    missing_models = sorted(m for m in model_refs
                            if not os.path.exists(os.path.join(_ASSETS, "models", m + ".json")))
    if missing_models:
        problems += 1
        print("BROKEN: model files referenced but absent: " + ", ".join(missing_models))

    unpainted = sorted(referenced - painted)
    if unpainted:
        problems += 1
        print("BROKEN: textures referenced with no painter: "
              + ", ".join("%s/%s" % ref for ref in unpainted))

    orphan_painters = sorted(painted - referenced)
    if orphan_painters:
        problems += 1
        print("ORPHAN: painters no model references: "
              + ", ".join("%s/%s" % ref for ref in orphan_painters))

    stray = []
    absent = []
    for folder, directory in DIRS.items():
        on_disk = set()
        if os.path.isdir(directory):
            on_disk = {f[:-4] for f in os.listdir(directory) if f.endswith(".png")}
        stray += sorted("%s/%s" % (folder, n) for n in on_disk - PAINTED[folder])
        if not args.list:
            for name in sorted(PAINTED[folder]):
                path = os.path.join(directory, name + ".png")
                if not os.path.exists(path) or os.path.getsize(path) == 0:
                    absent.append("%s/%s" % (folder, name))
    if stray:
        print("NOTICE: PNGs on disk with no painter (left untouched): " + ", ".join(stray))
    if absent:
        problems += 1
        print("BROKEN: painted textures missing or empty on disk: " + ", ".join(absent))

    print("coverage: %d referenced, %d painted, %d orphan painters, %d unpainted references"
          % (len(referenced), len(painted), len(orphan_painters), len(unpainted)))
    return 1 if problems else 0


def main(argv: list) -> int:
    parser = argparse.ArgumentParser(description="Generate NeroColonies placeholder textures.")
    parser.add_argument("--multiloader", action="store_true",
                        help="accepted for parity with the shared gradle task; has no effect")
    parser.add_argument("--force", action="store_true", help="overwrite existing textures")
    parser.add_argument("--list", action="store_true", help="report only, write nothing")
    args = parser.parse_args(argv)

    written = 0
    skipped = 0
    for motifs, folder in ((BLOCK_MOTIFS, "block"), (ITEM_MOTIFS, "item"),
                           (ENTITY_MOTIFS, "entity")):
        w, s = _write_set(motifs, folder, args)
        written += w
        skipped += s
    print("gen_textures: %d written, %d already present" % (written, skipped))
    return check_coverage(args)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
