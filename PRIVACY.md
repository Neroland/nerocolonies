# NeroColonies — Privacy & Data Protection

NeroColonies is designed to comply with POPIA and GDPR. This document describes what player data the
mod stores and how players and server admins control it.

## What is stored

By design, very little. Almost everything NeroColonies persists is about *places and things*:
colonies, claims, goods, research and colonist entities. Colonists in particular carry **nothing
player-shaped at all** — a colonist does not even know who owns its colony — so they are never in
scope for a data request.

There is exactly **one store that contains player-shaped data**, the colony index
(`nerocolonies:colonies`), and a player can appear in it three ways:

- **A colony's owner** — the player's existing Minecraft game UUID, one per colony.
- **A colony's access list** — up to 64 UUIDs of players the owner has granted access to.
- **Access-log rows** — optional and **off by default** (see below).

The colony's own goods live in a second store (`nerocolonies:stores`) which holds **item stacks
only**, keyed by colony id, and contains no player data whatsoever.

Colony **research** is stored on the colony record as a set of node ids. It is colony-local rather
than per-player, is shared by everyone with access to the colony, and is discarded when the colony
is dissolved. It is not personal data.

### The optional access log

When `accessLogEnabled` is switched on — it is `false` out of the box — a colony records rows of
exactly three things:

- the acting player's existing Minecraft game UUID;
- one action from a closed list: `found`, `open`, `rename`, `access_grant`, `access_revoke`,
  `owner_change`, `dissolve`;
- a whole-second epoch timestamp.

That is the entire schema, and its purpose is narrow: a shared colony on a multiplayer server
occasionally needs an answer to "who dissolved it?" or "who took my access away?". It is not
analytics and it is not a behaviour record. Rows are capped at 256 per colony, expire after
`accessLogRetentionDays`, and are deleted with the colony when it dissolves.

### What is deliberately not stored

**No player names. No IP addresses. No chat. No player coordinates and no position history.**

A colony record does hold its **beacon's position and dimension**, because a colony is a place in
the world and a claim has to know where it is. That is *world data* — where a block of the world is
— and not a fact about any person: it is not a record of where a player has been, it does not change
when players move, and it survives every player who ever visited. Nothing in this mod records a
player's location, and the access log deliberately files its rows under a **colony id** rather than
any coordinate.

There is no per-player research, no per-player progress and no per-player statistics of any kind.

### Nothing player-shaped leaves the server

Owner UUIDs and access lists are never sent to a client and are never returned by NeroColonies'
public query surface. That surface is **boolean-only**: callers ask "is this claimed?", "does this
player own this colony?", "may this player build here?" and receive `true` or `false`. No method on
it returns an owner UUID or a player name, and none ever will — the owner slot exists so the server
can answer "may this person do this?", not so anything can publish who lives where.

What a client is sent is colony *state*: name, morale, population, housing capacity, food stock,
life support, counts. Never membership.

## Retention

Two mechanisms, and both end in the same place:

- **NeroColonies' own sweep.** The first time the colony index is read in a server session, every
  access-log row older than `accessLogRetentionDays` (default 7) is deleted. The same pass also
  removes colony and outpost records whose beacon block is gone, which takes their rows with them.
  It never loads a chunk — a colony in an unloaded chunk is left alone, because an absent chunk is
  not evidence of anything.
- **Neroland Core's retention sweep.** Core calls the shared erasure hook below for every player
  inactive longer than its `DATA_RETENTION_DAYS` setting, reaching everything a manual erasure
  request reaches.

Both log **counts only** — never which colonies, and never which players.

## Access / export

A player can export their own NeroColonies records:

```text
/nerocolonies data export
```

The result is JSON containing exactly one player's own records and nobody else's:

- the ids of the colonies they **own**;
- the ids of the colonies they are a **member** of;
- their **own** access-log rows (colony id, action, timestamp).

**No other player's UUID appears anywhere in the result.** A colony's member list is never included:
the export tells you which colonies you are in, not who else is in them.

## Erasure

NeroColonies registers with Neroland Core's shared per-player data-erasure hook, so a single request
purges the player across all Nero mods at once:

- players, this mod only: `/nerocolonies data erase`
- players, every Nero mod: `/neroland data eraseme`
- admins: `/neroland data erase <uuid>`

For NeroColonies the request does three things:

1. strips the UUID from **every access list** that carries it;
2. deletes **every access-log row** filed against it, in every colony;
3. deals with the colonies it **owns**, per `erasureOwnedColonyPolicy`.

The hook is registered at mod construction, before any colony can exist and ahead of the store it
purges, precisely so that a later store can never be added without being covered by it.

**Nothing on the erasure path logs the player's identity** — only counts of colonies handled,
memberships removed and rows deleted.

### `erasureOwnedColonyPolicy`

| Value | Behaviour |
| --- | --- |
| `transfer_to_server` (**default**) | The colony is handed to the server. It keeps running, ownerless, and operators can administer or reassign it. |
| `dissolve` | The colony record is deleted outright. |

**Why transfer is the default.** A colony is frequently shared. Deleting a settlement that three
other players live in because one of them exercised a data-protection right would turn an erasure
request into a griefing tool — and it is not required by either POPIA or the GDPR, which ask that
the *personal data* be erased, not that the world be rearranged. Removing the owner UUID removes the
personal data; the colony that remains identifies nobody.

## Events and broadcasts

NeroColonies publishes colony food, oxygen and morale threshold crossings on Neroland Core's shared
event bus, so other mods can react to a colony in trouble. Every one of those events is **scoped to
a colony id and never to a player**. That is not incidental: the event bus is a broadcast surface
any mod can subscribe to, and a colony id identifies a place, not a person. Publishing can be
switched off entirely with `thresholdEventsEnabled`.

## Companion app (link module)

NeroColonies exposes colony information to a **Neroland companion app** through Neroland Core's link
API. NeroColonies ships no server, no HTTP and no outbound connection of its own — it only registers
what it is able to show; a separate bridge mod serves that to a paired app, and **that pairing is the
consent step**. With no bridge installed, nothing is exposed.

What an app can see is **scoped to the requesting player**: the colonies that player owns or is a
member of, and their state. It never enumerates other players, never returns another player's
records, never returns a member list and never returns a name. That scoping rule lives in exactly one
place in the code and is **never widened for permission level** — an operator's powers belong to a
live command source, not to a UUID arriving over a bridge. The module can be switched off entirely
with `linkModuleEnabled`.

Concretely:

- **Five read sections** — `colonies`, `colonists`, `jobs`, `research`, `exports` — all filtered to
  the requester's own colonies. Membership is reported as a **count**. The only coordinates in any
  payload are the requester's own colony beacons; job stations, housing and generators are counts and
  stable indexes, never positions.
- **Two actions** — `toggle_export` (which requires the player to be online, because the permission
  check is asked of a live player) and `acknowledge_alert` (which touches only the caller's own row in
  Core's alert store). Neither can reach another player's colony: "not yours" and "does not exist" are
  the same refusal, so an action cannot be used to probe for other people's bases.
- **Four owner-scoped events** (`life_support`, `morale`, `food`, `exports`) go to the colony's owner
  alone. The one **broadcast** (`colony_state`) reaches every session, so it carries a colony id, a
  dimension and a life-support state — not even the colony's name.
- **Two alerts**, raised for the colony's owner alone and rate-limited to one every five minutes per
  colony. Their text names a colony and a condition, never a player.
- A colony with **no owner** (after an erasure request under the default policy) raises no alert and
  publishes no owner-scoped event: there is nobody to tell, and inventing one would be the opposite of
  what the erasure request asked for.
- Erasure needs no separate wiring here: every read goes to the live colony index, so a player erased
  through Core's shared hook immediately reads as belonging to nothing.

## Telemetry

NeroColonies ships anonymous crash reporting via **Sentry** (EU ingest servers), matching the rest
of the Neroland ecosystem. It is **on by default and opt-out**:

- **Opt out:** set `telemetryEnabled=false` in `config/nerocolonies.properties` (takes effect on
  restart). This is a client-local setting — a server can never force it on or off.
- **NeroColonies-only:** a report is sent only if its stack trace touches
  `za.co.neroland.nerocolonies`; everything else is dropped before it leaves the game.

> **Current status: wired and completely inert.** This build carries a placeholder Sentry DSN, so
> nothing is sent anywhere and no network connection is opened, regardless of the config value. When
> a real DSN lands, everything described here applies. A build that carries no DSN (a fork, a
> stripped build) stays a hard no-op.

### What a report contains

Stack trace; NeroColonies / Minecraft / loader / OS / Java version strings; the ids and versions of
your other installed mods (capped at 300); four of this mod's own configuration values
(`maxColoniesTotal`, `claimRadius`, `colonistsPerColony`, `colonyTickIntervalTicks`); recent
non-personal in-game NeroColonies actions (breadcrumbs); anonymous stability and timing data on a 5%
sample.

### What a report never contains

No IP address, username, player UUID, world name or seed, coordinates, chat, or **any colony
ownership, access-list or access-log data**. `sendDefaultPii` is off, the machine hostname is never
attached, the Sentry user object is cleared on every event, stack frames have their absolute paths
stripped, and file paths are scrubbed of your OS account name before sending. Volume is bounded:
events are de-duplicated per session and capped at 10 per game session.
