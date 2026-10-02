# Nerans and professions

The people of a colony are called **Nerans**. This page covers who they are, how they spend a day,
what the symbols over their heads mean, and the twelve trades a colony hands out.

In commands and datapacks the entity id is still `nerocolonies:colonist`, and a few configuration
keys keep the old word (`colonistsPerColony`, `founderColonistCount`). Everywhere else they are
Nerans.

## What a Neran is

A Neran belongs to one colony. It has a home, a workplace, a trade and some experience in it, a
small carry inventory of nine slots for its tool, and, if names are switched on, a name. It records
whether it arrived as a founder and when, and whether it is a child.

It carries nothing about any player. A Neran does not know who owns its colony, so it is never in
scope for a data-erasure request.

Nerans are ordinary mobs with 20 health. They can be hurt, and one that dies returns whatever it was
carrying to colony storage; the colony takes a new arrival when its usual conditions allow. What a
colony never does is remove a Neran as a punishment — see
[Colony basics](Colony-Basics.md#nerans-are-never-removed-as-a-punishment).

## The day

Nerans follow the dimension's clock, not the sky, so a thunderstorm does not send everyone indoors.

| Clock time (ticks) | Phase | What a Neran does |
| --- | --- | --- |
| 0 – 6,000 | Work | Walks to its workplace and works its trade. An adult with no workplace walks home |
| 6,000 – 7,000 | Meal | Walks to the nearest granary or canteen |
| 7,000 – 11,000 | Work | As above |
| 11,000 – 12,500 | Evening | Gathers at the nearest plaza, or at the beacon if the colony has no gathering place yet |
| 12,500 – 23,500 | Night | Walks home and stays there |

Details that matter:

- **The meal is a walk, not a transaction.** What the colony eats is taken from its food stock on the
  colony cycle, wherever anyone is standing. A colony with no meal spot skips the walk; its Nerans
  wander for that hour and go back to work afterwards.
- **Work stops with morale.** Below `moraleWorkStopThreshold` nobody walks to work and nothing is
  gathered.
- **Night has a one-second margin** either side, so nobody turns round on the threshold tick.
- **Dimensions with no day/night cycle** are all working day, or all night if `fixedTimeIsDay` is
  `false`.
- Home is one of the housing blocks the colony's housing sweep found. A Neran with no home has
  nowhere to go at night and stays where it is.

## Status symbols

A Neran that is doing something worth knowing about shows a small symbol in front of its name when
you are within 12 blocks.

| Symbol | Status | Meaning |
| --- | --- | --- |
| `⚒` | Working | At its workplace, working, with its trade's tool |
| `❓` | No tool | At its workplace but without its trade's tool. It gathers at half the rate until the colony can give it one |
| `⇄` | Hauling | A Hauler carrying between the beacon and the building site |
| `⚔` | Guarding | A Guard on patrol or in a fight |
| `☺` | Social | Heading for the gathering place |
| `♨` | Hungry | Heading for its meal while the colony's food stock is empty |
| `☾` | Sleeping | It is night for this Neran |
| `✕` | No path | It gave up trying to reach where it was going and will try again in half a minute |

No symbol means nothing in particular: walking somewhere, or idle.

## Names

When `neranNamesEnabled` is on (the default), each new Neran is given a name drawn at random from a
pool of 64 in the language file. The pool is translation keys `neran.nerocolonies.name.0` to
`neran.nerocolonies.name.63`, so a resource pack can replace it. Two Nerans can draw the same name.

Switching the key off stops new Nerans being named. It does not rename anyone who already has a
name.

A member of the colony can rename a Neran with a name tag. Anybody else who tries is told they are
not a member and nothing happens.

## Children

Children are born once a colony is Growing — see
[Progression](Progression.md#arrivals-births-and-children). A child is drawn small, has no trade,
spends the working day at the gathering place or wandering, and sleeps at home at night. After
`childGrowthDays` in-game days (default 2) it grows up and the colony gives it a trade.

## Doors, paths and getting stuck

Nerans are built not to trap themselves.

- **Doors.** They open wooden doors, walk through and close them behind them, so a house with a door
  is a home.
- **Water.** They float rather than drown on a path.
- **Standing spots.** Homes, workplaces and the beacon are solid blocks. A Neran walks to a spot it
  can stand on beside the block, not into the block.
- **Staying in the claim.** One that strays past the claim edge walks back towards the beacon.
- **Giving up.** A Neran checks every couple of seconds whether its walk is getting anywhere. If it
  is not, it works through a short list: plan the path again, try a different side of the target,
  take a short hop to shake loose from a corner, and finally give up. That takes roughly ten seconds.
  It then shows `✕`, leaves that target alone for 30 seconds and does something else.
- **Rescue.** If a Neran gives up while it is inside, or right against, a building the colony
  finished or is putting up, and there is no cell beside it that it could step onto, it is moved to
  the nearest safe spot within 8 blocks, inside the claim and outside any building. That is the only
  case. A Neran stuck out in the open is never moved; it waits and tries again.
- **Buildings.** The colony never places a block into a cell somebody is standing in. Nerans in the
  way of a build are moved to the spot just outside the site, and a finished building that turns out
  to have no way in has a doorway opened for it. See [Buildings](Buildings.md#the-entrance-check).

Each give-up adds one to the colony's **stuck events** count, which `/nerocolonies colony info`
prints. It is a count and nothing more: a number that keeps climbing points at a workplace or a home
nobody can reach.

## Quiet mode

A Neran thinks at full rate only while a member of its colony — the owner or anyone on its access
list — is within `aiActiveRadius` blocks (default 64). It checks every two seconds.

With no member nearby it goes *quiet*: it may start a new walk or a new piece of work only during a
short window that comes round about twice a second. A walk already under way always finishes.

Quiet mode costs the colony nothing. The goods a trade gathers are worked out on the colony cycle and
do not depend on anyone walking anywhere, and Guards look for targets whether or not a member is
near. Setting `aiActiveRadius` to `0` makes every Neran quiet all the time.

## Trades

A colony has twelve trades. A trade is opened by buildings: each finished building opens a number of
places in every trade it unlocks, up to the trade's maximum for one colony.

| Trade | Opened by | Tool | Gathers each cycle | Places per building | Colony maximum |
| --- | --- | --- | --- | --- | --- |
| Farmer | Homestead Farm, Farm Plot, Greenhouse, Windmill, Crystal Biodome, Hydroponic Spire | `minecraft:iron_hoe` | `minecraft:wheat`, `minecraft:potato`, `minecraft:carrot`, `minecraft:wheat_seeds` (50% chance) | 2 | 8 |
| Forester | Lumber Yard | `minecraft:iron_axe` | `minecraft:oak_log`, `minecraft:stick` (40% chance) | 2 | 8 |
| Miner | Mine Head, Quarry Terrace | `minecraft:iron_pickaxe` | 2 × `minecraft:cobblestone`, `minecraft:coal`, `minecraft:raw_iron` (30% chance) | 2 | 8 |
| Builder | Founder's Lodge, Workshop Hall | `minecraft:iron_shovel` | — | 2 | 8 |
| Cook | Canteen | — | — | 2 | 8 |
| Hauler | Hauler's Depot | — | — | 2 | 8 |
| Toolsmith | Toolsmith's Forge | — | — | 2 | 8 |
| Guard | Watch Post, Barracks, Watchtower, Watchtower II, Watchtower III, Colony Citadel | `minecraft:iron_sword` | — | 3 | 8 |
| Beastkeeper | Kennel, Golem Forge | — | `minecraft:bone` (5% chance) | 2 | 8 |
| Researcher | Research Cabin, Observatory, Grand Archive | `minecraft:book` | `minecraft:redstone`, `minecraft:glass` (10% chance) | 2 | 8 |
| Quartermaster | Gratitude Cache Pavilion | — | — | 1 | 2 |
| Medic | Med Bay | — | — | 2 | 8 |

The table is in the order places are filled. Every colony cycle:

1. an adult who has a trade keeps it, as long as a place for it still exists;
2. an adult without a trade takes the first trade in the table that has a place open;
3. children have no trade.

Two things follow from that. A small colony fills its Farmer, Forester, Miner and Builder places
before anything else, so the trades lower down need more adults than those four have places for. And
places come from the colony's record of finished buildings, not from the blocks: breaking part of a
building does not close its trade.

A Neran that does lose its trade, because a datapack change removed the place, hands its tool back
to colony storage and starts again from no experience in whatever it takes next.

A Neran's **workplace** is the spot in front of the nearest building that opens its trade.

Some trades may also staff a job station, which then becomes the workplace instead:

| Trade | Job stations it may staff |
| --- | --- |
| Farmer | Farm Station, Hydroponics Station |
| Miner | Refinery Station |
| Builder | Fabricator Station |
| Toolsmith | Fabricator Station |

An adult with no trade may staff any station. See [Jobs & research](Jobs-and-Research.md#staffing).

### Tools

A trade with a tool wants every one of its Nerans to hold it. Each colony cycle, a tradesperson
without the tool is given one from colony storage if there is one. If there is none and the colony
has a Toolsmith, one iron ingot from storage is turned into the tool instead.

A Neran with its tool holds it in its hand. One without shows `❓` at work and gathers at half the
rate. Missing tools appear on the colony's needs list, so you can hand them over at a Needs Board.

Tools are never lost: when a Neran changes trade or dies, what it carried goes back to colony
storage, and is dropped on the ground only if storage is full.

### What a trade gathers

The "gathers" column is the part of a trade that keeps going whether or not you are watching. On
every colony cycle that falls in the daytime, while work has not stopped, each tradesperson rolls
once. On a success, one of each item listed goes into colony storage.

```text
chance = the trade's chance
       × the colony's morale multiplier
       × 1.0 with the tool, 0.5 without
       × (1 + 0.15 for each level above the first)
       × 1.5 if the trade gathers the colony's prioritised need
```

A chance above 100% means whole extra batches. If storage has no room, that batch is simply not
produced.

Three things to know:

- **Gathered food goes to colony storage, not to the food stock.** Wheat in storage feeds nobody.
  A Cook turns it into rations, or you can move it to the beacon's supply slots yourself.
- **The colony has to be loaded.** Trades gather on the colony cycle, which runs only while the
  beacon's chunk is loaded, and offline catch-up does not include them.
- This is how a colony slowly fills its own [needs list](Construction.md#the-needs-list). The times
  shown there are worked out from these rates.

### Levels

A Neran earns one point of experience for each daytime cycle it works and one more for each piece of
visible work it does. Levels cost a little more each time: with the shipped `xp_per_level` of 100,
level 2 comes at 100 points, level 3 at 300, level 4 at 600 and level 5, the highest, at 1,000.

Each level above the first adds 15% to the trade's gathering chance. Experience belongs to the trade:
a Neran that changes trade starts again.

### What you see them do

When a Neran is at its workplace in working hours, with a member near enough for it to be active, it
also does its trade's visible work. Blocks it changes are always inside the claim and within the
trade's work radius of its workplace (10 blocks for every shipped trade), and what it breaks goes to
colony storage.

| Trade | Visible work |
| --- | --- |
| Farmer | Harvests ripe crops and replants them. Sows wheat on empty farmland when colony storage holds seeds |
| Forester | Fells the nearest log, and replants a sapling from colony storage where the trunk stood on dirt |
| Hauler | Walks back and forth between the beacon and the building site |
| Guard | Walks between the colony's guard posts, and fights |
| Beastkeeper | Heals the colony's wounded guardian wolves and golems |
| Medic | Heals the nearest wounded Neran of its own colony |
| Miner, Builder, Cook, Toolsmith, Researcher, Quartermaster | Stand at their post |

> **A Forester fells any log it can reach.** Every log within 10 blocks of its workplace and inside
> the claim counts, up to 12 blocks above it, unless the block is in the `nerocolonies:protected`
> tag or is part of a building the colony itself finished. Keep log builds of your own out of reach
> of the Lumber Yard, or add the blocks to the tag.

### The special trades

Some trades do more than gather.

| Trade | What it does on the colony cycle |
| --- | --- |
| **Builder** | Every Builder is sent to the building site. Each one standing within 8 blocks of the site's access spot adds 50% to the build speed, up to four of them. With no Builders, one idle adult stands at the site instead and adds nothing |
| **Hauler** | Nothing beyond the walk. Materials are paid from colony storage directly; a Hauler moves no goods |
| **Toolsmith** | Lets the colony make a missing tool from one iron ingot |
| **Cook** | In the daytime, each Cook takes one item of colony food from colony storage and adds 3 rations to the food stock |
| **Medic** | In the daytime, each Medic lifts morale by 0.5, to a total of at most 2.0 per cycle |
| **Quartermaster** | Stocks the [Gratitude Cache](Gratitude-Cache.md) |
| **Beastkeeper** | Replaces missing guardian animals: a wolf for 2 bones, an iron golem for 4 iron blocks, one animal per cycle, taken from colony storage |
| **Guard** | The only trade that fights. See [Roles and defence](Roles-and-Defence.md#guards-and-guardians) |

"Colony food" is anything in the `nerocolonies:colony_food` item tag — see
[Colony basics](Colony-Basics.md#feeding-a-colony).

Trades are datapack content. The file format is on
[Content format](Content-Format.md#professions).

## See also

- [Progression](Progression.md) — stages, births and the growth curve
- [Buildings](Buildings.md) — which building opens which trade
- [Roles and defence](Roles-and-Defence.md) — Guards, guardians and who they attack
- [Jobs & research](Jobs-and-Research.md) — job stations and how they are staffed
- [Config](Config.md) — `aiActiveRadius`, `fixedTimeIsDay`, `neranNamesEnabled`, `childGrowthDays`
