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
| Action | `toggle_export` | Route a job's output to the export buffer, or back to storage |
| Action | `acknowledge_alert` | Acknowledge one of your own NeroColonies alerts |
| Event | `life_support` | One of your colonies changed life-support state |
| Event | `morale` | One of your colonies crossed the work-stop threshold |
| Event | `food` | One of your colonies ran out of rations, or started eating again |
| Event | `exports` | One of your export buffers filled up, or was drained |
| Event | `construction` | One of your colonies finished building a structure for itself |
| Event | `colony_state` | A colony changed life-support state (**broadcast**) |
| Alert | life support has failed | Raised for the colony's owner |
| Alert | morale collapsed, work stopped | Raised for the colony's owner |

Module id `nerocolonies`, **schema version 1**. The schema version is bumped whenever the shape of a
section changes, so an app can tell what it is parsing.

## What "yours" means

Every section starts from the same rule, and it is in exactly one place in the code: a request sees
the colonies its own player UUID **owns or is on the access list of**, and nothing else.

It is never widened for permission level. An operator's powers are a property of a live command
source, not of a UUID arriving over a bridge — a link module that honoured them would turn "I am an
admin" into "my phone can read every base on the server".

Every section also accepts an optional `colony` parameter to narrow to one colony. An id you cannot
see narrows to *nothing* rather than falling back to everything, so a typo never returns more than
was asked for.

## Section: `colonies`

```json
{
  "schema_version": 1,
  "player_online": true,
  "colonies": [
    {
      "id": "1f4f7a52-9e1a-4a2f-9a0e-2a1f7c4f9d21",
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
`assigned` (colonists holding a job), `idle`, and `work_stopped`.

The numbers come from the colony record and the job board rather than from walking an entity index,
so an unloaded colony reports its last known roster instead of zero.

## Section: `jobs`

```json
{
  "schema_version": 1,
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

## Why only two actions

Everything else a companion client might want to do to a colony — founding one, dissolving one,
researching a node, spending its stock, selling its goods, changing who may use it — either moves
items, spends resources or changes who has standing in a place. Doing any of those from a phone would
let a player alter the world, and other people's position in it, without being in it. Flipping where
a job's output goes changes no quantity of anything and is reversible with the same tap.

**`set_job_priority` is deliberately absent.** The job board has no priority model in 0.1.0 — slots
are allocated first-fit in a stable order — so an action by that name would either do nothing or
invent a mechanic through the back door. It belongs with the job board's next revision.

## Events and alerts

Five of the six events are **owner-scoped**: they are published to the colony's owner, and the
bridge routes them to that player's sessions and nobody else's. A colony with no owner (after an
erasure request under the default policy) publishes none of them, because there is nobody to tell.

`construction` fires once per completed structure and carries the blueprint id, the colony's new
structure total, its population and its housing capacity. It raises **no alert**: a colony building
itself a habitat is good news, and good news has no business surviving in an alert store until
somebody dismisses it. It is a topic rather than a snapshot section deliberately — adding a field to
a section would force a schema-version bump on every client, while a new topic costs an older client
nothing at all.

`colony_state` is the one **broadcast**, and it reaches every session, so it carries a colony id, a
dimension id and a life-support state — **not even the colony's name**, and certainly no owner, no
member count and no position. That is the same rule Core's threshold-event contract imposes on the
`nerocolonies:oxygen` channel, applied to the same information.

Two alerts are raised, both for the colony's owner alone: **life support has failed** (critical) and
**morale collapsed and work has stopped** (warning). An alert survives in Core's store until it is
acknowledged, which is what makes it the right tool for something you would want to be told about
with the game closed. Both are **rate-limited to once every five minutes per colony**, so a generator
flapping between powered and unpowered cannot turn your phone into an alarm clock, and the alert id
is one per colony per kind, so a re-raise replaces rather than stacks.

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

The scope of every one of them is a **colony id string, never a person**. They are crossings only —
a colony that has been starving for an hour publishes nothing further — which is what makes them
usable as quest-objective triggers. Switch them off with `thresholdEventsEnabled=false`.

## Privacy summary

- Snapshots are scoped to the requesting UUID's own colonies, in one place in the code, and are never
  widened for permission level.
- Membership is a count. No section, event, action result or alert ever contains another player's
  UUID, name or position.
- The only coordinates in any payload are your own colony's beacon, which is what lets an app tell
  two of your bases apart. Stations, housing and generators are counts and indexes.
- Broadcasts carry a place and a state, never a person.
- Alert text names a colony and a condition, never a player.
- Erasure needs no separate wiring: every read goes to the live colony index, so a player erased
  through Core's shared hook immediately reads as belonging to nothing.

See [`../PRIVACY.md`](../PRIVACY.md) and [Data storage](Data-Storage.md).
