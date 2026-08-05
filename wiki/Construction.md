# Autonomous construction

A colony builds itself. You place a beacon, two founders arrive with it, and they get on with putting
up a habitat — no build orders, no assignment screen, no clicking. Your lever is **supply**: bring the
materials and the same structure goes up four times faster.

This page covers the founders, the build loop, blueprints as datapack content, and the levers an
operator has.

## Founder colonists

Placing a colony beacon puts `founderColonistCount` colonists (default **2**) on the ground next to
it, immediately — not on the first colony cycle a minute and a half later. They are the seed of the
whole loop: housing is what lets colonists arrive, and building housing is what colonists do, so
without founders nothing can ever start.

Founders are held on the roster **regardless of housing capacity**. That is a floor, not an
exemption:

- they still count toward `colonistsPerColony` and the server-wide `maxLoadedColonists`;
- they get exactly the same life support, food and morale treatment as anybody else — on an airless
  world the usual curve applies (life support fails → morale decays → work stops → colonists idle),
  and **no colonist is ever killed or deleted for it**;
- a colony that drops below its founder count will replace them even while starving or without
  atmosphere. That exemption is deliberate: a colony with nobody left has nothing that can build the
  farm or the oxygen generator that would fix the problem, so gating the bootstrap on food and air
  would make such a colony permanently dead rather than merely in trouble. It is bounded by
  `founderColonistCount` and cannot grow a colony past it.

Set `founderColonistCount` to `0` to switch founders off. Autonomous construction then never starts
on its own — the colony waits for you to build the first housing by hand.

## The build loop

Every colony cycle (`colonyTickIntervalTicks`, default 100 ticks) a colony with nothing under
construction picks the highest-priority blueprint it is allowed to build, looks for somewhere to put
it, and starts. Thereafter it lays `constructionBlocksPerCycle` blocks per cycle (default **2**)
until the structure is finished, then picks the next one.

Deliberately slow. A colony growing visibly over minutes reads as a colony; one that snaps into
existence reads as a command block.

### Supplied and unsupplied

At the start of every cycle, a build that has not yet been paid for looks for its blueprint's
**materials in colony storage**. If they are all there they are consumed once and the build runs at
full rate. If they are not, the colonists fabricate from scrap instead: the same structure, free, at
`constructionUnsuppliedFactor` of the rate (default **0.25**).

Nothing is ever *blocked* on materials. A colony left entirely alone still grows, just slowly — a
colony that stops dead waiting for iron is a colony you have to babysit.

The check runs every cycle, not only when the build starts, so **dropping materials into colony
storage part way through speeds up the build already under way**. That is the whole player-facing
lever.

> Materials go into **colony storage**, which you reach through a Colony Depot inside the claim, or
> through any pipe or hopper inserting into the beacon. They do **not** go in the beacon's six supply
> slots — those are the food intake and refuse anything that is not food.

### Where a colony may build

| Rule | Why |
| --- | --- |
| Inside the claim only, re-checked per block | A claim can shrink when a `RANGE` module is pulled out |
| Only into blocks that are *replaceable* — air, grass, snow, water | Your chest, wall or torch is **never** overwritten, and neither is another structure |
| Loaded chunks only, never loading one | The far edge of a 97-block claim is often not loaded; the search skips it rather than paying to load it |
| Flat ground: the footprint's highest and lowest surface may differ by at most one | Colonies level nothing and dig nothing |
| The base must sit within 4 blocks below and the top within 12 above the beacon | Keeps a colony from terracing up a cliff, and keeps what it builds inside the band the housing sweep reads |
| The bottom layer must have solid ground under it | No floating structures |

Candidate sites are walked in **rings out from the beacon**, so a colony grows outward from its
centre rather than filling the claim from one corner. Eight candidates are examined per cycle; a
colony that is completely boxed in gives up for ten cycles before looking again, so a hemmed-in
colony costs nothing.

If a player builds something on a chosen site part way through, that cell is simply skipped — the
player wins, always.

### When it stops

Construction pauses (never cancels, never demolishes) when:

- `constructionEnabled` is `false`;
- morale has fallen below `moraleWorkStopThreshold` and work has stopped;
- life support is `FAILED`;
- `constructionRequiresColonist` is set (the default) and the colony's roster is empty;
- the colony has reached `maxAutoStructures`, or that blueprint's own `max`.

**Nothing NeroColonies built is ever demolished automatically.** A half-built structure whose
blueprint was removed from the datapack is abandoned in place, not torn down.

### Housing pressure

Housing blueprints are only eligible when the colony is actually short of bunks — fewer than two free
places. That is what keeps "autonomous" from turning into "sprawls to the edge of the claim". With
the shipped content the loop reads:

1. two founders, no housing → build a Habitat Pod;
2. capacity 2, population 2 → still no headroom → build a second pod;
3. capacity 4, population 2 → headroom → stop building housing, build a farm plot instead;
4. population grows to 4 → no headroom again → third pod.

### The builder

One colonist the job board did not need this cycle is pointed at the site and walks over to it, so
you can see where the colony is working. That is **all** it does.

Block placement is colony-tick logic and never consults the builder: `constructionRequiresColonist`
asks whether the colony *has* anybody, never whether anybody arrived. A colonist that cannot path to
a site — a wall, a cliff, deep water, night time — must not be able to stall a colony's growth.

Being a builder is a **role, not a personality**. It uses the `jobId` field a colonist already has,
it is reassigned from scratch every cycle, and any colonist will do.

### While nobody is there

Offline [catch-up](Colony-Basics.md#while-nobody-is-there-the-offline-catch-up) advances a colony's
**fabrication credit and places no blocks at all**. Laying a backlog's worth of blocks on the tick a
chunk loads would be a visible stutter and a lighting-update storm at exactly the worst moment.

The credit is capped at four cycles' worth, so a returning player sees the build resume briskly for a
few cycles and then settle to the normal rate. A colony never *starts* a new structure while nobody
is there.

## Watching it happen

The beacon's **Colony** tab shows one line:

- `Building Habitat Pod - 34%` — supplied, running at full rate;
- `Fabricating Habitat Pod - 34%` — unsupplied, running at `constructionUnsuppliedFactor`. Put the
  materials in colony storage;
- `Not building - 3 structure(s) up` — idle.

A completed structure also:

- publishes Core's `nerocolonies:structures` threshold crossing, scoped to the **colony id** and
  carrying the new total, so a NeroQuests objective can key off "this colony has built its third
  structure" with no coupling to this mod;
- pushes a `construction` event to the colony owner's companion sessions
  ([Link module](Link-Module.md));
- triggers an immediate housing rescan, so a finished habitat raises capacity within seconds rather
  than at the next scheduled sweep.

## Blueprints

Blueprints are plain datapack JSON at
`data/<namespace>/nerocolonies/blueprints/<path>.json`. The id is the file's namespace plus its path
without the extension, so a pack overrides a shipped blueprint by shipping the same id.

```json
{
  "name": "blueprint.nerocolonies.habitat_pod",
  "category": "housing",
  "priority": 10,
  "max": 6,
  "research": "nerocolonies:habitation/shelter",
  "palette": {
    "#": "minecraft:smooth_stone",
    "G": "minecraft:glass",
    "H": "nerocolonies:habitat_pod"
  },
  "layers": [
    [ "###", "###", "###" ],
    [ "###", "#H#", "#.#" ],
    [ "###", "#G#", "#.#" ],
    [ "###", "###", "###" ]
  ],
  "materials": [
    { "item": "minecraft:smooth_stone", "count": 16 },
    { "item": "minecraft:iron_ingot", "count": 6 },
    { "item": "minecraft:glass", "count": 2 }
  ]
}
```

| Field | Type | Default | Meaning |
| --- | --- | --- | --- |
| `name` | string | derived from the id | Translation key for the display name |
| `category` | string | `other` | `housing`, `farm`, `industry`, `storage`, `life_support`, `other`. Only `housing` behaves differently (the pressure rule). An unrecognised value becomes `other` |
| `priority` | int | `100` | Lower is built first |
| `max` | int | `4` | How many of this structure one colony may build. `0` disables the blueprint |
| `research` | id | — | Optional research node the colony must have unlocked |
| `palette` | map | *required* | One character → one block id |
| `layers` | array | *required* | The layout, see below |
| `materials` | array | `[]` | `ItemTarget` list — `{"item": …}` or `{"tag": …}` plus `count`. An empty list always builds at full speed |

### The layout

`layers` is a list of horizontal slices **bottom-up**. Each slice is a list of rows running
**north → south** (+Z); each row is a string running **west → east** (+X).

A character with **no palette entry is a hole**: nothing is placed and whatever is there is left
alone. `.` and a space are the conventions used by the shipped content, but any unmapped character
works.

Rows are padded to the widest row in the blueprint, so a ragged grid is a shape rather than an error.
Blocks are placed in their **default block state** — a blueprint describes a layout, not block
states, which is exactly what keeps it hand-authorable. Maximum size is 16 × 16 blocks and 12 layers.

Cells are built bottom layer first, then north → south, then west → east.

### Validation

Bad content is **never fatal**, and the severity split matters:

| Problem | Result |
| --- | --- |
| A palette entry naming an unregistered block | *Ignored* — those cells become holes and the rest of the structure still builds. Removing a mod from a pack leaves gaps, not a broken colony |
| A material naming an item that is not installed | *Ignored* — the blueprint simply always builds unsupplied |
| `research` naming a node that did not load | *Ignored* — the blueprint stays and never becomes eligible, which is more use in the report than deleting it |
| Every cell is a hole | *Dropped* — it can never do anything |
| No layers, an empty grid, or bigger than 16 × 16 × 12 | *Dropped* |

`/nerocolonies reload-check` lists everything the last load complained about, and reports the
blueprint count alongside jobs, research, housing and exports.

## Shipped blueprints

| Id | Category | Priority | Max | Puts up |
| --- | --- | --- | --- | --- |
| `nerocolonies:habitat_pod` | housing | 10 | 6 | A 3 × 3 stone pod around a Habitat Pod (capacity 2) |
| `nerocolonies:farm_plot` | farm | 20 | 2 | A 5 × 5 farmland patch with a water source and a Farm Station |
| `nerocolonies:depot_shed` | storage | 30 | 2 | A 3 × 3 shed around a Colony Depot |
| `nerocolonies:oxygen_hut` | life_support | 40 | 1 | A glazed 3 × 3 hut around an Oxygen Generator |
| `nerocolonies:research_cabin` | industry | 50 | 1 | A 4 × 4 cabin around a Research Station |

None of them require research, so a brand-new colony can work through the whole list. The Oxygen Hut
is useless on a breathable world and harmless there — it simply idles.

The stations and machines a colony builds for itself still need **power and inputs** from you. A
colony can put up a refinery; it cannot run a cable to it.

## Configuration

| Key | Default | Effect |
| --- | --- | --- |
| `founderColonistCount` | 2 | Colonists that arrive with a new beacon. `0` disables the bootstrap |
| `constructionEnabled` | true | Master switch |
| `constructionBlocksPerCycle` | 2 | Blocks placed per colony cycle at full rate |
| `constructionUnsuppliedFactor` | 0.25 | Rate multiplier without materials. `0` means an unsupplied colony never builds |
| `constructionRequiresColonist` | true | Whether an empty roster stops building |
| `maxAutoStructures` | 12 | Total structures one colony may build for itself |

See [Config](Config.md) for the full table.

## Privacy

Nothing on this page involves player data. A build plan is keyed by a **colony id** — a place, not a
person — and holds blueprint ids, a block position and counters. The threshold channel is
colony-scoped by contract; the companion event is owner-scoped and names no other player. See
[Data storage](Data-Storage.md).

## See also

- [Colony basics](Colony-Basics.md) — founding, housing, population, the colony cycle
- [Content format](Content-Format.md) — the other datapack schemas
- [Config](Config.md) — every key named here
- [Link module](Link-Module.md) — what a companion app sees
