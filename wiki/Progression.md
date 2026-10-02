# Progression

A colony grows through five stages. This page says exactly what moves it from one to the next, what
each stage opens up, how fast a colony of a given size builds and breeds, and what to check when
growth has stopped.

## The five stages

| Stage | Reached when | What changes |
| --- | --- | --- |
| **Founding** | The beacon is placed | Only the four Starter Works may be built, and only from materials you supply |
| **Settled** | Every Starter Work has been built once | The Settled buildings open up, and the colony may fabricate from scrap when it has no materials |
| **Growing** | `stageGrowingPopulation` Nerans (default 6) **and** `stageGrowingStructures` finished structures (default 6) | Growing buildings; children are born; the claim starts to widen; the structure cap doubles |
| **Thriving** | `stageThrivingPopulation` (default 16) **and** `stageThrivingStructures` (default 14) | Thriving buildings; the structure cap rises again |
| **Metropolis** | `stageMetropolisPopulation` (default 32) **and** `stageMetropolisStructures` (default 24) | The showpiece buildings |

The rules behind the table:

- **Both numbers are needed at once.** Sixteen Nerans in ten structures is still Growing.
- **Population** is everybody on the roster, children included. **Structures** is every building the
  colony has finished, whether it chose the building itself or you placed it with the
  [Colony Planner](Buildings.md#the-colony-planner). An upgrade replaces its building rather than
  adding one, so it does not raise the count.
- **Stages only go up.** A colony that later loses people or housing keeps the stage it earned.
- The check runs once per colony cycle. A colony that meets a later threshold goes straight there;
  it does not have to spend a cycle in each stage on the way.
- All six thresholds are configuration keys — see [Config](Config.md#stages-growth-and-breeding).

When a colony advances, every member who is online is told `<colony> has grown: it is now <stage>.`
The crossing is also published on Core's threshold event bus (channel `nerocolonies:stage`, scoped to
the colony id, value 0–4) unless `thresholdEventsEnabled` is off, and sent to companion apps
([Link module](Link-Module.md)).

The beacon's **Needs** tab shows the current stage and the next milestone: `next: the Starter Works`
while founding, then `next: 6 Nerans, 6 buildings` and so on. `/nerocolonies colony info` prints the
stage and the structure count.

## The Starter Works

Four buildings stand between Founding and Settled. They are the blueprints whose `stage` is
`founding`, built in this order:

| Starter Work | Blocks to place | Materials the colony needs from you |
| --- | --- | --- |
| Founder's Lodge | 190 | 52 × `#minecraft:planks`, 23 × `minecraft:cobblestone`, 6 × `#minecraft:logs`, 3 × `minecraft:glass`, 2 × `nerocolonies:habitat_pod`, 1 × `minecraft:crafting_table`, 1 × `minecraft:lantern`, 1 × `minecraft:oak_door` |
| Homestead Farm | 114 | 19 × `minecraft:dirt`, 15 × `minecraft:cobblestone`, 13 × `#minecraft:planks`, 2 × `minecraft:lantern`, 1 × `#minecraft:logs`, 1 × `nerocolonies:farm_station` |
| Lumber Yard | 122 | 23 × `minecraft:dirt`, 18 × `#minecraft:logs`, 14 × `#minecraft:planks`, 3 × `minecraft:oak_sapling`, 1 × `minecraft:lantern` |
| Mine Head | 104 | 29 × `minecraft:cobblestone`, 14 × `#minecraft:logs`, 3 × `minecraft:lantern`, 2 × `minecraft:ladder`, 2 × `minecraft:rail`, 1 × `minecraft:gravel` |

An entry starting with `#` is an item tag: any planks, any logs.

**Why they need your materials.** From Settled onwards a colony with nothing in store still builds,
slowly, by fabricating from scrap. While it is founding it does not: the Starter Works go up from
supplied materials or not at all, so the first four buildings are your contribution to the colony.
After that it can carry itself.

How it plays:

1. The two founders arrive with the beacon. The colony picks a site for the first Starter Work and
   waits. The beacon's Colony tab reads `Waiting for the Starter Works' materials. See the Needs
   tab.`
2. Put the listed materials into **colony storage**: through a Colony Depot inside the claim, by
   using a Needs Board while holding the item, or with a hopper or pipe into the beacon. The beacon's
   six supply slots are for food only.
3. The moment the whole set for the current building is in storage it is taken, once, and the
   building goes up at full speed. Then the next Starter Work begins.

A part-delivered set is not taken: the materials stay in storage until the set is complete. The
Needs tab and the Needs Board both list what is still missing. A Starter Works material is always
marked `needs your help`, with no time estimate, because the colony will not gather it for itself.

You can choose where a Starter Work goes by placing it yourself with the Colony Planner. It still
needs its materials.

Offline catch-up never advances an unpaid Starter Work.

**Worlds from before stages existed.** A colony saved by an earlier version has no stage on record.
The first time it is looked at it is placed in Settled if it has already built at least one housing
blueprint and one farm blueprint, or has more Nerans than `founderColonistCount`; otherwise it starts
in Founding.

**Datapacks.** A pack that ships no `founding` blueprint has no Starter Works, and its colonies
become Settled on their first cycle.

## What each stage unlocks

| Stage | Blueprints that become buildable | Structures the colony will build unprompted | Gift table |
| --- | --- | --- | --- |
| Founding | The 4 Starter Works | 12 | `gratitude/tier_1` |
| Settled | 18 more, among them cottages, the granary and canteen, the Needs Board and Gratitude Cache pavilions, the watch post and the kennel | 12 | `gratitude/tier_2` |
| Growing | 13 more, among them the longhouse, barracks, watchtowers, med bay and golem forge | 24 | `gratitude/tier_3` |
| Thriving | 11 more, among them the town hall, citadel, bazaar and the first landmarks | 36 | `gratitude/tier_4` |
| Metropolis | 5 more: the arcology tower, the grand archive and three landmarks | 48 | `gratitude/tier_5` |

The full list, with sizes and what each building provides, is on [Buildings](Buildings.md#shipped-buildings).

The third column is `maxAutoStructures` (default 12) multiplied by one for Founding and Settled, two
for Growing, three for Thriving and four for Metropolis. It limits only what the colony starts on its
own. Buildings you place with the Colony Planner are not stopped by it, though they count towards
it; upgrades are not stopped by it either, and do not change the count.

Besides buildings, reaching **Growing** switches on two things that follow the growth curve below:
births and the claim bonus.

The gift table is what the [Gratitude Cache](Gratitude-Cache.md) rolls.

## The growth curve

A colony grows slowly at first, then faster, then levels off. One number drives it:

```text
score = population + 2 × structures
g     = 1 / (1 + e^(−0.15 × (score − 20)))
```

`g` is close to 0 for a new colony, exactly 0.5 at a score of 20, and close to 1 for a large one.
Three things scale with it, each capped by a configuration key:

| What | Formula | Applies |
| --- | --- | --- |
| Construction speed | `1 + (growthMaxBuildMultiplier − 1) × g` | Always |
| Chance of a birth per colony cycle | `breedingBaseChance × (0.25 + 0.75 × g)` | From Growing |
| Claim radius bonus, in blocks | `growthMaxClaimBonus × g`, rounded | From Growing |

With the default configuration (`growthMaxBuildMultiplier` 3.0, `breedingBaseChance` 0.08,
`growthMaxClaimBonus` 32):

| Nerans | Structures | Score | Build speed | Birth chance per cycle | Claim bonus |
| --- | --- | --- | --- | --- | --- |
| 2 | 0 | 2 | × 1.13 | — (not Growing) | — (not Growing) |
| 2 | 4 | 10 | × 1.36 | — (not Growing) | — (not Growing) |
| 6 | 6 | 18 | × 1.85 | 4.6% | +14 blocks |
| 10 | 10 | 30 | × 2.64 | 6.9% | +26 blocks |
| 16 | 14 | 44 | × 2.95 | 7.8% | +31 blocks |
| 32 | 24 | 80 | × 3.00 | 8.0% | +32 blocks |

So a colony that has just become Growing builds nearly twice as fast as a new one, and by the time
it is Thriving it is within a few per cent of the cap.

The build speed multiplies `constructionBlocksPerCycle`. Supply, and Builders on site, multiply it
again — see [Construction](Construction.md#how-fast-it-builds).

## Claim growth

A colony's claim radius is `claimRadius`, plus whatever `RANGE` modules in the beacon add, plus the
growth bonus above. With the defaults that is 48 blocks at founding and up to 80 for a large colony.

The beacon re-works the figure every 100 ticks. It follows the colony's *current* population and
structure count, so a colony that shrinks gives a little of its bonus back; the stage itself never
goes down. Before Growing the bonus is zero.

A wider claim is more ground for the colony to build on, more ground the housing sweep reads, and
more ground its guards cover.

The growth bonus never reaches into a neighbour. It is trimmed so that the claim stops one block
short of any other colony's claim in the same dimension. Only the growth is trimmed: the base radius
and whatever `RANGE` modules add are always kept.

## Arrivals, births and children

A colony gains people in two ways.

**Arrivals** work at every stage: while there is a free bed, food in the store and life support
holding, a newcomer walks in. Before Growing that is one per colony cycle. See
[Colony basics](Colony-Basics.md#population-growth).

**Births** start at Growing, and from then on the colony grows mostly from within. Once per colony
cycle the colony checks all of these:

| Condition | Governed by |
| --- | --- |
| Breeding is switched on | `breedingEnabled` (default `true`) |
| The colony is at least Growing | the stage thresholds |
| There are at least two adults | — |
| There is a free bed under both the housing capacity and `colonistsPerColony` | housing; `colonistsPerColony` (default 48) |
| The food store holds enough for the whole colony for several cycles | `breedingSurplusCycles` (default 10) × `foodPerColonistPerCycle` × population |
| Morale is at or above the floor | `breedingMoraleFloor` (default 50) |
| The server-wide Neran limit has room | `maxLoadedColonists` (default 300) |

If they all hold, it rolls the birth chance from the table above. On a success a **child** Neran
appears near the beacon.

Arrivals and births draw on the same free beds, and **births come first**: each cycle the colony
rolls for a child before it considers a newcomer, so a child has first call on a free bed and one
free bed is enough.

Once a colony is Growing, with breeding switched on, newcomers also slow down. One arrives only
every `immigrationIntervalCycles` colony cycles (default 6), and never on a cycle in which a child
was born. Set the key to `1` for an arrival every cycle, as before Growing. With `breedingEnabled`
off, every cycle is an arrival cycle at every stage.

A child:

- counts as population straight away: it eats, needs a bed and counts towards the stage thresholds;
- has no trade and does no work. During the working day it heads for the colony's gathering place
  and wanders; at night it sleeps at home like everyone else;
- grows up after `childGrowthDays` in-game days (default 2), and is given a trade on the next colony
  cycle that has a place open. Set the key to `0` and children are born grown.

`/nerocolonies colony info` reports how many births a colony has had. The beacon's People tab shows
how many children it has now.

Births need the colony's chunk to be loaded. Offline catch-up brings neither arrivals nor births.

## When a colony is not growing

Open the beacon. Two tabs each carry a line that names the reason.

### People tab: why nobody is arriving

| The tab says | Meaning | What to do |
| --- | --- | --- |
| `Arrivals paused: life support has failed.` | The dimension is airless and the colony has no oxygen | See [Life support](Life-Support.md) |
| `Arrivals paused: the food store is empty. Put food in the supply row.` | The food stock is zero | Put food in the beacon's supply slots, or see to it that a Cook has food in colony storage to work with |
| `This colony has reached its population cap.` | The roster is at `colonistsPerColony` | A server setting |
| `Housing is full. The colony builds more when it runs short.` | Every bed is taken | Supply the materials for the housing it is building, or place housing yourself |
| `The server-wide Neran limit has been reached.` | The server is at `maxLoadedColonists` | A server setting |
| `The colony builds its own housing. Stock colony storage with materials to build faster.` | Nothing is wrong; arrivals are coming | — |

The reasons are checked in that order and the first one that applies is shown.

### Colony tab: why nothing is being built

| The tab says | Meaning |
| --- | --- |
| `Waiting for the Starter Works' materials. See the Needs tab.` | The colony is founding and the current Starter Work is unpaid |
| `Morale is too low to work.` | Morale is below `moraleWorkStopThreshold` |
| `Life support has failed, so building has stopped.` | As it says |
| `There is nobody here to build.` | The roster is empty and `constructionRequiresColonist` is on |
| `The colony has built as many structures as the server allows.` | It has reached the structure cap for its stage. Reaching the next stage raises it |
| `Nothing is needed right now.` | No blueprint is eligible: everything allowed at this stage is built, what is left needs research the colony does not have, or it is housing the colony does not need yet |
| `Looking for flat, open ground in the claim. Clear trees or level a spot to help.` | Something is eligible but no site fits |
| `Autonomous construction is turned off on this server.` | `constructionEnabled` is `false` |

A line reading `Fabricating <building> - 34% (no materials)` is not a stop: the colony is building
at the unsupplied rate. Put the materials in colony storage and it speeds up.

### The stage is not advancing

- Look at the Needs tab's `next:` line and compare it with the People tab's Neran count and the
  Colony tab's structure count. Both targets have to be met.
- Population is capped by housing. If the colony has stopped building homes, the usual cause is that
  it has enough: it only starts a new house when fewer than two beds are free.
- Structures are capped by stage (12 at Settled by default) and by each blueprint's own maximum. The
  Growing threshold of 6 is well inside the Settled cap.
- No children yet? That is expected before Growing, and after it a birth still needs spare food,
  morale of 50 and a free bed.
- Beds filling more slowly than they used to? From Growing on, newcomers arrive only every
  `immigrationIntervalCycles` cycles; the free beds are being left for children.

## See also

- [Buildings](Buildings.md) — what a colony builds and where
- [Nerans and professions](Nerans-and-Professions.md) — the people, and how trades are handed out
- [Construction](Construction.md) — the build loop and its speed
- [Gratitude cache](Gratitude-Cache.md) — the gifts that improve with each stage
- [Config](Config.md) — every key named on this page
