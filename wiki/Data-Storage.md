# Data storage

Everything NeroColonies saves about a player, where it lives, how long it stays, and how to get rid
of it. The formal statement is [`../PRIVACY.md`](../PRIVACY.md); this page is the practical version.

> **The short version:** two stores hold player-shaped data — a colony's owner UUID and its access
> list in one, its Chief and Enemy lists in the other — and neither leaves the server as an id. Three
> more stores hold items, buildings and counters and no player data. An optional access log is off by
> default. One erase request clears the lot, and every other Nero mod with it.

## The stores

All of them are saved on the **overworld**, so they are loaded even while a colony's own dimension is
not.

### 1. The colony index — `nerocolonies:colonies`

The one server-wide record of every colony. It holds per colony:

| Field | Example | Why |
| --- | --- | --- |
| colony id | `9b1f…` | a random id for the *place*, not a person |
| name | `Colony 3` | display text. New colonies get a neutral `Colony N`; a player may rename it. Sanitised and length-capped |
| dimension and beacon position | `minecraft:overworld`, `120, 71, -640` | **world data**: where a block of the world is, not where a person is |
| claim radius | `48` | the claim |
| **owner UUID** | one player UUID | the one identity a colony carries |
| **access list** | up to 64 UUIDs | the colony's members: its Allies and its Chiefs |
| state | morale, population, housing capacity, food stock, life support, research ids, outpost ids, timestamps | colony state; none of it is player-shaped |

The same store holds the **outpost** records (an outpost has no owner and no access list of its own
— it borrows its parent's) and the optional access-log rows described below.

A colony can be **ownerless**. The nil UUID in the owner slot means "the server owns this", which is
what an erasure request leaves behind under the default policy. Such a colony keeps running and can
be administered by operators; it simply has no player owner.

### 2. The colony stores — `nerocolonies:stores`

A colony's **goods**: its working stock and its export buffer, both keyed by colony id.

**Items only.** There is nothing player-shaped in this store at all, so it is out of scope for an
erasure request — erasing a player never touches a colony's goods. That is exactly what the
`transfer_to_server` ownership policy is for.

It is a separate store from the index on purpose: the colony record is a small value that is copied
on every morale tick, and two 54-slot item lists have no business being copied that often.

The consequence worth knowing: dissolving a colony drops **and forgets** its store in one operation.
Doing either without the other would duplicate the goods or silently delete them.

### 3. The construction index — `nerocolonies:construction`

What each colony has built and is building:

- a count per blueprint id;
- every **finished building**: its blueprint, the position of its corner, which way it faces and its
  level. Up to 512 per colony;
- the **building under way**: blueprint, corner, facing, how far down the build order it has got,
  banked fabrication credit, whether its materials were paid for and whether it is an upgrade;
- the **queue of hand-placed plans**: blueprint, corner and facing for each. Up to 16 per colony.

Enough to resume a half-built structure after a restart, to know where the colony's buildings stand,
and to remember what its owner and Chiefs asked for.

**Nothing player-shaped.** It is keyed by colony id and holds blueprint ids, block positions and
counters. **A plan does not record who placed it.** The store is therefore out of scope for an
erasure request in exactly the way the goods are.

Dissolving a colony forgets its record; the retention sweep drops any record whose colony has gone
by some other route. **What a colony already built stays standing** — NeroColonies never demolishes
anything it put up.

### 4. The roles store — `nerocolonies:roles`

A colony's **Chief list** and **Enemy list**: two sets of player UUIDs per colony, at most 64 in
each. See [Roles and defence](Roles-and-Defence.md) for what the lists do.

This is the second place player ids are stored, and it is kept deliberately bare:

- **Ids only.** No names, no timestamps, no reasons, no record of who made the change.
- **A Chief is also on the access list** in the colony index; this store records only the extra
  rank. An Enemy is on no other list.
- **They live exactly as long as the colony.** Every way a colony can end — the beacon broken, the
  `colony dissolve` command, the retention sweep, an erasure request under the `dissolve` policy —
  removes its role lists at the same moment.
- **Erasure removes a player from every list of every colony**, the Enemy lists included. The
  gameplay rule that only the owner, a Chief or retribution can lift an enmity does not outrank a
  data-protection request.

It is a separate store from the colony index because the colony record has no room for more fields,
and so that erasure has one obvious extra place to look.

### 5. The life store — `nerocolonies:life`

Everything else a living colony accumulates, keyed by colony id:

| Field | What it is |
| --- | --- |
| stage | Founding to Metropolis |
| counters | stuck events reported by its Nerans, births, the game time of the last birth, retributions claimed against it |
| Gratitude Cache | the items in the cache, when it was last stocked, and whether the owner has shared it |
| prioritised need | one item id, or nothing |
| sandbox flag | set only for the [gallery](Gallery.md)'s colony |

**Nothing player-shaped.** The counters are counts: the retribution counter does not say who claimed
it. The cache holds items.

When a colony is dissolved, whatever is in its Gratitude Cache drops at the beacon along with the
colony's storage (and, like storage, is discarded if that chunk is not loaded).

### 6. The access log — optional, off by default

When `accessLogEnabled` is switched on (it is `false` out of the box), a colony records rows of
exactly three things:

- the acting player's existing Minecraft game **UUID**;
- one **action** from a closed list — `found`, `open`, `rename`, `access_grant`, `access_revoke`,
  `owner_change`, `dissolve`;
- a whole-second **timestamp**.

Five of the seven are written today: `rename` and `owner_change` are reserved, and no code path
records them yet.

That is the entire schema. Never a name, never an IP, never chat, and **never coordinates** — rows
are filed under a colony id, which is as precise as the location ever gets.

Its purpose is narrow and worth stating: a shared colony on a multiplayer server occasionally needs
an answer to "who dissolved it?" or "who took my access away?". It is not analytics, it is not a
behaviour record, and it does not exist unless an operator turns it on. Rows are capped at 256 per
colony, expire after `accessLogRetentionDays` (default 7), and go with the colony when it dissolves
— they only ever existed to explain what happened to a colony that still exists.

Putting somebody on the access list writes an `access_grant` row and taking them off writes an
`access_revoke` row, whichever role change caused it. The Chief rank and the Enemy mark themselves
are never logged.

### Nerans

A Neran is an entity in the world, saved with its chunk. It carries its colony id, home and
workplace positions, job, trade and experience, whether it is a founder or a child and when it
arrived or was born, a nine-slot carry inventory, and a name if names are switched on.

The name is drawn from a fixed pool in the language file. It is not a player's name and is not
derived from one. A Neran carries no player id of any kind and is never in scope for an erasure
request.

Guardian wolves and golems carry an entity tag naming their colony id, and nothing else.

### Kept in memory only

One thing is held per player while the server runs and is never written to disk: which building a
member has selected with the Colony Planner. It is forgotten when they log out.

### What is *not* stored, anywhere

No player names. No IP addresses. No chat. **No player coordinates and no position history** — the
positions in this mod are a colony's beacon and its buildings, which are blocks in the world rather
than facts about a person. No per-player research: research lives on the colony. No record of who
contributed what to a colony's needs, who planned a building or who opened the Gratitude Cache.

## What leaves the server

**No player id is ever sent to a client or a companion app.** A client is sent a colony's *state* —
morale, population, food, stage, needs — the viewer's own role, and three counts: Allies, Chiefs and
Enemies.

There is one case where **names** are sent, and only names: when the colony's view is sent to
somebody who may manage that colony's members — its owner, a Chief, or an operator.

For that viewer the server looks up a current name for each stored id — from the online player list,
or else from the server's own local profile cache, or `?` when it has neither — and includes the
list so the beacon's Roles tab can show who is already a Chief, an Ally or an Enemy. Nobody can be
asked to manage a list they cannot see. The names are not stored by this mod, not logged, and are
replaced by the next update; somebody who stops being a member is sent an empty view, which clears
them. An Ally is never sent them.

Commands never print a name or an id of a member: see [Commands](Commands.md#privacy).

## The boolean-only query surface

Anything outside the privileged server-side path — another mod, a command's suggestion provider, the
link module, a client sync payload — asks its questions through a public API that answers
**`true`/`false`**:

- is this position claimed?
- is a colony's beacon here?
- does a colony with this id exist?
- does this player own this colony?
- may this player act on this colony?
- may this player build here?

**No method on it returns an owner UUID or a player name**, and none ever will. The owner slot exists
so the server can answer "may this person do this?", not so anything can publish who lives where.
Non-identifying colony state — name, morale, population, food — has its own accessors, because that
is what a GUI and a companion app legitimately display.

## Erasure

NeroColonies registers with Neroland Core's shared per-player erasure hook, so one request purges you
across every Nero mod at once.

- **As a player:** `/neroland data eraseme`, or `/nerocolonies data erase`. Both run the same shared
  erasure.
- **As an operator:** `/neroland data erase <uuid>`

The hook is registered **early** — before any colony can exist and ahead of the stores it purges —
because registering late is the classic way an erasure request silently misses a store.

A player can appear in NeroColonies' data four ways, and all four are dealt with:

1. **Access lists.** The UUID is stripped from every colony that carries it.
2. **Chief and Enemy lists.** The UUID is stripped from every list in the roles store.
3. **Access-log rows.** Every row filed against the UUID is deleted, in every colony.
4. **Owned colonies.** Handled per `erasureOwnedColonyPolicy`.

### The owned-colony policy

| Value | Behaviour |
| --- | --- |
| `transfer_to_server` (**default**) | The colony is handed to the server. It keeps running, ownerless, and operators can administer or reassign it. |
| `dissolve` | The colony record is deleted outright, and with it, at once, its goods, its build record, its Chief and Enemy lists and its life record. |

**Why transfer is the default.** A colony is frequently shared. Deleting a settlement that three
other players live in because one of them exercised a data-protection right would turn an erasure
request into a griefing tool — and it is not required by either POPIA or the GDPR, which ask that
the *personal data* be erased, not that the world be rearranged. Removing the owner UUID removes the
personal data, and the colony's **name is replaced with a neutral `Colony N`** at the same time,
because a name the owner chose can contain their own name. The colony that remains identifies
nobody.

Under `dissolve` the goods are forgotten rather than dropped: an erasure request can arrive while
the colony's chunk is not loaded, and there is nowhere to drop them.

Worlds from before 0.3 named new colonies `<player>'s Colony`. On the first start after updating, a
one-time pass renames every colony whose name is still exactly its owner's current profile name in
that pattern (and every server-owned colony still in it) to a neutral `Colony N`. It logs a count,
never a name.

**Nothing on the erasure path logs who was erased** — only counts of colonies handled, memberships
removed, Chief and Enemy listings removed and rows deleted.

## Retention

Two mechanisms:

- **NeroColonies' own sweep.** The first time the colony index is read in a server session, and
  then **every hour** while the server runs, it deletes access-log rows older than
  `accessLogRetentionDays` (default 7). The start-of-session pass also removes colony and outpost
  records whose beacon block is gone, and outposts whose parent colony no longer exists, and then
  drops any build record, role lists or life record whose colony no longer exists. It never loads a
  chunk: a colony in an unloaded chunk is left alone entirely, because an absent chunk is not
  evidence of anything.
- **Core's retention sweep.** Core calls the shared erasure hook above for players inactive longer
  than its own `DATA_RETENTION_DAYS` setting, which reaches everything the manual request reaches.

Both log **counts only**, never which colonies or which players.

Role lists have no expiry of their own. They are kept for as long as the colony exists, because the
feature cannot work without them, and no longer.

## Access (export)

```text
/nerocolonies data export
```

returns exactly one player's own colony-related records as JSON and nothing else:

| Field | What it lists |
| --- | --- |
| `owned_colonies` | ids of colonies they **own** |
| `member_of_colonies` | ids of colonies whose access list they are on |
| `chief_of_colonies` | ids of colonies they are a **Chief** of |
| `enemy_of_colonies` | ids of colonies that list them as an **Enemy** |
| `access_log` | their **own** access-log rows: colony id, action, timestamp |

**No other player's UUID appears anywhere in the result.** A member list is never included — the
export tells you which colonies you are in, not who else is.

A companion app shows a player their own colonies and their own role in each, scoped to the asking
player automatically — see [Link module](Link-Module.md).

## Resilience: saved-data recovery

Two layers protect the saved data:

- **One bad entry costs one entry.** Every list in every store loads entry by entry. An entry that
  cannot be read (an item from a mod that has been removed, a malformed id) is skipped and counted
  in the log, and everything else loads normally.
- **An unreadable file is kept.** If a whole file still cannot be read, the recovery guard first
  copies it to `<name>.dat.corrupt-<time>` beside the original, then starts that store empty, so the
  server starts instead of crashing on every load. An operator can repair the copy and put it back.

## Events and broadcasts

Colony food, oxygen, morale, structure and stage threshold crossings are published on Core's event
bus, scoped to a **colony id** and never to a person — a colony id identifies a place. That matters
because the event bus is a broadcast surface any mod can subscribe to. Switch the publishing off with
`thresholdEventsEnabled`.

Messages the colony sends to its own members — a stage reached, a retribution claimed — name the
colony and nobody else.

## Telemetry

Crash reporting is a separate thing entirely, contains no player data, and is opt-out. See
[Telemetry](Telemetry.md).

## See also

- [`../PRIVACY.md`](../PRIVACY.md) — the formal statement
- [Roles and defence](Roles-and-Defence.md) — what the role lists are for
- [Colony basics](Colony-Basics.md) — the colony record in play
- [Commands](Commands.md) — `data export`, `data erase`
- [Link module](Link-Module.md) — the companion-app read path
- [Config](Config.md) — `accessLogEnabled`, `accessLogRetentionDays`, `erasureOwnedColonyPolicy`
