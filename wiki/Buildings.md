# Buildings

What a colony builds, how it decides, where it puts things, how you place a building yourself, and a
reference table of every building that ships with the mod.

For the pace of building and the part your materials play, see [Construction](Construction.md). For
the stages that gate the buildings, see [Progression](Progression.md).

## What gets built next

Whenever a colony has nothing under construction it takes the first of these that applies:

1. **A building you placed by hand** with the Colony Planner, oldest first.
2. **An upgrade** of a building that already stands.
3. **Its own choice**: the most urgent blueprint it is eligible for, at the best site it can find.

One building is under construction at a time.

### What the colony is eligible to choose

For its own choice a blueprint has to pass every one of these:

| Rule | Detail |
| --- | --- |
| The colony has reached the blueprint's stage | While Founding, only the Starter Works |
| It is a first-level building | Higher levels arrive only as upgrades |
| The colony has not built its `max` of it | Each blueprint carries its own limit |
| It fits the server's size limits | `maxBlueprintFootprint` (default 32) on either axis, `maxBlueprintHeight` (default 40) |
| Its research, if it names any, is unlocked | — |
| Housing only: the colony is short of beds | Fewer than two free. The Starter Works are exempt |
| The colony is under its structure cap | `maxAutoStructures` times its stage multiplier — see [Progression](Progression.md#what-each-stage-unlocks) |

Among the blueprints that pass, the lowest `priority` number goes first, ties broken by id. Priority
is one order across every stage: a Growing-stage Longhouse (13) is more urgent than a Settled-stage
Farm Plot (20) once the colony is Growing and short of beds.

**The housing rule** is what keeps a colony from covering its claim in houses. With the shipped
content a newly Settled colony has four beds in the Founder's Lodge and two founders: two beds free,
so no housing. It builds a farm plot, a depot shed and so on. When arrivals fill the lodge it builds a
home, and the cycle repeats.

### Finding a site

Each cycle the colony takes the **two most urgent** eligible blueprints and tests up to eight
candidate sites for each. Candidates are walked in rings outwards from the beacon, so a colony grows
from its centre. When it has been all the way round without success it rests for ten cycles and
starts again.

If neither of the two can be sited, nothing further down the list is tried. A colony that has no
room for its two most urgent buildings reads `Looking for flat, open ground in the claim` even though
a smaller building might fit. Clearing or levelling ground fixes it, and so does placing one of them
by hand.

## Where a colony may build

| Rule | Detail |
| --- | --- |
| Inside the claim | Every column of the footprint, and re-checked for every block as it is placed |
| Loaded chunks only | The colony never loads a chunk to look at it or to build in it |
| Roughly level ground | The ground under the footprint may vary by at most two blocks. Trees, plants and snow layers are ignored when measuring |
| Near the beacon's level | The lowest ground under the footprint must be within 4 blocks below and 4 above the beacon |
| A street around every building | At least 2 blocks between its footprint and any building the colony has finished |
| Nothing of yours in the way | Every cell that will receive a block must hold something replaceable (air, grass, water) or something the colony may clear |
| Solid ground underneath | No part of the bottom layer may hang over a gap |

The bottom layer rests on the lowest ground under the footprint, and bumps of up to two blocks are
dug out as it builds.

**Your blocks win.** If you build on a site part way through, the colony skips those cells. It does
not overwrite a block it is not allowed to clear, and it never demolishes anything it has built.

### Which way it faces

Every blueprint has a front: its south side, where the entrance is. A building the colony places for
itself is turned so the front faces the beacon. Block states turn with it, so stairs and doors still
point the right way. A blueprint can opt out with `"rotate": false`.

### Land clearing

With `landClearingEnabled` on (the default) a colony clears natural blocks out of its way. What it
breaks goes into colony storage; anything that does not fit is dropped where it stood.

Three block tags decide what "natural" means, and a datapack can add to or replace any of them:

| Tag | File | What it holds |
| --- | --- | --- |
| `nerocolonies:clearable` | `data/nerocolonies/tags/block/clearable.json` | Everything the colony may break to make room: the vegetation tag below, plus dirt, grass, podzol, mycelium, sand, gravel, stone and its common variants, deepslate, tuff, snow blocks and moss blocks |
| `nerocolonies:clearable_vegetation` | `data/nerocolonies/tags/block/clearable_vegetation.json` | The part of that list that sits *on top of* the ground: logs, leaves, flowers, saplings, grasses, ferns, snow layers, vines, mushrooms, bamboo, pumpkins and melons. These are also ignored when the colony measures how level a site is |
| `nerocolonies:protected` | `data/nerocolonies/tags/block/protected.json` | Blocks the colony never breaks, whatever else they are: beds, doors, rails, signs, chests, barrels, shulker boxes, hoppers, furnaces, redstone components, spawners, and every NeroColonies block |

Beyond the tags, two things are never touched: **any block with a block entity**, and any block of
another finished building.

A blueprint also marks the cells it wants empty — an interior, a doorway — and natural blocks in
those cells are cleared too, working from the top of the blueprint down. Clearing stops at the
blueprint's own edges: the crown of a tree taller than the building is left where it is.

Set `landClearingEnabled` to `false` and a colony builds only where the ground is already clear.

### The entrance check

When a building with an interior is finished, the colony checks that the inside can be walked into
from the spot in front of it. If it cannot, a doorway one block wide and two high is opened in the
wall nearest that spot. The blocks removed go to colony storage.

That spot is the building's **access spot**: a place to stand just outside the footprint, at the
middle of the front where possible. It is where the builder stands, where anyone in the way of a
build is moved to, and where a tradesperson working at that building goes.

## The Colony Planner

The **Colony Planner** is an item for the colony's owner and its Chiefs. It places a building of
your choice at a spot of your choice. It is crafted, shapeless, from a paper, a compass and a copper
ingot.

1. **Stand inside the claim and use it in the air.** Each use selects the next building the colony
   can plan and shows its name and footprint above the hotbar.
2. **Sneak-use it** to turn the selected building a quarter turn clockwise. It starts with its front
   facing south.
3. **Use it on the ground.** The footprint is drawn in particles, centred on the block you clicked:
   green sparkles if the building fits there, flames if it does not.
4. **Use it on the same spot again** within 30 seconds to confirm. The plan joins the queue.

What can be planned is nearly the same list the colony chooses from: first-level buildings, at or
below the colony's stage, with their research unlocked, inside the server's size limits and not yet
at their `max` counting what is queued. The differences are the point of the tool:

- the housing rule does not apply, so you can add homes before the colony feels short;
- the structure cap does not apply;
- while the colony is Founding you can plan the Starter Works, which lets you choose where they go.

A planned building is paid for and built exactly like any other. Planning does not make it free and
does not skip the queue of blocks to place; it only decides *what* and *where*.

Things to know:

- The queue holds `maxConcurrentPlans` plans (default 4). When it is full, wait for a building to
  start.
- A site is checked when you preview it and again when its turn comes. If something has been built
  there in the meantime the plan is dropped, without taking any materials.
- Which building you have selected is remembered only in memory, per player, and is forgotten when
  you log out.
- Anybody who is not the owner or a Chief is told they may not, and nothing happens.

### The Chief's Planning Table

The **Chief's Planning Table** is a block that reads the plans back to you. Use it and it lists, in
chat, the buildings the colony can plan with their footprints, then what is queued and in what
order. It is crafted from three paper, four copper ingots and a crafting table, and the colony builds
one for itself in its Planning Hall.

The same readout is available from anywhere with `/nerocolonies colony plan list <colony>`, and
`/nerocolonies colony plan cancel <colony> <n>` removes queued plan number *n*. See
[Commands](Commands.md#colony-plan-listcancel).

## Upgrades

A blueprint can name a successor with `upgrade_to`. Once a building stands, and the colony has
reached the successor's stage and research, the building is upgraded **in place**: same corner, same
facing, the next level's blocks laid over the old ones. The successor must have the same footprint.

- An upgrade is chosen ahead of any new building the colony would pick for itself, so a new Neran
  Cottage is taken up to level III before the colony moves on.
- It needs the successor's materials, and builds from scrap without them like anything else.
- Blocks that differ are swapped, and what comes out goes to colony storage. Protected blocks and
  blocks with a block entity are left alone.
- It replaces the building's record rather than adding one, so the structure count does not change.

The shipped chains are Neran Cottage → II → III and Watchtower → II → III.

## What a finished building is

When the last block is placed the colony records the building: which blueprint, where, which way it
faces and its level. That record is what opens trades, marks meal spots and gathering places, keeps
other buildings two blocks away and makes upgrades possible. A colony remembers up to 512 of them.

The record is not re-checked against the world. Breaking part of a finished building does not close
the trade it opened, and the colony does not repair it.

Housing is the exception that reads the world: beds are counted by the housing sweep from the
Habitat Pods actually standing in the claim, so breaking those does cost capacity. See
[Colony basics](Colony-Basics.md#housing-and-the-housing-scan).

## Shipped buildings

Fifty-one blueprints ship with the mod. Within each stage they are listed most urgent first.

Reading the tables:

- **Footprint** is width × depth × height in blocks, before any turning.
- **Max** is how many one colony builds. `upgrade only` marks a level that is reached by upgrading.
- **Housing for N** is the capacity of the Habitat Pods the building contains.
- **Trade** means the building opens places in that trade — see
  [Nerans and professions](Nerans-and-Professions.md#trades).
- **Contains a …** names a working block inside the building. A station or machine the colony builds
  for itself still needs power and inputs from you.

### Founding: the Starter Works

| Building | Category | Footprint (w × d × h) | Max | Unlocks and provides | Upgrades to |
| --- | --- | --- | --- | --- | --- |
| Founder's Lodge | Housing | 9 × 6 × 7 | 1 | housing for 4; trade: Builder | — |
| Homestead Farm | Farm | 9 × 9 × 3 | 1 | trade: Farmer; contains a Farm Station | — |
| Lumber Yard | Industry | 9 × 9 × 4 | 1 | trade: Forester | — |
| Mine Head | Industry | 7 × 7 × 7 | 1 | trade: Miner | — |

### Settled

| Building | Category | Footprint (w × d × h) | Max | Unlocks and provides | Upgrades to |
| --- | --- | --- | --- | --- | --- |
| Habitat Pod | Housing | 5 × 5 × 5 | 6 | housing for 2 | — |
| Neran Cottage | Housing | 9 × 7 × 8 | 8 | housing for 2 | Neran Cottage II |
| Neran Cottage II | Housing | 9 × 7 × 9 | upgrade only | housing for 4 | Neran Cottage III |
| Neran Cottage III | Housing | 9 × 7 × 11 | upgrade only | housing for 6 | — |
| Farm Plot | Farm | 5 × 5 × 2 | 2 | trade: Farmer; contains a Farm Station | — |
| Depot Shed | Storage | 5 × 5 × 5 | 2 | contains a Colony Depot | — |
| Granary | Storage | 9 × 7 × 9 | 2 | midday meal spot | — |
| Hauler's Depot | Storage | 11 × 7 × 9 | 1 | trade: Hauler; contains a Colony Depot | — |
| Canteen | Civic | 13 × 7 × 10 | 1 | trade: Cook; midday meal spot | — |
| Oxygen Hut | Life Support | 5 × 5 × 6 | 1 | contains an Oxygen Generator | — |
| Needs Board Pavilion | Civic | 5 × 5 × 6 | 1 | contains a Needs Board | — |
| Gratitude Cache Pavilion | Civic | 5 × 5 × 6 | 1 | trade: Quartermaster; enables the Gratitude Cache; contains a Gratitude Cache | — |
| Planning Hall | Civic | 11 × 7 × 9 | 1 | contains a Chief's Planning Table | — |
| Toolsmith's Forge | Industry | 11 × 7 × 11 | 1 | trade: Toolsmith; contains a Fabricator Station | — |
| Research Cabin | Industry | 6 × 6 × 5 | 1 | trade: Researcher; contains a Research Station | — |
| Watch Post | Defence | 5 × 5 × 10 | 2 | trade: Guard; guard post | — |
| Kennel | Defence | 9 × 7 × 4 | 1 | trade: Beastkeeper; keeps 2 guardian wolves | — |
| Well Plaza | Civic | 9 × 9 × 5 | 1 | evening gathering place | — |

### Growing

| Building | Category | Footprint (w × d × h) | Max | Unlocks and provides | Upgrades to |
| --- | --- | --- | --- | --- | --- |
| Longhouse | Housing | 17 × 7 × 9 | 4 | housing for 8; needs research: Basic Shelter | — |
| Greenhouse | Farm | 9 × 13 × 10 | 2 | trade: Farmer; contains a Hydroponics Station; needs research: Hydroponics | — |
| Stables Pasture | Farm | 15 × 11 × 7 | 1 | — | — |
| Workshop Hall | Industry | 15 × 9 × 11 | 1 | trade: Builder; contains a Fabricator Station; needs research: Component Fabrication | — |
| Barracks | Defence | 13 × 7 × 9 | 2 | housing for 4; trade: Guard; guard post | — |
| Watchtower | Defence | 7 × 7 × 13 | 3 | trade: Guard; guard post | Watchtower II |
| Watchtower II | Defence | 7 × 7 × 22 | upgrade only | trade: Guard; guard post | Watchtower III |
| Watchtower III | Defence | 7 × 7 × 32 | upgrade only | trade: Guard; guard post | — |
| Med Bay | Civic | 11 × 7 × 9 | 1 | trade: Medic | — |
| Market Square | Civic | 15 × 15 × 5 | 1 | evening gathering place | — |
| Quarry Terrace | Industry | 13 × 11 × 8 | 1 | trade: Miner; contains a Refinery Station; needs research: Ore Refining | — |
| Golem Forge | Defence | 11 × 9 × 11 | 1 | trade: Beastkeeper; keeps 1 guardian iron golem | — |
| Windmill | Farm | 13 × 9 × 19 | 1 | trade: Farmer; contains a Farm Station | — |

### Thriving

| Building | Category | Footprint (w × d × h) | Max | Unlocks and provides | Upgrades to |
| --- | --- | --- | --- | --- | --- |
| Crystal Biodome | Farm | 25 × 25 × 13 | 1 | trade: Farmer; contains a Farm Station | — |
| Hydroponic Spire | Farm | 15 × 15 × 40 | 1 | trade: Farmer; contains a Hydroponics Station; needs research: Hydroponics | — |
| Observatory | Industry | 17 × 17 × 14 | 1 | trade: Researcher; contains a Research Station | — |
| Grand Town Hall | Civic | 23 × 21 × 39 | 1 | evening gathering place; contains a Needs Board, a Chief's Planning Table | — |
| Grand Bazaar | Civic | 27 × 19 × 13 | 1 | evening gathering place | — |
| Amphitheatre | Civic | 27 × 21 × 11 | 1 | evening gathering place | — |
| Colony Citadel | Defence | 31 × 31 × 14 | 1 | trade: Guard; guard post | — |
| Harbour | Civic | 25 × 17 × 10 | 1 | evening gathering place | — |
| Nexus Spire | Landmark | 21 × 21 × 40 | 1 | — | — |
| Lighthouse | Landmark | 11 × 11 × 32 | 1 | — | — |
| Sky Bridge | Landmark | 31 × 9 × 27 | 1 | — | — |

### Metropolis

| Building | Category | Footprint (w × d × h) | Max | Unlocks and provides | Upgrades to |
| --- | --- | --- | --- | --- | --- |
| Arcology Tower | Housing | 19 × 19 × 40 | 2 | housing for 16; needs research: Pressurised Modules | — |
| Grand Archive | Civic | 25 × 19 × 21 | 1 | trade: Researcher; contains a Research Station | — |
| Floating Garden | Landmark | 21 × 21 × 19 | 1 | — | — |
| Skyport | Landmark | 27 × 27 × 35 | 1 | — | — |
| Colossus | Landmark | 15 × 13 × 34 | 1 | — | — |

Every design is original to this mod; see [`../CREDITS.md`](../CREDITS.md). Blueprints are datapack
content: the file format is on [Content format](Content-Format.md#blueprints).

## Configuration

| Key | Default | Effect |
| --- | --- | --- |
| `maxBlueprintFootprint` | 32 | Largest footprint, on either axis, the colony will build or let you plan |
| `maxBlueprintHeight` | 40 | Tallest blueprint the colony will build or let you plan |
| `landClearingEnabled` | true | Whether natural blocks are cleared from a site |
| `maxConcurrentPlans` | 4 | Hand-placed plans a colony may have queued |
| `maxAutoStructures` | 12 | Structures a Settled colony builds unprompted; multiplied at later stages |

See [Config](Config.md) for ranges.

## See also

- [Construction](Construction.md) — build speed, supply and the needs list
- [Progression](Progression.md) — the stages that gate these buildings
- [Nerans and professions](Nerans-and-Professions.md) — the trades buildings open
- [Roles and defence](Roles-and-Defence.md) — who may plan, and what the defence buildings do
- [Content format](Content-Format.md) — writing your own blueprints
