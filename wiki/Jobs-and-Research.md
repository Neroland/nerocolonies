# Jobs and research

What a colony does all day, and how it gets better at it.

## Job stations

Four station blocks ship with the mod:

| Block | Shipped job | Needs research |
| --- | --- | --- |
| `nerocolonies:farm_station` | `nerocolonies:farm` — 1 wheat seed → 2 wheat, 200 ticks, 1 colonist | — |
| `nerocolonies:hydroponics_station` | `nerocolonies:hydroponics` — 1 wheat seed → 3 carrots, 240 ticks, 1 colonist | `nerocolonies:life_support/hydroponics` |
| `nerocolonies:refinery_station` | `nerocolonies:refine` — 1 raw iron → 1 iron ingot, 300 ticks, 2 colonists | `nerocolonies:industry/refining` |
| `nerocolonies:fabricator_station` | `nerocolonies:fabricate` — 1 iron ingot + 2 redstone → 1 repeater, 400 ticks, 2 colonists, exports | `nerocolonies:industry/fabrication` |

A station **holds no recipe and no inventory of its own**. It has two upgrade slots, an energy
buffer, and nothing else. Its recipe comes from the datapack job definitions that name its *block
id*, so one block-entity type serves every station block, and a datapack can add a job to an
existing station — or point a job at some other mod's block entirely — with no code at all. See
[Content format](Content-Format.md).

Its inputs come from colony storage and its outputs go back there (or into the export buffer), so
there is no per-station inventory to fill, clog or lose.

Power comes in on every face and modules come in through the item channel; nothing ever leaves a
station.

| Property | Value |
| --- | --- |
| Energy buffer | 20,000 |
| Maximum energy transfer | 500 per tick |
| Energy per completed craft | 120, reduced by `EFFICIENCY` modules |
| Upgrade slots | 2 |

## Production runs on the colony tick, not on the block

A station does not run its own recipe. Each tick it files itself with the colony's **job board**
(every 40 ticks) and re-resolves which claim it stands in (every 200 ticks) — and the *colony's*
cycle drives every station it owns, inside the one `colonyTickBudgetMs` watchdog.

The alternative — N block entities each ticking their own recipe — would put a colony's whole
production cost outside the budget that exists to bound it, and would make "twenty stations" a
server problem rather than a design choice. What a station itself does per tick is a countdown and,
occasionally, a map write.

The job board is **session state**. Nothing about it is persisted: a station's real existence is its
block, a registration that stops being refreshed expires on its own, and a reloaded world rebuilds
the board from the stations that re-register. A station standing on unclaimed ground has no colony
and does nothing — that is not an error, it is a station somebody has not finished building around.

## Throughput

Each cycle, every active station advances by:

```text
progress += elapsed ticks
          x jobBaseRateMultiplier   // server-wide scalar, default 1.0
          x morale multiplier       // moraleMinMultiplier..1.0 from colony morale
          x workers                 // assigned colonists (1 for an unstaffed job)
          x speed multiplier        // SPEED modules on the station
          x power factor;           // 1.0 powered, 0.35 unpowered
```

When progress reaches the job's `ticks` value a craft completes, up to **eight crafts per station
per cycle** so a backlog cannot spike a tick. Unspent progress is never banked past one cycle: a
station blocked for an hour does not fire an hour of output the instant it is unblocked.

**Unpowered is slow, not stopped.** A station with too little energy for a craft still works, at
0.35x. That is the same graceful-failure rule the rest of the mod follows: a colony whose cable was
cut gets visibly slower rather than stopping dead for a reason the player cannot see.

A craft is **all or nothing**. Destination room is checked first, then the inputs are consumed, then
the outputs are placed. Checking room first is what keeps a full colony from quietly eating its own
inputs, and the all-or-nothing input consumption is what stops a job that has two of its three
inputs from destroying them every cycle.

A station is reported as **blocked** when it is short of inputs, has nowhere to put its output,
needs colonists it does not have, or sits below its job's own `morale_floor`.

## Job slots

Only a limited number of stations work at once: `jobSlotsPerColony` (default 4) plus whatever
research adds. Allocation is **first-fit in a stable position order**, so which stations hold the
slots does not churn between ticks. Stations that miss out hold their progress and simply do not
advance.

Outposts get their own separate small budget of `outpostJobSlots` (default 1) **each**, which is
what stops an outpost being a way to buy more colony throughput than research allows.

When morale is below `moraleWorkStopThreshold` no slots are allocated at all. The colony's fast exit
skips production entirely, and the board still updates each station's state so a player who opens
one is told *why* it is idle rather than left looking at a silent machine.

## Staffing

Colonists are handed to the active stations first-fit, in the same stable order. A colonist assigned
to a station walks to it; a colonist left over has its assignment cleared, so a colonist whose
station was broken stops walking to a hole in the ground.

A job with `colonists: 0` is fully automated and runs on one notional worker. A job that asks for
hands and has none does nothing and reports itself blocked.

**Outpost stations are staffed on paper** from the parent's roster, capped at
`outpostColonistCap`. An outpost may be half a kilometre from its colony, and marching colonists
across that gap every cycle would be a pathfinding bill with nothing to show for it. A remote work
site being staffed nominally is the honest simplification.

## Where the goods live

There is exactly **one working stock per colony**, reached through several blocks: the colony beacon
exposes it as a standard item capability, and every **colony depot** (`nerocolonies:colony_depot`)
placed inside the claim is another window onto the same goods. Adding a depot therefore adds
*access*, not capacity.

Capacity comes from `CAPACITY` upgrade modules in the beacon: 18 slots base, plus 9 per module, up
to 54. Slots past the current gate refuse insertion but still read and extract, so removing a module
strands nothing — it just stops anything new going in until the overflow has been drawn down.

Job outputs go to the working stock unless the job is flagged `export` **or** the station's own
export switch is on, in which case they go to the export buffer — see
[Exports & outposts](Exports-and-Outposts.md). The job's flag says what a recipe is *for*; the
station's switch says what this colony is doing with it today.

## The research station

`nerocolonies:research_station` is where a colony spends goods and power to unlock a node.

It **stores nothing**: no inventory, no modules, no per-station state. A node's cost is paid out of
**colony storage** and the unlock is written to the **colony record**. Two research stations in one
colony are two doors onto the same research programme, not two programmes — the same rule the colony
depot follows, and for the same reason: the colony is the unit, not the block.

| Property | Value |
| --- | --- |
| Energy buffer | 100,000 |
| Maximum energy transfer | 2,000 per tick |
| Energy per unlock | 5,000 |
| Upgrade slots | 0 |

Power is a **hard requirement** here rather than a rate penalty. Research is a discrete event, so
there is nothing to slow down: it either happens or it waits for the buffer to fill.

Everything is checked server-side. The client sends "unlock this node" and nothing more; existence,
prerequisites, duplicate unlocks, power and affordability are all decided from the server's own copy
of the content and the colony's own storage. Order matters — everything that can refuse an unlock is
checked *before* anything is spent, so a refused unlock costs nothing at all. The possible outcomes
are: unlocked, already unlocked, unknown node, prerequisites missing, not enough power, cannot
afford.

The research screen shows affordability without the client ever being sent the colony's inventory:
the server computes which nodes are available *and* payable and ships that as a list of ids.

## Research is colony-local

An unlocked node lives on the **colony record**, not on a player. It is therefore **not personal
data**, it is shared by everyone with access to the colony, and dissolving the colony discards it.
There is deliberately no per-player research: a colony is a place, and its technology belongs to the
place.

What is stored is a set of node **ids** and nothing else. Everything downstream derives its numbers
on demand from the currently loaded definitions, so a datapack that re-tunes a node takes effect on
`/reload` with no migration, and a node that disappears from a pack simply stops contributing rather
than leaving a stale bonus baked into a saved world.

## The research graph

A node has a presentational `branch`, a title, a list of prerequisites, a cost paid from colony
storage, and a list of effects. The real graph is the prerequisite list; `branch` only groups nodes
in the research screen.

The shipped tree:

```text
habitation/shelter ─┬─ habitation/pressurised_modules ── habitation/residential_blocks
                    ├─ industry/refining ─┬─ industry/fabrication
                    │                     └─ trade/manifest
                    └─ life_support/recyclers ── life_support/hydroponics
```

| Node | Cost | Effects |
| --- | --- | --- |
| `habitation/shelter` | 4 iron ingots | +2 morale, +1 job slot |
| `habitation/pressurised_modules` | 8 iron ingots, 4 glass | habitat module housing, +2 morale |
| `habitation/residential_blocks` | 16 iron ingots, 8 copper ingots | habitat block housing, +3 morale |
| `industry/refining` | 8 iron ingots | the refine job, +1 job slot |
| `industry/fabrication` | 12 iron ingots, 8 redstone | the fabricate job, +1 job slot |
| `life_support/recyclers` | 6 copper ingots | oxygen burn x 0.85 |
| `life_support/hydroponics` | 8 copper ingots, 8 glass | the hydroponics job, oxygen burn x 0.9, +2 morale |
| `trade/manifest` | 6 gold ingots | the refined-metals and fabricated-goods exports |

### The effect types

| Type | Field | What it does |
| --- | --- | --- |
| `nerocolonies:housing_tier` | `tier` | makes a housing tier countable in this colony |
| `nerocolonies:job_unlock` | `job` | makes a job assignable in this colony |
| `nerocolonies:job_slots` | `amount` | adds simultaneously worked job slots |
| `nerocolonies:oxygen_efficiency` | `multiplier` | multiplies life-support oxygen burn; below 1.0 is an improvement, and multipliers compound |
| `nerocolonies:export_unlock` | `export` | makes an export entry sellable from this colony |
| `nerocolonies:morale_bonus` | `amount` | a flat addition to the morale target; may be negative |

A job, housing tier or export entry that names a `research` prerequisite is available once that node
is unlocked **or** once any unlocked node carries a matching unlock effect. Content that names no
prerequisite is always available.

Full field-by-field schemas are in [Content format](Content-Format.md).

## See also

- [Content format](Content-Format.md) — writing your own jobs and research
- [Exports & outposts](Exports-and-Outposts.md) — what the export flag routes into
- [Colony basics](Colony-Basics.md) — morale, and the colony cycle jobs run on
- [Config](Config.md) — `jobSlotsPerColony`, `jobBaseRateMultiplier`, `colonyTickBudgetMs`
