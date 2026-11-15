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
- **Work can take a Neran away from its workplace.** A Forester walking to a tree, a Miner in its
  quarry or a Hauler on a delivery may go beyond its trade's work radius, up to 96 blocks, but never
  outside the claim.
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
| `⇄` | Hauling | Carrying goods: food to the beacon, or a tool to a Neran who lacks one |
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

Quiet mode costs the colony nothing. A Neran that is not working for real is covered by the colony
cycle (see [What a trade gathers](#what-a-trade-gathers)), and Guards look for targets whether or not
a member is near. Setting `aiActiveRadius` to `0` makes every Neran quiet all the time.

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

A trade with a tool wants every one of its Nerans to hold it. While a Hauler or a Toolsmith is at
work, tools are carried over by hand: a Hauler takes a stored tool to a Neran who lacks one, and a
Toolsmith does the same or makes the tool at its forge first. Otherwise, each colony cycle a
tradesperson without the tool is given one from colony storage if there is one, or, if the colony has
a Toolsmith, has one made from storage.

A tool a Toolsmith makes costs its real recipe: an iron hoe or sword two iron ingots, an axe or
pickaxe three, a shovel one, each with its sticks; a book three paper and a leather. A trade tool
added by a datapack that is not one of these costs one iron ingot.

A Neran with its tool holds it in its hand. One without shows `❓` at work and gathers at half the
rate. Missing tools appear on the colony's needs list, so you can hand them over at a Needs Board.

Tools are never lost: when a Neran changes trade or dies, what it carried goes back to colony
storage, and is dropped on the ground only if storage is full.

### What a trade gathers

Nerans gather for real (see [What they do](#what-they-do)): what a Farmer harvests, a Forester fells
or a Miner digs goes into colony storage. The "gathers" column is the fallback that keeps a colony
producing when its Nerans are not working for real: unloaded, quiet, waiting for a path, or with
nothing to do. On every colony cycle that falls in the daytime, while work has not stopped, each
tradesperson that has **not** put goods in storage itself in the last two colony cycles rolls once. On
a success, one of each item listed goes into colony storage. A Neran that is working for real is
skipped, so its goods are never counted twice.

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
work it does. Levels cost a little more each time: with the shipped `xp_per_level` of 100,
level 2 comes at 100 points, level 3 at 300, level 4 at 600 and level 5, the highest, at 1,000.

Each level above the first adds 15% to the trade's gathering chance. Experience belongs to the trade:
a Neran that changes trade starts again.

### What they do

When a Neran is at its workplace in working hours it does its trade's job, one piece every second or
so. A Neran starts work when a member is near enough for it to be active, and carries on until the
work phase ends. Everything it breaks goes to colony storage (and onto the ground only if storage is
full), and every block it changes follows the colony's land rules:

- inside the claim, checked for every block;
- never a block in the `nerocolonies:protected` tag, and never a block with a block entity (chests,
  furnaces, machines);
- never into a cell where somebody is standing;
- fields and quarries are never laid out on or beside the colony's own buildings or the building
  site.

| Trade | What it does |
| --- | --- |
| Farmer | Harvests ripe crops and replants them, around its workplace (the farm buildings' farmland) and in its own field. Lays out a 9 × 9 field on flat grass or dirt near the workplace, puts a water source in the middle if there is no water in it, tills it to farmland and sows it from the colony's wheat seeds, carrots, potatoes and beetroot seeds. With no seed in storage it cuts wild grass for some. Every few strokes it breeds livestock near its workplace, like a Beastkeeper |
| Forester | Walks to the nearest tree in the claim (up to 20 blocks from its workplace), fells the whole tree one log at a time, replants a sapling of the same kind from storage where the trunk stood, and gathers the saplings, sticks and apples the leaves drop |
| Miner | Digs a 5 × 5 quarry beside its workplace (from two blocks out), a layer at a time, up to 16 blocks deep. The rim is left as a spiral stair so it can walk down and back out |
| Builder | Stands at the building site and works the block being laid next. Builders near the site speed the build (see below) |
| Hauler | Carries food from colony storage to the beacon's food supply slots when the colony's rations are short, and takes stored tools to Nerans who lack them |
| Cook | Cooks raw meat, fish, potatoes and kelp from colony storage, and bakes bread from three wheat. With nothing to cook it carries food to the beacon like a Hauler |
| Toolsmith | Makes the trade tool a Neran lacks at its forge, from colony storage, and hands it over |
| Researcher | Works at the colony's research station (or its own building) and prepares the next research: for research the colony could take next but cannot yet pay for, it turns what storage holds into what the cost is missing |
| Quartermaster | Walks between the colony's crates and depots, the beacon and the Gratitude Cache pavilion, merging part stacks and closing gaps in colony storage so its goods take fewer slots |
| Guard | Walks between the colony's guard posts, and fights |
| Beastkeeper | Heals the colony's wounded guardian wolves and golems, and breeds the colony's livestock: two grown animals of a kind in the claim are fed their breeding food from colony storage, up to 12 head of a kind within 16 blocks. Tamed animals and guardians are left alone |
| Medic | Heals the nearest wounded Neran of its own colony |

Details that matter:

- **Fields.** A field is taken only where at least 60% of its cells are grass, dirt or farmland with
  nothing but air or plants above them. The water goes in only where it cannot spill: solid ground
  below and on all four sides. A Farmer keeps 16 carrots and 16 potatoes back from the Cook and the
  Hauler as seed.
- **Trees.** A tree is at most 64 connected logs standing on dirt with natural leaves on it. Logs with
  only player-placed leaves, or a mass of more than 64 logs, are someone's build and are left alone,
  as are logs inside the colony's own buildings or the building site. Keep log builds of your own out
  of the claim, give them placed leaves, or add their blocks to `nerocolonies:protected`.
- **Quarries.** A quarry is sited 5 to 16 blocks from the Miner's workplace, where at least 60% of its
  top layer is quarryable. A Miner breaks only blocks in the `nerocolonies:quarryable` tag (stone,
  deepslate, netherrack and the like, ores, dirt, sand, gravel, clay; a datapack can change it) and
  never a block with water or lava in or beside it. A finished quarry is left as it is, and the Miner
  starts another. A quarry is an open pit: mind the edge.
- **Carrying.** A Hauler or Cook takes goods out of colony storage exactly as they are and puts them
  into the beacon's supply slots or the other Neran's inventory. Anything it cannot deliver goes back
  to colony storage; nothing is lost and nothing is copied.
- **Researchers prepare, they do not choose.** They refine sand to glass, raw iron, copper and gold to
  ingots, sugar cane to paper, paper and leather to books, cobblestone to stone, stone to smooth stone
  and oak logs to charcoal, one batch at a time and only while the next research is short of it.
  Unlocking a node is still your choice at the research station.

### Labourers

An adult without a trade (the two founders, before the colony has any workplaces, or anyone the
colony has no place for yet) is a labourer. While something is being built, labourers go to the
site and lay it by hand with the Builders. A labourer sent to a job station works as the trade that
station belongs to (a farm or hydroponics station farms around it, a refinery station digs a quarry
beside it). Otherwise labourers gather wood like a Forester, anywhere in the claim. A labourer earns
no trade experience. `/nerocolonies colony workers` shows what each Neran is working as.

### The special trades

Some trades do more than gather.

| Trade | What it does on the colony cycle |
| --- | --- |
| **Builder** | Every Builder is sent to the building site. Each one standing within 8 blocks of the site's access spot adds 50% to the build speed, up to four of them. With no Builders, one idle adult stands at the site instead and adds nothing |
| **Hauler** | While at work, carries tools over by hand instead of the colony issuing them. Construction materials are still paid from colony storage directly |
| **Toolsmith** | Lets the colony make a missing tool from the tool's cost when no Toolsmith is at work |
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
