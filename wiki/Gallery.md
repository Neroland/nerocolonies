# The gallery

`/nerocolonies gallery` builds a showcase: every building a colony can put up and one Neran of every
trade, laid out on a floor of its own around whoever ran the command. It is a creative-mode tool for
operators — for looking at the buildings before a colony has earned them, for checking a datapack's
blueprints, and for watching what Nerans do.

```text
/nerocolonies gallery            build the gallery around you
/nerocolonies gallery release    start the held demonstrations
/nerocolonies gallery clear      remove it completely
```

## Before you run it

> **Build it on flat, open ground you do not mind losing.** Everything standing above the floor
> inside the gallery's square — a hillside, trees, a building — is cut away to make room, and
> clearing the gallery later does **not** put it back.

All three commands need:

- **permission level 2** (the same level as the other operator commands);
- to be run by a **player**, not the console;
- that player to be in **creative mode**.

Building also needs all of these, and refuses with a message saying which one failed:

| Requirement | Why |
| --- | --- |
| No gallery exists yet | A server holds one at a time. Clear the old one first |
| The whole footprint is in loaded chunks | The gallery never loads a chunk. The refusal tells you how many blocks of loaded world it needs in every direction; raise your view distance and try again |
| The floor is at most 400 × 400 blocks | A datapack with enough large blueprints can exceed that, and is refused |
| There is build height for the tallest blueprint | — |
| The server is under `maxColoniesTotal` | The gallery is a colony record, and counts |
| The floor would not touch any colony's or outpost's claim | — |

On success the reply gives the size: how many buildings, how many Nerans, and the floor's dimensions.

## What is built

**The floor.** A square of smooth stone laid at the level of your feet, leaving the ground under it
as it was, with a light set into it every eight blocks so nothing spawns there. A short strip sticks
out past its south edge for one of the demonstrations. The beacon at the centre goes three blocks
north of where you stood.

**The buildings.** Every blueprint that has blocks is placed at once, complete, and recorded as a
finished building. An upgrade chain shows its top level only. Ordinary buildings stand in avenues
running west to east, sorted by stage and then category, the earliest stages nearest the centre and
all facing it. Landmarks and Metropolis buildings stand round the outer ring. Each has a floating
label: its name, its stage, category and level, and the trades it opens.

**The court**, in the middle:

- the colony **beacon**;
- a **Gratitude Cache** holding one roll of every stage's gift table, a **Needs Board** and a
  **Chief's Planning Table**, each labelled;
- **a stall for every trade** (up to 24), with one Neran of that trade holding its tool at a small
  work area: ripe wheat for the Farmer, trunks for the Forester, a wounded patient for the Medic, a
  wounded guardian wolf for the Beastkeeper, a half-built site for the Builder and the Hauler;
- **the door house**: a Neran whose home is inside and whose workplace is outside, so it uses the
  door at dawn and dusk;
- **the maze**: a workplace sealed behind glass at the end of a maze. The Neran walks the maze, fails
  to arrive, works through its recovery steps and gives up for half a minute, rather than pushing at
  the glass;
- **the claim edge**: a Neran held just outside the claim on the south strip;
- **the guard demonstration**: hostile mobs sealed in a glass pen and frozen in place, a guard
  post with a Guard, and a kennel yard with guardian wolves.

## `gallery release`

Two of the demonstrations wait until you are watching. `release` lets the held mobs go:

- the pen's hostile mobs wake up, the pen's wall and the kennel yard's gate open, and the Guard and
  the wolves deal with what comes out;
- the Neran outside the claim edge walks back in.

Stand near the one you want to watch. If any held mob is within 64 blocks of you, only the ones
that near are released; from anywhere else, everything is released at once. The reply is a count,
or `Nothing in the gallery is waiting to be released.`

## `gallery clear`

Removes the gallery: every block from the floor upwards across the whole floor, every label, mob and
Neran the gallery put there, any items lying on it, and every record kept for it.

- It finds the gallery from its own records, so it works after a restart.
- It **never loads a chunk**. If part of the floor is not loaded, the loaded part is cleared and you
  are told how many chunks remain: move closer and run it again. The gallery's records are kept until
  the last chunk is done.
- The ground one block below the floor is left as it was. **What was cut away to make room is not
  restored.**
- It removes *everything* above the floor, including anything built or stored there since. Containers
  are removed with their contents and nothing is dropped. Do not keep things in the gallery.
- Players are never moved or touched.

## The sandbox colony

Behind the gallery is one colony record, named `Gallery`, owned by the server, set to Metropolis with
every research node unlocked. Its claim is exactly the floor.

**It never runs a colony cycle.** A real beacon block stands at its centre but is not bound to the
record, and the beacon is what drives a colony. So the sandbox has none of the following:

| Not done | So |
| --- | --- |
| Housing sweep, arrivals, departures, births | The Nerans you see are the ones it was built with |
| Trade assignment, simulated gathering, job stations | Nobody changes trade and nothing accumulates in storage |
| Construction, upgrades | The half-built site in the Builder's stall stays half built |
| Food, morale, life support | Nothing decays |
| Gratitude Cache stocking | The cache holds its opening rolls and no more |
| Stage advance, progression gates, threshold events | No quest or progression trigger in another mod ever fires for it |
| Companion-app events and alerts | It reports nowhere |

The Nerans themselves still think. Walking, visible work, door use and guarding are the Neran's own
behaviour, not the colony cycle, which is what makes the stalls and the demonstrations worth
watching. Nerans are quiet unless a member of their colony is near, and the sandbox has no members,
so expect them to act a little less often than in a colony of your own.

### Counted, and not counted

- The gallery's Nerans **count** towards the server-wide `maxLoadedColonists`. A full gallery leaves
  that much less room for arrivals in real colonies until it is cleared.
- The record **counts** towards `maxColoniesTotal`.
- It counts towards nobody's `maxColoniesPerPlayer` and appears in nobody's `colony list`: it has no
  owner and no members.
- It does appear in `/nerocolonies admin list`, as `Gallery`.

## Privacy

Nothing about the player who ran the command is stored or logged. The sandbox colony has no owner, no
access list and no role lists. Replies and log lines are counts and sizes.

## See also

- [Buildings](Buildings.md) — the buildings on show
- [Nerans and professions](Nerans-and-Professions.md) — what the stalls demonstrate
- [Roles and defence](Roles-and-Defence.md) — the rule the guard demonstration follows
- [Commands](Commands.md) — the rest of the `/nerocolonies` tree
