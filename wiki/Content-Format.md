# Content format

Jobs, research nodes, housing tiers and export entries are all **datapack JSON**. Adding a job, a
research branch or a trade good needs no Java at all.

## Where files go

```text
data/<namespace>/nerocolonies/jobs/<path>.json
data/<namespace>/nerocolonies/research/<path>.json
data/<namespace>/nerocolonies/housing/<path>.json
data/<namespace>/nerocolonies/exports/<path>.json
```

**The id is the file path.** `data/mypack/nerocolonies/research/mining/drills.json` is the node
`mypack:mining/drills`. Subdirectories are part of the id, which is how the shipped research tree
gets its `habitation/`, `industry/`, `life_support/` and `trade/` grouping.

Every schema accepts an `id` field, and every schema **ignores it** — it exists only so a file that
was written out by a generator still loads. The path always wins.

**A pack overrides a definition by shipping the same id.** Drop your own
`data/nerocolonies/nerocolonies/jobs/farm.json` into a datapack and it replaces the shipped farm job
entirely; ordinary datapack precedence decides which pack wins.

Content is re-read whenever the server's datapacks are reloaded, so `/reload` applies changes
immediately. Nothing is migrated and nothing needs to be: what a colony stores is a set of *ids*,
and every number is derived from the currently loaded definitions on demand.

## Item targets and item amounts

Two small shapes appear throughout.

An **item target** (job inputs, export targets) selects **exactly one** of a single item id or an
item tag, plus a count:

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

An **item amount** (job outputs, research costs) is always a concrete item:

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
| `colonists` | integer | `1` | 0–64 | workers wanted; `0` is fully automated |
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
| `capacity` | integer | `1` | 0–256 | colonists this block seats |
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

## See also

- [Jobs & research](Jobs-and-Research.md) — how these definitions behave in play
- [Exports & outposts](Exports-and-Outposts.md) — how export entries are valued
- [Colony basics](Colony-Basics.md) — the housing sweep that reads housing tiers
- [Commands](Commands.md) — `reload-check`
