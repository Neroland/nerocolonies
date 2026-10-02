# Commands

Everything NeroColonies exposes lives under one root, `/nerocolonies`. Most of it is reachable from
the colony beacon's own interface as well; the command tree exists for the things a keyboard does
better — managing roles for somebody who is offline, checking a datapack, and the two
data-protection commands.

The tree is built once in shared code and registered identically on Fabric, NeoForge and Forge, so
it is the same on every loader.

## At a glance

| Command | Who | What it does |
| --- | --- | --- |
| `/nerocolonies colony list` | anyone | Your colonies: id, name, dimension, morale, population |
| `/nerocolonies colony info [<colony>]` | member | One colony in detail. With no id, the one you are standing in |
| `/nerocolonies colony rename <colony> <name>` | owner | Renames a colony |
| `/nerocolonies colony role ally add <colony> <player>` | owner or Chief | Makes a player an Ally |
| `/nerocolonies colony role ally remove <colony> <player>` | owner or Chief | Removes an Ally. Removing one who is a Chief takes the owner |
| `/nerocolonies colony role chief add <colony> <player>` | owner | Makes a player a Chief |
| `/nerocolonies colony role chief remove <colony> <player>` | owner | Unmakes a Chief; they stay an Ally |
| `/nerocolonies colony role enemy add <colony> <player> [confirm]` | owner or Chief | Marks a player as an Enemy |
| `/nerocolonies colony role enemy remove <colony> <player>` | owner or Chief | Clears an Enemy mark |
| `/nerocolonies colony role ally list <colony>` | member | How many Allies the colony has |
| `/nerocolonies colony role chief list <colony>` | member | How many Chiefs |
| `/nerocolonies colony role enemy list <colony>` | member | How many Enemies |
| `/nerocolonies colony access add <colony> <player>` | owner or Chief | The older spelling of `role ally add` |
| `/nerocolonies colony access remove <colony> <player>` | owner or Chief | The older spelling of `role ally remove` |
| `/nerocolonies colony access list <colony>` | member | The older spelling of `role ally list` |
| `/nerocolonies colony plan list <colony>` | owner or Chief | What the colony can plan, and what is queued |
| `/nerocolonies colony plan cancel <colony> <n>` | owner or Chief | Removes queued plan number *n* |
| `/nerocolonies colony cache share <colony> <true or false>` | owner | Shares the Gratitude Cache with members, or stops |
| `/nerocolonies colony need prioritise <colony> [<item>]` | owner or Chief | Puts one need first; with no item, clears it |
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
| `/nerocolonies gallery` | operator, in creative | Builds the showcase gallery around you |
| `/nerocolonies gallery release` | operator, in creative | Starts the gallery's held demonstrations |
| `/nerocolonies gallery clear` | operator, in creative | Removes the gallery |

"Operator" means permission level 2 (`gamemaster`), the same level `/neroland` uses. "Member" means
the colony's owner, a Chief or an Ally. An operator may run every player-level command on any colony
and acts as its owner.

The roles themselves — what an owner, a Chief and an Ally may each do — are on
[Roles and defence](Roles-and-Defence.md).

## Arguments

**`<colony>`** is a colony id — the UUID shown by `colony list` and `admin list`. Tab-completion
offers your own colonies (or every colony, for an operator), with the colony's name as the hint, so
you rarely have to type one out.

A colony you are not a member of is answered exactly like one that does not exist: `No colony of
yours has that id.` The two answers together would let anyone probe for other people's colonies.
Only a member is ever told that their rank is the problem.

**`<player>`** is an **online player's name, or a raw UUID**. It is deliberately not an entity
selector, and a command never looks a typed name up in the server's profile cache:

- a colony's roles have to be manageable for somebody who is offline, and a selector cannot name a
  player who has left;
- turning an offline *name* into a UUID means consulting a store that correlates names with UUIDs.
  NeroColonies will not drive that lookup from text a player typed.

The practical consequence: **to act on somebody who is offline, use their UUID.** The colony
beacon's own role editor is online-only for the same reason.

**`<node>`** is a research node id such as `nerocolonies:industry/refining`. It is the last argument
of its subcommand and is read greedily, because an id contains `:` and `/`. A bare path is read as
`nerocolonies:`-namespaced, so `industry/refining` works too.

**`<item>`** is an item id such as `minecraft:oak_log`. It is also last and read greedily. It has to
name a real item.

## The player commands

### `colony list`

The colonies you own or are a member of — one line each with the id, the name, the dimension,
morale and population. Nothing else's, and no owners.

### `colony info [<colony>]`

One colony in full:

- where its beacon is and its claim radius;
- morale and whether work has stopped, population against housing capacity, food stock;
- life-support state and how many oxygen generators are feeding it;
- job slots in use, job stations, research count;
- export buffer fill and worth, and how many outposts it has;
- its **stage**, how many **structures** it has built, how many **stuck events** its Nerans have
  reported and how many **births** it has had;
- how many members have access and whether it still has an owner;
- how many **Allies**, **Chiefs** and **Enemies** it has.

The last two lines are **counts**, not rosters, and that is true for operators as well — see
[Privacy](#privacy) below.

Run with no id while standing inside a claim and it uses that colony (an outpost resolves to its
parent).

### `colony rename`

Owner only. The name is cleaned of formatting codes and control characters and cut to 32 characters.

### `colony role`

```text
/nerocolonies colony role ally|chief|enemy add <colony> <player>
/nerocolonies colony role ally|chief|enemy remove <colony> <player>
/nerocolonies colony role ally|chief|enemy list <colony>
/nerocolonies colony role enemy add <colony> <player> confirm
```

`add` and `remove` take a name or a UUID. On success the answer is `Roles updated.` followed by the
colony's three counts. It says what happened and never to whom.

`list` answers with how many players hold that role, the limit (64), and your own role. Any member
may ask.

Marking somebody who is currently a **member** as an Enemy is refused the first time, with an
explanation, and goes through when `confirm` is added. Nothing changes on the refused attempt.

A change that is not allowed answers with the reason:

| Answer | Meaning |
| --- | --- |
| `Your rank in this colony does not allow that.` | For example a Chief making a Chief, or an Ally changing anything |
| `That player owns this colony.` | Nobody changes the owner's role |
| `You cannot change your own role.` | — |
| `Server operators cannot be marked as enemies.` | The target is online and is an operator |
| `That player already has that role.` | — |
| `That player does not have that role.` | On a remove |
| `That list is full.` | 64 members, or 64 Enemies |

Putting somebody on the access list, or taking them off, is recorded in the optional access log,
which is **off by default** — see [Data storage](Data-Storage.md). The Chief rank and the Enemy mark
themselves are never logged.

### `colony access add|remove|list`

The spelling from before roles existed. It is the Ally branch under its old name: same handlers,
same rules, same answers. `list` used to be owner-only and is now open to any member, because all it
gives is a count.

### `colony plan list|cancel`

For the owner and Chiefs.

`plan list` prints what the [Chief's Planning Table](Buildings.md#the-chiefs-planning-table) prints:
up to twelve buildings the colony can plan, with their footprints, and then the queue of hand-placed
plans in order. It has to be run by a player.

`plan cancel <colony> <n>` removes queued plan number *n*, counting from 1 as `plan list` does. A
building that has already started is not a queued plan and cannot be cancelled this way.

### `colony cache share`

Owner only. `true` lets every member open the [Gratitude Cache](Gratitude-Cache.md); `false` makes
it the owner's alone again. The same switch is on the beacon's Roles tab.

### `colony need prioritise`

For the owner and Chiefs. Names the item whose gatherers should work half as fast again; with no
item, clears the priority. The item has to exist, but it does not have to be on the needs list
today, so you can line up what the next building will want. See
[Construction](Construction.md#prioritising-a-need).

### `data export`

Prints, to you and nobody else, what NeroColonies has stored about you: the ids of the colonies you
own, of the colonies you are a member of, of the colonies you are a Chief of and of the colonies
that list you as an Enemy, and your own access-log rows. No other player's UUID appears anywhere in
it.

This is the data-access half of the mod's POPIA/GDPR position. It works for the calling player only;
there is deliberately no "export somebody else" subcommand. See
[Data storage](Data-Storage.md#access-export).

### `data erase`

Erases you. This routes through Neroland Core's shared erasure hook, so **one request purges you
across every installed Nero mod**, not just this one. Core's own `/neroland data eraseme` is the
same call from the other end; either is enough.

Within NeroColonies it strips your UUID from every access list, every Chief list and every Enemy
list, deletes your access-log rows, and deals with the colonies you own according to the
`erasureOwnedColonyPolicy` config key — by default transferring them to the server so a shared
settlement keeps running, ownerless, rather than vanishing out from under the people who live in it.

## The operator commands

### `colony dissolve <colony>`

Deletes the colony record, and with it the colony's build record, role lists, stage and counters.
The colony's goods are dropped at its beacon when that chunk is loaded, and discarded when it is
not — there is nowhere to drop items in an unloaded chunk, and leaving the store behind would leak
it forever. The contents of its Gratitude Cache are handled the same way. This is the one
subcommand that announces itself to other operators; the announcement carries a colony name and
nothing else.

### `colony transfer <colony> <player>`

Sets a new owner. If the new owner was on the access list they are removed from it, because owner
and member are separate slots, and any Chief rank or Enemy mark they carried in that colony is
cleared. The previous owner is not kept on as a member.

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
line reports how many outposts exist server-wide. A [gallery](Gallery.md), if one exists, appears
here as `Gallery`.

### `reload-check`

The command to run after `/reload`. It re-reads the colony content if the datapacks have changed,
and reports how much of each kind loaded, then what was rejected. The report has this shape:

```text
Colony content: 4 job(s), 8 research node(s), 3 housing tier(s), 3 export entr(ies), 51 blueprint(s).
Professions: 12.
No validation issues.
```

The numbers in the example are how many definitions of each kind ship with the mod. The counts are
of definitions that **loaded**, so a number lower than you expect means something was dropped.

When something was rejected, the last line becomes `N validation issue(s):` followed by up to 100
lines, each marked **DROPPED** (the definition is not loaded at all) or **IGNORED** (it loaded, but
part of it was skipped), with the resource id and the reason. If the datapacks had changed since the
content was last read it also says so.

Anyone with a colony screen open is then re-sent the new content, so nobody has to close and reopen
it.

Bad content is never fatal in NeroColonies: a malformed job, a dangling prerequisite, a blueprint
naming a block that is not installed or a trade no building unlocks drops or flags the offending
entry and the rest of the pack still loads. This command is how you find out that happened without
reading the server log. What each kind of content is checked for is on
[Content format](Content-Format.md#what-is-checked).

### `purge-stale`

Runs the retention sweep immediately instead of waiting for the next server start: expired
access-log rows, colony records whose beacon block is gone, and outposts whose parent has been
dissolved. It reports those three counts. The sweep also drops build records, role lists and
stage records left behind by colonies that no longer exist.

### `gallery`, `gallery release`, `gallery clear`

A creative-mode showcase of every building and trade. All three need an operator who is a player in
creative mode. They are described on [Gallery](Gallery.md); read its warning about terrain before
building one.

## Privacy

- **No command prints an owner, a member or an enemy** — not a name and not a UUID. `admin list`
  reports places and state; `colony info` and the `role … list` commands report counts; a role
  change answers with what happened and never to whom. An operator who genuinely needs to know who
  plays where has the server's own player data, not this mod's.
- The one place membership is shown by name is the beacon's Roles tab, to somebody who may manage
  that colony's members. See [Roles and defence](Roles-and-Defence.md#at-the-beacon-the-roles-tab).
- **Output goes to the invoker alone.** Every command sends its result without the "broadcast to
  operators" flag, so results stay out of `latest.log` under the `logAdminCommands` game rule. The
  one exception is `colony dissolve`, which is destructive and therefore announced — and announces a
  colony name, which is player-chosen text about a place.
- **`data export` and `data erase` act on the calling player only.**
- An unexpected failure inside a subcommand is caught, reported politely, and sent to the opt-out
  crash reporter with the **subcommand name only** — never its arguments, which may name a player or
  a colony.

## See also

- [Roles and defence](Roles-and-Defence.md) — what the roles mean
- [Admin guide](Admin-Guide.md) — the operator's wider view
- [Gallery](Gallery.md) — the gallery commands
- [Data storage](Data-Storage.md) — what is stored, and for how long
- [Config](Config.md) — every configuration key
