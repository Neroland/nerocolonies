# Content format

Jobs, research nodes, housing tiers, export entries, blueprints and professions are all **datapack
JSON**. Adding a job, a research branch, a trade good, a building or a whole trade needs no Java at
all.

## Where files go

```text
data/<namespace>/nerocolonies/jobs/<path>.json
data/<namespace>/nerocolonies/research/<path>.json
data/<namespace>/nerocolonies/housing/<path>.json
data/<namespace>/nerocolonies/exports/<path>.json
data/<namespace>/nerocolonies/blueprints/<path>.json
data/<namespace>/nerocolonies/professions/<path>.json
```

**The id is the file path.** `data/mypack/nerocolonies/research/mining/drills.json` is the node
`mypack:mining/drills`. Subdirectories are part of the id, which is how the shipped research tree
gets its `habitation/`, `industry/`, `life_support/` and `trade/` grouping.

The job, research, housing and export schemas accept an `id` field and **ignore it** — it exists
only so a file that was written out by a generator still loads. The path always wins.

**A pack overrides a definition by shipping the same id.** Drop your own
`data/nerocolonies/nerocolonies/jobs/farm.json` into a datapack and it replaces the shipped farm job
entirely; ordinary datapack precedence decides which pack wins.

Content is re-read whenever the server's datapacks are reloaded, so `/reload` applies changes
immediately. Nothing is migrated and nothing needs to be: what a colony stores is a set of *ids*,
and every number is derived from the currently loaded definitions on demand.

Three more things a pack can change live in vanilla locations rather than here: the block tags that
govern land clearing ([Buildings](Buildings.md#land-clearing)), the item tags that define colony
food ([Colony basics](Colony-Basics.md#feeding-a-colony)) and the Gratitude Cache's loot tables
([Gratitude cache](Gratitude-Cache.md#for-pack-makers)).

## Item targets and item amounts

Two small shapes appear throughout.

An **item target** (job inputs, export targets, blueprint materials) selects **exactly one** of a
single item id or an item tag, plus a count:

```json
{ "item": "minecraft:wheat", "count": 4 }
{ "tag":  "c:crops",         "count": 4 }
```

| Field | Type | Default | Notes |
| --- | --- | --- | --- |
| `item` | item id | — | mutually exclusive with `tag` |
| `tag` | item tag id, written **without** a leading `#` | — | mutually exclusive with `item` |
| `count` | integer | `1` | minimum 1 |

Declaring both, or neither, is a decode error and **drops the owning definition** with a warning.

Tags are the preferred form throughout the shipped content: a tag lets a farming mod, a planet mod
or any third party satisfy a colony job with its own produce, and needs no compat code on either
side. Hard item ids are used only where the item is unmistakably vanilla.

An **item amount** (job outputs, research costs, what a trade gathers) is always a concrete item:

```json
{ "item": "minecraft:iron_ingot", "count": 8 }
```

| Field | Type | Default | Notes |
| --- | --- | --- | --- |
| `item` | item id | — | required |
| `count` | integer | `1` | minimum 1 |

## Jobs

`data/<namespace>/nerocolonies/jobs/<path>.json`

The shipped `nerocolonies:fabricate`:

```json
{
  "station": "nerocolonies:fabricator_station",
  "inputs": [
    { "item": "minecraft:iron_ingot", "count": 1 },
    { "item": "minecraft:redstone", "count": 2 }
  ],
  "outputs": [
    { "item": "minecraft:repeater", "count": 1 }
  ],
  "ticks": 400,
  "colonists": 2,
  "morale_floor": 35.0,
  "research": "nerocolonies:industry/fabrication",
  "export": true
}
```

| Field | Type | Default | Range | Meaning |
| --- | --- | --- | --- | --- |
| `station` | block id | — (required) | — | the block this job runs on |
| `inputs` | list of item targets | `[]` | — | consumed from colony storage, all or nothing |
| `outputs` | list of item amounts | `[]` | — | placed in colony storage, or in the export buffer |
| `ticks` | integer | `200` | 1–72,000 | progress needed for one craft |
| `colonists` | integer | `1` | 0–64 | Nerans wanted at the station; `0` is fully automated |
| `morale_floor` | double | `20.0` | 0–100 | below this colony morale the job will not run |
| `research` | node id | none | — | research prerequisite, if any |
| `export` | boolean | `false` | — | route the output to the export buffer |

Every magnitude here is scaled at runtime by `jobBaseRateMultiplier` and by the colony's morale
multiplier, so the JSON expresses **shape** — what turns into what, and roughly how fast — not
balance.

A job is **dropped** when it has no outputs, when its station block is not registered, when one of
its inputs resolves to nothing in this launch, or when one of its outputs names an unregistered
item. A recipe missing an ingredient is not a cheaper recipe, it is a broken one.

## Research nodes

`data/<namespace>/nerocolonies/research/<path>.json`

The shipped `nerocolonies:habitation/pressurised_modules`:

```json
{
  "branch": "habitation",
  "title": "research.nerocolonies.habitation.pressurised_modules",
  "requires": [ "nerocolonies:habitation/shelter" ],
  "cost": [
    { "item": "minecraft:iron_ingot", "count": 8 },
    { "item": "minecraft:glass", "count": 4 }
  ],
  "effects": [
    { "type": "nerocolonies:housing_tier", "tier": "nerocolonies:habitat_module" },
    { "type": "nerocolonies:morale_bonus", "amount": 2.0 }
  ]
}
```

| Field | Type | Default | Meaning |
| --- | --- | --- | --- |
| `branch` | string | `"general"` | presentational grouping in the research screen only |
| `title` | translation key | derived | falls back to `research.<namespace>.<path with dots>` |
| `requires` | list of node ids | `[]` | the actual graph |
| `cost` | list of item amounts | `[]` | paid from colony storage, all or nothing |
| `effects` | list of effects | `[]` | see below |

Costs are paid from **colony storage**, and unlocks are written to the **colony record** — research
is colony-local and is not personal data.

### Research effects

Effects are a dispatched type keyed on `type`:

| `type` | Field | Default | Range | Effect |
| --- | --- | --- | --- | --- |
| `nerocolonies:housing_tier` | `tier` (housing id) | — (required) | — | makes a housing tier countable in this colony |
| `nerocolonies:job_unlock` | `job` (job id) | — (required) | — | makes a job assignable in this colony |
| `nerocolonies:job_slots` | `amount` (integer) | `1` | — | adds simultaneously worked job slots |
| `nerocolonies:oxygen_efficiency` | `multiplier` (double) | `0.9` | 0.05–4.0 | multiplies life-support oxygen burn; below 1.0 is an improvement, and every unlocked node compounds |
| `nerocolonies:export_unlock` | `export` (export id) | — (required) | — | makes an export entry sellable from this colony |
| `nerocolonies:morale_bonus` | `amount` (double) | `1.0` | -100–100 | flat addition to the morale target |

A **blueprint** can also name a research node as its prerequisite, with its own `research` field;
that needs no effect on the node.

**An unregistered `type` is not an error.** It decodes to an inert *Unknown* effect: the node still
loads, that one effect does nothing, and the id is logged once per session. A datapack written for a
newer NeroColonies therefore degrades rather than bricking an older jar. An add-on mod may register
its own effect types the same way the built-ins are registered; ids are namespaced, so a collision
is the registering mod's own doing.

### How the research graph is validated

Three passes, in order:

1. **Effects that point at content which did not load are reported and kept.** They simply match
   nothing. Keeping them means removing one mod does not silently reshape a tree.
2. **Dangling prerequisites are pruned and the node stays.** A node that requires an id no longer in
   any pack loses that one requirement rather than the whole node.
3. **Cycles are dropped.** The graph is peeled from its roots; anything left is in — or behind — a
   prerequisite cycle and can never be unlocked, so it is removed.

## Housing tiers

`data/<namespace>/nerocolonies/housing/<path>.json`

The shipped `nerocolonies:habitat_module`:

```json
{
  "block": "nerocolonies:habitat_module",
  "tier": 2,
  "capacity": 4,
  "comfort": 0.65,
  "research": "nerocolonies:habitation/pressurised_modules"
}
```

| Field | Type | Default | Range | Meaning |
| --- | --- | --- | --- | --- |
| `block` | block id | — (required) | — | the block that counts as this housing |
| `tier` | integer | `1` | 1–16 | ranking; used to break ties between two tiers naming one block |
| `capacity` | integer | `1` | 0–256 | Nerans this block seats |
| `comfort` | double | `0.5` | 0–1 | weight in the morale housing term |
| `research` | node id | none | — | research prerequisite, if any |

Housing is matched by **block**, not by block entity: one block-state comparison during the sweep,
no block entity needed, and a pack can declare *any* block in the game — vanilla beds, another mod's
crew module — as colony housing.

A tier is **dropped** when its block is not registered or when its capacity is zero. If two tiers
claim the same block, the one with the higher `tier` wins, deterministically.

## Export entries

`data/<namespace>/nerocolonies/exports/<path>.json`

The shipped `nerocolonies:refined_metals`:

```json
{
  "target": { "item": "minecraft:iron_ingot", "count": 1 },
  "base_value": 4.0,
  "stack_size": 64,
  "research": "nerocolonies:trade/manifest"
}
```

| Field | Type | Default | Range | Meaning |
| --- | --- | --- | --- | --- |
| `target` | item target | — (required) | — | what this entry values |
| `base_value` | double | `1.0` | 0–1,000,000 | credits per item, before `exportValueMultiplier` |
| `stack_size` | integer | `64` | 1–64 | reserved; accepted and validated, but not yet read by the sale path |
| `research` | node id | none | — | research prerequisite, if any |

An entry is **dropped** when its target resolves to no item in this launch — an empty tag sells
nothing.

## Blueprints

`data/<namespace>/nerocolonies/blueprints/<path>.json`

A blueprint is one building a colony can put up. An example that uses every field:

```json
{
  "name": "blueprint.mypack.storehouse",
  "category": "storage",
  "stage": "settled",
  "priority": 35,
  "max": 2,
  "level": 1,
  "upgrade_to": "mypack:storehouse_2",
  "unlocks": [ "nerocolonies:hauler" ],
  "roles": [ "eat" ],
  "capacity": { "housing": 0, "storage": 27, "jobs": 2 },
  "research": "nerocolonies:habitation/shelter",
  "palette": {
    "#": "minecraft:oak_planks",
    "S": "minecraft:oak_stairs[facing=south,half=bottom]",
    "D": "nerocolonies:colony_depot",
    "_": "minecraft:air"
  },
  "layers": [
    [ "###", "###", "###" ],
    [ "###", "#D#", "#_#" ],
    [ "###", "#_#", "#_#" ],
    [ "###", "###", "SSS" ]
  ],
  "materials": [
    { "tag": "minecraft:planks", "count": 24 },
    { "item": "nerocolonies:colony_depot", "count": 1 }
  ],
  "rotate": true
}
```

| Field | Type | Default | Meaning |
| --- | --- | --- | --- |
| `name` | translation key | derived | Display name. Falls back to `blueprint.<namespace>.<path with dots>` |
| `category` | string | `other` | `housing`, `farm`, `industry`, `storage`, `life_support`, `civic`, `defence`, `landmark` or `other`. An unrecognised value becomes `other`. Only `housing` changes behaviour: it is built only when the colony is short of beds |
| `stage` | string | `settled` | The colony stage the blueprint needs: `founding`, `settled`, `growing`, `thriving` or `metropolis`. An unrecognised value becomes `settled`. A `founding` blueprint is a **Starter Work** |
| `priority` | integer | `100` | Lower is built first. 0–10,000 |
| `max` | integer | `4` | How many of this structure one colony may build. 0–64; `0` disables the blueprint |
| `level` | integer | `1` | 1–9. A blueprint above level 1 is never chosen or planned on its own; it is reached only as an upgrade |
| `upgrade_to` | blueprint id | none | The blueprint this one is upgraded into. It must have the same width and depth |
| `unlocks` | list of profession ids | `[]` | Trades this building opens places in |
| `roles` | list of strings | `[]` | What the building is for. See below |
| `capacity` | object | all `0` | `housing`, `storage` and `jobs`: what the building is meant to offer. Descriptive only — see below |
| `research` | node id | none | Research the colony must have unlocked |
| `palette` | map | `{}` | One character → one block-state string |
| `layers` | list | `[]` | The layout. Required unless `structure` is given |
| `structure` | structure id | none | A vanilla structure file to take the layout from. Used only when `layers` is absent |
| `materials` | list of item targets | `[]` | What the colony pays, once, to build at full speed. An empty list always builds at full speed |
| `rotate` | boolean | `true` | Whether the colony may turn the building so its front faces the beacon |

### The layout

`layers` is a list of horizontal slices **bottom-up**. Each slice is a list of rows running
**north → south** (+Z); each row is a string running **west → east** (+X). The **front** of a
building is its south side, the last row of each slice: that is where an entrance belongs, and it is
the side the colony turns towards the beacon.

Rows are padded with spaces to the widest row in the blueprint, so a ragged grid is a shape rather
than an error. Cells are built bottom layer first, then north → south, then west → east.

Every character means one of three things:

| The character is | Result |
| --- | --- |
| In the palette, mapped to a block | That block state is placed |
| In the palette, mapped to `minecraft:air` | A **clear cell**: natural blocks there are cleared before building, and nothing is placed. Use it for interiors and doorways |
| Not in the palette | A **hole**: nothing is placed and whatever is there is left alone |

### Block-state strings

A palette value is a block id, optionally followed by properties in square brackets, exactly as the
`/setblock` command writes them:

```text
minecraft:oak_planks
minecraft:oak_stairs[facing=north,half=bottom]
minecraft:oak_door[facing=south,half=lower,hinge=left,open=false]
```

A bare id means the block's default state. A property or a value the block does not have is ignored
and the rest of the state still applies, so a blueprint written for one Minecraft version degrades
on another rather than vanishing. Two-block things — doors, tall plants, beds — need both halves in
the palette, as in the door example above and its `half=upper` twin.

### Structure files

Instead of `palette` and `layers`, a blueprint may point at a vanilla structure file:

```json
{ "structure": "mypack:town_hall" }
```

reads `data/mypack/structure/town_hall.nbt`, the file a structure block saves. Every other blueprint
field works as usual. At load time the structure becomes an ordinary grid:

- each distinct block state becomes a palette entry;
- **air** in the structure becomes a clear cell;
- `minecraft:structure_void`, and any position the file does not mention, becomes a hole;
- **block-entity contents and entities in the file are ignored.** A chest in the structure is built
  as an empty chest. Copying inventories out of a datapack would be an item duplication path.

The building still goes up a few blocks per cycle like any other.

### Rotation

With `"rotate": true` the colony turns the building in quarter turns so that its front faces the
beacon, and a member placing it with the Colony Planner may turn it by hand. Block states are turned
with it using the game's own rotation, so stairs, doors and logs stay correct. Set `rotate` to
`false` for a building that only makes sense one way round.

### Stages, levels and upgrades

- `stage` gates the blueprint. `founding` blueprints are the Starter Works: they are the only thing a
  Founding colony builds, they are built only from supplied materials while it is Founding, and a
  colony becomes Settled when each has been built once. A pack with no `founding` blueprints has no
  such gate. See [Progression](Progression.md).
- To make an upgrade chain, give the first building `"level": 1` and `"upgrade_to"` its successor,
  and give the successor `"level": 2` and the **same width and depth**. The successor's own `stage`
  and `research` decide when the upgrade may happen. A successor with a different footprint is never
  upgraded to.
- `max` counts the first level only; an upgrade does not use up any of it.

### Roles, unlocks and capacity

`unlocks` lists professions. Each finished building opens that profession's `per_building` places.

`roles` are short tags. The colony acts on these six:

| Role | Effect |
| --- | --- |
| `eat` | Nerans walk here for their midday meal |
| `social` | Nerans gather here in the evening |
| `guard_post` | Guards patrol between these |
| `kennel` | The colony keeps two guardian wolves here, if it has a Beastkeeper |
| `golem_forge` | The colony keeps one guardian iron golem here, if it has a Beastkeeper |
| `gratitude_cache` | Enables Gratitude Cache stocking, if the colony has a Quartermaster |

Any other string is accepted and carried along without effect. The shipped content uses `sleep`,
`planning` and `needs_board` that way, as labels.

`capacity` is descriptive. Nothing in the colony reads it: beds are counted by the housing sweep from
the housing blocks actually in the building, storage is what colony storage holds, and job places
come from `unlocks`. Keep it truthful for anything that displays it.

### Limits

| Limit | Value |
| --- | --- |
| Footprint the format accepts | 48 × 48 |
| Height the format accepts | 64 layers |
| Footprint a server will build by default | 32 on either axis (`maxBlueprintFootprint`) |
| Height a server will build by default | 40 (`maxBlueprintHeight`) |
| Palette key | exactly one character |
| `max` | 0–64 |
| `level` | 1–9 |

A blueprint over the format's limits is dropped at load. One within them but over a server's
configured limits loads, and is skipped by the planner and the Colony Planner on that server.

## Professions

`data/<namespace>/nerocolonies/professions/<path>.json`

A profession is a Neran trade. The shipped `nerocolonies:farmer`:

```json
{
  "name": "profession.nerocolonies.farmer",
  "behaviour": "nerocolonies:farmer",
  "tool": "minecraft:iron_hoe",
  "stations": [
    "nerocolonies:farm_station",
    "nerocolonies:hydroponics_station"
  ],
  "priority": 10,
  "per_building": 2,
  "max_per_colony": 8,
  "xp_per_level": 100,
  "max_level": 5,
  "work_radius": 10,
  "outputs": [
    { "item": "minecraft:wheat", "count": 1 },
    { "item": "minecraft:potato", "count": 1 },
    { "item": "minecraft:carrot", "count": 1 },
    { "item": "minecraft:wheat_seeds", "count": 1 }
  ],
  "output_chance": 0.5
}
```

| Field | Type | Default | Range | Meaning |
| --- | --- | --- | --- | --- |
| `name` | translation key | derived | — | falls back to `profession.<namespace>.<path with dots>` |
| `behaviour` | id | — (required) | — | which built-in visible work the Neran does. See below |
| `tool` | item id | none | — | the tool a Neran of this trade holds. Without it the trade gathers at half rate |
| `stations` | list of block ids | `[]` | — | job-station blocks a Neran of this trade may staff |
| `priority` | integer | `100` | 0–10,000 | lower is filled first when trades are handed out |
| `per_building` | integer | `2` | 0–64 | places each unlocking building opens |
| `max_per_colony` | integer | `8` | 0–256 | most places one colony can have |
| `xp_per_level` | integer | `100` | 1–100,000 | experience step between levels |
| `max_level` | integer | `5` | 1–10 | highest level |
| `work_radius` | integer | `10` | 1–32 | how far from the workplace visible work reaches, in blocks |
| `outputs` | list of item amounts | `[]` | — | what one Neran gathers on a successful roll |
| `output_chance` | double | `1.0` | 0–64 | chance of that per daytime colony cycle; above 1 means whole extra batches |

**Numbers and bindings are data; behaviours are code.** `behaviour` names one of the twelve
behaviours compiled into the mod: `nerocolonies:farmer`, `forester`, `miner`, `builder`, `hauler`,
`cook`, `toolsmith`, `guard`, `beastkeeper`, `researcher`, `quartermaster` and `medic`. An unknown id
falls back to standing at the workplace.

A profession is opened by any blueprint that lists it in `unlocks`. One that no blueprint unlocks
loads, is reported, and is never assigned.

**The special trades are tied to the profession's id, not to its behaviour.** Fighting belongs to
`nerocolonies:guard`; the site speed bonus to `nerocolonies:builder`; meals, tool-making, the morale
lift, cache stocking and guardian upkeep to `nerocolonies:cook`, `toolsmith`, `medic`,
`quartermaster` and `beastkeeper`. To retune one of those, override the shipped file under the same
id. A new profession that borrows the `nerocolonies:guard` behaviour patrols the guard posts but does
not fight.

How these numbers play out is on [Nerans and professions](Nerans-and-Professions.md#trades).

## Bad content is never fatal

This is a hard rule. Every malformed file, unknown effect type, dangling reference, cycle and
unregistered id is logged at warning level against its resource id, and the offending entry is
**dropped or pruned** — the rest of the pack still loads. Even a load that fails outright leaves the
server running with no colony content rather than crashing it.

The same complaints are collected as a report so an operator can see what a pack got wrong without
reading the server log:

```text
/nerocolonies reload-check
```

Issues come in two severities: **DROPPED** (the definition is not loaded at all) and **IGNORED**
(the definition loaded, but part of it was skipped). Nothing in the report is player data — resource
ids and codec messages only, and never a filesystem path.

### What is checked

| Content | Problem | Result |
| --- | --- | --- |
| Any | The file is not valid JSON, or a required field is missing or of the wrong type | DROPPED |
| Housing | The block is not registered, or the capacity is zero | DROPPED |
| Job | No outputs; unregistered station; an input that resolves to nothing; an unregistered output | DROPPED |
| Export | The target resolves to no item | DROPPED |
| Research | An effect of an unknown type, or one that unlocks content which did not load | IGNORED — the effect does nothing |
| Research | A prerequisite that does not exist | IGNORED — that prerequisite is removed |
| Research | The node is in, or behind, a prerequisite cycle | DROPPED |
| Blueprint | Neither `layers` nor `structure`; an empty grid; bigger than 48 × 48 × 64; a palette key that is not one character | DROPPED |
| Blueprint | The structure file is missing, unreadable or too large | DROPPED |
| Blueprint | A palette entry names an unregistered block | IGNORED — those cells are left empty and the rest still builds |
| Blueprint | It places no blocks at all | DROPPED |
| Blueprint | `research` names a node that did not load | IGNORED — the blueprint stays and can never be built |
| Blueprint | A material is not present in this launch | IGNORED — the blueprint can never be supplied, so it always builds from scrap. A Starter Work in that state cannot be built by a Founding colony at all |
| Blueprint | `upgrade_to` names a blueprint that did not load | IGNORED — it simply has no upgrade |
| Blueprint | `unlocks` names a profession that did not load | IGNORED |
| Profession | The tool is not a registered item | IGNORED — Nerans of that trade work empty-handed |
| Profession | An output is not a registered item | IGNORED — that output is skipped |
| Profession | No blueprint unlocks it | IGNORED — it can never be assigned |

The report also gives the number of definitions of each kind that loaded. Content is validated in
the order housing, jobs, exports, research, blueprints, professions, so each kind is checked against
what actually survived before it.

## See also

- [Buildings](Buildings.md) — how blueprints are chosen and placed, and the shipped set
- [Nerans and professions](Nerans-and-Professions.md) — how professions behave in play
- [Jobs & research](Jobs-and-Research.md) — how job and research definitions behave in play
- [Exports & outposts](Exports-and-Outposts.md) — how export entries are valued
- [Colony basics](Colony-Basics.md) — the housing sweep that reads housing tiers
- [Commands](Commands.md) — `reload-check`
