# Commands

Everything NeroColonies exposes lives under one root, `/nerocolonies`. Most of it is reachable from
the colony beacon's own interface as well; the command tree exists for the things a keyboard does
better — managing an access list for somebody who is offline, checking a datapack, and the two
data-protection commands.

The tree is built once in shared code and registered identically on Fabric, NeoForge and Forge, so
it is the same on every loader.

## At a glance

| Command | Who | What it does |
| --- | --- | --- |
| `/nerocolonies colony list` | anyone | Your colonies: id, name, dimension, morale, population |
| `/nerocolonies colony info [<colony>]` | member | One colony in detail. With no id, the one you are standing in |
| `/nerocolonies colony rename <colony> <name>` | owner | Renames a colony |
| `/nerocolonies colony access list <colony>` | owner | How many members the colony has |
| `/nerocolonies colony access add <colony> <player>` | owner | Grants access |
| `/nerocolonies colony access remove <colony> <player>` | owner | Revokes access |
| `/nerocolonies data export` | anyone | Prints your own stored records as JSON |
| `/nerocolonies data erase` | anyone | Erases you across every installed Nero mod |
| `/nerocolonies colony dissolve <colony>` | operator | Deletes a colony record and drops its goods |
| `/nerocolonies colony transfer <colony> <player>` | operator | Hands a colony to another player |
| `/nerocolonies colony tp <colony>` | operator | Teleports you to the colony's beacon |
| `/nerocolonies colony set-morale <colony> <value>` | operator | Nudges morale (0–100) |
| `/nerocolonies colony grant-research <colony> <node>` | operator | Unlocks a node with no cost |
| `/nerocolonies colony sell <colony>` | operator | Sells the colony's export buffer |
| `/nerocolonies admin list [<dimension>]` | operator | Every colony on the server, or in one dimension |
| `/nerocolonies reload-check` | operator | The datapack validation report |
| `/nerocolonies purge-stale` | operator | Runs the retention sweep now |

"Operator" means permission level 2 (`gamemaster`), the same level `/neroland` uses. "Member" means
the colony's owner, anybody on its access list, or an operator. "Owner" excludes access-list
members: a member may *use* a colony, not decide who else may.

## Arguments

**`<colony>`** is a colony id — the UUID shown by `colony list` and `admin list`. Tab-completion
offers your own colonies (or every colony, for an operator), with the colony's name as the hint, so
you rarely have to type one out.

**`<player>`** is an **online player's name, or a raw UUID**. It is deliberately not an entity
selector and deliberately never a profile-cache lookup:

- an access list has to be manageable for somebody who is offline, and a selector cannot name a
  player who has left;
- turning an offline *name* into a UUID means consulting the server's profile cache, which is a
  store that correlates names with UUIDs. NeroColonies will not drive that lookup from user input.

The practical consequence: **to add somebody who is offline, use their UUID.** The colony beacon's
own access editor is online-only for the same reason.

**`<node>`** is a research node id such as `nerocolonies:industry/refining`. It is the last argument
of its subcommand and is read greedily, because an id contains `:` and `/`. A bare path is read as
`nerocolonies:`-namespaced, so `industry/refining` works too.

## The player commands

### `colony list`

The colonies you own or are on the access list of — one line each with the id, the name, the
dimension, morale and population. Nothing else's, and no owners.

### `colony info [<colony>]`

One colony in full: where its beacon is, its claim radius, morale and whether work has stopped,
population against housing capacity, food stock, life-support state and how many oxygen generators
are feeding it, job slots in use, research count, export buffer fill and worth, and how many
outposts it has.

The last line reports how many members the colony has and whether it still has an owner. It reports
a **count**, not a roster, and that is true for operators as well — see [Privacy](#privacy) below.

Run with no id while standing inside a claim and it uses that colony (an outpost resolves to its
parent).

### `colony access add|remove|list`

Owner-only. `add` and `remove` take a name or a UUID; `list` answers with a number.

Every grant and revoke is recorded in the optional access log, which is **off by default** — see
[Data storage](Data-Storage.md).

### `data export`

Prints, to you and nobody else, exactly what NeroColonies has stored about you: the ids of the
colonies you own, the ids of the colonies you are a member of, and your own access-log rows. No
other player's UUID appears anywhere in it.

This is the data-access half of the mod's POPIA/GDPR position. It works for the calling player only;
there is deliberately no "export somebody else" subcommand.

### `data erase`

Erases you. This routes through Neroland Core's shared erasure hook, so **one request purges you
across every installed Nero mod**, not just this one. Core's own `/neroland data eraseme` is the
same call from the other end; either is enough.

Within NeroColonies it strips your UUID from every access list, deletes your access-log rows, and
deals with the colonies you own according to the `erasureOwnedColonyPolicy` config key — by default
transferring them to the server so a shared settlement keeps running, ownerless, rather than
vanishing out from under the people who live in it.

## The operator commands

### `colony dissolve <colony>`

Deletes the colony record. The colony's goods are dropped at its beacon when that chunk is loaded,
and discarded when it is not — there is nowhere to drop items in an unloaded chunk, and leaving the
store behind would leak it forever. This is the one subcommand that announces itself to other
operators; the announcement carries a colony name and nothing else.

### `colony transfer <colony> <player>`

Sets a new owner. If the new owner was on the access list they are removed from it, because owner
and member are separate slots and holding both would double-count them.

### `colony set-morale <colony> <value>`

A nudge, not a pin. Morale is recomputed toward its target on the next colony tick, so this is
useful for testing a threshold rather than for holding a colony happy.

### `colony grant-research <colony> <node>`

Unlocks a node with no cost, no power and no prerequisite check. It still refuses a node the colony
already has, and still refuses an id that is not loaded.

### `colony sell <colony>`

Runs the same sale the beacon's Sell button does. It refuses, without taking anything, when the
colony has no owner to pay or when no economy mod is installed to pay with.

### `admin list [<dimension>]`

Every colony record, or every record in one dimension: id, name, dimension, morale, population, and
a marker when life support has failed. **No owners.** The output is capped at 100 rows, and the last
line reports how many outposts exist server-wide.

### `reload-check`

The command to run after `/reload`. It re-reads the colony content if the datapacks have changed,
reports how many jobs, research nodes, housing tiers and export entries survived, and lists
everything that was dropped or ignored, with the reason. Anyone with a colony screen open is then
re-sent the new content, so nobody has to close and reopen it.

Bad content is never fatal in NeroColonies: a malformed job, a dangling prerequisite or a research
cycle drops the offending entry and the rest of the pack still loads. This command is how you find
out that happened without reading the server log. See [Content format](Content-Format.md).

### `purge-stale`

Runs the retention sweep immediately instead of waiting for the next server start: expired
access-log rows, colony records whose beacon block is gone, and outposts whose parent has been
dissolved. It reports three counts.

## Privacy

- **No command prints an owner or a member.** `admin list` reports places and state; `colony access
  list` and `colony info` report a count. A colony's membership never leaves the server, whoever is
  asking — an operator who genuinely needs to know who plays where has the server's own player data,
  not this mod's.
- **Output goes to the invoker alone.** Every command sends its result without the "broadcast to
  operators" flag, so results stay out of `latest.log` under the `logAdminCommands` game rule. The
  one exception is `colony dissolve`, which is destructive and therefore announced — and announces a
  colony name, which is player-chosen text about a place.
- **`data export` and `data erase` act on the calling player only.**
- An unexpected failure inside a subcommand is caught, reported politely, and sent to the opt-out
  crash reporter with the **subcommand name only** — never its arguments, which may name a player or
  a colony.

## See also

- [Admin guide](Admin-Guide.md) — the operator's wider view
- [Data storage](Data-Storage.md) — what is stored, and for how long
- [Config](Config.md) — every configuration key
