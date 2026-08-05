# Data storage

Everything NeroColonies saves about a player, where it lives, how long it stays, and how to get rid
of it. The formal statement is [`../PRIVACY.md`](../PRIVACY.md); this page is the practical version.

> **The short version:** one store holds player-shaped data at all — a colony's owner UUID and its
> access list — and it never leaves the server. A second store holds items and no player data. An
> optional access log is off by default. One erase request clears the lot, and every other Nero mod
> with it.

## The two stores

### 1. The colony index — `nerocolonies:colonies`

The one server-wide record of every colony. It is saved on the **overworld**, so it is loaded even
while a colony's own dimension is not, and it holds per colony:

| Field | Example | Why |
| --- | --- | --- |
| colony id | `9b1f…` | a random id for the *place*, not a person |
| name | `Ada's Colony` | player-chosen display text, sanitised and length-capped |
| dimension and beacon position | `nerospace:cygnus`, `120, 71, -640` | **world data**: where a block of the world is, not where a person is |
| claim radius | `48` | the claim |
| **owner UUID** | `4f3c…` | the one identity a colony carries |
| **access list** | up to 64 UUIDs | who else may act on it |
| state | morale, population, housing capacity, food stock, life support, research ids, outpost ids, timestamps | colony state; none of it is player-shaped |

The same store holds the **outpost** records (an outpost has no owner and no access list of its own
— it borrows its parent's) and the optional access-log rows described below.

**Owner UUIDs and access lists never leave the server.** Nothing in the public query surface returns
them: callers ask boolean questions and get boolean answers (see below). The client is sent a
colony's *state* — morale, population, food, counts — and never its membership.

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

### 3. The access log — optional, off by default

When `accessLogEnabled` is switched on (it is `false` out of the box), a colony records rows of
exactly three things:

- the acting player's existing Minecraft game **UUID**;
- one **action** from a closed list — `found`, `open`, `rename`, `access_grant`, `access_revoke`,
  `owner_change`, `dissolve`;
- a whole-second **timestamp**.

That is the entire schema. Never a name, never an IP, never chat, and **never coordinates** — rows
are filed under a colony id, which is as precise as the location ever gets.

Its purpose is narrow and worth stating: a shared colony on a multiplayer server occasionally needs
an answer to "who dissolved it?" or "who took my access away?". It is not analytics, it is not a
behaviour record, and it does not exist unless an operator turns it on. Rows are capped at 256 per
colony, expire after `accessLogRetentionDays` (default 7), and go with the colony when it dissolves
— they only ever existed to explain what happened to a colony that still exists.

### What is *not* stored, anywhere

No player names. No IP addresses. No chat. **No player coordinates and no position history** — the
only position in the whole mod is a colony's own beacon, which is a block in the world rather than a
fact about a person. No per-player research: research lives on the colony. No colonist owner:
colonist entities carry nothing player-shaped at all and are never in scope for an erasure request.

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

- **As a player:** `/neroland data eraseme`, or `/nerocolonies data erase` for this mod alone
- **As an operator:** `/neroland data erase <uuid>`

The hook is registered **early** — before any colony can exist and ahead of the store it purges —
because registering late is the classic way an erasure request silently misses a store.

A player can appear in the colony index three ways, and all three are dealt with:

1. **Access lists.** The UUID is stripped from every colony that carries it.
2. **Access-log rows.** Every row filed against the UUID is deleted, in every colony.
3. **Owned colonies.** Handled per `erasureOwnedColonyPolicy`.

### The owned-colony policy

| Value | Behaviour |
| --- | --- |
| `transfer_to_server` (**default**) | The colony is handed to the server. It keeps running, ownerless, and operators can administer or reassign it. |
| `dissolve` | The colony record is deleted outright. |

**Why transfer is the default.** A colony is frequently shared. Deleting a settlement that three
other players live in because one of them exercised a data-protection right would turn an erasure
request into a griefing tool — and it is not required by either POPIA or the GDPR, which ask that
the *personal data* be erased, not that the world be rearranged. Removing the owner UUID removes the
personal data; the colony that remains identifies nobody.

**Nothing on the erasure path logs who was erased** — only counts of colonies handled, memberships
removed and rows deleted.

## Retention

Two mechanisms:

- **NeroColonies' own sweep.** The first time the colony index is read in a server session, it
  deletes access-log rows older than `accessLogRetentionDays` (default 7). The same pass also
  removes colony and outpost records whose beacon block is gone, and outposts whose parent colony no
  longer exists. It never loads a chunk: a colony in an unloaded chunk is left alone entirely,
  because an absent chunk is not evidence of anything.
- **Core's retention sweep.** Core calls the shared erasure hook above for players inactive longer
  than its own `DATA_RETENTION_DAYS` setting, which reaches everything the manual request reaches.

Both log **counts only**, never which colonies or which players.

## Access (export)

```text
/nerocolonies data export
```

returns exactly one player's own colony-related records as JSON and nothing else:

- the ids of colonies they **own**;
- the ids of colonies they are a **member** of;
- their **own** access-log rows (colony id, action, timestamp).

**No other player's UUID appears anywhere in the result.** A member list is never included — the
export tells you which colonies you are in, not who else is.

The same data is readable through a companion app, scoped to the asking player automatically — see
[Link module](Link-Module.md).

## Resilience: saved-data recovery

Every saved-data read in this mod goes through a recovery guard: a corrupt or unreadable file
degrades to an **empty store**, written clean at the next save, instead of crashing the server
repeatedly on every load.

The cost of that degradation is bounded and deliberate — an unreadable colony index means colonies
have to be re-founded; an unreadable store means a colony's goods. Both are better than an
unstartable world.

## Events and broadcasts

Colony food, oxygen and morale threshold crossings are published on Core's event bus, scoped to a
**colony id** and never to a person — a colony id identifies a place. That matters because the event
bus is a broadcast surface any mod can subscribe to. Switch the publishing off with
`thresholdEventsEnabled`.

## Telemetry

Crash reporting is a separate thing entirely, contains no player data, and is opt-out. See
[Telemetry](Telemetry.md).

## See also

- [`../PRIVACY.md`](../PRIVACY.md) — the formal statement
- [Colony basics](Colony-Basics.md) — the access list in play
- [Commands](Commands.md) — `data export`, `data erase`
- [Link module](Link-Module.md) — the companion-app read path
- [Config](Config.md) — `accessLogEnabled`, `accessLogRetentionDays`, `erasureOwnedColonyPolicy`
