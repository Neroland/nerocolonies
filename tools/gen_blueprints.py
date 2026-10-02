#!/usr/bin/env python3
"""Generate the NeroColonies blueprint catalogue.

Run from the repository root:

    python3 tools/gen_blueprints.py

Every blueprint is an original design built from the parametric helpers in this file. The script
writes one JSON file per blueprint into
``common/src/main/resources/data/nerocolonies/nerocolonies/blueprints/`` (overwriting), writes the
en_us display names to ``build/content_lang.json``, then runs ``tools/check_blueprints.py``.

Coordinates: x runs west -> east, z runs north -> south, y runs up. The FRONT of every building is
the +Z (south) side, where the entrance goes. Layer 0 is the floor. Standard library only.
"""
import json
import math
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "common", "src", "main", "resources", "data", "nerocolonies",
                       "nerocolonies", "blueprints")
LANG_OUT = os.path.join(ROOT, "build", "content_lang.json")

AIR = "minecraft:air"
WOODS = ("oak", "spruce", "birch", "dark_oak", "jungle", "acacia", "mangrove", "cherry")


def mc(s):
    return s if ":" in s else "minecraft:" + s


def nc(s):
    return "nerocolonies:" + s


def rng(a, b):
    return range(min(a, b), max(a, b) + 1)


# --------------------------------------------------------------------------- block-state helpers

FULL = {
    "stone_brick": "stone_bricks", "cobblestone": "cobblestone", "quartz": "quartz_block",
    "smooth_quartz": "smooth_quartz", "cut_copper": "cut_copper",
    "oxidized_cut_copper": "oxidized_cut_copper", "prismarine_brick": "prismarine_bricks",
    "deepslate_tile": "deepslate_tiles", "brick": "bricks", "sandstone": "sandstone",
    "mud_brick": "mud_bricks",
}


def full(mat):
    return mc(mat + "_planks") if mat in WOODS else mc(FULL[mat])


def stairs(mat, facing, half="bottom"):
    return mc("%s_stairs[facing=%s,half=%s]" % (mat, facing, half))


def slab(mat, t="bottom"):
    return mc("%s_slab[type=%s]" % (mat, t))


def log(wood="oak", axis="y"):
    return mc("%s_log[axis=%s]" % (wood, axis))


LANTERN = "minecraft:lantern[hanging=false]"
HANG = "minecraft:lantern[hanging=true]"
POD = nc("habitat_pod")


def ladder(facing="south"):
    return "minecraft:ladder[facing=%s]" % facing


# --------------------------------------------------------------------------- the builder

class B:
    """A sparse voxel grid. Unset cells are holes (left alone when the colony builds)."""

    def __init__(self, skirt="cobblestone"):
        self.c = {}
        self.skirt = mc(skirt)

    def set(self, x, y, z, s):
        if s is None:
            self.c.pop((x, y, z), None)
        else:
            self.c[(x, y, z)] = mc(s)

    def get(self, x, y, z):
        return self.c.get((x, y, z))

    def fill(self, x0, y0, z0, x1, y1, z1, s):
        for x in rng(x0, x1):
            for y in rng(y0, y1):
                for z in rng(z0, z1):
                    self.set(x, y, z, s)

    def clear(self, x0, y0, z0, x1, y1, z1):
        self.fill(x0, y0, z0, x1, y1, z1, AIR)

    def floor(self, x0, z0, x1, z1, s, y=0):
        self.fill(x0, y, z0, x1, y, z1, s)

    def shell(self, x0, y0, z0, x1, y1, z1, s):
        """Hollow cuboid: only the six faces."""
        lx, hx, ly, hy, lz, hz = min(x0, x1), max(x0, x1), min(y0, y1), max(y0, y1), min(z0, z1), max(z0, z1)
        for x in range(lx, hx + 1):
            for y in range(ly, hy + 1):
                for z in range(lz, hz + 1):
                    if x in (lx, hx) or y in (ly, hy) or z in (lz, hz):
                        self.set(x, y, z, s)

    def walls(self, x0, z0, x1, z1, y0, y1, s):
        for y in rng(y0, y1):
            for x in rng(x0, x1):
                self.set(x, y, z0, s)
                self.set(x, y, z1, s)
            for z in rng(z0, z1):
                self.set(x0, y, z, s)
                self.set(x1, y, z, s)

    def posts(self, pts, y0, y1, s):
        for (x, z) in pts:
            for y in rng(y0, y1):
                self.set(x, y, z, s)

    def corners(self, x0, z0, x1, z1, y0, y1, s):
        self.posts([(x0, z0), (x1, z0), (x0, z1), (x1, z1)], y0, y1, s)

    def crenellate(self, x0, z0, x1, z1, y, s):
        """Alternating merlons on a rectangular perimeter."""
        for x in rng(x0, x1):
            for z in (z0, z1):
                if (x - x0) % 2 == 0:
                    self.set(x, y, z, s)
        for z in rng(z0, z1):
            for x in (x0, x1):
                if (z - z0) % 2 == 0:
                    self.set(x, y, z, s)

    def disc(self, cx, cz, r, y, s):
        R = r + 0.5
        n = int(math.ceil(R))
        for dx in range(-n, n + 1):
            for dz in range(-n, n + 1):
                if math.hypot(dx, dz) <= R:
                    self.set(cx + dx, y, cz + dz, s)

    def cylinder(self, cx, cz, r, y0, y1, s, inner=AIR, rib=None, ribs=8):
        """Upright cylinder wall; inner fills the inside (None = leave it, s = solid)."""
        R, I = r + 0.5, r - 0.5
        n = int(math.ceil(R))
        for dx in range(-n, n + 1):
            for dz in range(-n, n + 1):
                d = math.hypot(dx, dz)
                if d > R:
                    continue
                if d > I:
                    st = s
                    if rib and _is_rib(dx, dz, ribs):
                        st = rib
                elif inner is not None:
                    st = inner
                else:
                    continue
                for y in rng(y0, y1):
                    self.set(cx + dx, y, cz + dz, st)

    def dome(self, cx, cy, cz, r, s, inner=AIR, rib=None, ribs=8):
        """Upper hemisphere shell centred on (cx, cy, cz)."""
        R, I = r + 0.5, r - 0.5
        n = int(math.ceil(R))
        for dx in range(-n, n + 1):
            for dz in range(-n, n + 1):
                for dy in range(0, n + 1):
                    d = math.sqrt(dx * dx + dy * dy + dz * dz)
                    if d > R:
                        continue
                    if d > I:
                        st = rib if (rib and _is_rib(dx, dz, ribs)) else s
                    elif inner is not None:
                        st = inner
                    else:
                        continue
                    self.set(cx + dx, cy + dy, cz + dz, st)

    def cone(self, cx, cz, r, y0, h, s, inner=AIR, rib=None, ribs=8):
        """Spire: a cylinder whose radius shrinks linearly to a point over h layers."""
        for i in range(h):
            rr = r * (1.0 - float(i) / h)
            if rr < 0.75:
                self.set(cx, y0 + i, cz, s)
            else:
                self.cylinder(cx, cz, rr, y0 + i, y0 + i, s, inner, rib, ribs)

    def pyramid(self, x0, z0, x1, z1, y, mat):
        """Hip roof of stairs on a rectangle, closing with a full block cap."""
        i = 0
        while True:
            a, b, c, d = x0 + i, x1 - i, z0 + i, z1 - i
            if a > b or c > d:
                break
            if a == b or c == d:
                self.fill(a, y + i, c, b, y + i, d, full(mat))
                break
            for x in rng(a, b):
                self.set(x, y + i, c, stairs(mat, "south"))
                self.set(x, y + i, d, stairs(mat, "north"))
            for z in rng(c + 1, d - 1):
                self.set(a, y + i, z, stairs(mat, "east"))
                self.set(b, y + i, z, stairs(mat, "west"))
            i += 1
        return y + i

    def gable_x(self, x0, x1, z0, z1, y, mat, wall, oh=1, inner=AIR, slope=None, cap=None):
        """Gable roof with its ridge running east-west; slopes face north and south."""
        i = 0
        yy = y
        while True:
            zn, zs, yy = z0 + i, z1 - i, y + i
            if zn > zs:
                return yy - 1
            if zn == zs:
                for x in rng(x0 - oh, x1 + oh):
                    self.set(x, yy, zn, cap or slope or full(mat))
                return yy
            for x in rng(x0 - oh, x1 + oh):
                self.set(x, yy, zn, slope or stairs(mat, "south"))
                self.set(x, yy, zs, slope or stairs(mat, "north"))
            for z in range(zn + 1, zs):
                self.set(x0, yy, z, wall)
                self.set(x1, yy, z, wall)
                if inner:
                    for x in range(x0 + 1, x1):
                        self.set(x, yy, z, inner)
            i += 1

    def gable_z(self, x0, x1, z0, z1, y, mat, wall, oh=0, inner=AIR, slope=None, cap=None):
        """Gable roof with its ridge running north-south; slopes face west and east."""
        i = 0
        while True:
            xw, xe, yy = x0 + i, x1 - i, y + i
            if xw > xe:
                return yy - 1
            if xw == xe:
                for z in rng(z0 - oh, z1 + oh):
                    self.set(xw, yy, z, cap or slope or full(mat))
                return yy
            for z in rng(z0 - oh, z1 + oh):
                self.set(xw, yy, z, slope or stairs(mat, "east"))
                self.set(xe, yy, z, slope or stairs(mat, "west"))
            for x in range(xw + 1, xe):
                self.set(x, yy, z0, wall)
                self.set(x, yy, z1, wall)
                if inner:
                    for z in range(z0 + 1, z1):
                        self.set(x, yy, z, inner)
            i += 1

    def arch_x(self, x0, x1, z0, z1, y0, spring, s):
        """Round arch opening spanning x0..x1 (through z0..z1) with straight sides `spring` tall."""
        w = x1 - x0 + 1
        r = w / 2.0
        cx = (x0 + x1) / 2.0
        crown = y0 + spring + int(math.ceil(r))
        for x in rng(x0, x1):
            h = math.sqrt(max(0.0, r * r - (x - cx) ** 2))
            top = y0 + spring - 1 + int(round(h))
            for z in rng(z0, z1):
                for y in range(y0, top + 1):
                    self.set(x, y, z, AIR)
                for y in range(top + 1, crown + 1):
                    self.set(x, y, z, s)
        return crown

    def arch_z(self, z0, z1, x0, x1, y0, spring, s):
        """Round arch opening spanning z0..z1 (through x0..x1)."""
        w = z1 - z0 + 1
        r = w / 2.0
        cz = (z0 + z1) / 2.0
        crown = y0 + spring + int(math.ceil(r))
        for z in rng(z0, z1):
            h = math.sqrt(max(0.0, r * r - (z - cz) ** 2))
            top = y0 + spring - 1 + int(round(h))
            for x in rng(x0, x1):
                for y in range(y0, top + 1):
                    self.set(x, y, z, AIR)
                for y in range(top + 1, crown + 1):
                    self.set(x, y, z, s)
        return crown

    def colonnade_x(self, x0, x1, step, z, y0, y1, s, base=None, cap=None):
        x = x0
        while x <= x1:
            self.posts([(x, z)], y0, y1, s)
            if base:
                self.set(x, y0, z, base)
            if cap:
                self.set(x, y1, z, cap)
            x += step

    def staircase(self, x, z, y, n, mat, ascend="north", width=1, support=None):
        """Straight flight of n steps; each step climbs one block toward `ascend`."""
        dx, dz = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}[ascend]
        px, pz = (1, 0) if dz else (0, 1)
        for k in range(n):
            for w in range(width):
                cx, cz = x + dx * k + px * w, z + dz * k + pz * w
                self.set(cx, y + k, cz, stairs(mat, ascend))
                for yy in range(y + k + 1, y + k + 4):
                    if self.get(cx, yy, cz) is None:
                        self.set(cx, yy, cz, AIR)
                if support:
                    for yy in range(y, y + k):
                        self.set(cx, yy, cz, support)

    def bridge_x(self, x0, x1, z0, z1, y, deck, rail):
        self.fill(x0, y, z0, x1, y, z1, deck)
        for x in rng(x0, x1):
            self.set(x, y + 1, z0, rail)
            self.set(x, y + 1, z1, rail)
            for z in range(z0 + 1, z1):
                for yy in (y + 1, y + 2):
                    self.set(x, yy, z, AIR)

    def door(self, x, y, z, wood="oak", facing="south"):
        self.set(x, y, z, "minecraft:%s_door[facing=%s,half=lower,hinge=left,open=false]" % (wood, facing))
        self.set(x, y + 1, z, "minecraft:%s_door[facing=%s,half=upper,hinge=left,open=false]" % (wood, facing))

    def opening(self, x0, x1, z, y0=1, h=2):
        self.fill(x0, y0, z, x1, y0 + h - 1, z, AIR)


def _is_rib(dx, dz, ribs):
    if ribs >= 4 and (dx == 0 or dz == 0):
        return True
    return ribs >= 8 and abs(dx) == abs(dz)


# --------------------------------------------------------------------------- composite shapes

def cottage(b, x0, z0, w, d, wall_h, wall="oak_planks", base="cobblestone", post=None,
            roof="spruce", floor="spruce_planks", oh=1, door_x=None, window="glass_pane",
            gable_wall=None, door=True, y0=0):
    """A walled building with a gable roof (ridge east-west) and a front (south) door.

    Returns (x1, z1, top_y). Interior cells are cleared to air so the inside is open.
    """
    x1, z1 = x0 + w - 1, z0 + d - 1
    post = post or log("oak")
    b.floor(x0, z0, x1, z1, floor, y0)
    b.walls(x0, z0, x1, z1, y0, y0, base)
    b.clear(x0 + 1, y0 + 1, z0 + 1, x1 - 1, y0 + wall_h, z1 - 1)
    b.walls(x0, z0, x1, z1, y0 + 1, y0 + 1, base)
    if wall_h > 1:
        b.walls(x0, z0, x1, z1, y0 + 2, y0 + wall_h, wall)
    b.corners(x0, z0, x1, z1, y0 + 1, y0 + wall_h, post)
    if window:
        wy = y0 + 2
        for z in range(z0 + 2, z1 - 1, 2):
            b.set(x0, wy, z, window)
            b.set(x1, wy, z, window)
        for x in range(x0 + 2, x1 - 1, 2):
            b.set(x, wy, z0, window)
    dx = door_x if door_x is not None else (x0 + x1) // 2
    if window:
        for x in (dx - 2, dx + 2):
            if x0 < x < x1:
                b.set(x, y0 + 2, z1, window)
    if door:
        b.door(dx, y0 + 1, z1)
    top = b.gable_x(x0, x1, z0, z1, y0 + wall_h + 1, roof, gable_wall or wall, oh)
    return x1, z1, top


def flat_hut(b, x0, z0, w, d, h, wall, roof_slab, floor=None, door_x=None, window="glass"):
    """Small flat-roofed module (sci-fi pods, sheds)."""
    x1, z1 = x0 + w - 1, z0 + d - 1
    b.floor(x0, z0, x1, z1, floor or wall)
    b.clear(x0 + 1, 1, z0 + 1, x1 - 1, h, z1 - 1)
    b.walls(x0, z0, x1, z1, 1, h, wall)
    b.fill(x0, h + 1, z0, x1, h + 1, z1, roof_slab)
    dx = door_x if door_x is not None else (x0 + x1) // 2
    if window:
        for x in range(x0 + 1, x1):
            if x != dx:
                b.set(x, 2, z0, window)
        b.set(x0, 2, (z0 + z1) // 2, window)
        b.set(x1, 2, (z0 + z1) // 2, window)
    b.door(dx, 1, z1, "birch" if "copper" in wall or "smooth" in wall or "quartz" in wall else "oak")
    return x1, z1


# --------------------------------------------------------------------------- catalogue

CATALOGUE = []


def bp(bid, **meta):
    def wrap(fn):
        CATALOGUE.append((bid, fn, meta))
        return fn
    return wrap


# ---- Starter Works (founding) -----------------------------------------------------------------

@bp("founders_lodge", category="housing", stage="founding", priority=1, max=1,
    unlocks=["builder"], roles=["sleep"], capacity={"housing": 4})
def founders_lodge():
    b = B()
    cottage(b, 1, 0, 7, 6, 3, wall="oak_planks", base="cobblestone", roof="spruce")
    b.set(2, 1, 1, POD)
    b.set(6, 1, 1, POD)
    b.set(6, 1, 3, "crafting_table")
    b.set(2, 1, 3, LANTERN)
    return b


@bp("homestead_farm", category="farm", stage="founding", priority=2, max=1,
    unlocks=["farmer"], capacity={"jobs": 2})
def homestead_farm():
    b = B()
    b.floor(0, 0, 8, 8, "cobblestone")
    b.floor(1, 1, 7, 7, "farmland[moisture=7]")
    b.floor(4, 1, 4, 7, "water")
    b.clear(1, 1, 1, 7, 2, 7)
    b.walls(0, 0, 8, 8, 1, 1, "oak_fence")
    b.set(0, 1, 4, nc("farm_station"))
    b.opening(4, 4, 8)
    b.posts([(0, 0), (8, 0)], 1, 1, log("oak"))
    b.set(0, 2, 0, LANTERN)
    b.set(8, 2, 0, LANTERN)
    b.set(4, 0, 8, "cobblestone")
    return b


@bp("lumber_yard", category="industry", stage="founding", priority=3, max=1,
    unlocks=["forester"], capacity={"jobs": 2})
def lumber_yard():
    b = B()
    b.floor(0, 0, 8, 8, "grass_block")
    b.walls(0, 0, 8, 8, 0, 0, log("oak", "x"))
    for z in (0, 8):
        for x in rng(0, 8):
            b.set(x, 0, z, log("oak", "x"))
    for x in (0, 8):
        for z in rng(1, 7):
            b.set(x, 0, z, log("oak", "z"))
    b.clear(1, 1, 1, 7, 3, 7)
    for (x, z) in ((2, 2), (6, 2), (2, 5)):
        b.set(x, 0, z, "dirt")
        b.set(x, 1, z, "oak_sapling[stage=0]")
    b.walls(0, 0, 8, 8, 1, 1, "oak_fence")
    b.opening(4, 4, 8)
    b.set(6, 1, 6, log("oak", "y"))          # chopping block
    b.fill(4, 1, 7, 7, 1, 7, log("oak", "x"))  # log pile
    b.fill(5, 2, 7, 6, 2, 7, log("oak", "x"))
    b.set(4, 1, 7, AIR)
    b.set(7, 1, 5, LANTERN)
    return b


@bp("mine_head", category="industry", stage="founding", priority=4, max=1,
    unlocks=["miner"], capacity={"jobs": 2})
def mine_head():
    b = B()
    b.floor(0, 0, 6, 6, "cobblestone")
    b.set(3, 0, 3, "gravel")
    b.clear(1, 1, 1, 5, 4, 5)
    b.walls(0, 0, 6, 0, 1, 1, "cobblestone_wall")
    b.posts([(0, 1), (0, 2), (6, 1), (6, 2)], 1, 1, "cobblestone_wall")
    b.corners(2, 2, 4, 4, 1, 5, log("spruce"))
    for x in rng(2, 4):
        b.set(x, 6, 2, log("spruce", "x"))
        b.set(x, 6, 4, log("spruce", "x"))
    b.set(2, 6, 3, log("spruce", "z"))
    b.set(4, 6, 3, log("spruce", "z"))
    b.set(3, 6, 3, log("spruce", "x"))
    b.set(3, 5, 3, HANG)
    b.posts([(3, 1)], 1, 4, "cobblestone")
    for y in rng(1, 4):
        b.set(3, y, 2, ladder("south"))
    for z in rng(3, 6):
        b.set(3, 1, z, "rail[shape=north_south]")
    b.set(1, 1, 5, LANTERN)
    b.set(5, 1, 5, LANTERN)
    b.opening(2, 2, 6)
    b.opening(4, 4, 6)
    return b


# ---- Settled ----------------------------------------------------------------------------------

def neran_cottage_level(level):
    b = B()
    wall_h = {1: 3, 2: 4, 3: 5}[level]
    base = {1: "cobblestone", 2: "stone_bricks", 3: "cut_copper"}[level]
    roof = {1: "spruce", 2: "dark_oak", 3: "dark_oak"}[level]
    wall = {1: "oak_planks", 2: "oak_planks", 3: "birch_planks"}[level]
    _, _, top = cottage(b, 1, 0, 7, 7, wall_h, wall=wall, base=base, roof=roof,
                        post=log("spruce" if level > 1 else "oak"))
    pods = [(2, 1, 1), (6, 1, 1), (2, 1, 4)][:level]
    for p in pods:
        b.set(*p, POD)
    b.set(6, 1, 4, LANTERN)
    if level >= 2:
        for x in (2, 6):
            b.set(x, 1, 5, "flower_pot")
    if level >= 3:
        b.set(4, top + 1, 3, "lightning_rod")
        b.fill(2, 4, 2, 6, 4, 2, "spruce_slab[type=top]")   # sleeping loft rail
    return b


@bp("neran_cottage", category="housing", stage="settled", priority=10, max=8, level=1,
    upgrade_to="neran_cottage_2", roles=["sleep"], capacity={"housing": 2})
def neran_cottage():
    return neran_cottage_level(1)


@bp("neran_cottage_2", category="housing", stage="settled", priority=11, max=8, level=2,
    upgrade_to="neran_cottage_3", roles=["sleep"], capacity={"housing": 4})
def neran_cottage_2():
    return neran_cottage_level(2)


@bp("neran_cottage_3", category="housing", stage="settled", priority=12, max=8, level=3,
    roles=["sleep"], capacity={"housing": 6})
def neran_cottage_3():
    return neran_cottage_level(3)


@bp("granary", category="storage", stage="settled", priority=30, max=2, roles=["eat"],
    capacity={"storage": 27})
def granary():
    b = B()
    cottage(b, 1, 0, 7, 7, 4, wall="spruce_planks", base="cobblestone", roof="dark_oak",
            post=log("dark_oak"), window=None)
    for (x, z) in ((2, 1), (3, 1), (5, 1), (6, 1), (2, 2), (6, 2), (2, 4), (6, 4)):
        b.set(x, 1, z, "hay_block[axis=y]")
    for (x, z) in ((2, 1), (3, 1), (6, 1), (6, 2)):
        b.set(x, 2, z, "hay_block[axis=x]")
    b.set(4, 3, 0, "glass_pane")
    return b


@bp("canteen", category="civic", stage="settled", priority=40, max=1, roles=["eat"],
    unlocks=["cook"], capacity={"jobs": 2})
def canteen():
    b = B()
    _, _, top = cottage(b, 1, 0, 11, 7, 4, wall="oak_planks", base="stone_bricks", roof="spruce")
    for tx in (4, 8):
        b.set(tx, 1, 3, "oak_fence")
        b.set(tx, 2, 3, "oak_pressure_plate")
        b.set(tx - 1, 1, 3, stairs("oak", "west"))
        b.set(tx + 1, 1, 3, stairs("oak", "east"))
    b.set(6, 1, 1, "smoker[facing=south,lit=false]")
    b.set(7, 1, 1, "crafting_table")
    b.set(2, 1, 5, LANTERN)
    b.set(10, 1, 5, LANTERN)
    b.posts([(10, 0)], 1, top + 1, "cobblestone")
    return b


@bp("toolsmiths_forge", category="industry", stage="settled", priority=45, max=1,
    unlocks=["toolsmith"], capacity={"jobs": 2})
def toolsmiths_forge():
    b = B()
    _, _, top = cottage(b, 1, 0, 9, 7, 4, wall="stone_bricks", base="cobblestone",
                        post=log("spruce"), roof="stone_brick", door=False, window="iron_bars")
    b.opening(4, 6, 6, 1, 3)
    b.set(2, 1, 4, "anvil[facing=north]")
    b.set(5, 1, 1, "blast_furnace[facing=south,lit=false]")
    b.set(7, 1, 1, "smithing_table")
    b.set(3, 1, 1, "grindstone[face=floor,facing=south]")
    b.set(8, 1, 4, nc("fabricator_station"))
    b.set(2, 1, 1, LANTERN)
    b.posts([(5, 0)], 1, top + 2, "cobblestone")
    return b


@bp("haulers_depot", category="storage", stage="settled", priority=31, max=1,
    unlocks=["hauler"], capacity={"storage": 54, "jobs": 2})
def haulers_depot():
    b = B()
    cottage(b, 1, 0, 9, 7, 4, wall="spruce_planks", base="cobblestone", roof="oak",
            post=log("oak"), door=False)
    b.opening(4, 6, 6, 1, 3)
    b.set(5, 1, 1, nc("colony_depot"))
    b.fill(2, 1, 1, 3, 1, 2, log("oak", "z"))
    b.fill(7, 1, 1, 8, 1, 2, "hay_block[axis=y]")
    b.set(2, 2, 1, log("oak", "z"))
    b.set(2, 1, 4, LANTERN)
    b.set(8, 1, 4, LANTERN)
    return b


@bp("watch_post", category="defence", stage="settled", priority=50, max=2,
    roles=["guard_post"], unlocks=["guard"], capacity={"jobs": 1})
def watch_post():
    b = B()
    b.floor(0, 0, 4, 4, "cobblestone")
    b.clear(1, 1, 1, 3, 5, 4)
    b.corners(0, 0, 4, 4, 1, 6, log("spruce"))
    b.fill(1, 1, 0, 3, 5, 0, "spruce_planks")
    for y in rng(1, 6):
        b.set(2, y, 1, ladder("south"))
    b.fill(0, 6, 0, 4, 6, 4, "spruce_planks")
    b.set(2, 6, 1, ladder("south"))
    b.walls(0, 0, 4, 4, 7, 7, "spruce_fence")
    b.clear(1, 7, 1, 3, 8, 3)
    b.corners(0, 0, 4, 4, 8, 8, "spruce_fence")
    b.fill(0, 9, 0, 4, 9, 4, "spruce_slab[type=bottom]")
    b.set(2, 8, 2, HANG)
    b.set(1, 1, 3, LANTERN)
    return b


@bp("well_plaza", category="civic", stage="settled", priority=60, max=1, roles=["social"])
def well_plaza():
    b = B()
    b.floor(0, 0, 8, 8, "stone_bricks")
    for x in rng(0, 8):
        for z in rng(0, 8):
            if (x + z) % 3 == 0:
                b.set(x, 0, z, "cobblestone")
    b.clear(0, 1, 0, 8, 3, 8)
    b.floor(3, 3, 5, 5, "cobblestone")
    b.set(4, 0, 4, "water")
    b.walls(3, 3, 5, 5, 1, 1, "cobblestone_wall")
    b.corners(3, 3, 5, 5, 2, 3, "oak_fence")
    b.fill(3, 4, 3, 5, 4, 5, "oak_slab[type=bottom]")
    b.set(4, 4, 4, "oak_planks")
    b.set(4, 3, 4, HANG)
    for (x, z) in ((0, 0), (8, 0), (0, 8), (8, 8)):
        b.set(x, 1, z, log("oak"))
        b.set(x, 2, z, LANTERN)
    b.set(1, 1, 4, stairs("oak", "west"))
    b.set(7, 1, 4, stairs("oak", "east"))
    b.set(4, 1, 1, stairs("oak", "north"))
    return b


@bp("kennel", category="defence", stage="settled", priority=55, max=1, roles=["kennel"],
    unlocks=["beastkeeper"], capacity={"jobs": 1})
def kennel():
    b = B()
    b.floor(0, 0, 8, 6, "grass_block")
    b.clear(1, 1, 1, 7, 2, 5)
    b.walls(0, 0, 8, 6, 1, 1, "oak_fence")
    b.opening(4, 4, 6)
    b.floor(0, 0, 3, 3, "spruce_planks")
    b.walls(0, 0, 3, 3, 1, 2, "spruce_planks")
    b.corners(0, 0, 3, 3, 1, 2, log("spruce"))
    b.clear(1, 1, 1, 2, 2, 2)
    b.opening(2, 2, 3)
    b.fill(0, 3, 0, 3, 3, 3, "spruce_slab[type=bottom]")
    b.set(1, 1, 1, "hay_block[axis=y]")
    b.set(7, 1, 1, "cauldron")
    b.set(8, 2, 0, LANTERN)
    b.set(5, 1, 1, "hay_block[axis=x]")
    return b


def pavilion(b, w, d, post, roof_mat, floor_s, h=3):
    x1, z1 = w - 1, d - 1
    b.floor(0, 0, x1, z1, floor_s)
    b.clear(0, 1, 0, x1, h, z1)
    b.corners(0, 0, x1, z1, 1, h, post)
    b.fill(0, h + 1, 0, x1, h + 1, z1, slab(roof_mat))
    b.fill(1, h + 1, 1, x1 - 1, h + 1, z1 - 1, full(roof_mat))
    b.fill(1, h + 2, 1, x1 - 1, h + 2, z1 - 1, slab(roof_mat))


@bp("needs_board_pavilion", category="civic", stage="settled", priority=41, max=1,
    roles=["needs_board"])
def needs_board_pavilion():
    b = B()
    pavilion(b, 5, 5, log("oak"), "oak", "stone_bricks")
    b.set(2, 1, 0, nc("needs_board"))
    b.set(1, 1, 0, "oak_planks")
    b.set(3, 1, 0, "oak_planks")
    b.set(2, 3, 2, HANG)
    return b


@bp("gratitude_cache_pavilion", category="civic", stage="settled", priority=42, max=1,
    roles=["gratitude_cache"], unlocks=["quartermaster"], capacity={"jobs": 1})
def gratitude_cache_pavilion():
    b = B("smooth_stone")
    pavilion(b, 5, 5, "quartz_pillar[axis=y]", "cut_copper", "smooth_quartz")
    b.set(2, 1, 2, nc("gratitude_cache"))
    for (x, z) in ((0, 0), (4, 0)):
        b.set(x, 1, z, "quartz_pillar[axis=y]")
    b.set(1, 1, 0, "flower_pot")
    b.set(3, 1, 0, "flower_pot")
    b.set(2, 3, 2, HANG)
    return b


@bp("planning_hall", category="civic", stage="settled", priority=43, max=1, roles=["planning"])
def planning_hall():
    b = B()
    cottage(b, 1, 0, 9, 7, 4, wall="oak_planks", base="stone_bricks", roof="dark_oak",
            post=log("dark_oak"))
    b.set(5, 1, 3, nc("planning_table"))
    b.set(3, 1, 3, "cartography_table")
    for x in rng(2, 8):
        b.set(x, 1, 1, "bookshelf")
        b.set(x, 2, 1, "bookshelf")
    b.set(5, 2, 1, LANTERN)
    b.set(2, 1, 5, LANTERN)
    return b


@bp("research_cabin", category="industry", stage="settled", priority=50, max=1,
    unlocks=["researcher"], capacity={"jobs": 2})
def research_cabin():
    b = B("smooth_stone")
    flat_hut(b, 0, 0, 6, 6, 3, "smooth_stone", "smooth_stone_slab[type=bottom]", door_x=3)
    b.set(1, 1, 1, nc("research_station"))
    b.set(4, 1, 1, "bookshelf")
    b.set(4, 1, 3, LANTERN)
    b.fill(1, 3, 0, 4, 3, 0, "glass")
    return b


@bp("oxygen_hut", category="life_support", stage="settled", priority=40, max=1,
    capacity={"jobs": 0})
def oxygen_hut():
    b = B("smooth_stone")
    flat_hut(b, 0, 0, 5, 5, 3, "smooth_stone", "smooth_stone", door_x=2)
    b.set(2, 1, 2, nc("oxygen_generator"))
    b.fill(1, 4, 1, 3, 4, 3, "glass")
    b.set(2, 5, 2, "glass")
    b.set(1, 1, 1, "cut_copper")
    b.set(3, 1, 1, "cut_copper")
    return b


@bp("habitat_pod", category="housing", stage="settled", priority=10, max=6, roles=["sleep"],
    capacity={"housing": 2})
def habitat_pod():
    b = B("smooth_stone")
    flat_hut(b, 0, 0, 5, 5, 3, "smooth_stone", "smooth_stone_slab[type=bottom]", door_x=2)
    b.set(2, 1, 1, POD)
    b.walls(0, 0, 4, 4, 1, 1, "cut_copper")
    b.door(2, 1, 4, "birch")
    b.set(1, 1, 3, LANTERN)
    return b


@bp("farm_plot", category="farm", stage="settled", priority=20, max=2, unlocks=["farmer"],
    capacity={"jobs": 2})
def farm_plot():
    b = B()
    b.floor(0, 0, 4, 4, "farmland[moisture=7]")
    b.set(2, 0, 2, "water")
    b.set(0, 0, 0, "cobblestone")
    b.set(0, 1, 0, nc("farm_station"))
    b.clear(1, 1, 0, 4, 1, 4)
    b.clear(0, 1, 1, 0, 1, 4)
    return b


@bp("depot_shed", category="storage", stage="settled", priority=30, max=2,
    capacity={"storage": 27})
def depot_shed():
    b = B("smooth_stone")
    flat_hut(b, 0, 0, 5, 5, 3, "smooth_stone", "smooth_stone_slab[type=bottom]", door_x=2)
    b.set(2, 1, 1, nc("colony_depot"))
    b.set(1, 1, 1, log("oak", "z"))
    b.set(3, 1, 1, log("oak", "z"))
    return b


# ---- Growing ----------------------------------------------------------------------------------

@bp("longhouse", category="housing", stage="growing", priority=13, max=4, roles=["sleep"],
    capacity={"housing": 8}, research="habitation/shelter")
def longhouse():
    b = B()
    cottage(b, 1, 0, 15, 7, 4, wall="spruce_planks", base="cobblestone", roof="dark_oak",
            post=log("spruce"))
    b.posts([(5, 0), (9, 0), (13, 0), (5, 6), (11, 6)], 1, 4, log("spruce"))
    for x in (2, 6, 10, 14):
        b.set(x, 1, 1, POD)
    b.set(4, 1, 3, LANTERN)
    b.set(12, 1, 3, LANTERN)
    b.set(8, 1, 1, "crafting_table")
    return b


@bp("market_square", category="civic", stage="growing", priority=60, max=1, roles=["social"])
def market_square():
    b = B()
    b.floor(0, 0, 14, 14, "stone_bricks")
    for x in rng(0, 14):
        for z in rng(0, 14):
            if (x * 7 + z * 3) % 5 == 0:
                b.set(x, 0, z, "cobblestone")
            if abs(x - 7) + abs(z - 7) == 3:
                b.set(x, 0, z, "smooth_stone")
    b.clear(0, 1, 0, 14, 4, 14)
    # fountain
    b.walls(5, 5, 9, 9, 1, 1, "quartz_slab[type=bottom]")
    b.floor(6, 6, 8, 8, "water")
    b.posts([(7, 7)], 0, 2, "quartz_pillar[axis=y]")
    b.set(7, 3, 7, "sea_lantern")
    # stalls
    wool = ["red_wool", "yellow_wool", "light_blue_wool", "lime_wool"]
    for i, (sx, sz) in enumerate(((1, 1), (11, 1), (1, 11), (11, 11))):
        b.corners(sx, sz, sx + 2, sz + 2, 1, 2, "oak_fence")
        b.fill(sx, 3, sz, sx + 2, 3, sz + 2, wool[i])
        b.fill(sx, 1, sz + 1, sx + 2, 1, sz + 1, "oak_slab[type=top]")
    for (x, z) in ((0, 7), (14, 7), (7, 0)):
        b.set(x, 1, z, log("oak"))
        b.set(x, 2, z, LANTERN)
    return b


@bp("barracks", category="defence", stage="growing", priority=50, max=2,
    roles=["sleep", "guard_post"], unlocks=["guard"], capacity={"housing": 4, "jobs": 3})
def barracks():
    b = B()
    _, _, top = cottage(b, 1, 0, 11, 7, 4, wall="stone_bricks", base="cobblestone",
                        post=log("spruce"), roof="spruce", window="iron_bars")
    b.set(2, 1, 1, POD)
    b.set(10, 1, 1, POD)
    for x in (4, 5, 7, 8):
        b.set(x, 1, 1, "spruce_fence")
    b.set(6, 1, 1, "crafting_table")
    b.set(2, 1, 4, LANTERN)
    b.set(10, 1, 4, LANTERN)
    return b


@bp("golem_forge", category="defence", stage="growing", priority=65, max=1,
    roles=["golem_forge"], unlocks=["beastkeeper"], capacity={"jobs": 1})
def golem_forge():
    b = B()
    _, _, top = cottage(b, 1, 0, 9, 9, 5, wall="stone_bricks", base="cobblestone",
                        post="polished_andesite", roof="stone_brick", door=False,
                        window="iron_bars")
    b.opening(4, 6, 8, 1, 3)
    b.set(5, 1, 2, "iron_block")
    b.set(5, 2, 2, "iron_block")
    b.set(2, 1, 2, "anvil[facing=east]")
    b.set(8, 1, 2, "blast_furnace[facing=west,lit=false]")
    b.set(8, 1, 4, "blast_furnace[facing=west,lit=false]")
    b.fill(2, 1, 5, 2, 3, 5, "iron_bars")
    b.set(5, 1, 5, LANTERN)
    b.posts([(1, 0), (9, 0)], 1, top, "cobblestone")
    return b


@bp("med_bay", category="civic", stage="growing", priority=55, max=1, unlocks=["medic"],
    capacity={"jobs": 2})
def med_bay():
    b = B("smooth_stone")
    cottage(b, 1, 0, 9, 7, 4, wall="white_concrete", base="smooth_quartz",
            post="quartz_pillar[axis=y]", roof="quartz", floor="smooth_quartz", window="glass_pane")
    for x in (2, 3, 7, 8):
        b.set(x, 1, 1, "white_carpet")
    b.set(5, 1, 1, "brewing_stand")
    b.set(4, 1, 1, "cauldron")
    b.set(6, 1, 1, "white_concrete")
    b.set(2, 1, 5, LANTERN)
    b.set(8, 1, 5, LANTERN)
    b.set(5, 4, 6, "lime_concrete")
    b.set(4, 4, 6, "lime_concrete")
    b.set(6, 4, 6, "lime_concrete")
    return b


@bp("stables_pasture", category="farm", stage="growing", priority=25, max=1,
    capacity={"jobs": 1})
def stables_pasture():
    b = B()
    b.floor(0, 0, 14, 10, "grass_block")
    b.clear(1, 1, 1, 14, 2, 10)
    b.walls(0, 0, 14, 10, 1, 1, "oak_fence")
    b.opening(9, 10, 10)
    # stable shed on the west side
    b.floor(0, 0, 4, 10, "spruce_planks")
    b.walls(0, 0, 4, 10, 1, 3, "spruce_planks")
    b.posts([(0, 0), (4, 0), (0, 10), (4, 10), (4, 5), (0, 5)], 1, 3, log("spruce"))
    b.clear(1, 1, 1, 3, 3, 9)
    b.opening(2, 2, 10, 1, 2)
    for z in (3, 7):
        b.opening(4, 4, z, 1, 2)
    b.gable_z(0, 4, 0, 10, 4, "spruce", "spruce_planks", oh=0)
    for z in (1, 2, 8, 9):
        b.set(1, 1, z, "hay_block[axis=y]")
    b.set(3, 1, 1, "cauldron")
    b.set(14, 2, 0, LANTERN)
    b.set(5, 2, 10, LANTERN)
    return b


@bp("windmill", category="farm", stage="growing", priority=70, max=1, unlocks=["farmer"],
    capacity={"jobs": 2})
def windmill():
    b = B()
    cx, cz = 6, 3
    b.disc(cx, cz, 3, 0, "cobblestone")
    b.cylinder(cx, cz, 3, 1, 5, "cobblestone")
    b.cylinder(cx, cz, 3, 6, 12, "oak_planks")
    for y in (6, 12):
        b.disc(cx, cz, 2, y, "spruce_planks")
    b.set(cx, 6, cz - 1, AIR)
    for y in rng(1, 11):
        b.set(cx, y, cz - 2, ladder("south"))
    for y in (3, 9):
        b.set(cx - 3, y, cz, "glass_pane")
        b.set(cx + 3, y, cz, "glass_pane")
    b.cone(cx, cz, 4, 13, 5, "spruce_planks")
    b.door(cx, 1, cz + 3)
    b.set(cx - 1, 1, cz, nc("farm_station"))
    b.set(cx + 1, 1, cz, "hay_block[axis=y]")
    b.opening(cx, cx, cz + 4)
    b.set(cx, 0, cz + 4, "cobblestone")
    hub = 12
    b.set(cx, hub, cz + 4, log("oak", "z"))
    for k in range(1, 7):
        b.set(cx + k, hub, cz + 4, "oak_fence")
        b.set(cx - k, hub, cz + 4, "oak_fence")
        b.set(cx, hub + k, cz + 4, "oak_fence")
        if hub - k > 4:
            b.set(cx, hub - k, cz + 4, "oak_fence")
    for k in range(2, 7):
        b.set(cx + k, hub + 1, cz + 4, "white_wool")
        b.set(cx - k, hub - 1, cz + 4, "white_wool")
        b.set(cx - 1, hub + k, cz + 4, "white_wool")
        if hub - k > 4:
            b.set(cx + 1, hub - k, cz + 4, "white_wool")
    return b


@bp("quarry_terrace", category="industry", stage="growing", priority=60, max=1,
    unlocks=["miner"], capacity={"jobs": 3}, research="industry/refining")
def quarry_terrace():
    b = B()
    b.floor(0, 0, 12, 10, "stone")
    for (z0, z1, h, s) in ((0, 2, 3, "stone"), (3, 5, 2, "andesite"), (6, 7, 1, "cobblestone")):
        b.fill(0, 1, z0, 12, h, z1, s)
    for x in rng(0, 12):
        if x % 3 == 0:
            b.set(x, 3, 1, "gravel")
            b.set(x, 2, 4, "tuff")
    b.clear(0, 1, 8, 12, 3, 10)
    b.clear(0, 4, 0, 12, 4, 2)
    b.clear(0, 3, 3, 12, 3, 5)
    b.clear(0, 2, 6, 12, 2, 7)
    b.set(2, 1, 9, nc("refinery_station"))
    for x in rng(4, 12):
        b.set(x, 1, 10, "rail[shape=east_west]")
    b.staircase(6, 7, 1, 3, "cobblestone", "north")
    b.posts([(10, 8)], 1, 6, log("spruce"))
    for z in rng(4, 8):
        b.set(10, 7, z, log("spruce", "z"))
    b.set(10, 6, 4, "oak_fence")
    b.set(10, 5, 4, HANG)
    b.fill(0, 1, 8, 1, 1, 8, "cobblestone")
    b.set(0, 2, 8, LANTERN)
    return b


@bp("greenhouse", category="farm", stage="growing", priority=22, max=2, unlocks=["farmer"],
    capacity={"jobs": 2}, research="life_support/hydroponics")
def greenhouse():
    b = B()
    x1, z1 = 8, 12
    b.floor(0, 0, x1, z1, "smooth_stone")
    b.floor(1, 3, 3, 11, "farmland[moisture=7]")
    b.floor(5, 3, 7, 11, "farmland[moisture=7]")
    b.floor(4, 3, 4, 10, "water")
    b.clear(1, 1, 1, x1 - 1, 4, z1 - 1)
    b.walls(0, 0, x1, z1, 1, 4, "glass")
    b.walls(0, 0, x1, z1, 1, 1, "quartz_block")
    b.posts([(0, 0), (x1, 0), (0, z1), (x1, z1), (0, 6), (x1, 6)], 1, 4, "quartz_pillar[axis=y]")
    b.gable_z(0, x1, 0, z1, 5, "quartz", "glass", oh=0, slope="glass", cap="quartz_block")
    b.door(4, 1, z1, "birch")
    b.set(2, 1, 1, nc("hydroponics_station"))
    b.set(6, 1, 1, nc("hydroponics_station"))
    b.set(4, 1, 1, LANTERN)
    return b


@bp("workshop_hall", category="industry", stage="growing", priority=45, max=1,
    unlocks=["builder"], capacity={"jobs": 4}, research="industry/fabrication")
def workshop_hall():
    b = B()
    _, _, top = cottage(b, 1, 0, 13, 9, 5, wall="oak_planks", base="stone_bricks",
                        post=log("dark_oak"), roof="dark_oak", door=False)
    b.opening(6, 8, 8, 1, 3)
    b.set(3, 1, 2, nc("fabricator_station"))
    b.set(11, 1, 2, nc("fabricator_station"))
    b.set(5, 1, 1, "crafting_table")
    b.set(9, 1, 1, "crafting_table")
    b.set(7, 1, 1, "smithing_table")
    b.fill(2, 1, 6, 2, 3, 6, "scaffolding[bottom=false,distance=0]")
    b.set(7, 1, 4, LANTERN)
    b.set(12, 1, 6, LANTERN)
    return b


def watchtower_level(level):
    b = B("stone_bricks")
    top = {1: 8, 2: 13, 3: 18}[level]
    b.floor(0, 0, 6, 6, "stone_bricks")
    b.clear(1, 1, 1, 5, top, 5)
    b.walls(0, 0, 6, 6, 1, top, "stone_bricks")
    b.corners(0, 0, 6, 6, 1, top, "polished_andesite")
    for fy in range(5, top + 1, 5):
        b.floor(1, 1, 5, 5, "spruce_planks", fy)
    for y in rng(1, top + 1):
        b.set(3, y, 1, ladder("south"))
    for y in range(3, top, 5):
        b.set(0, y, 3, "iron_bars")
        b.set(6, y, 3, "iron_bars")
        b.set(3, y, 0, "iron_bars")
    b.door(3, 1, 6, "spruce")
    deck = top + 1
    b.floor(0, 0, 6, 6, "stone_bricks", deck)
    b.set(3, deck, 1, ladder("south"))
    b.clear(1, deck + 1, 1, 5, deck + 3, 5)
    b.crenellate(0, 0, 6, 6, deck + 1, "stone_bricks")
    b.set(3, 1, 4, LANTERN)
    if level == 1:
        b.set(1, deck + 1, 5, LANTERN)
    else:
        b.corners(0, 0, 6, 6, deck + 2, deck + 3, "spruce_fence")
        b.set(3, deck + 3, 3, HANG)
        if level == 2:
            b.pyramid(0, 0, 6, 6, deck + 4, "spruce")
        else:
            b.fill(0, deck + 4, 0, 6, deck + 4, 6, "cut_copper_slab[type=bottom]")
            b.cone(3, 3, 3, deck + 5, 7, "cut_copper", inner=None)
            b.set(3, deck + 12, 3, "lightning_rod")
    return b


@bp("watchtower", category="defence", stage="growing", priority=52, max=3, level=1,
    upgrade_to="watchtower_2", roles=["guard_post"], unlocks=["guard"], capacity={"jobs": 2})
def watchtower():
    return watchtower_level(1)


@bp("watchtower_2", category="defence", stage="growing", priority=53, max=3, level=2,
    upgrade_to="watchtower_3", roles=["guard_post"], unlocks=["guard"], capacity={"jobs": 3})
def watchtower_2():
    return watchtower_level(2)


@bp("watchtower_3", category="defence", stage="growing", priority=54, max=3, level=3,
    roles=["guard_post"], unlocks=["guard"], capacity={"jobs": 4})
def watchtower_3():
    return watchtower_level(3)


# ---- Thriving ---------------------------------------------------------------------------------

@bp("grand_town_hall", category="civic", stage="thriving", priority=80, max=1,
    roles=["social", "planning"])
def grand_town_hall():
    b = B("stone_bricks")
    # main hall x1..21, z0..14
    hx0, hx1, hz0, hz1, wall_h = 1, 21, 0, 14, 8
    b.floor(hx0, hz0, hx1, hz1, "spruce_planks")
    b.walls(hx0, hz0, hx1, hz1, 0, 0, "stone_bricks")
    b.clear(hx0 + 1, 1, hz0 + 1, hx1 - 1, wall_h, hz1 - 1)
    b.walls(hx0, hz0, hx1, hz1, 1, wall_h, "stone_bricks")
    b.posts([(x, z) for x in range(hx0, hx1 + 1, 4) for z in (hz0, hz1)], 1, wall_h, "quartz_pillar[axis=y]")
    b.posts([(x, z) for z in range(hz0, hz1 + 1, 4) for x in (hx0, hx1)], 1, wall_h, "quartz_pillar[axis=y]")
    for x in range(hx0 + 2, hx1 - 1, 4):
        for y in rng(3, 6):
            b.set(x, y, hz0, "glass_pane")
    for z in range(hz0 + 2, hz1 - 1, 4):
        for y in rng(3, 6):
            b.set(hx0, y, z, "glass_pane")
            b.set(hx1, y, z, "glass_pane")
    b.gable_x(hx0, hx1, hz0, hz1, wall_h + 1, "dark_oak", "stone_bricks", oh=1)
    for x in (5, 17):
        for z in range(3, 13, 4):
            b.posts([(x, z)], 1, wall_h, "quartz_pillar[axis=y]")
    b.set(11, 1, 2, nc("planning_table"))
    b.set(8, 1, 1, nc("needs_board"))
    b.fill(9, 1, 1, 13, 1, 1, "stone_brick_slab[type=bottom]")
    for z in rng(5, 11):
        for x in (8, 14):
            if z % 2:
                b.set(x, 1, z, stairs("spruce", "north"))
    for (x, z) in ((3, 2), (19, 2), (3, 12), (19, 12)):
        b.set(x, 1, z, LANTERN)
    # clock tower x8..14, z14..20 at the front
    tx0, tx1, tz0, tz1, ttop = 8, 14, 14, 20, 26
    b.floor(tx0, tz0, tx1, tz1, "polished_andesite")
    b.clear(tx0 + 1, 1, tz0, tx1 - 1, ttop, tz1 - 1)
    b.walls(tx0, tz0, tx1, tz1, 1, ttop, "stone_bricks")
    b.corners(tx0, tz0, tx1, tz1, 1, ttop, "quartz_pillar[axis=y]")
    b.clear(tx0 + 1, 1, tz0, tx1 - 1, 5, tz0)                 # opens into the hall
    b.arch_x(10, 12, tz1, tz1, 1, 3, "chiseled_stone_bricks")  # grand entrance
    for fy in (10, 16, 22):
        b.floor(tx0 + 1, tz0 + 1, tx1 - 1, tz1 - 1, "spruce_planks", fy)
    for y in rng(1, ttop):
        b.set(tx0 + 1, y, tz0 + 2, ladder("east"))
    # clock faces on south, east, west at y 18..22
    cy = 20
    for (fx, fz, axis) in ((11, tz1, "z"), (tx0, 17, "x"), (tx1, 17, "x")):
        for d in range(-2, 3):
            for dy in range(-2, 3):
                if max(abs(d), abs(dy)) == 2:
                    st = "quartz_block"
                else:
                    st = "white_concrete"
                if axis == "z":
                    b.set(fx + d, cy + dy, fz, st)
                else:
                    b.set(fx, cy + dy, fz + d, st)
        if axis == "z":
            b.set(fx, cy, fz, "black_concrete")
            b.set(fx, cy + 1, fz, "black_concrete")
            b.set(fx + 1, cy, fz, "black_concrete")
        else:
            b.set(fx, cy, fz, "black_concrete")
            b.set(fx, cy + 1, fz, "black_concrete")
            b.set(fx, cy, fz - 1, "black_concrete")
    # belfry
    bel = ttop + 1
    b.floor(tx0, tz0, tx1, tz1, "stone_bricks", bel)
    b.clear(tx0, bel + 1, tz0, tx1, bel + 3, tz1)
    b.corners(tx0, tz0, tx1, tz1, bel + 1, bel + 3, "quartz_pillar[axis=y]")
    b.set(11, bel + 1, 17, "bell[attachment=floor,facing=south]")
    b.fill(tx0, bel + 4, tz0, tx1, bel + 4, tz1, "cut_copper")
    b.cone(11, 17, 3, bel + 5, 7, "cut_copper", inner=None)
    return b


@bp("colony_citadel", category="defence", stage="thriving", priority=85, max=1,
    roles=["guard_post"], unlocks=["guard"], capacity={"jobs": 6})
def colony_citadel():
    b = B("stone_bricks")
    n = 30
    b.floor(0, 0, n, n, "grass_block")
    b.walls(0, 0, n, n, 0, 0, "stone_bricks")
    for t in (0, 1):
        b.walls(t, t, n - t, n - t, 1, 6, "stone_bricks")
    b.walls(1, 1, n - 1, n - 1, 6, 6, "stone_brick_slab[type=top]")
    b.crenellate(0, 0, n, n, 7, "stone_bricks")
    for (cx, cz) in ((3, 3), (27, 3), (3, 27), (27, 27)):
        b.disc(cx, cz, 3, 0, "stone_bricks")
        b.cylinder(cx, cz, 3, 1, 11, "stone_bricks", inner=AIR)
        b.disc(cx, cz, 3, 12, "stone_bricks")
        b.cylinder(cx, cz, 3, 13, 13, "stone_brick_wall", inner=AIR)
        b.set(cx, 12, cz, "stone_bricks")
        b.set(cx, 13, cz, LANTERN)
        for y in (4, 8):
            b.set(cx, y, cz + 3, "iron_bars")
            b.set(cx, y, cz - 3, "iron_bars")
    # gatehouse at the front centre
    gx0, gx1, gz0 = 11, 19, 25
    b.fill(gx0, 1, gz0, gx1, 10, n, "stone_bricks")
    b.clear(gx0 + 1, 1, gz0 + 1, gx1 - 1, 9, n - 1)
    b.arch_x(14, 16, gz0, n, 1, 3, "chiseled_stone_bricks")
    b.crenellate(gx0, gz0, gx1, n, 11, "stone_bricks")
    for (x, z) in ((gx0 + 1, n - 1), (gx1 - 1, n - 1)):
        b.set(x, 1, z, LANTERN)
    b.clear(14, 1, 24, 16, 3, 24)
    # inner keep
    kx0, kx1, kz0, kz1 = 11, 19, 7, 15
    b.floor(kx0, kz0, kx1, kz1, "stone_bricks")
    b.clear(kx0 + 1, 1, kz0 + 1, kx1 - 1, 9, kz1 - 1)
    b.walls(kx0, kz0, kx1, kz1, 1, 9, "stone_bricks")
    b.corners(kx0, kz0, kx1, kz1, 1, 10, "polished_andesite")
    b.floor(kx0, kz0, kx1, kz1, "stone_bricks", 10)
    b.crenellate(kx0, kz0, kx1, kz1, 11, "stone_bricks")
    b.door(15, 1, kz1, "spruce")
    for y in rng(1, 10):
        b.set(15, y, kz0 + 1, ladder("south"))
    for y in (3, 7):
        b.set(kx0, y, 11, "iron_bars")
        b.set(kx1, y, 11, "iron_bars")
    b.set(13, 1, 9, LANTERN)
    b.set(17, 1, 9, LANTERN)
    return b


@bp("hydroponic_spire", category="farm", stage="thriving", priority=75, max=1,
    unlocks=["farmer"], capacity={"jobs": 8}, research="life_support/hydroponics")
def hydroponic_spire():
    b = B("quartz_block")
    c = 7
    b.floor(0, 0, 14, 14, "smooth_quartz")
    b.disc(c, c, 6, 0, "smooth_stone")
    b.cylinder(c, c, 6, 1, 28, "glass", rib="quartz_pillar[axis=y]")
    for fy in (6, 12, 18, 24):
        b.disc(c, c, 5, fy, "smooth_stone")
    for y in rng(1, 28):
        b.set(c, y, c - 5, ladder("south"))
    for fy in (0, 6, 12, 18):
        for (dx, dz) in ((-3, 0), (3, 0), (0, 3), (-2, -3), (2, -3)):
            b.set(c + dx, fy + 1, c + dz, nc("hydroponics_station"))
        b.set(c, fy + 1, c, "sea_lantern")
    b.cylinder(c, c, 6, 29, 29, "quartz_block", inner="quartz_block")
    b.cone(c, c, 5, 30, 9, "glass", inner=AIR, rib="quartz_block", ribs=4)
    b.set(c, 39, c, "sea_lantern")
    b.door(c, 1, c + 6, "birch")
    b.opening(c, c, 14)
    b.set(c, 0, 14, "smooth_quartz")
    return b


@bp("crystal_biodome", category="farm", stage="thriving", priority=72, max=1,
    unlocks=["farmer"], capacity={"jobs": 4})
def crystal_biodome():
    b = B("smooth_stone")
    c, r = 12, 12
    b.floor(0, 0, 24, 24, "smooth_stone")
    b.disc(c, c, r - 1, 0, "grass_block")
    b.dome(c, 0, c, r, "glass", rib="quartz_block", ribs=8)
    # paths (cross) and pond
    for k in range(-10, 11):
        b.set(c + k, 0, c, "gravel")
        b.set(c, 0, c + k, "gravel")
    b.disc(c, c, 1, 0, "water")
    b.set(c, 0, c, "water")
    # farm plots
    for (px, pz) in ((5, 5), (19, 5)):
        b.fill(px - 2, 0, pz - 2, px + 2, 0, pz + 2, "farmland[moisture=7]")
        b.set(px, 0, pz, "water")
    b.set(14, 1, 21, nc("farm_station"))
    flowers = ["poppy", "dandelion", "azure_bluet", "oxeye_daisy", "cornflower", "allium",
               "blue_orchid", "lily_of_the_valley"]
    i = 0
    for x in range(2, 23):
        for z in range(2, 23):
            if b.get(x, 0, z) == "minecraft:grass_block" and math.hypot(x - c, z - c) < 10:
                if (x * 5 + z * 3) % 11 == 0:
                    b.set(x, 1, z, flowers[i % len(flowers)])
                    i += 1
    for (x, z, s) in ((8, 16, "birch_sapling[stage=0]"), (16, 16, "cherry_sapling[stage=0]"),
                      (16, 9, "oak_sapling[stage=0]"), (8, 9, "birch_sapling[stage=0]")):
        b.set(x, 0, z, "grass_block")
        b.set(x, 1, z, s)
    for (x, z) in ((10, 14), (14, 10)):
        b.set(x, 1, z, LANTERN)
    b.clear(c - 1, 1, 23, c + 1, 3, 24)
    return b


@bp("observatory", category="industry", stage="thriving", priority=78, max=1,
    unlocks=["researcher"], capacity={"jobs": 2})
def observatory():
    b = B("smooth_stone")
    c = 8
    b.floor(0, 0, 16, 16, "smooth_stone")
    x0, x1 = 2, 14
    b.floor(x0, x0, x1, x1, "polished_andesite")
    b.clear(x0 + 1, 1, x0 + 1, x1 - 1, 6, x1 - 1)
    b.walls(x0, x0, x1, x1, 1, 6, "quartz_block")
    b.corners(x0, x0, x1, x1, 1, 6, "quartz_pillar[axis=y]")
    for k in range(x0 + 2, x1 - 1, 3):
        for y in (3, 4):
            b.set(k, y, x0, "glass_pane")
            b.set(x0, y, k, "glass_pane")
            b.set(x1, y, k, "glass_pane")
    b.floor(x0, x0, x1, x1, "smooth_quartz", 7)
    b.dome(c, 7, c, 6, "oxidized_cut_copper", rib="cut_copper", ribs=4)
    for dy in range(1, 7):
        for dz in range(-6, 1):
            if b.get(c, 7 + dy, c + dz) == "minecraft:cut_copper":
                b.set(c, 7 + dy, c + dz, "glass")
    for k in range(0, 5):
        b.set(c, 8 + k, c - k, "copper_block")
    b.set(c, 13, c - 5, "glass")
    b.set(c, 8, c + 1, "copper_block")
    b.set(c, 7, c - 3, AIR)
    b.posts([(c, c - 4)], 1, 6, "quartz_pillar[axis=y]")
    for y in rng(1, 7):
        b.set(c, y, c - 3, ladder("south"))
    b.set(5, 1, 5, nc("research_station"))
    b.fill(9, 1, 4, 12, 2, 4, "bookshelf")
    b.set(11, 1, 11, LANTERN)
    b.set(5, 1, 11, LANTERN)
    b.door(c, 1, x1, "birch")
    b.opening(c, c, 16)
    b.set(c, 0, 16, "smooth_stone")
    for (x, z) in ((0, 0), (16, 0), (0, 16), (16, 16)):
        b.posts([(x, z)], 1, 2, "quartz_pillar[axis=y]")
        b.set(x, 3, z, LANTERN)
    return b


@bp("grand_bazaar", category="civic", stage="thriving", priority=82, max=1, roles=["social"],
    capacity={"jobs": 4})
def grand_bazaar():
    b = B("smooth_sandstone")
    W, D = 26, 18
    b.floor(0, 0, W, D, "smooth_sandstone")
    for x in rng(0, W):
        for z in rng(0, D):
            if (x + z) % 4 == 0:
                b.set(x, 0, z, "terracotta")
    b.clear(1, 1, 1, W - 1, 6, D - 1)
    for z in (0, D):
        for x0 in range(0, W, 4):
            b.posts([(x0, z)], 1, 6, "cut_sandstone")
            if x0 + 3 < W:
                b.fill(x0 + 1, 1, z, x0 + 3, 6, z, "cut_sandstone")
                b.arch_x(x0 + 1, x0 + 3, z, z, 1, 3, "cut_sandstone")
    for x in (0, W):
        for z0 in range(0, D, 6):
            b.posts([(x, z0)], 1, 6, "cut_sandstone")
            if z0 + 5 < D:
                b.fill(x, 1, z0 + 1, x, 6, z0 + 5, "cut_sandstone")
                b.arch_z(z0 + 2, z0 + 4, x, x, 1, 2, "cut_sandstone")
    b.fill(0, 7, 0, W, 7, D, "cut_copper_slab[type=bottom]")
    b.fill(1, 7, 1, W - 1, 7, D - 1, "smooth_sandstone")
    b.dome(13, 7, 9, 5, "cut_copper", inner=AIR, rib="smooth_quartz", ribs=4)
    b.clear(9, 7, 5, 17, 7, 13)
    b.disc(13, 9, 4, 7, AIR)
    wool = ["red_wool", "orange_wool", "yellow_wool", "cyan_wool", "purple_wool", "lime_wool"]
    i = 0
    for sz in (3, 13):
        for sx in (3, 9, 17, 21):
            b.fill(sx, 1, sz, sx + 2, 1, sz, "oak_slab[type=top]")
            b.corners(sx, sz, sx + 2, sz + 2, 1, 3, "oak_fence")
            b.fill(sx, 4, sz, sx + 2, 4, sz + 2, wool[i % len(wool)])
            i += 1
    b.posts([(13, 9)], 1, 2, "quartz_pillar[axis=y]")
    b.set(13, 3, 9, "sea_lantern")
    for (x, z) in ((2, 9), (24, 9), (13, 2), (13, 16)):
        b.set(x, 1, z, LANTERN)
    return b


@bp("amphitheatre", category="civic", stage="thriving", priority=84, max=1, roles=["social"])
def amphitheatre():
    b = B("stone_bricks")
    W, D, cx = 26, 20, 13
    b.floor(0, 0, W, D, "stone_bricks")
    for x in rng(0, W):
        for z in rng(0, D):
            d = math.hypot(x - cx, z)
            if 6 <= d <= 20.5:
                h = int((d - 6) // 2) + 1
                if h > 1:
                    b.fill(x, 1, z, x, h - 1, z, "stone_bricks")
                dx, dz = x - cx, z
                if abs(dz) >= abs(dx):
                    f = "south"
                else:
                    f = "east" if dx > 0 else "west"
                b.set(x, h, z, stairs("stone_brick", f))
                b.clear(x, h + 1, z, x, h + 2, z)
    for z in range(6, D + 1):
        h = max(1, int((z - 6) // 2) + 1)
        b.set(cx, h, z, "polished_andesite")
    # tunnel entrance through the seating from the front
    b.clear(cx - 1, 1, 14, cx + 1, 3, D)
    b.fill(cx - 1, 4, 14, cx + 1, 4, D, "stone_bricks")
    b.set(cx, 4, D, "chiseled_stone_bricks")
    # stage and backdrop
    b.clear(cx - 6, 1, 0, cx + 6, 6, 5)
    b.fill(cx - 5, 1, 0, cx + 5, 1, 3, "spruce_planks")
    b.fill(cx - 5, 2, 0, cx + 5, 6, 0, "stone_bricks")
    b.colonnade_x(cx - 5, cx + 5, 2, 1, 2, 6, "quartz_pillar[axis=y]")
    b.fill(cx - 5, 7, 0, cx + 5, 7, 1, "quartz_slab[type=bottom]")
    for x in (cx - 4, cx + 4):
        b.set(x, 2, 3, LANTERN)
    b.set(cx, 2, 1, "sea_lantern")
    for (x, z) in ((1, 20), (25, 20)):
        b.set(x, 1, z, LANTERN) if b.get(x, 1, z) is None else None
    return b


@bp("nexus_spire", category="landmark", stage="thriving", priority=110, max=1)
def nexus_spire():
    b = B("stone_bricks")
    c = 10
    b.floor(0, 0, 20, 20, "stone_bricks")
    b.disc(c, c, 10, 0, "polished_andesite")
    b.disc(c, c, 7, 0, "prismarine_bricks")
    b.disc(c, c, 3, 0, "quartz_block")
    b.cylinder(c, c, 9, 1, 6, AIR, inner=AIR)
    for a in range(8):
        ang = a * math.pi / 4
        px, pz = c + int(round(9 * math.cos(ang))), c + int(round(9 * math.sin(ang)))
        b.posts([(px, pz)], 1, 6, "quartz_pillar[axis=y]")
        b.set(px, 7, pz, "chiseled_quartz_block")
        # flying buttress toward the tower
        for k in range(1, 5):
            t = k / 5.0
            bx = int(round(c + (9 - 4 * t) * math.cos(ang)))
            bz = int(round(c + (9 - 4 * t) * math.sin(ang)))
            b.set(bx, 7 + k * 2, bz, "stone_bricks")
            b.set(bx, 8 + k * 2, bz, "stone_bricks")
    b.cylinder(c, c, 9, 7, 7, "stone_brick_slab[type=bottom]", inner=None)
    b.cylinder(c, c, 5, 1, 22, "prismarine_bricks", inner=AIR)
    for y in (6, 12, 18):
        b.cylinder(c, c, 5, y, y, "sea_lantern", inner=None)
    for y in (9, 15, 21):
        for (dx, dz) in ((5, 0), (-5, 0), (0, -5)):
            b.set(c + dx, y, c + dz, "glass")
    b.disc(c, c, 5, 23, "quartz_block")
    b.cone(c, c, 5, 24, 15, "prismarine_bricks", inner=AIR, rib="quartz_block", ribs=4)
    b.set(c, 39, c, "sea_lantern")
    b.clear(c, 1, c + 5, c, 3, c + 5)
    b.clear(c - 1, 1, c + 6, c + 1, 3, 20)
    b.set(c, 1, c, LANTERN)
    return b


@bp("sky_bridge", category="landmark", stage="thriving", priority=115, max=1)
def sky_bridge():
    b = B("stone_bricks")
    b.floor(0, 0, 30, 8, "stone_bricks")
    for x in rng(0, 30):
        b.set(x, 0, 4, "polished_andesite")
    deck = 16
    for (x0, x1) in ((0, 6), (24, 30)):
        z0, z1, top = 1, 7, 22
        b.clear(x0 + 1, 1, z0 + 1, x1 - 1, top, z1 - 1)
        b.walls(x0, z0, x1, z1, 1, top, "stone_bricks")
        b.corners(x0, z0, x1, z1, 1, top, "quartz_pillar[axis=y]")
        for fy in (6, 11, deck):
            b.floor(x0 + 1, z0 + 1, x1 - 1, z1 - 1, "smooth_stone", fy)
        mx = (x0 + x1) // 2
        for y in rng(1, top):
            b.set(mx, y, z0 + 1, ladder("south"))
        for y in range(3, top, 4):
            b.set(x0, y, 4, "glass_pane")
            b.set(x1, y, 4, "glass_pane")
            b.set(mx, y, z0, "glass_pane")
        b.door(mx, 1, z1, "spruce")
        b.opening(mx, mx, 8)
        b.set(mx, 0, 8, "polished_andesite")
        b.fill(x0, top + 1, z0, x1, top + 1, z1, "cut_copper")
        b.pyramid(x0 + 1, z0 + 1, x1 - 1, z1 - 1, top + 2, "cut_copper")
    # bridge deck between the towers, z3..5
    b.bridge_x(7, 23, 3, 5, deck, "smooth_quartz", "glass_pane")
    b.clear(6, deck + 1, 4, 6, deck + 2, 4)
    b.clear(24, deck + 1, 4, 24, deck + 2, 4)
    for x in range(7, 24, 4):
        b.set(x, deck + 2, 3, LANTERN)
        b.set(x, deck + 1, 3, "quartz_block")
    # arched truss under the deck
    for x in rng(7, 23):
        t = (x - 15) / 9.0
        y = deck - 1 - int(round(5 * t * t))
        b.set(x, y, 3, "stone_bricks")
        b.set(x, y, 5, "stone_bricks")
        if x % 3 == 0:
            for yy in range(y + 1, deck):
                b.set(x, yy, 3, "iron_bars")
                b.set(x, yy, 5, "iron_bars")
    return b


@bp("lighthouse", category="landmark", stage="thriving", priority=112, max=1)
def lighthouse():
    b = B("stone_bricks")
    c = 5
    b.floor(0, 0, 10, 10, "stone_bricks")
    b.disc(c, c, 4, 0, "polished_andesite")
    b.cylinder(c, c, 4, 1, 6, "stone_bricks")
    b.posts([(c, c - 3)], 1, 6, "stone_bricks")
    for y in range(7, 23):
        band = "red_concrete" if ((y - 7) // 4) % 2 else "white_concrete"
        b.cylinder(c, c, 3, y, y, band)
    for y in rng(1, 22):
        b.set(c, y, c - 2, ladder("south"))
    for y in (10, 18):
        b.set(c, y, c + 3, "glass_pane")
    b.disc(c, c, 4, 23, "stone_bricks")
    b.set(c, 23, c - 2, ladder("south"))
    b.cylinder(c, c, 4, 24, 24, "iron_bars", inner=None)
    b.cylinder(c, c, 2, 24, 27, "glass", inner=AIR)
    b.set(c, 24, c, "sea_lantern")
    b.set(c, 25, c, "glowstone")
    b.set(c, 26, c, "sea_lantern")
    b.disc(c, c, 2, 28, "cut_copper")
    b.dome(c, 28, c, 2, "cut_copper", inner="cut_copper")
    b.set(c, 31, c, "lightning_rod")
    b.door(c, 1, c + 4, "spruce")
    b.opening(c, c, 10)
    b.set(c, 0, 10, "stone_bricks")
    b.set(c, 24, c - 2, AIR)
    return b


@bp("harbour", category="civic", stage="thriving", priority=88, max=1, roles=["social"],
    capacity={"storage": 27, "jobs": 2})
def harbour():
    b = B("stone_bricks")
    W, D = 24, 16
    b.floor(0, 0, W, D, "stone_bricks")
    b.floor(1, 1, W - 1, 9, "water")
    b.walls(0, 0, W, 10, 1, 1, "stone_brick_wall")
    b.clear(1, 1, 1, W - 1, 2, 9)
    b.clear(0, 1, 11, W, 3, D)
    for px in (5, 12, 19):
        b.fill(px, 0, 2, px + 1, 0, 10, "spruce_planks")
        b.set(px, 1, 10, AIR)
        b.set(px + 1, 1, 10, AIR)
        for z in range(2, 10, 4):
            b.set(px, 1, z, "spruce_fence")
            b.set(px + 1, 1, z, "spruce_fence")
        b.set(px, 2, 2, LANTERN)
    # crane
    b.posts([(2, 12)], 1, 8, log("spruce"))
    for z in rng(5, 12):
        b.set(2, 9, z, log("spruce", "z"))
    b.fill(2, 6, 5, 2, 8, 5, "oak_fence")
    b.set(2, 5, 5, HANG)
    b.fill(1, 1, 13, 3, 1, 14, "hay_block[axis=y]")
    # boathouse
    cottage(b, 15, 11, 7, 5, 3, wall="spruce_planks", base="stone_bricks", roof="dark_oak",
            post=log("spruce"), oh=0)
    b.set(16, 1, 12, log("oak", "x"))
    b.set(20, 1, 12, LANTERN)
    for (x, z) in ((0, 11), (W, 11), (9, 15)):
        b.set(x, 1, z, log("spruce"))
        b.set(x, 2, z, LANTERN)
    return b


# ---- Metropolis -------------------------------------------------------------------------------

@bp("arcology_tower", category="housing", stage="metropolis", priority=14, max=2,
    roles=["sleep"], capacity={"housing": 16}, research="habitation/pressurised_modules")
def arcology_tower():
    b = B("smooth_quartz")
    c = 9
    b.floor(0, 0, 18, 18, "smooth_quartz")
    x0, x1 = 1, 17
    b.floor(x0, x0, x1, x1, "polished_andesite")
    b.clear(x0 + 1, 1, x0 + 1, x1 - 1, 10, x1 - 1)
    b.walls(x0, x0, x1, x1, 1, 11, "glass")
    for k in range(x0, x1 + 1, 2):
        b.posts([(k, x0), (k, x1), (x0, k), (x1, k)], 1, 11, "quartz_block")
    b.floor(x0, x0, x1, x1, "smooth_stone", 6)
    b.floor(x0, x0, x1, x1, "quartz_block", 11)
    # pods: 4 per floor (y1 and y7) => 8 pods, 16 Nerans
    for py in (1, 7):
        for (px, pz) in ((3, 3), (15, 3), (3, 15), (15, 15)):
            b.set(px, py, pz, POD)
        for (px, pz) in ((5, 5), (13, 5), (5, 13), (13, 13)):
            b.set(px, py, pz, LANTERN)
    # ladder core
    for y in rng(1, 33):
        b.set(c, y, c, "quartz_pillar[axis=y]")
        b.set(c, y, c + 1, ladder("south"))
    # tower above
    b.cylinder(c, c, 6, 12, 33, "glass", inner=AIR, rib="quartz_block", ribs=8)
    for fy in (12, 18, 24, 30):
        b.disc(c, c, 5, fy, "smooth_stone")
        b.set(c, fy, c + 1, ladder("south"))
        b.set(c, fy, c, "quartz_pillar[axis=y]")
    for y in rng(1, 33):
        b.set(c, y, c + 1, ladder("south"))
    b.disc(c, c, 6, 34, "cut_copper")
    b.cone(c, c, 6, 35, 5, "cut_copper", inner=AIR)
    b.set(c, 39, c, "lightning_rod")
    b.clear(c - 1, 1, x1, c + 1, 3, x1)
    b.opening(c - 1, c + 1, 18, 1, 2)
    b.set(c, 0, 18, "smooth_quartz")
    return b


@bp("skyport", category="landmark", stage="metropolis", priority=120, max=1)
def skyport():
    b = B("smooth_stone")
    N, c = 26, 13
    b.floor(0, 0, N, N, "smooth_stone")
    b.clear(0, 1, N, N, 2, N)
    b.disc(c, c, 10, 1, "polished_andesite")
    for dx in range(-11, 12):
        for dz in range(-11, 12):
            d = math.hypot(dx, dz)
            if 7.5 < d <= 8.5:
                b.set(c + dx, 1, c + dz, "yellow_concrete")
    for a in range(8):
        ang = a * math.pi / 4
        b.set(c + int(round(10 * math.cos(ang))), 1, c + int(round(10 * math.sin(ang))), "sea_lantern")
    # rocket
    b.cylinder(c, c, 2, 2, 22, "white_concrete", inner=None)
    for y in (6, 14, 20):
        b.cylinder(c, c, 2, y, y, "quartz_block", inner=None)
    b.set(c, 18, c + 2, "light_blue_stained_glass")
    b.cone(c, c, 2, 23, 6, "white_concrete", inner=None)
    b.set(c, 29, c, "red_concrete")
    for (dx, dz) in ((3, 0), (-3, 0), (0, 3), (0, -3)):
        for y in rng(2, 6):
            b.set(c + dx, y, c + dz, "light_gray_concrete")
        b.set(c + dx + (1 if dx > 0 else -1 if dx < 0 else 0), 2, c + dz + (1 if dz > 0 else -1 if dz < 0 else 0), "light_gray_concrete")
    # gantry tower behind (north)
    g0, g1 = c - 2, c + 2
    gz0, gz1 = 2, 6
    b.corners(g0, gz0, g1, gz1, 1, 32, "cut_copper")
    for y in range(4, 33, 4):
        b.walls(g0, gz0, g1, gz1, y, y, "iron_bars")
        b.corners(g0, gz0, g1, gz1, y, y, "cut_copper")
    b.fill(g0, 1, gz0, g1, 1, gz1, "copper_grate")
    for y in rng(2, 32):
        b.set(c, y, gz0 + 1, ladder("south"))
        b.set(c, y, gz0, "cut_copper")
    for ay in (20, 28):
        b.fill(c, ay, gz1 + 1, c, ay, c - 3, "cut_copper_slab[type=top]")
        b.set(c, ay + 1, gz1 + 1, "iron_bars")
    b.fill(g0, 33, gz0, g1, 33, gz1, "cut_copper_slab[type=bottom]")
    b.set(c, 34, gz0 + 2, "lightning_rod")
    for (x, z) in ((0, 0), (N, 0), (0, N), (N, N)):
        b.set(x, 1, z, "sea_lantern")
    return b


@bp("colossus", category="landmark", stage="metropolis", priority=125, max=1)
def colossus():
    b = B("stone_bricks")
    b.floor(0, 0, 14, 12, "stone_bricks")
    b.clear(0, 1, 12, 14, 2, 12)
    b.fill(1, 1, 1, 13, 2, 11, "stone_bricks")
    b.walls(1, 1, 13, 11, 2, 2, "chiseled_stone_bricks")
    for x in rng(4, 10):
        b.set(x, 1, 12, stairs("stone_brick", "north"))
        b.set(x, 2, 11, stairs("stone_brick", "north"))
    b.set(1, 3, 11, LANTERN)
    b.set(13, 3, 11, LANTERN)
    q = "quartz_block"
    # boots and legs (2x3 each)
    for lx in (5, 8):
        b.shell(lx, 3, 5, lx + 1, 4, 8, "polished_andesite")
        b.shell(lx, 5, 5, lx + 1, 13, 7, q)
    # belt, torso
    b.shell(4, 14, 4, 10, 14, 8, "cut_copper")
    b.shell(4, 15, 4, 10, 25, 8, q)
    b.set(7, 21, 8, "sea_lantern")
    b.set(6, 21, 8, "light_blue_stained_glass")
    b.set(8, 21, 8, "light_blue_stained_glass")
    # arms with copper shoulder pads and stone hands
    for ax in (2, 11):
        b.shell(ax, 17, 5, ax + 1, 25, 7, q)
        b.shell(ax, 15, 5, ax + 1, 16, 7, "polished_andesite")
        b.shell(ax, 24, 4, ax + 1, 26, 8, "cut_copper")
    # backpack on the north side
    b.shell(5, 16, 2, 9, 24, 3, "oxidized_cut_copper")
    for y in (18, 21):
        b.set(5, y, 2, "sea_lantern")
        b.set(9, y, 2, "sea_lantern")
    b.set(7, 25, 2, "lightning_rod")
    # neck, helmet, visor, antenna
    b.shell(6, 26, 5, 8, 26, 7, "polished_andesite")
    b.shell(5, 27, 4, 9, 31, 8, q)
    b.fill(6, 29, 8, 8, 30, 8, "tinted_glass")
    b.fill(5, 32, 4, 9, 32, 8, "cut_copper_slab[type=bottom]")
    b.fill(6, 32, 5, 8, 32, 7, "cut_copper")
    b.set(7, 33, 6, "lightning_rod")
    return b


@bp("floating_garden", category="landmark", stage="metropolis", priority=118, max=1)
def floating_garden():
    b = B("stone_bricks")
    N = 20
    b.floor(0, 0, N, N, "stone_bricks")
    for x in rng(0, N):
        for z in rng(0, N):
            if (x + z) % 5 == 0:
                b.set(x, 0, z, "mossy_stone_bricks")
    b.clear(0, 1, N, N - 1, 2, N)
    pil = ((4, 4), (16, 4), (4, 16), (16, 16))
    for (px, pz) in pil:
        b.cylinder(px, pz, 1, 1, 13, "quartz_block", inner="quartz_block")
        for k in range(1, 4):
            for (sx, sz) in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                b.set(px + sx * (k + 1), 13 - 3 + k, pz + sz * (k + 1), "quartz_block")
    py = 14
    b.fill(1, py, 1, N - 1, py, N - 1, "stone_bricks")
    b.fill(1, py + 1, 1, N - 1, py + 1, N - 1, "stone_bricks")
    b.fill(2, py + 1, 2, N - 2, py + 1, N - 2, "grass_block")
    b.fill(9, py + 1, 9, 11, py + 1, 11, "water")
    b.walls(1, 1, N - 1, N - 1, py + 2, py + 2, "oak_fence")
    b.clear(2, py + 2, 2, N - 2, py + 4, N - 2)
    flowers = ["poppy", "cornflower", "azure_bluet", "allium", "oxeye_daisy", "dandelion"]
    i = 0
    for x in range(3, N - 2):
        for z in range(3, N - 2):
            if b.get(x, py + 1, z) == "minecraft:grass_block" and (x * 3 + z * 7) % 6 == 0:
                b.set(x, py + 2, z, flowers[i % len(flowers)])
                i += 1
    for (x, z, s) in ((5, 5, "cherry_sapling[stage=0]"), (15, 5, "birch_sapling[stage=0]"),
                      (5, 15, "azalea"), (15, 15, "flowering_azalea")):
        b.set(x, py + 2, z, s)
    for (x, z) in ((2, 2), (N - 2, 2), (2, N - 2), (N - 2, N - 2)):
        b.set(x, py + 2, z, LANTERN)
    # staircase up the east side from the front
    b.staircase(N, N, 1, 15, "stone_brick", "north", support="stone_bricks")
    b.set(N - 1, py + 2, N - 14, AIR)
    b.clear(N - 1, py + 2, N - 14, N - 1, py + 3, N - 14)
    return b


@bp("grand_archive", category="civic", stage="metropolis", priority=90, max=1,
    unlocks=["researcher"], capacity={"jobs": 4})
def grand_archive():
    b = B("stone_bricks")
    x0, x1, z0, z1, wall_h = 1, 23, 0, 18, 10
    b.floor(x0, z0, x1, z1, "dark_oak_planks")
    b.walls(x0, z0, x1, z1, 0, 0, "stone_bricks")
    b.clear(x0 + 1, 1, z0 + 1, x1 - 1, wall_h, z1 - 1)
    b.walls(x0, z0, x1, z1, 1, wall_h, "stone_bricks")
    b.posts([(x, z) for x in range(x0, x1 + 1, 4) for z in (z0, z1)], 1, wall_h, "quartz_pillar[axis=y]")
    b.posts([(x, z) for z in range(z0, z1 + 1, 6) for x in (x0, x1)], 1, wall_h, "quartz_pillar[axis=y]")
    for x in range(x0 + 2, x1 - 1, 4):
        for y in rng(3, 8):
            b.set(x, y, z0, "glass_pane")
    for z in range(z0 + 3, z1 - 1, 6):
        for y in rng(3, 8):
            b.set(x0, y, z, "glass_pane")
            b.set(x1, y, z, "glass_pane")
    top = b.gable_x(x0, x1, z0, z1, wall_h + 1, "dark_oak", "stone_bricks", oh=1)
    for y in (top, top - 1):
        for x in rng(x0, x1):
            for z in rng(z0, z1):
                if b.get(x, y, z) and "dark_oak_stairs" in b.get(x, y, z) and y == top - 1:
                    b.set(x, y, z, "glass")
    # bookshelf walls, mezzanine with railing, shelves above
    for y in rng(1, 4):
        for x in rng(x0 + 1, x1 - 1):
            b.set(x, y, z0 + 1, "bookshelf")
        for z in rng(z0 + 2, z1 - 2):
            b.set(x0 + 1, y, z, "bookshelf")
            b.set(x1 - 1, y, z, "bookshelf")
    mz = 5
    b.fill(x0 + 1, mz, z0 + 1, x1 - 1, mz, z0 + 3, "spruce_planks")
    b.fill(x0 + 1, mz, z0 + 1, x0 + 3, mz, z1 - 1, "spruce_planks")
    b.fill(x1 - 3, mz, z0 + 1, x1 - 1, mz, z1 - 1, "spruce_planks")
    for x in rng(x0 + 4, x1 - 4):
        b.set(x, mz + 1, z0 + 3, "spruce_fence")
    for z in rng(z0 + 4, z1 - 1):
        b.set(x0 + 3, mz + 1, z, "spruce_fence")
        b.set(x1 - 3, mz + 1, z, "spruce_fence")
    for y in rng(mz + 1, mz + 3):
        for x in rng(x0 + 1, x1 - 1):
            b.set(x, y, z0 + 1, "bookshelf")
        for z in rng(z0 + 2, z1 - 2):
            b.set(x0 + 1, y, z, "bookshelf")
            b.set(x1 - 1, y, z, "bookshelf")
    b.posts([(x0 + 2, z1 - 1)], 1, mz, "quartz_pillar[axis=y]")
    for y in rng(1, mz + 1):
        b.set(x0 + 2, y, z1 - 2, ladder("north"))
    b.set(x0 + 2, mz, z1 - 2, ladder("north"))
    b.set(12, 1, 9, nc("research_station"))
    for (tx, tz) in ((8, 8), (16, 8), (8, 13), (16, 13)):
        b.fill(tx - 1, 1, tz, tx + 1, 1, tz, "spruce_slab[type=top]")
        b.set(tx, 2, tz, LANTERN)
    b.arch_x(11, 13, z1, z1, 1, 3, "chiseled_quartz_block")
    return b


# --------------------------------------------------------------------------- emit

PREF = {
    AIR: "_", "minecraft:oak_planks": "P", "minecraft:spruce_planks": "p",
    "minecraft:cobblestone": "C", "minecraft:stone_bricks": "#", "minecraft:glass": "G",
    "minecraft:glass_pane": "g", POD: "H", "minecraft:water": "W", "minecraft:grass_block": "d",
    "minecraft:smooth_stone": "S", "minecraft:quartz_block": "Q", "minecraft:bookshelf": "B",
    LANTERN: "*", HANG: "^", "minecraft:oak_fence": "f", "minecraft:spruce_fence": "e",
    "minecraft:farmland[moisture=7]": "F", "minecraft:cut_copper": "U",
    "minecraft:quartz_pillar[axis=y]": "I", "minecraft:hay_block[axis=y]": "Y",
}
POOL = ("abcdhijklmnoqrstuvwxyzADEJKLMNORTVXZ0123456789$%&+=-~<>/|!?:;@,'`()[]{}")

EXACT = {"anvil", "smithing_table", "blast_furnace", "grindstone", "crafting_table",
         "cartography_table", "smoker", "cauldron", "brewing_stand", "bell", "lantern",
         "sea_lantern", "flower_pot", "lightning_rod", "glowstone", "iron_block", "beacon"}


def material_key(state):
    bid = state.split("[")[0]
    ns, name = bid.split(":")
    if bid == AIR or name in ("water",):
        return None
    if ns == "nerocolonies":
        return ("item", bid, True)
    if name.endswith("_door"):
        if "half=upper" in state:
            return None
        return ("item", bid, True)
    if name.endswith("_sapling") or name in ("azalea", "flowering_azalea"):
        return ("item", bid, True)
    if name in EXACT:
        return ("item", bid, True)
    wood = next((w for w in WOODS if name.startswith(w + "_")), None)
    if wood and (name.endswith("_log") or name.endswith("_wood")):
        return ("tag", "minecraft:logs", False)
    if wood:
        return ("tag", "minecraft:planks", False)
    if name in ("farmland", "grass_block", "dirt", "coarse_dirt", "dirt_path"):
        return ("item", "minecraft:dirt", False)
    if name.startswith("stone_brick") or name in ("chiseled_stone_bricks", "mossy_stone_bricks"):
        return ("item", "minecraft:stone_bricks", False)
    if name.startswith("cobblestone"):
        return ("item", "minecraft:cobblestone", False)
    if "quartz" in name:
        return ("item", "minecraft:quartz_block", False)
    if "copper" in name:
        return ("tag", "c:ingots/copper", False)
    if name in ("iron_bars",):
        return ("tag", "c:ingots/iron", False)
    if name.startswith("prismarine"):
        return ("item", "minecraft:prismarine_bricks", False)
    if name.startswith("smooth_stone"):
        return ("item", "minecraft:smooth_stone", False)
    if name in ("glass", "glass_pane", "tinted_glass") or name.endswith("stained_glass"):
        return ("item", "minecraft:glass", False)
    if name.endswith("sandstone"):
        return ("item", "minecraft:sandstone", False)
    if name in ("poppy", "dandelion", "azure_bluet", "oxeye_daisy", "cornflower", "allium",
                "blue_orchid", "lily_of_the_valley"):
        return ("item", bid, True)
    return ("item", bid, False)


def materials_for(states):
    counts = {}
    for s in states:
        k = material_key(s)
        if k is None:
            continue
        key = (k[0], k[1])
        n, exact = counts.get(key, (0, k[2]))
        counts[key] = (n + 1, exact)
    out = []
    for (kind, ident), (n, exact) in counts.items():
        c = n if exact else max(1, int(math.ceil(n * 0.45)))
        out.append((kind, ident, c))
    out.sort(key=lambda t: (-t[2], t[1]))
    return [{kind: ident, "count": c} for (kind, ident, c) in out]


PROFESSIONS = ["farmer", "forester", "miner", "builder", "hauler", "cook", "toolsmith", "guard",
               "beastkeeper", "researcher", "quartermaster", "medic"]

NAME_OVERRIDES = {
    "toolsmiths_forge": "Toolsmith's Forge", "haulers_depot": "Hauler's Depot",
    "founders_lodge": "Founder's Lodge", "neran_cottage_2": "Neran Cottage II",
    "neran_cottage_3": "Neran Cottage III", "watchtower_2": "Watchtower II",
    "watchtower_3": "Watchtower III", "med_bay": "Med Bay",
}

PROFESSION_NAMES = {p: p.replace("_", " ").title() for p in PROFESSIONS}
PROFESSION_NAMES["beastkeeper"] = "Beastkeeper"
PROFESSION_NAMES["quartermaster"] = "Quartermaster"

THANK_YOU_LORE = [
    "We planted a row of flowers by the beacon, just for you.",
    "Every roof here stands because you believed in us.",
    "The little ones drew your portrait. It is mostly smiles.",
    "We saved you the warmest seat by the hearth.",
    "Wherever you wander, this colony is your home.",
]


def display_name(bid):
    return NAME_OVERRIDES.get(bid) or bid.replace("_", " ").title()


def emit(bid, b, meta):
    cells = dict(b.c)
    if not cells:
        raise SystemExit("%s: empty blueprint" % bid)
    xs = [k[0] for k in cells]
    ys = [k[1] for k in cells]
    zs = [k[2] for k in cells]
    mnx, mny, mnz = min(xs), min(ys), min(zs)
    if mny != 0:
        raise SystemExit("%s: lowest layer is y=%d, expected 0" % (bid, mny))
    W, H, D = max(xs) - mnx + 1, max(ys) + 1, max(zs) - mnz + 1
    norm = {(x - mnx, y, z - mnz): s for (x, y, z), s in cells.items()}
    for x in range(W):
        for z in range(D):
            if (x, 0, z) not in norm or norm[(x, 0, z)] == AIR:
                norm[(x, 0, z)] = b.skirt
    used = []
    for s in sorted(set(norm.values())):
        used.append(s)
    palette = {}
    taken = set(".")
    for s in used:
        ch = PREF.get(s)
        if ch and ch not in taken:
            palette[s] = ch
            taken.add(ch)
    pool = [c for c in POOL if c not in taken]
    for s in used:
        if s not in palette:
            if not pool:
                raise SystemExit("%s: too many distinct block states" % bid)
            palette[s] = pool.pop(0)
    layers = []
    for y in range(H):
        rows = []
        for z in range(D):
            rows.append("".join(palette[norm[(x, y, z)]] if (x, y, z) in norm else "."
                                for x in range(W)))
        layers.append(rows)
    doc = {"name": "blueprint.nerocolonies." + bid,
           "category": meta["category"],
           "stage": meta["stage"],
           "priority": meta["priority"],
           "max": meta["max"],
           "level": meta.get("level", 1)}
    if meta.get("upgrade_to"):
        doc["upgrade_to"] = "nerocolonies:" + meta["upgrade_to"]
    if meta.get("unlocks"):
        doc["unlocks"] = ["nerocolonies:" + u for u in meta["unlocks"]]
    if meta.get("roles"):
        doc["roles"] = list(meta["roles"])
    cap = {"housing": 0, "storage": 0, "jobs": 0}
    cap.update(meta.get("capacity", {}))
    doc["capacity"] = cap
    if meta.get("research"):
        doc["research"] = "nerocolonies:" + meta["research"]
    doc["palette"] = {ch: s for s, ch in sorted(palette.items(), key=lambda kv: kv[1])}
    doc["layers"] = layers
    doc["materials"] = materials_for(norm.values())
    doc["rotate"] = True
    path = os.path.join(OUT_DIR, bid + ".json")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(dumps(doc))
        f.write("\n")
    return path


def dumps(doc):
    """JSON with one layer row per line and compact palette/material entries."""
    lines = ["{"]
    keys = list(doc.keys())
    for i, k in enumerate(keys):
        v = doc[k]
        comma = "," if i < len(keys) - 1 else ""
        if k == "palette":
            lines.append('  "palette": {')
            items = list(v.items())
            for j, (ch, s) in enumerate(items):
                lines.append("    %s: %s%s" % (json.dumps(ch), json.dumps(s), "," if j < len(items) - 1 else ""))
            lines.append("  }" + comma)
        elif k == "layers":
            lines.append('  "layers": [')
            for j, layer in enumerate(v):
                lines.append("    [")
                for r, row in enumerate(layer):
                    lines.append("      %s%s" % (json.dumps(row), "," if r < len(layer) - 1 else ""))
                lines.append("    ]" + ("," if j < len(v) - 1 else ""))
            lines.append("  ]" + comma)
        elif k == "materials":
            lines.append('  "materials": [')
            for j, m in enumerate(v):
                lines.append("    %s%s" % (json.dumps(m), "," if j < len(v) - 1 else ""))
            lines.append("  ]" + comma)
        else:
            lines.append("  %s: %s%s" % (json.dumps(k), json.dumps(v), comma))
    lines.append("}")
    return "\n".join(lines)


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    os.makedirs(os.path.dirname(LANG_OUT), exist_ok=True)
    lang = {}
    for bid, fn, meta in CATALOGUE:
        emit(bid, fn(), meta)
        lang["blueprint.nerocolonies." + bid] = display_name(bid)
    for p in PROFESSIONS:
        lang["profession.nerocolonies." + p] = PROFESSION_NAMES[p]
    lang["item.nerocolonies.thank_you_note"] = "Thank-you Note"
    for i, line in enumerate(THANK_YOU_LORE):
        lang["item.nerocolonies.thank_you_note.lore.%d" % i] = line
    with open(LANG_OUT, "w", encoding="utf-8", newline="\n") as f:
        json.dump(lang, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print("wrote %d blueprints to %s" % (len(CATALOGUE), os.path.relpath(OUT_DIR, ROOT)))
    print("wrote %d lang entries to %s" % (len(lang), os.path.relpath(LANG_OUT, ROOT)))
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import check_blueprints
    return check_blueprints.main([OUT_DIR])


if __name__ == "__main__":
    sys.exit(main())
