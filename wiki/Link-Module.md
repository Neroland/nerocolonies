# Link module (companion app)

NeroColonies can show **your** colonies to a Neroland companion app. It does that through
**Neroland Core's link API**: NeroColonies registers what it can show and what it can do, and a
separate bridge mod serves that to your paired app over your own network.

NeroColonies itself ships **no server, no HTTP, no accounts and no outbound connection**. It only
fills in a registry entry inside Core. With no bridge mod installed, the link module does nothing at
all. It can also be switched off outright with `linkModuleEnabled=false`, in which case nothing is
registered and no event is ever published.

## What it exposes

| Kind | Name | What it is |
| --- | --- | --- |
| Section | `colonies` | **Your** colonies, with their state |
| Section | `colonists` | Population and staffing counts per colony |
| Section | `jobs` | Job slots, and what each station is doing |
| Section | `research` | What each colony has unlocked, could unlock, can pay for, plus the node catalogue |
| Section | `exports` | Buffer fill, its worth, and each colony's manifest |
| Section | `summary` | Stage, population, food, morale and needs at a glance (schema 2) |
| Section | `needs` | What each colony is short of, why, and how long until it has it (schema 2) |
| Section | `buildings` | Finished, under-way and planned buildings (schema 2) |
| Section | `professions` | Head-count against places, per trade (schema 2) |
| Section | `roles` | Your own role, and how many allies, chiefs and enemies (schema 2) |
| Section | `cache` | How full the Gratitude Cache is (schema 2) |
| Action | `toggle_export` | Route a job's output to the export buffer, or back to storage |
| Action | `acknowledge_alert` | Acknowledge one of your own NeroColonies alerts |
| Action | `prioritise_need` | Ask a colony to gather one need first, or clear that (schema 2) |
| Action | `toggle_cache_sharing` | Share the Gratitude Cache with allies, or stop (schema 2) |
| Event | `life_support` | One of your colonies changed life-support state |
| Event | `morale` | One of your colonies crossed the work-stop threshold |
| Event | `food` | One of your colonies ran out of rations, or started eating again |
| Event | `exports` | One of your export buffers filled up, or was drained |
| Event | `construction` | One of your colonies finished building a structure for itself |
| Event | `stage` | One of your colonies reached a new growth stage |
| Event | `neran_born` | A Neran was born in one of your colonies |
| Event | `needs` | A colony's needs list started, or stopped, wanting your help |
| Event | `enemy` | Enemies are inside one of your colonies' claims (a count) |
| Event | `cache` | A Gratitude Cache was stocked, or is full |
| Event | `guards` | A colony's guards engaged a target |
| Event | `colony_state` | A colony changed life-support state (**broadcast**) |
| Alert | life support has failed | Raised for each colony member |
| Alert | morale collapsed, work stopped | Raised for each colony member |
| Alert | enemies inside the claim | Raised for each colony member |
| Alert | the colony needs your help | Raised for each colony member |
| Alert | the Gratitude Cache is full | Raised for each colony member |

Module id `nerocolonies`, **schema version 2**. The schema version is bumped whenever the shape of a
section changes, so an app can tell what it is parsing.

Version 2 **added** six sections, two actions and six events. The five version 1 sections are still
served with the same fields they always had, so an app written against version 1 keeps working; the
only thing it will notice is that `schema_version` now reads `2` in every envelope.

## What "yours" means

Every section starts from the same rule, and it is in exactly one place in the code: a request sees
the colonies its own player UUID **owns, or is an Ally or a Chief of**, and nothing else. Somebody a
colony has marked as an Enemy sees nothing of it, and neither does a stranger.

It is never widened for permission level. An operator's powers are a property of a live command
source, not of a UUID arriving over a bridge — a link module that honoured them would turn "I am an
admin" into "my phone can read every base on the server".

The gallery's sandbox colony is not a colony anybody plays. It appears in no section, answers no
action and publishes no event.

Every section also accepts an optional `colony` parameter to narrow to one colony. An id you cannot
see narrows to *nothing* rather than falling back to everything, so a typo never returns more than
was asked for.

## Section: `colonies`

```json
{
  "schema_version": 2,
  "player_online": true,
  "colonies": [
    {
      "id": "1f4f7a52-…",
      "name": "Kestrel Landing",
      "dimension": "minecraft:overworld",
      "is_owner": true,
      "beacon": { "x": 128, "y": 71, "z": -344 },
      "claim_radius": 48,
      "morale": 74,
      "work_stopped": false,
      "output_multiplier": 0.805,
      "population": 9,
      "housing_capacity": 12,
      "food_stock": 143,
      "starving": false,
      "life_support": "OK",
      "life_support_ok": true,
      "oxygen_generators": 2,
      "research_unlocked": 5,
      "outposts": 1,
      "members": 2,
      "has_owner": true,
      "created_at": 184203
    }
  ]
}
```

`life_support` is `OK`, `DEGRADED` or `FAILED`. `members` is a **count**; there is no roster field
and there will not be one.

## Section: `colonists`

Counts, never entities. `population`, `housing_capacity`, `population_cap` (the config cap),
`assigned` (Nerans holding a job), `idle`, and `work_stopped`.

The numbers come from the colony record and the job board rather than from walking an entity index,
so an unloaded colony reports its last known roster instead of zero.

## Section: `jobs`

```json
{
  "schema_version": 2,
  "player_online": true,
  "colonies": [
    {
      "id": "1f4f7a52-…",
      "name": "Kestrel Landing",
      "dimension": "minecraft:overworld",
      "is_owner": true,
      "job_slots": 6,
      "job_slots_used": 4,
      "work_stopped": false,
      "stations": [
        {
          "index": 0,
          "job": "nerocolonies:refine",
          "name": "Refine",
          "active": true,
          "blocked": false,
          "assigned": 2,
          "required": 2,
          "progress": 0.42,
          "outpost": false,
          "export_routed": true
        }
      ]
    }
  ]
}
```

Stations are reported by **index** into the job board's own stable order — never by position.
`export_routed` is omitted entirely when the station's chunk is not loaded, because the routing
switch lives on the block and no chunk is ever loaded to answer a snapshot.

## Section: `research`

Per colony: `unlocked`, `available` (prerequisites met, not yet taken) and `affordable` (available
*and* the colony's storage holds the cost), each an array of node ids, plus `job_slots`.

Alongside them, a `nodes` catalogue — id, branch, printable name, translation key, prerequisites and
cost — which is world content, identical for every player, and is what lets an app draw a tree
rather than a list of opaque ids.

Note what is *not* there: the colony's inventory. The server decides what is affordable and sends a
list of ids, exactly as the in-game research screen works.

## Section: `exports`

Per colony: `buffer_filled`, `buffer_slots`, `buffer_full`, `value` (what the buffer would fetch
right now), `sellable`, and a `manifest` array of the export entries with `unlocked` per entry. The
envelope carries `market_available`, which is false when no economy mod is installed — in which case
goods still accumulate but cannot be sold.

## The schema 2 sections

The six sections added in schema version 2 share one shape:

```json
{ "schema_version": 2, "player_online": true, "colonies": [ … ] }
```

and every row in `colonies` starts with the same five fields:

| Field | What it is |
| --- | --- |
| `id` | The colony's id |
| `name` | The colony's name |
| `dimension` | The dimension it stands in |
| `is_owner` | Whether **you** own it |
| `role` | **Your** role in it: `owner`, `chief` or `ally` |

`role` is about the person asking and nobody else. It is the only role any payload ever spells out.

No chunk is loaded to answer any of them. Figures that come from the Nerans themselves — children,
and how many Nerans hold each trade — are read only while the colony is loaded; an unloaded colony
keeps its row and reports those as zero.

## Section: `summary`

```json
{
  "schema_version": 2,
  "player_online": true,
  "headline": "1 colony",
  "sections": [
    {
      "title": "Kestrel Landing",
      "items": [
        { "label": "Stage", "value": "Growing",
          "detail": "Next stage at 20 population and 12 structures", "severity": "info" },
        { "label": "Population", "value": "14 / 18", "detail": "3 children", "severity": "info" },
        { "label": "Food", "value": "96", "severity": "info" },
        { "label": "Morale", "value": "72", "severity": "info" },
        { "label": "Needs", "value": "3", "detail": "1 cannot be met without help",
          "severity": "warn" }
      ]
    }
  ],
  "colonies": [
    {
      "id": "1f4f7a52-…",
      "name": "Kestrel Landing",
      "dimension": "minecraft:overworld",
      "is_owner": true,
      "role": "owner",
      "stage": "growing",
      "stage_index": 2,
      "tier": 3,
      "population": 14,
      "children": 3,
      "housing_capacity": 18,
      "food_stock": 96,
      "starving": false,
      "life_support": "OK",
      "morale": 72,
      "work_stopped": false,
      "needs_count": 3,
      "needs_eta_solo_minutes": 12,
      "needs_eta_help_minutes": 4,
      "next_milestone": { "population": 20, "structures": 12 },
      "structures_built": 9
    }
  ]
}
```

`stage` is `founding`, `settled`, `growing`, `thriving` or `metropolis`; `stage_index` is its
position in that list (0 to 4) and `tier` is `stage_index + 1`, the same number the Gratitude Cache
uses to pick its rewards.

`needs_eta_solo_minutes` and `needs_eta_help_minutes` are the estimate for the building under way:
how long it will take if the colony is left alone, and if it is supplied now. Either can be `-1`.
The solo figure is `-1` when the colony cannot finish alone — a founding colony's Starter Works
build only from materials you bring. The help figure is `-1` when the building cannot be done at
all, which is the case on a server that has set `constructionBlocksPerCycle` to `0`. Both are `0`
when nothing is under way.

`next_milestone` is the population and structure count the next stage asks for. It is omitted at
the top stage, and while a colony is still founding, where the milestone is its Starter Works
rather than a number.

`headline` and `sections` say the same things as plain label/value lines — one titled block per
colony — for a client that draws generic lists rather than a colony screen. Each item has a `label`
and a `value`, a `severity` of `info`, `warn` or `critical`, and a `detail` and a `status` only when
there is something to say. The headline counts your colonies and, when any of them is starving, has
stopped work or has lost life support, how many need attention.

## Section: `needs`

```json
{
  "id": "1f4f7a52-…",
  "name": "Kestrel Landing",
  "dimension": "minecraft:overworld",
  "is_owner": true,
  "role": "owner",
  "needs": [
    {
      "item": "minecraft:oak_log",
      "name_key": "block.minecraft.oak_log",
      "needed": 64,
      "have": 40,
      "reason": "construction",
      "eta_solo_minutes": 12,
      "eta_help_minutes": 0,
      "priority": true
    },
    {
      "item": "#nerocolonies:colony_food",
      "name_key": "need.nerocolonies.food",
      "needed": 140,
      "have": 96,
      "reason": "food",
      "eta_help_minutes": 0,
      "priority": false
    }
  ]
}
```

`item` is an item id, or `#` and a tag id when any item in a tag will do. `reason` is `starter`
(a Starter Works material, which only you can supply), `construction`, `food` or `tools`.

`eta_solo_minutes` is how long the colony's own gatherers will take at their present rate. It is
omitted when the need will not arrive without help — nothing in the colony gathers that item, it is
a Starter Works material, or it is the food need, as in the second entry above.
`eta_help_minutes` is always `0`: a need is met the moment the item is delivered to a Colony Depot
or the Needs Board.

`priority` marks the one need the colony has been asked to gather first.

The list is what the *colony* lacks. It never says what any player is carrying.

## Section: `buildings`

```json
{
  "id": "1f4f7a52-…",
  "name": "Kestrel Landing",
  "dimension": "minecraft:overworld",
  "is_owner": true,
  "role": "owner",
  "completed": [
    {
      "blueprint": "nerocolonies:lumber_yard",
      "name_key": "blueprint.nerocolonies.lumber_yard",
      "category": "industry",
      "level": 1,
      "stage": "founding"
    }
  ],
  "in_progress": {
    "blueprint": "nerocolonies:neran_cottage_2",
    "name_key": "blueprint.nerocolonies.neran_cottage_2",
    "percent": 62,
    "supplied": true,
    "upgrade": true
  },
  "planned": [
    {
      "blueprint": "nerocolonies:gratitude_cache_pavilion",
      "name_key": "blueprint.nerocolonies.gratitude_cache_pavilion"
    }
  ]
}
```

`completed` lists every finished building, oldest first: its blueprint, the blueprint's `category`
(`housing`, `farm`, `industry`, `storage`, `life_support`, `civic`, `defence`, `landmark` or
`other`), the `level` it stands at, and the `stage` the blueprint belongs to.

`in_progress` is the building going up now, and is omitted when nothing is. `supplied` says whether
its materials are paid for, and `upgrade` whether it replaces a standing building rather than adding
a new one.

`planned` is the queue of buildings placed by hand and waiting their turn.

Buildings are reported **by blueprint, never by position** — the same choice the `jobs` section
makes for stations.

## Section: `professions`

```json
{
  "id": "1f4f7a52-…",
  "name": "Kestrel Landing",
  "dimension": "minecraft:overworld",
  "is_owner": true,
  "role": "owner",
  "professions": [
    {
      "id": "nerocolonies:farmer",
      "name_key": "profession.nerocolonies.farmer",
      "count": 3,
      "places": 4
    }
  ],
  "children": 3,
  "unassigned": 1
}
```

One entry per trade the colony has a place or a worker for: `count` is how many adult Nerans hold
it and `places` is how many its finished buildings open. `children` and `unassigned` (adults without
a trade) make up the rest of the population.

Counts, never Nerans: no entity id and no Neran's name.

## Section: `roles`

```json
{
  "id": "1f4f7a52-…",
  "name": "Kestrel Landing",
  "dimension": "minecraft:overworld",
  "is_owner": false,
  "role": "chief",
  "your_role": "chief",
  "allies": 2,
  "chiefs": 1,
  "enemies": 0
}
```

`your_role` is your own role, and the other three are **counts**. There is no list of who holds
which role, in this section or any other, and there will not be one: an app told who a colony's
allies and enemies are has been told where those people play and who they have fallen out with.

## Section: `cache`

```json
{
  "id": "1f4f7a52-…",
  "name": "Kestrel Landing",
  "dimension": "minecraft:overworld",
  "is_owner": true,
  "role": "owner",
  "has_cache": true,
  "filled": 21,
  "slots": 27,
  "full": false,
  "last_stocked_minutes_ago": 14,
  "shared_with_allies": false
}
```

`has_cache` is whether the colony has finished a Gratitude Cache building. `filled` is how many of
its `slots` hold something. `last_stocked_minutes_ago` is `-1` if the cache has never been stocked.
`shared_with_allies` is the owner's switch for whether allies may open it.

Every member sees this row, whether or not they may open the cache, because it says how full the
cache is and **never what is in it**.

## Action: `toggle_export`

Routes every loaded station running one job to the export buffer, or back to colony storage.

```json
{ "colony": "1f4f7a52-…", "job": "nerocolonies:refine", "export": true }
```

`export` is optional; omitted, the action flips whatever the first matching station currently has,
so a repeated tap toggles rather than fighting itself.

The action names a **job**, not a station, and that is a privacy choice as much as a convenient one:
naming a station would mean sending your app a set of block positions to choose from. A colony rarely
has two stations on one job, and when it does, "route my refining output to trade" is what was meant
for both.

**Requires you to be online.** That is not squeamishness: the permission this mod defines is asked of
a live player and includes the operator override, and re-deriving it from a bare UUID would create a
second permission path. Two permission paths are one too many.

Refusals: `NOT_OWNER` for a colony you cannot see (which is also the answer for one that does not
exist, so the action cannot be used to probe for other people's bases), `PLAYER_OFFLINE_REQUIRED`,
and `VALIDATION` for a job the colony has no station for or whose stations are all unloaded.

## Action: `acknowledge_alert`

```json
{ "alert": "life_support.1f4f7a52-…" }
```

Marks one of your own alerts as read in Core's shared alert store. Works while you are offline —
that is rather the point of an alert. The store is per-player by construction, so this can only ever
reach your own row.

## Action: `prioritise_need`

Asks a colony to gather one need first. Trades that gather the prioritised item work faster on it.

```json
{ "colony": "1f4f7a52-…", "item": "minecraft:oak_log" }
```

`item` is an item id as the `needs` section reports it. An **empty string clears** the priority. A
`#tag` need cannot be prioritised — a colony prioritises one item, and a tag is not one.

**Owner or Chief.** Your role is read from the colony's own stored role lists on every call; nothing
an app says about its own rank is believed. It works while you are offline.

Refusals: `NOT_OWNER` for a colony you cannot see or one that does not exist, and also for an ally,
who can see the colony but may not steer it; `VALIDATION` when `item` is missing, is a `#tag`, or
is not an item id.

## Action: `toggle_cache_sharing`

Shares the Gratitude Cache with the colony's allies, or stops sharing it.

```json
{ "colony": "1f4f7a52-…", "shared": true }
```

`shared` is optional; omitted, the switch flips.

**Owner only.** The cache is the colony's thank-you to its owner, and who else may open it is the
owner's call alone. It works while you are offline.

Refusals: `NOT_OWNER` for a colony you cannot see, one that does not exist, or one you do not own;
`VALIDATION` when `shared` is present but is not `true` or `false`.

## Why so few actions

Everything else a companion client might want to do to a colony — founding one, dissolving one,
researching a node, spending its stock, selling its goods, changing who belongs to it — either moves
items, spends resources or changes who has standing in a place. Doing any of those from a phone would
let a player alter the world, and other people's position in it, without being in it.

The actions that are here are settings. Flipping where a job's output goes changes no quantity of
anything and is reversible with the same tap. Prioritising a need moves nothing and spends nothing;
it tells the colony's own gatherers what to work on first. Sharing the cache is the owner's own
switch and adds nobody to the colony.

**`set_job_priority` is deliberately absent.** The job board has no priority model in 0.1.0 — slots
are allocated first-fit in a stable order — so an action by that name would either do nothing or
invent a mechanic through the back door. It belongs with the job board's next revision.

## Events and alerts

All but one of the events are **member-scoped**: they are published to each member of the colony —
its owner, its chiefs and its allies — exactly the people whose snapshots already show it, and the
bridge routes them to those players' sessions and nobody else's. A colony with no owner (after an
erasure request under the default policy) still tells its remaining members; with no members at all
it publishes none of them.

Every member-scoped payload starts with `schema_version`, `colony` (its id), `name`, `dimension` and
`timestamp`. What each topic adds:

| Topic | Adds | Fires when |
| --- | --- | --- |
| `life_support` | `state`, `life_support_ok`, `oxygen_generators`, `population` | Life support changes state |
| `morale` | `morale`, `work_stopped`, `threshold` | Morale crosses the work-stop threshold |
| `food` | `starving`, `food_stock`, `population` | The colony starts or stops starving |
| `exports` | `buffer_full` | The export buffer fills up or is drained |
| `construction` | `blueprint`, `structures_built`, `population`, `housing_capacity` | A structure is finished |
| `stage` | `stage`, `stage_index` | The colony reaches a new growth stage |
| `neran_born` | `population` | A Neran is born |
| `needs` | `critical`, `total` | The needs list starts, or stops, wanting your help |
| `enemy` | `enemies` | Enemies enter the claim |
| `cache` | `stocked`, `full`, `filled`, `slots` | The Gratitude Cache is stocked, or a restock does not fit (reported once, until there is room again) |
| `guards` | nothing | The colony's guards engage a target (at most once a minute) |

`construction` fires once per completed structure. It raises **no alert**: a colony building itself
a habitat is good news, and good news has no business surviving in an alert store until somebody
dismisses it. The same goes for `stage` and `neran_born`.

`needs` is a crossing, not a ticker. `critical` is how many needs the colony cannot meet on its own
and `total` is the length of the whole list. The event fires when `critical` goes from none to some,
and again — with `critical: 0` — when it returns to none. A change between two non-zero counts
publishes nothing.

`enemy` carries **how many** enemies are inside the claim and never who they are. `guards` carries
nothing beyond the colony.

`colony_state` is the one **broadcast**, and it reaches every session, so it carries a colony id, a
dimension id and a life-support state — **not even the colony's name**, and certainly no owner, no
member count and no position. That is the same rule Core's threshold-event contract imposes on the
`nerocolonies:oxygen` channel, applied to the same information.

Five alerts are raised, each for every colony member:

| Alert | Severity | Raised when |
| --- | --- | --- |
| Life support has failed | critical | Life support reaches `FAILED` |
| Morale collapsed, work stopped | warning | Morale falls through the work-stop threshold |
| Enemies inside the claim | critical | The `enemy` event fires |
| The colony needs your help | warning | The `needs` event fires with `critical` above zero |
| The Gratitude Cache is full | warning | A restock does not fit; once, until there is room again |

An alert survives in Core's store until it is acknowledged, which is what makes it the right tool for
something you would want to be told about with the game closed. Every one is **rate-limited to once
every five minutes per colony per kind**, so a generator flapping between powered and unpowered
cannot turn your phone into an alarm clock, and the alert id is one per colony per kind, so a
re-raise replaces rather than stacks. Alert text is a colony name, a condition and at most a count.

Nothing published here can disturb the game: every publisher is wrapped, and a link failure is
logged and swallowed rather than thrown at a colony tick.

## Threshold events

Separately from the link module, NeroColonies publishes crossings on Core's **threshold event bus**,
which any mod can subscribe to:

| Channel | Fires when |
| --- | --- |
| `nerocolonies:food_stock` | A colony starts or stops starving |
| `nerocolonies:oxygen` | A colony's life support fails or recovers |
| `nerocolonies:morale` | A colony crosses the work-stop threshold |
| `nerocolonies:structures` | A colony finishes building a structure for itself (the value is the new total) |
| `nerocolonies:stage` | A colony reaches a new growth stage (the value is the stage index, 0 to 4) |

The scope of every one of them is a **colony id string, never a person**. They are crossings only —
a colony that has been starving for an hour publishes nothing further — which is what makes them
usable as quest-objective triggers. Switch them off with `thresholdEventsEnabled=false`.

## Privacy summary

- Snapshots are scoped to the colonies the requesting UUID owns or is an Ally or Chief of, in one
  place in the code, and are never widened for permission level. Enemies and strangers see nothing.
- People are counts. Members, allies, chiefs, enemies and Nerans are all reported as how many, and no
  section, event, action result or alert ever contains another player's UUID, name or position.
- The only role any payload spells out is **your own**.
- The only coordinates in any payload are your own colony's beacon, which is what lets an app tell
  two of your bases apart. Stations, housing and generators are counts and indexes; buildings are
  blueprint ids.
- The Gratitude Cache is reported as how full it is, never what is in it.
- Every action re-checks your role on the server, from the colony's own records, on every call.
- Broadcasts carry a place and a state, never a person.
- Alert text names a colony and a condition, never a player.
- The sandbox colony appears nowhere.
- Erasure needs no separate wiring: every read goes to the live colony index, so a player erased
  through Core's shared hook immediately reads as belonging to nothing.

See [`../PRIVACY.md`](../PRIVACY.md) and [Data storage](Data-Storage.md).
