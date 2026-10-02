#!/usr/bin/env python3
"""Validate the NeroColonies blueprint JSON files.

Run from the repository root (``tools/gen_blueprints.py`` calls it automatically):

    python3 tools/check_blueprints.py [blueprint_dir]

Exits non-zero if any blueprint breaks a rule, after printing a summary table.
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_DIR = os.path.join(ROOT, "common", "src", "main", "resources", "data", "nerocolonies",
                           "nerocolonies", "blueprints")

MAX_W, MAX_D, MAX_H = 32, 32, 40
HOUSING_LAYERS = 12
HOUSING_BLOCKS = {"nerocolonies:habitat_pod", "nerocolonies:habitat_module",
                  "nerocolonies:habitat_block"}
CATEGORIES = {"housing", "farm", "industry", "storage", "life_support", "civic", "defence",
              "landmark", "other"}
STAGES = {"founding", "settled", "growing", "thriving", "metropolis"}
PROFESSIONS = {"nerocolonies:" + p for p in (
    "farmer", "forester", "miner", "builder", "hauler", "cook", "toolsmith", "guard",
    "beastkeeper", "researcher", "quartermaster", "medic")}
ROLES = {"sleep", "eat", "social", "guard_post", "kennel", "golem_forge", "gratitude_cache",
         "planning", "needs_board"}
FORBIDDEN = ("tnt", "fire", "lava", "command_block", "chest", "barrel", "furnace[")


def block_id(state):
    return state.split("[")[0].strip()


def check(path, research_ids):
    errors = []
    with open(path, encoding="utf-8") as f:
        doc = json.load(f)
    bid = os.path.splitext(os.path.basename(path))[0]
    pal = doc.get("palette", {})
    layers = doc.get("layers", [])
    for k, v in pal.items():
        if len(k) != 1:
            errors.append("palette key %r is not one character" % k)
        if any(bad in v for bad in FORBIDDEN) and "blast_furnace" not in v:
            errors.append("forbidden block %s" % v)
    if doc.get("name") != "blueprint.nerocolonies." + bid:
        errors.append("name should be blueprint.nerocolonies.%s" % bid)
    if doc.get("category") not in CATEGORIES:
        errors.append("bad category %r" % doc.get("category"))
    if doc.get("stage") not in STAGES:
        errors.append("bad stage %r" % doc.get("stage"))
    H = len(layers)
    D = max((len(l) for l in layers), default=0)
    W = max((len(r) for l in layers for r in l), default=0)
    if H == 0 or H > MAX_H:
        errors.append("height %d outside 1..%d" % (H, MAX_H))
    if W > MAX_W or D > MAX_D:
        errors.append("footprint %dx%d exceeds %dx%d" % (W, D, MAX_W, MAX_D))
    blocks = 0
    for y, layer in enumerate(layers):
        if len(layer) != D:
            errors.append("layer %d has %d rows, expected %d" % (y, len(layer), D))
        for z, row in enumerate(layer):
            if len(row) != W:
                errors.append("layer %d row %d is %d wide, expected %d" % (y, z, len(row), W))
            for x, ch in enumerate(row):
                state = pal.get(ch)
                if state is None:
                    if y == 0:
                        errors.append("floor hole at x=%d z=%d" % (x, z))
                    continue
                bidn = block_id(state)
                if bidn != "minecraft:air":
                    blocks += 1
                elif y == 0:
                    errors.append("floor air at x=%d z=%d" % (x, z))
                if bidn in HOUSING_BLOCKS and y >= HOUSING_LAYERS:
                    errors.append("housing block at layer %d (must be < %d)" % (y, HOUSING_LAYERS))
    if blocks == 0:
        errors.append("no non-air blocks")
    # front (+Z) opening: air or a door in the last row of layer 1 or 2
    opened = False
    for y in (1, 2):
        if y < H and layers[y]:
            for ch in layers[y][-1]:
                st = pal.get(ch)
                if st and (block_id(st) == "minecraft:air" or block_id(st).endswith("_door")):
                    opened = True
    if not opened:
        errors.append("no front (+Z) opening in layer 1 or 2")
    for u in doc.get("unlocks", []):
        if u not in PROFESSIONS:
            errors.append("unknown profession %s" % u)
    for r in doc.get("roles", []):
        if r not in ROLES:
            errors.append("unknown role %s" % r)
    res = doc.get("research")
    if res is not None and research_ids is not None and res not in research_ids:
        errors.append("unknown research %s" % res)
    housing = sum(1 for l in layers[:HOUSING_LAYERS] for r in l for ch in r
                  if block_id(pal.get(ch, "")) in HOUSING_BLOCKS)
    return doc, (W, D, H), blocks, housing, errors


def research_ids_from(bp_dir):
    rdir = os.path.join(os.path.dirname(bp_dir), "research")
    if not os.path.isdir(rdir):
        return None
    ids = set()
    for base, _, files in os.walk(rdir):
        for f in files:
            if f.endswith(".json"):
                rel = os.path.relpath(os.path.join(base, f), rdir)[:-5].replace(os.sep, "/")
                ids.add("nerocolonies:" + rel)
    return ids


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    bp_dir = argv[0] if argv else DEFAULT_DIR
    research = research_ids_from(bp_dir)
    files = sorted(f for f in os.listdir(bp_dir) if f.endswith(".json"))
    docs, failed = {}, 0
    rows = []
    for f in files:
        doc, dims, blocks, housing, errors = check(os.path.join(bp_dir, f), research)
        bid = f[:-5]
        docs[bid] = (doc, dims, errors)
        rows.append((bid, doc.get("stage", "?"), dims, blocks, housing))
    # upgrade chains
    for bid, (doc, dims, errors) in docs.items():
        up = doc.get("upgrade_to")
        if up:
            target = up.split(":", 1)[1]
            if target not in docs:
                errors.append("upgrade_to %s does not exist" % up)
            elif docs[target][1][:2] != dims[:2]:
                errors.append("upgrade_to %s footprint %dx%d differs from %dx%d"
                              % (up, docs[target][1][0], docs[target][1][1], dims[0], dims[1]))
    order = ["founding", "settled", "growing", "thriving", "metropolis"]
    rows.sort(key=lambda r: (order.index(r[1]) if r[1] in order else 9, r[0]))
    print("%-26s %-11s %-10s %6s %4s" % ("id", "stage", "WxDxH", "blocks", "pods"))
    for bid, stage, (W, D, H), blocks, housing in rows:
        print("%-26s %-11s %-10s %6d %4d" % (bid, stage, "%dx%dx%d" % (W, D, H), blocks, housing))
    for bid, (doc, dims, errors) in sorted(docs.items()):
        for e in errors:
            print("ERROR %s: %s" % (bid, e))
            failed += 1
    print("%d blueprints, %d errors" % (len(docs), failed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
