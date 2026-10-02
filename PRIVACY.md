# NeroColonies — Privacy & Data Protection

NeroColonies is designed to comply with POPIA and GDPR. This document describes what player data the
mod stores and how players and server admins control it.

## What is stored

By design, very little. Almost everything NeroColonies persists is about *places and things*:
colonies, claims, goods, buildings, research and Neran entities. Nerans (the colony's people; their
entity id is still `nerocolonies:colonist`) carry **nothing player-shaped at all** — a Neran does
not even know who owns its colony — so they are never in scope for a data request.

**Two stores contain player-shaped data**, and in both it is the player's existing Minecraft game
UUID and nothing else about them.

The colony index (`nerocolonies:colonies`), where a player can appear three ways:

- **A colony's owner** — one UUID per colony.
- **A colony's access list** — up to 64 UUIDs: the players who are that colony's **Allies**. Chiefs
  are on it too (see *Role lists* below).
- **Access-log rows** — optional and **off by default** (see below).

The role store (`nerocolonies:roles`), where a player can appear two ways:

- **A colony's Chief list** — up to 64 UUIDs.
- **A colony's Enemy list** — up to 64 UUIDs.

The other three stores hold no player data at all. The colony's goods live in
`nerocolonies:stores`, which holds **item stacks only**, keyed by colony id.

`nerocolonies:construction` records what each colony has built for itself, the structure it is
part way through and the buildings its owner or Chiefs have planned: blueprint ids, block
positions, rotations and counters. A plan does not record who placed it.

`nerocolonies:life` holds each colony's growth stage, its counters (births, stuck events,
retributions), the items in its Gratitude Cache and whether that cache is shared, and the one need
that has been prioritised. Like the other two it is keyed by colony id, contains **no player data
whatsoever**, and is out of scope for a data request.

Colony **research** is stored on the colony record as a set of node ids. It is colony-local rather
than per-player, is shared by everyone with access to the colony, and is discarded when the colony
is dissolved. It is not personal data.

### Role lists

A colony has an owner and three lists: **Allies**, **Chiefs** and **Enemies**.

- **What.** Each entry is a Minecraft game UUID. There is no name, no timestamp and no record of
  who added it. The Ally list is the access list on the colony record. A Chief is an Ally with
  extra rank, so a Chief is on the access list and also on the Chief list in the role store. An
  Enemy is on the Enemy list only.
- **Why.** The feature cannot work without them. The server has to answer "may this player use
  this colony's blocks, plan its buildings or manage its members?" and "may this colony's guards
  attack this player?", including for players who are offline.
- **How much.** At most 64 entries per list per colony.
- **How long.** For the life of the colony. The lists are deleted with the colony, at once, when
  its beacon is broken, by `/nerocolonies colony dissolve`, when an erasure request dissolves it
  (the `dissolve` policy below), and by the retention pass for a colony whose beacon has vanished.
- **Erasure.** A request removes the player from every list of every colony; see *Erasure* below.

Adding or removing an Ally writes the access log's existing `access_grant` or `access_revoke` row
when that log is switched on. Marking a member as an Enemy takes them off the access list first, so
it writes a revoke row too. A Chief or Enemy change writes no row of its own.

### The optional access log

When `accessLogEnabled` is switched on — it is `false` out of the box — a colony records rows of
exactly three things:

- a player's existing Minecraft game UUID: the player who acted for `found`, `open` and
  `dissolve`, and the player whose access changed for `access_grant` and `access_revoke`;
- one action from a closed list: `found`, `open`, `rename`, `access_grant`, `access_revoke`,
  `owner_change`, `dissolve` (in this release nothing writes a `rename` or `owner_change` row);
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

**Colony names.** A new colony is named `Colony N`, not after its founder. Its name after that is
whatever its owner chooses to call it; an erasure request under the transfer policy replaces it
with a neutral one.

**Neran names.** A Neran that arrives is given a display name picked at random from a fixed pool
of 64 names in the language file (`neranNamesEnabled`). The names are not derived from any player
and are not personal data. A member may rename a Neran with a name tag, as with any mob; that text
is whatever the member typed, and the game stores it as the entity's custom name.

### Processed in memory, never stored

A few things need a player's id or name for a moment and keep nothing:

- **Kill attribution (the retribution rule).** When a player dies, the server reads who killed
  them from the damage source (the attacker, or the owner of the projectile or pet that did it)
  and compares that id with the Enemy lists of the colonies the dead player owns. On a match the
  Enemy entry is removed, the colony's online members are told that an enemy has claimed
  retribution (naming nobody), and a per-colony counter goes up by one. The killer's id is used
  for that one comparison and is not stored or logged.
- **The Colony Planner's selection.** Which building a player has selected, and where they last
  previewed it, is held in server memory under the player's id so that a second use can confirm
  the first. It is dropped when the player leaves and when the server stops, and is never saved,
  logged or sent anywhere.
- **A name typed to change a role.** In the beacon's role editor a typed name is matched against
  the players who are online and then dropped: it is not stored, logged or echoed back, and the
  answer says what happened without naming anybody. A command accepts an online player's name or a
  UUID, and never looks a typed name up in the server's profile cache.
- **Enemies inside a claim.** The colony tick counts how many listed Enemies are inside the claim
  so it can raise an event. It keeps that count for the session, and never who.

### What leaves the server

**No player UUID is ever sent to a client**, and none is returned by NeroColonies' public query
surface. That surface is **boolean-only**: callers ask "is this claimed?", "does this player own
this colony?", "may this player build here?" and receive `true` or `false`. No method on it
returns an owner UUID or a player name, and none ever will — the owner slot exists so the server
can answer "may this person do this?", not so anything can publish who lives where. Commands keep
the same rule: `colony info` and the `role ... list` commands answer with counts, and a role change
answers with what happened and never to whom.

What a client is sent is colony *state*: name, morale, population, housing capacity, food stock,
life support, stage, needs and counts, plus two facts about the viewer themselves (whether they own
the colony, and their own role in it). Membership is three counts: Allies, Chiefs and Enemies.

#### The one exception: the Roles tab

A viewer whose role lets them **manage a colony's members** — its owner, a Chief, or a server
operator — is asked to decide who is an Ally, a Chief or an Enemy, and cannot do that without
seeing who already is. For that viewer, and nobody else, the snapshot of that one colony also
carries the current player **names** of its Chiefs, Allies and Enemies, each with its role, and
the beacon's Roles tab shows them.

- The names are resolved **on the server, at the moment the snapshot is built**: from the player
  list for somebody online, otherwise from the server's own profile cache (a local lookup, not a
  network one), otherwise shown as `?`. The lookup is by an id the colony already holds, never by
  text a client sent.
- They are **never stored and never logged** by this mod. The colony keeps ids; a name exists for
  as long as it takes to write the packet.
- **No UUID is on the wire**, in either direction.
- They go to that one viewer and are replaced by the next snapshot. The roster is capped at 64
  entries and each name at 32 characters. A player who stops being a member is sent an empty
  snapshot, which clears whatever they were shown.
- An Ally is sent their own role and the three counts. A player who is not a member is not sent a
  snapshot at all.

## Retention

Role lists live as long as their colony and are deleted with it (see *Role lists* above).
Everything else is bounded by two mechanisms, and both end in the same place:

- **NeroColonies' own sweep.** The first time the colony index is read in a server session, and
  then once an hour while the server runs, every access-log row older than
  `accessLogRetentionDays` (default 7) is deleted. The start-of-session pass also removes colony
  and outpost records whose beacon block is gone, which takes their access-log rows, role lists,
  goods and build records with them. It never loads a chunk — a colony in an unloaded chunk is left
  alone, because an absent chunk is not evidence of anything.
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
- the ids of the colonies they are a **member** of, as an Ally or a Chief;
- the ids of the colonies they are a **Chief** of (`chief_of_colonies`);
- the ids of the colonies that list them as an **Enemy** (`enemy_of_colonies`);
- their **own** access-log rows (colony id, action, timestamp).

**No other player's UUID appears anywhere in the result.** A colony's member list is never included:
the export tells you which colonies you are in or marked by, not who else is in them.

## Erasure

NeroColonies registers with Neroland Core's shared per-player data-erasure hook, so a single request
purges the player across all Nero mods at once:

- players, this mod only: `/nerocolonies data erase`
- players, every Nero mod: `/neroland data eraseme`
- admins: `/neroland data erase <uuid>`

For NeroColonies the request does four things:

1. strips the UUID from **every access list** that carries it, which ends every Ally and Chief
   membership;
2. removes it from **every Chief list and every Enemy list**, in every colony;
3. deletes **every access-log row** filed against it, in every colony;
4. deals with the colonies it **owns**, per `erasureOwnedColonyPolicy`.

**An Enemy listing is erased like any other.** In play, an enmity is lifted only by retribution or
by the colony's owner or a Chief. A data-erasure request overrides that rule, because the law
outranks the gameplay. Afterwards the player is a stranger to that colony: its guards no longer
treat them as an enemy, and they have no access to it.

The hook is registered at mod construction, before any colony can exist and ahead of the stores it
purges, precisely so that a later store can never be added without being covered by it.

**Nothing on the erasure path logs the player's identity** — only counts of colonies handled,
memberships removed, Chief and Enemy listings removed and rows deleted.

### `erasureOwnedColonyPolicy`

| Value | Behaviour |
| --- | --- |
| `transfer_to_server` (**default**) | The colony is handed to the server. It keeps running, ownerless, and operators can administer or reassign it. Its name is replaced with a neutral `Colony N`, because a name its owner chose can contain their name. |
| `dissolve` | The colony record is deleted outright, and with it, at once, its goods, build record, life record and role lists. |

**Why transfer is the default.** A colony is frequently shared. Deleting a settlement that three
other players live in because one of them exercised a data-protection right would turn an erasure
request into a griefing tool — and it is not required by either POPIA or the GDPR, which ask that
the *personal data* be erased, not that the world be rearranged. Removing the owner UUID removes the
personal data; the colony that remains identifies nobody.

## Events and broadcasts

NeroColonies publishes colony food, oxygen, morale, structure-count and stage threshold crossings
on Neroland Core's shared event bus, so other mods can react to a colony in trouble or one that has
grown. Every one of those events is **scoped to a colony id and never to a player**. That is not
incidental: the event bus is a broadcast surface any mod can subscribe to, and a colony id
identifies a place, not a person. Publishing can be switched off entirely with
`thresholdEventsEnabled`.

The chat messages a colony sends to its own online members (a new stage, a retribution) name the
colony and never a player.

## Companion app (link module)

NeroColonies exposes colony information to a **Neroland companion app** through Neroland Core's link
API. NeroColonies ships no server, no HTTP and no outbound connection of its own — it only registers
what it is able to show; a separate bridge mod serves that to a paired app, and **that pairing is the
consent step**. With no bridge installed, nothing is exposed.

What an app can see is **scoped to the requesting player**: the colonies that player owns or is an
Ally or a Chief of, and their state. A player a colony has marked as an Enemy sees nothing of it.
It never enumerates other players, never returns another player's records, never returns a member
list and never returns a name. That scoping rule lives in exactly one place in the code and is
**never widened for permission level** — an operator's powers belong to a live command source, not
to a UUID arriving over a bridge. The module can be switched off entirely with `linkModuleEnabled`.

Concretely, for **schema version 2** (this release):

- **Eleven read sections** — `colonies`, `colonists`, `jobs`, `research` and `exports` from version
  1, unchanged, plus `summary`, `needs`, `buildings`, `professions`, `roles` and `cache` — all
  filtered to the requester's own colonies. People are **counts**: the `roles` section carries the
  requester's **own** role and how many Allies, Chiefs and Enemies the colony has, and there is no
  roster field. The only coordinates in any payload are the requester's own colony beacons;
  buildings are listed by blueprint, never by position, job stations, housing and generators are
  counts and stable indexes, and the Gratitude Cache is reported as how full it is, never what is
  in it.
- **Four actions** — `toggle_export` (which requires the player to be online, because the
  permission check is asked of a live player), `acknowledge_alert` (which touches only the caller's
  own row in Core's alert store), `prioritise_need` (owner or Chief) and `toggle_cache_sharing`
  (owner). The last two check the caller's role against the colony's stored lists on every call,
  with no operator elevation. None can reach another player's colony: "not yours" and "does not
  exist" are the same refusal, so an action cannot be used to probe for other people's bases.
- **Eleven member-scoped events** (`life_support`, `morale`, `food`, `exports`, `construction`,
  `stage`, `neran_born`, `needs`, `enemy`, `cache`, `guards`) go to the colony's owner and the
  players on its access list, who are the people whose snapshots already show that colony. They
  carry a colony id, its name, a dimension and counts; `enemy` says how many enemies are inside the
  claim and never who. The one **broadcast** (`colony_state`) reaches every session, so it carries
  a colony id, a dimension and a life-support state — not even the colony's name.
- **Five alerts** (life support failed, work stopped, enemies inside the claim, the Gratitude Cache
  is full, the colony needs help), raised for each member of the colony and rate-limited to one
  every five minutes per colony per kind. Their text names a colony and a condition, and at most a
  count, never a player.
- A colony with **no owner** (after an erasure request under the default policy) still tells the
  players left on its access list, and nobody else. With no members it raises and publishes
  nothing: there is nobody to tell, and inventing somebody would be the opposite of what the
  erasure request asked for.
- The gallery's **sandbox colony** (`/nerocolonies gallery`) has no owner and no members, and is
  left out of every section, action, event and alert.
- Erasure needs no separate wiring here: every read goes to the live colony index and role store,
  so a player erased through Core's shared hook immediately reads as belonging to nothing.

## Telemetry

NeroColonies ships anonymous crash reporting via **Sentry** (EU ingest servers), matching the rest
of the Neroland ecosystem. It is **on by default and opt-out**:

- **Opt out:** set `telemetryEnabled=false` in `config/nerocolonies.properties` (takes effect on
  restart). This is a client-local setting — a server can never force it on or off.
- **NeroColonies-only:** a report is sent only if its stack trace touches
  `za.co.neroland.nerocolonies`; everything else is dropped before it leaves the game.

> **Current status: live.** This build carries a real Sentry DSN, so everything described here
> applies: reports are sent unless you opt out with `telemetryEnabled=false`. A build whose DSN has
> been stripped back to the placeholder (a fork, a stripped build) stays a hard no-op and opens no
> connection.

### What a report contains

Stack trace; NeroColonies / Minecraft / loader / OS / Java version strings; the ids and versions of
your other installed mods (capped at 300); four of this mod's own configuration values
(`maxColoniesTotal`, `claimRadius`, `colonistsPerColony`, `colonyTickIntervalTicks`); an anonymous,
per-launch session marker for crash-free-rate statistics. No breadcrumbs and no performance timing
are collected. Text in a report is scrubbed of home-directory paths and of anything shaped like a
UUID before it leaves the machine. The roles, stages and other additions in 0.3.0 added no field
to a report: the same four configuration values are still the only ones sent.

### What a report never contains

No IP address, username, player UUID, world name or seed, coordinates, chat, or **any colony
ownership, access-list, role-list or access-log data**. `sendDefaultPii` is off, the machine hostname is never
attached, the Sentry user object is cleared on every event, stack frames have their absolute paths
stripped, and file paths are scrubbed of your OS account name before sending. Volume is bounded:
events are de-duplicated per session and capped at 10 per game session.
