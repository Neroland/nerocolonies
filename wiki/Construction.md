# Autonomous construction

A colony builds itself. You place a beacon, two founders arrive with it, you supply the four Starter
Works, and from then on the colony picks its own buildings and puts them up a few blocks at a time —
no build orders, no assignment screen. Your lever is **supply**: bring the materials and the same
structure goes up four times faster.

This page covers the founders, the pace of building, supply, the needs list and the levers an
operator has. *What* a colony builds and *where* is on [Buildings](Buildings.md).

## Founders

Placing a colony beacon puts `founderColonistCount` Nerans (default **2**) on the ground next to
it, immediately — not on the first colony cycle. They are the seed of the whole loop: housing is what
lets Nerans arrive, and building housing is what Nerans do, so without founders nothing can ever
start.

Founders are held on the roster **regardless of housing capacity**. That is a floor, not an
exemption:

- they still count toward `colonistsPerColony` and the server-wide `maxLoadedColonists`;
- they get exactly the same life support, food and morale treatment as anybody else — on an airless
  world the usual curve applies (life support fails → morale decays → work stops → Nerans idle),
  and **no Neran is ever removed for it**;
- a colony that drops below its founder count will replace them even while starving or without
  atmosphere. That exemption is deliberate: a colony with nobody left has nothing that can build the
  farm or the oxygen generator that would fix the problem, so gating the bootstrap on food and air
  would make such a colony permanently dead rather than merely in trouble. It is bounded by
  `founderColonistCount` and cannot grow a colony past it.

Set `founderColonistCount` to `0` to switch founders off. Autonomous construction then never starts
on its own — the colony waits for you to build the first housing by hand.

## The build loop

Every colony cycle (`colonyTickIntervalTicks`, default 100 ticks) a colony with nothing under
construction chooses what to build next and where, and starts. The order of choice — your
hand-placed plans, then upgrades, then its own pick — and the rules for a site are on
[Buildings](Buildings.md#what-gets-built-next). Thereafter it clears the site and lays blocks, bottom
layer first, until the structure is finished, then chooses again.

### How fast it builds

```text
blocks per cycle = constructionBlocksPerCycle          (default 2)
                 × the growth multiplier               (1.0 for a new colony, towards 3.0 for a large one)
                 × (1 + 0.5 for each Builder on site)   (at most four Builders count)
                 × 1.0 if supplied, constructionUnsuppliedFactor if not   (default 0.25)
```

- The **growth multiplier** follows the colony's population and structure count — see
  [Progression](Progression.md#the-growth-curve).
- A **Builder on site** is a Neran with the Builder trade standing within 8 blocks of the spot in
  front of the building. Four of them triple the rate.
- Fractions carry over from one cycle to the next, so an unsupplied new colony still places a block
  every other cycle rather than none.
- Clearing a natural block out of the way costs the same as placing one.

Deliberately slow at first. A colony growing visibly over minutes reads as a colony; one that snaps
into existence reads as a command block.

### Supplied and unsupplied

At the start of every cycle, a build that has not yet been paid for looks for its blueprint's
**materials in colony storage**. If the whole list is there it is taken, once, and the build runs at
full rate. If it is not, the Nerans fabricate from scrap instead: the same structure, free, at
`constructionUnsuppliedFactor` of the rate.

From the Settled stage on, nothing is ever *blocked* on materials. A colony left entirely alone still
grows, just slowly — a colony that stops dead waiting for iron is a colony you have to babysit.

**The Starter Works are the exception.** While a colony is Founding, its four Starter Works are not
fabricated from scrap: they wait for your materials. See
[Progression](Progression.md#the-starter-works).

The check runs every cycle, not only when the build starts, so **putting materials into colony
storage part way through speeds up the build already under way**.

> Materials go into **colony storage**, which you reach through a Colony Depot inside the claim,
> through a Needs Board, or through any pipe or hopper inserting into the beacon. They do **not** go
> in the beacon's six supply slots — those are the food intake and refuse anything that is not food.

A blueprint with no materials list always builds at full speed.

### When it stops

Construction pauses (never cancels, never demolishes) when:

- `constructionEnabled` is `false`;
- morale has fallen below `moraleWorkStopThreshold` and work has stopped;
- life support is `FAILED`;
- `constructionRequiresColonist` is set (the default) and the colony's roster is empty;
- the colony is Founding and the current Starter Work has not been supplied;
- for the colony's own picks only: it has reached its structure cap, or nothing is eligible, or no
  site fits.

The beacon's Colony tab names the reason. The lines are listed on
[Progression](Progression.md#colony-tab-why-nothing-is-being-built).

**Nothing NeroColonies built is ever demolished automatically.** A half-built structure whose
blueprint was removed from the datapack, or reshaped by a reload, is abandoned in place, not torn
down.

### The builder

While something is being built, every Neran with the **Builder** trade is sent to the spot in front
of the site. If the colony has no Builders, one adult with nothing else to do stands there instead,
so you can still see where the colony is working.

**Nobody is ever built into a wall.** A block is never placed into a cell where any living thing is
standing: Nerans in the way are moved to the spot in front of the site, and anyone else — a player,
an animal — is simply waited for. Arrivals never appear inside the structure being built.

Block placement is colony-cycle logic and does not wait for anybody to arrive.
`constructionRequiresColonist` asks whether the colony *has* anybody, never whether anybody reached
the site, so a Neran that cannot find a path cannot stall a colony's growth. Builders who do arrive
make it faster; that is all.

### While nobody is there

Offline [catch-up](Colony-Basics.md#while-nobody-is-there-the-offline-catch-up) advances a colony's
**fabrication credit and places no blocks at all**. Laying a backlog's worth of blocks on the tick a
chunk loads would be a visible stutter and a lighting-update storm at exactly the worst moment.

The credit is capped at four cycles' worth, so a returning player sees the build resume briskly for a
few cycles and then settle to the normal rate. A colony never *starts* a new structure while nobody
is there, and an unsupplied Starter Work gains nothing.

## The needs list

A colony keeps a list of what it is short of. The list is never stored: it is worked out afresh each
time anyone looks, from three things.

| Source | What appears |
| --- | --- |
| **Construction** | The unpaid materials of the building under way — or, while Founding with nothing started, of the next Starter Work |
| **Food** | The shortfall when the food stock is below ten cycles of what the colony eats |
| **Tools** | One line per kind of tool that tradespeople are missing and colony storage cannot cover |

A need you have prioritised comes first; after that, Starter Works materials, other construction
materials, food, then tools.

### Two estimates

Every line says how much the colony has against how much it wants, and how long it will take.

- **Alone** is how long the colony's own tradespeople will take to gather the rest at their present
  rate. If no trade gathers that item the line reads `needs your help` instead. Planks, doors and
  lanterns are in that group: Foresters bring in logs, not planks.
- **With help** is no time at all. Hand it over and the need is met.

The beacon's Needs tab adds an estimate for the whole building: `Build: alone ~40 min, helped
~6 min`, or `Build: needs your help, then ~6 min` for a Starter Work. "Alone" is the quicker of
gathering everything and then building at full speed, or fabricating from scrap. The estimates do not
count Builders on site, so a staffed site finishes sooner than it says.

### Where to see it

- **The beacon's Needs tab** shows the next milestone, the whole needs list (up to eight lines, each
  with how much the colony has, how much it wants and an estimate), and the build estimate. Hover a
  line to read it in full if its name was shortened.
- **A Needs Board** (`nerocolonies:needs_board`) prints the list in chat when a member uses it
  empty-handed, up to eight lines.
- **A companion app** — see [Link module](Link-Module.md).

The Needs Board is crafted from three paper, four wooden slabs and an iron ingot. The colony also
builds one for itself in its Needs Board Pavilion.

### Contributing

Every need is paid from **colony storage**, and there are three ways to put something there. Any
member of the colony may use them.

- **At the beacon: the `Give needed items` button.** It sits above your inventory on every tab of the
  beacon's screen. Carry the items to the beacon, open it and press the button: the colony takes
  everything in your hotbar and inventory that the needs list wants, and no more of each thing than it
  is short of. The button lights up only when you are carrying something on the list, and on the
  Needs tab each need you can pay towards gets a green pip. A new colony has no depot yet, so this
  is how the Starter Works get their materials.
- **A Needs Board.** Use it while holding something on the list. The colony takes as much of the
  stack as it still needs and thanks you; if it does not need the item it says so and takes nothing.
- **A Colony Depot.** Open it and put the items in. A depot is a door onto colony storage itself, so
  it takes anything, needed or not. Hoppers and pipes into a depot or the beacon work too.

Neither the button nor the board stores anything itself, and neither takes more than is wanted.
Food is the one need they do not take: food goes in the beacon's food supply slots. If colony
storage is full the colony takes what fits and says so; a `CAPACITY` module in the beacon makes it
bigger.

### Prioritising a need

The owner or a Chief can put one need first. The trades that gather it then work **half as fast
again** until the priority is cleared or moved.

- At the beacon: click the need on the Needs tab; click it again to clear it.
- By command: `/nerocolonies colony need prioritise <colony> <item>`, or with no item to clear it.
- From a companion app: the `prioritise_need` action.

At the beacon any line can be clicked, including one that stands for a tag — any logs, any planks,
food. Prioritising a tag speeds up every trade that gathers something in it. The command and the
companion app take a single item, such as `minecraft:oak_log`, whether or not it is on the list
today. There is one priority per colony: setting a new one replaces the old.

## Watching it happen

The beacon's **Colony** tab has a **Construction** section. Its heading shows how many structures
the colony has built (`3 built`), and under it:

- `Building Habitat Pod`, a percentage and a progress bar — supplied, running at full rate;
- `Fabricating Habitat Pod`, a percentage, an amber progress bar and a note that there are no
  materials — unsupplied, running at `constructionUnsuppliedFactor`. Put the materials in colony
  storage;
- when nothing is being built, the reason, in full.

A completed structure also:

- publishes Core's `nerocolonies:structures` threshold crossing, scoped to the **colony id** and
  carrying the new total, so a quest objective in another mod can key off "this colony has built its
  third structure" with no coupling to this one;
- pushes a `construction` event to companion sessions ([Link module](Link-Module.md));
- triggers an immediate housing rescan, so a finished home raises capacity within seconds rather
  than at the next scheduled sweep;
- may advance the colony's stage on the same cycle.

## Blueprints

Every building is plain datapack JSON at
`data/<namespace>/nerocolonies/blueprints/<path>.json`. The format, its limits and how bad content is
handled are on [Content format](Content-Format.md#blueprints). The buildings that ship with the mod
are listed on [Buildings](Buildings.md#shipped-buildings).

The stations and machines a colony builds for itself still need **power and inputs** from you. A
colony can put up a refinery; it cannot run a cable to it.

## Configuration

| Key | Default | Effect |
| --- | --- | --- |
| `founderColonistCount` | 2 | Nerans that arrive with a new beacon. `0` disables the bootstrap |
| `constructionEnabled` | true | Master switch |
| `constructionBlocksPerCycle` | 2 | Blocks placed per colony cycle before the multipliers |
| `constructionUnsuppliedFactor` | 0.25 | Rate multiplier without materials. `0` means an unsupplied colony never builds |
| `constructionRequiresColonist` | true | Whether an empty roster stops building |
| `growthMaxBuildMultiplier` | 3.0 | Ceiling of the growth multiplier |
| `maxAutoStructures` | 12 | Structures a Settled colony builds unprompted; multiplied at later stages |

See [Config](Config.md) for the full table.

## Privacy

Nothing on this page involves player data. A build record is keyed by a **colony id** — a place, not
a person — and holds blueprint ids, block positions and counters. A hand-placed plan does not record
who placed it. The threshold channel is colony-scoped by contract; the companion event names no
player. See [Data storage](Data-Storage.md).

## See also

- [Buildings](Buildings.md) — what is built, where, the Colony Planner and every shipped building
- [Progression](Progression.md) — stages, the Starter Works and the growth curve
- [Nerans and professions](Nerans-and-Professions.md) — the Builder trade, and what trades gather
- [Colony basics](Colony-Basics.md) — founding, housing, population, the colony cycle
- [Content format](Content-Format.md) — the blueprint schema
- [Config](Config.md) — every key named here
- [Link module](Link-Module.md) — what a companion app sees
