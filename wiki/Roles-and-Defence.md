# Roles and defence

Who may do what in a colony, how to change that, what marking somebody as an Enemy does, and what
the colony's Guards, wolves and golems will and will not attack.

## The four roles

| Role | Who | How many |
| --- | --- | --- |
| **Owner** | The player who placed the beacon | One. A colony can also be ownerless — see [Data storage](Data-Storage.md) |
| **Chief** | A member the owner has promoted | Up to 64 |
| **Ally** | A member | Allies and Chiefs together, up to 64 |
| **Enemy** | Somebody the colony has marked | Up to 64 |

Everybody else is a **stranger**. Owner, Chief and Ally are the colony's *members*; an Enemy is never
a member.

Players who had access to a colony before roles existed are Allies now, with exactly the rights they
had.

**Operators** (permission level 2 or better) are treated as the owner of every colony, as they
always were.

## Who may do what

| | Owner | Chief | Ally | Stranger or Enemy |
| --- | --- | --- | --- | --- |
| Open the beacon, depots, job stations, the research station, the oxygen generator and outpost beacons | yes | yes | yes | no |
| Read the Needs Board and hand items to it | yes | yes | yes | no |
| Rename a Neran with a name tag | yes | yes | yes | no |
| Unlock research, sell exports, switch a station's output between storage and exports | yes | yes | yes | no |
| Open the Gratitude Cache | yes | only if shared | only if shared | no |
| Plan buildings with the Colony Planner; read the Planning Table; cancel a plan | yes | yes | no | no |
| Prioritise a need | yes | yes | no | no |
| Add and remove Allies; mark and clear Enemies | yes | yes | no | no |
| Remove an Ally who is a Chief; mark a Chief as an Enemy | yes | no | no | no |
| Make and unmake Chiefs | yes | no | no | no |
| Share the Gratitude Cache; rename the colony | yes | no | no | no |
| Dissolve the colony by sneak-breaking the beacon | yes | no | no | no |

A member who tries something above their rank is told `Your rank in this colony does not allow
that.` A stranger is told `You are not a member of this colony.` Either way nothing happens.

Every one of these is decided on the server, from the colony's own record. The same table applies
whether the request comes from a block, the beacon's interface, a command or a companion app.

## Changing roles

Two front ends, one set of rules.

### The rules

- Nobody can change the owner's role, and nobody can change their own.
- **Allies** are added and removed by the owner or a Chief.
- **Chiefs** are made and unmade by the owner alone. Making somebody a Chief gives them access first
  if they had none; unmaking one leaves them an Ally. A Chief cannot remove another Chief.
- **Enemies** are marked and cleared by the owner or a Chief.
- Adding somebody as an Ally or a Chief clears any Enemy mark on them.
- A list that is full refuses the change.

### By command

```text
/nerocolonies colony role ally   add|remove <colony> <player>
/nerocolonies colony role chief  add|remove <colony> <player>
/nerocolonies colony role enemy  add <colony> <player> [confirm]
/nerocolonies colony role enemy  remove <colony> <player>
/nerocolonies colony role ally|chief|enemy list <colony>
```

`<player>` is an online player's name or a raw UUID, so the commands work for somebody who is
offline. `list` answers with a count, the list's limit and your own role; it never prints who. The
older `/nerocolonies colony access add|remove|list` still works and means `role ally`. Full details
are on [Commands](Commands.md#colony-role).

### At the beacon: the Roles tab

What the tab shows depends on who is looking.

| Viewer | Sees |
| --- | --- |
| Any member | Their own role, and three counts: Allies, Chiefs, Enemies |
| The owner, a Chief, or an operator | The same, plus the editor and the **names** of the colony's Chiefs, Allies and Enemies, each tagged `[C]`, `[A]` or `[E]` |
| The owner | Also the `Cache: private` / `Cache: shared` switch for the [Gratitude Cache](Gratitude-Cache.md) |

An Ally is never sent anybody's name. Names are shown only to somebody who may manage the list,
because nobody can be asked to decide who belongs without seeing who already does.

To change a role, type a player's name in the field (or click one in the list), press the role
button until it reads Ally, Chief or Enemy, and press **Add** or **Remove**. The beacon's editor matches the name
against **online players only**; for somebody offline, use the command with their UUID. The answer
says what happened and does not repeat the name.

How the names get there: the colony stores player ids, never names. When the server builds the view
for somebody who may manage members it looks up each stored id — the online player list first, then
the server's own profile cache — and sends the names to that one viewer. A player the server cannot
name appears as `?`. The names are not saved or logged by the mod, and no player id is ever sent to a
client. See [Data storage](Data-Storage.md#4-the-roles-store--nerocoloniesroles).

## Enemies

### Who can be marked

Anybody except the colony's owner, yourself, and a server operator. An operator is recognised only
while online; marking one who is offline is accepted, and makes no difference to them, because
guards never attack an operator and an operator can always act on the colony.

### What marking does

- **Guards and guardian animals attack them** inside the claim and a margin around it, as long as
  `guardsAttackEnemyPlayers` is on. See the next section.
- **They lose any membership.** An Enemy cannot be an Ally or a Chief. Marking a member takes them
  off the access list and out of the Chiefs first.
- **The colony notices them.** When a player the guards would attack walks into the claim, the
  colony's companion sessions get an alert that enemies are inside. It carries a count, never a
  name.

An Enemy who was not a member loses nothing else: a stranger could not open the colony's blocks to
begin with.

### Marking a member needs confirming

Marking somebody who is currently a member is a demotion, so it has to be asked for twice. The first
attempt changes nothing and answers:

> That player is a member of this colony, and marking them as an enemy removes their access. To go
> ahead, add "confirm" to the command, or hold Shift and press Add at the beacon.

So either run `/nerocolonies colony role enemy add <colony> <player> confirm`, or hold Shift while
pressing Add. Marking a **Chief** additionally takes the owner; a Chief cannot do it.

### Lifting it

An Enemy mark ends in one of four ways:

1. the owner or a Chief clears it (`role enemy remove`, or Remove at the beacon);
2. the owner or a Chief adds the player as an Ally, or the owner makes them a Chief;
3. the Enemy claims **retribution** (below);
4. the player asks for their data to be erased, which removes them from every list of every colony.

## Guards and guardians

A colony defends itself with three things, and all three obey one rule.

| Defender | Where it comes from |
| --- | --- |
| **Guards** | Nerans with the Guard trade, opened by watch posts, barracks, watchtowers and the citadel. They carry an iron sword when the colony can give them one, and patrol between the guard posts |
| **Guardian wolves** | Two per Kennel, raised by a Beastkeeper for 2 bones each from colony storage |
| **Guardian iron golems** | One per Golem Forge, raised by a Beastkeeper for 4 iron blocks from colony storage |

A Beastkeeper raises at most one animal per colony cycle and replaces any that are lost, as long as
the materials are in storage. Without a Beastkeeper the animals a colony already has stay, but
missing ones are not replaced. Beastkeepers also heal them.

### What they attack

Inside the claim, plus `guardPursuitMargin` blocks beyond its edge (default 16):

| Target | Attacked? |
| --- | --- |
| Hostile mobs | **Yes**, except creepers |
| Creepers | No — one going off would blow a hole in the colony |
| A player on this colony's Enemy list | **Yes**, if `guardsAttackEnemyPlayers` is on (the default) and the player is not in creative or spectator mode |
| The colony's owner, any operator, any player who is not on the Enemy list | Never |
| Nerans, of this colony or any other | Never |
| Another colony's guardian animals | Never |
| Animals and other passive mobs | Never |

A Guard picks the nearest valid target within 16 blocks of itself. Guards are the only Nerans that
fight; every other trade never attacks anything.

Guardian wolves are tame but have no owner, so they do not hunt sheep, and the colony points them at
targets itself, once per colony cycle. A guardian that is chasing something it may not attack is
called off, and one that has strayed past the margin is walked home. Hurting a guardian golem by
accident does not turn it on you.

Because a chase ends at the margin, an Enemy who leaves the claim and keeps going is not followed
across the map.

### Switching it off for players

Set `guardsAttackEnemyPlayers` to `false` and Guards and guardians fight hostile mobs only. The Enemy
list still exists and marking still removes a member's access, but the colony never raises a hand to
a player, and the enemies-inside alert stays silent as well.

## Retribution

An Enemy has one way to clear their own name.

**When a player on a colony's Enemy list kills that colony's owner, they are taken off that colony's
Enemy list.** It counts whether the killing blow was dealt directly, by a projectile they fired, or
by an animal they own. If the victim owns several colonies that list the killer, each of them clears
the mark.

The colony's members who are online are told:

> The feud with `<colony>` is settled: an enemy has had their revenge and is an enemy no longer.

The message names the colony and nobody else. The colony adds one to a count of retributions. Who
the killer was is used for that one comparison and is not stored or logged.

Only the owner's death counts — not a Chief's, not an Ally's — and only that colony's list changes.

## Servers with PvP switched off

NeroColonies does not read the server's PvP setting. What follows from the code:

- **Guards and guardians are mobs, not players.** Whether they attack a listed Enemy is decided by
  `guardsAttackEnemyPlayers` alone. On a server that does not want players harmed over a colony
  dispute, set it to `false`.
- **Retribution needs an Enemy to kill the owner.** Where the server stops one player from killing
  another, that cannot happen, so an Enemy mark is lifted only by the owner or a Chief, or by an
  erasure request.
- Everything else about roles — access, planning rights, the cache — has nothing to do with combat
  and works the same.

## Privacy

A colony's role lists are player ids, and that is all they are: no names, no timestamps, no reasons.
They exist for as long as the colony does, go with it when it is dissolved, and an erasure request
removes the player from every one of them, the Enemy lists included. Commands answer with counts.
Guardian animals carry a tag naming their colony and nothing about any player. The full account is on
[Data storage](Data-Storage.md).

## Configuration

| Key | Default | Effect |
| --- | --- | --- |
| `guardsAttackEnemyPlayers` | true | Whether Guards and guardian animals attack players on the Enemy list |
| `guardPursuitMargin` | 16 | Blocks beyond the claim edge a target is still pursued |

## See also

- [Commands](Commands.md) — the `colony role` commands in full
- [Nerans and professions](Nerans-and-Professions.md) — the Guard and Beastkeeper trades
- [Buildings](Buildings.md) — the watch post, kennel, barracks, watchtower and golem forge
- [Gratitude cache](Gratitude-Cache.md) — the one thing only an owner may open
- [Data storage](Data-Storage.md) — what the role lists hold, and erasure
