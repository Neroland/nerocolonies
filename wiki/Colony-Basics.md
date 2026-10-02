# Colony basics

Everything about the colony itself: founding one, what it claims, who may touch it, who lives in
it, how happy they are, and what happens while nobody is watching.

## Founding a colony

Place a **Colony Beacon** (`nerocolonies:colony_beacon`). That is the whole ritual — there is no
multiblock to assemble and no ceremony to perform. The block validates the placement the moment it
lands, and a refused placement removes the block again, hands the beacon back to you and tells you
why. An inert beacon left standing would be worse than a clear refusal.

A placement is refused when any of these is true:

| Check | Governed by | Refusal |
| --- | --- | --- |
| The server already has as many colonies as it allows | `maxColoniesTotal` (default 200) | server cap reached |
| Founding is switched off entirely | `maxColoniesPerPlayer` set to `0` | founding disabled |
| You already own your allowance | `maxColoniesPerPlayer` (default 3) | personal cap reached |
| Another colony's beacon is too close | `minColonySpacing` (default 192 blocks) | too close to an existing colony |
| The new claim would touch an existing claim | `claimRadius` (default 48) | claims would overlap |

Spacing and overlap are both measured **horizontally** and **within one dimension** — a colony
directly below another one in a different dimension is not a conflict. No refusal ever names another
player: "too close to an existing colony" is as specific as it gets, which is also all a prospective
settler needs to know.

A successful placement also puts **`founderColonistCount` Nerans (default 2) on the ground next to
the beacon, immediately**. They are the seed of everything that follows — see
[Construction](Construction.md).

The new colony starts in the **Founding** stage. Its first job is the four Starter Works, and those
need materials from you: see [Progression](Progression.md#the-starter-works).

Founding also asks Neroland Core to open its `first_colony` progression gate. It *asks* — the gate
has its own requirements and NeroColonies does not force past them, and nothing in this mod ever
requires a gate to be open. The write is a signal to the rest of the ecosystem and can be switched
off with `gateWritesEnabled`.

## The claim

A claim is a **square** centred on the beacon, unlimited vertically. Its radius is `claimRadius`
blocks in each horizontal direction — 97 blocks across at the default 48 — plus two things that
widen it:

- `RANGE` upgrade modules in the beacon;
- from the Growing stage, a bonus that follows the colony's size, up to `growthMaxClaimBonus` blocks
  (default 32). The bonus stops short of any neighbouring colony's claim. See
  [Progression](Progression.md#claim-growth).

Claims are permissive by design. NeroColonies claims exist so that a colony can be run, not so that
the world can be fenced off: **unclaimed ground is always buildable by anyone**, and a claim only
ever refuses somebody inside it.

## Who may do what

A colony has four roles:

- **The owner** — the player who placed the beacon.
- **Chiefs** — members the owner has promoted. They may plan buildings and manage Allies and
  Enemies.
- **Allies** — members. They may use the colony: open its blocks, feed it, contribute to its needs.
- **Enemies** — players the colony has marked. They are not members, and its guards attack them
  unless the server has switched that off.

**Operators** — permission level 2 or better — are treated as the owner of every colony.

Players who had access to a colony before roles existed are Allies now, with the rights they had. The
full table of who may do what, how roles are changed and what marking an Enemy does is on
[Roles and defence](Roles-and-Defence.md).

A colony can also be **ownerless**: after a data-erasure request under the default policy the owner
slot is handed to the server, and the colony keeps running with operators able to administer it. See
[Data storage](Data-Storage.md).

Capture, contest and faction interaction are out of scope. A colony's guards defend its claim against
marked Enemies; nothing lets one colony take another.

## Dissolving a colony

**Sneak and break the beacon, as the owner or an operator.** Anybody else — and anybody not
crouching — finds the block simply refuses to break. The sneak is the confirmation prompt: a colony
record is far too expensive to lose to a stray pickaxe swing.

Dissolving drops the colony's whole store (working stock *and* export buffer) at the beacon and
deletes the colony record. Everything else kept for the colony goes with it: its access-log rows,
its research, its record of what it built, its Chief and Enemy lists, its stage and counters, and
its outposts — an outpost has no independent existence.

What it built stays standing. Its Nerans are not removed either: they are left without a colony,
idle.

> The **Gratitude Cache** is part of the colony record, not of the store. Its contents are not
> dropped. Empty it before you dissolve the colony.

A colony whose beacon disappeared without a break event (an explosion, a world edit) is caught by
the retention sweep instead: see [Admin guide](Admin-Guide.md).

## Nerans

The colony's people are **Nerans**. Each one belongs to one colony, has a home and — once the colony
has buildings that open trades — a trade, a workplace and a tool. They keep a daily round of work, a
midday meal, an evening together and a night at home, open and close doors, and may carry a name.

A Neran carries **nothing player-shaped at all**. It does not know who owns its colony.

Most Nerans never fight. **Guards** do: they attack hostile mobs, and players the colony has marked
as Enemies, inside the claim and a margin around it. Nothing in this mod makes any Neran hostile to
anybody else.

Nerans do some of their work in front of you — harvesting, felling, patrolling — but the colony does
not depend on it. Production, construction and the goods each trade gathers are all worked out on the
colony cycle, so a Neran that cannot find a path does not stall anything.

With no member of the colony within `aiActiveRadius` (default 64 blocks) a Neran goes *quiet* and
starts new walks and new work only in short windows. A walk already under way always finishes.

All of this is covered in detail on [Nerans and professions](Nerans-and-Professions.md).

### Nerans are never removed as a punishment

This is worth stating plainly because it is the single most important rule in the mod. Starvation
and life-support failure decay morale. Morale collapse stops work and leaves everyone idle. **None
of those three ever removes a Neran.** The only path by which the colony removes one is losing the
housing that seats it (see below), because a bunk that no longer exists cannot be slept in.

A Neran is still a mob in the world, and can be hurt and killed like one. The colony takes a new
arrival when its usual conditions allow.

## Housing and the housing scan

Housing is matched by **block**, not by block entity. A datapack `housing` definition names a block
id, a capacity and a comfort value, so a colony can be housed in a NeroColonies habitat, another
mod's crew module, or plain vanilla beds, with no compat code on either side. See
[Content format](Content-Format.md).

The three shipped tiers:

| Block | Tier | Capacity | Comfort | Needs research |
| --- | --- | --- | --- | --- |
| `nerocolonies:habitat_pod` | 1 | 2 | 0.35 | — |
| `nerocolonies:habitat_module` | 2 | 4 | 0.65 | `nerocolonies:habitation/pressurised_modules` |
| `nerocolonies:habitat_block` | 3 | 8 | 0.90 | `nerocolonies:habitation/residential_blocks` |

Capacity is how many Nerans the block seats; comfort (0–1) is its weight in the morale housing
term. A cramped pod can seat the same number of people as a proper module and still feel worse to
live in.

Every home the colony builds for itself — the Founder's Lodge, cottages, longhouses, barracks, the
arcology tower — is a shell around a number of Habitat Pods, and the pods are what the scan counts.
Housing you place by hand inside the claim counts in exactly the same way.

**The scan is budgeted.** Reading every block in a 97-block-wide claim on a cadence would be the
most expensive thing this mod does, so the sweep is a cursor over the claim's chunks: two loaded
chunks per slice, over a band from 6 levels below the beacon to 12 above, with slices 20 ticks
apart. Only when a full cycle closes are the totals committed — a half-finished sweep never makes
capacity flicker — and the colony then rests for `housingScanIntervalTicks` (default 600) before
starting again.

Unloaded chunks are **skipped, never loaded**. A colony whose claim is half-unloaded reports the
housing it can actually see, which is also the housing its Nerans could actually reach. Housing
whose tier needs research the colony has not unlocked is not counted at all.

## Population growth

The rules are short:

- **Founders bootstrap.** `founderColonistCount` Nerans arrive with the beacon and are held on the
  roster *regardless of housing* — without them nothing could ever start, because housing is what
  lets Nerans arrive and building housing is what Nerans do. They are a floor, not an exemption:
  they still count toward both caps and take exactly the same survival treatment as everybody else.
  See [Construction](Construction.md).
- **Housing is the cap.** Above the founder floor the colony grows toward
  `min(housing capacity, colonistsPerColony)`. `colonistsPerColony` defaults to 48. Until the colony
  is Growing, one newcomer arrives per colony cycle while there is room.
- **Survival is the gate.** Nobody arrives while life support has failed or the food store is empty.
  A colony in trouble stops growing before it starts shrinking. Replacing a lost *founder* is the one
  exemption, because a colony with nobody left cannot fix the very problems the gate is testing for.
- **Growing colonies have children.** From the Growing stage, a colony with spare food, a free bed
  and decent morale has a chance each cycle of a birth. Births are handled before arrivals, and
  newcomers then arrive only every `immigrationIntervalCycles` cycles (default 6), so free beds go
  mostly to children. See [Progression](Progression.md#arrivals-births-and-children).
- **Losing housing shrinks the roster.** Surplus Nerans leave, the most recent arrival first and
  founders last, never below the founder floor.
- **Nothing shrinks on a guess.** When a beacon's chunk loads, the colony uses its saved housing
  figure until the first housing sweep has finished, and neither grows nor shrinks the roster before
  then. Nerans who strayed up to 16 blocks past the claim edge still count (and are walked back),
  so they are not replaced by newcomers.

A server-wide ceiling, `maxLoadedColonists` (default 300), bounds the total across every colony.

New arrivals appear within six blocks of the beacon on solid, dry ground that is not inside the
structure being built, preferring a spot that can walk to the beacon; if there is nowhere to stand,
the colony simply tries again next cycle. New colonies are named `Colony N`; rename yours with
`/nerocolonies colony rename`.

The beacon's People tab says why nobody is arriving when nobody is. The lines it can show are listed
on [Progression](Progression.md#people-tab-why-nobody-is-arriving).

## Morale

Morale is a weighted sum, moved toward gradually, with two consequences.

```text
target = moraleBase
       + moraleWeightHousing      * housing comfort (0..1)
       + moraleWeightFood         * food reserve    (0..1)
       + moraleWeightLifeSupport  * life support    (1.0 OK / 0.5 DEGRADED / 0.0 FAILED)
       - moraleWeightCrowding     * overcrowding    (0..1)
       - moraleWeightHazard       * planet hazard   (0 or 1)
       + research morale bonuses
```

The result is clamped to 0–100. **Every weight is a configuration key**, so a server can make morale
a gentle nudge or the whole game without touching code — see [Config](Config.md).

The food term measures the store against eight cycles of reserve: below that it falls off
proportionally, above it there is no further bonus, so hoarding is not a morale strategy. The
overcrowding term is how far past its housing the colony is packed. The hazard term is the only one
that can be inert — it is non-zero only when a planet mod reports a hazardous dimension, and is
exactly zero on Earth and everywhere else.

Morale then moves toward that target by at most `moraleChangeRate` (default 2.0) points per colony
cycle and is **never snapped**. One bad cycle cannot collapse a colony, and one repair cannot
instantly redeem one.

A colony with **Medics** gets a small lift on top: 0.5 points per Medic each daytime cycle, at most
2.0, added before the step above. It is a nudge against the current, not a new target.

### The two consequences

- **An output multiplier.** A smooth curve from `moraleMinMultiplier` (default 0.25) at zero morale
  to 1.0 at full. Production is a slope, not a cliff. The same multiplier scales what the trades
  gather.
- **A work-stop threshold.** Below `moraleWorkStopThreshold` (default 20) jobs halt, building stops,
  trades gather nothing and Nerans idle. Individual jobs may also set their own higher
  `morale_floor`, which blocks that one job without stopping the colony.

Morale also gates births, which need `breedingMoraleFloor` (default 50). Beyond that, those are the
*only* consequences. Nothing is destroyed, nobody is removed, and nothing is lost that cannot be
recovered by fixing the cause.

The beacon's comparator output tracks morale, scaled 0–15, so redstone can react to a colony in
trouble.

## While nobody is there: the offline catch-up

**A colony ticks only while its beacon's chunk is loaded.** When the chunk comes back, the colony
works out how long it was away, clamps that to `catchUpMaxHours` (default 24) and applies the missed
cycles in one aggregate step at `catchUpEfficiency` (default 0.5).

Consumption is applied first, then life support over the whole window, then construction credit, then
morale — so a colony that would have starved while away is found starving rather than found fed and
starving one tick later, and a colony left with no atmosphere comes back in `FAILED`. Catch-up
advances a part-built structure's fabrication credit but **places no blocks**, so returning never
triggers a burst of block placement in a chunk that has just loaded.

What catch-up does **not** include: arrivals and births, the goods trades gather, Cooks' meals,
Gratitude Cache stocking, and stage advances. Those happen only while the colony is loaded. Job
stations are not caught up either. A colony left alone therefore comes back having eaten and with
nothing new in store, which is the point: being there is always better.

Everything is one aggregate step: a colony away for the full 24 hours costs the same to catch up as
one away for a minute.

The alternative — ticking every colony on the server forever — was rejected on three counts:

- **Cost.** It would make the mod's worst case the number of colonies ever founded rather than the
  number currently being played.
- **Exploit surface.** If offline colonies produced at full rate there would be no reason ever to
  visit one, and every reason to found as many as the cap allows and walk away.
- **Honesty.** A colony that keeps producing while unloaded has to invent its inputs, because the
  machines that would have supplied them were not running either.

Set `catchUpMaxHours` to `0` to disable catch-up entirely.

## The colony cycle

Each colony runs its cycle every `colonyTickIntervalTicks` (default 100), offset by its own id so
two hundred colonies never land on the same game tick. In order:

1. **life support** — the colony's physical situation, before anything reacts to it;
2. **food** — intake from the beacon's supply slots, then the cycle's consumption;
3. **population** — births first, then departures and arrivals, gated on the two above;
4. **trades** — who holds which trade, and where each works;
5. **jobs** — job-station production, inside the shared per-tick budget;
6. **construction** — the colony's own building work ([Construction](Construction.md));
7. **the trades' work** — tools handed out, goods gathered, meals cooked, the Gratitude Cache
   stocked, guardian animals kept up;
8. **stage** — advanced if the colony has earned the next one ([Progression](Progression.md));
9. **morale** — last, because it is a reaction to everything above;
10. **threshold events** — published only on an actual crossing, scoped to a colony id.

On top of that, `colonyTickBudgetMs` (default 5 ms) caps the total colony work done in any one game
tick. A colony that is due when the budget is spent stays due and runs on a later tick — it is never
skipped.

## Feeding a colony

A colony eats from its **food stock**, an abstract count of rations. Each Neran eats
`foodPerColonistPerCycle` (default 1) rations per cycle. Set it to `0` and colonies are never hungry.

Rations get into the stock in two ways:

- **The beacon's six supply slots** — by hand, by hopper or by pipe. The colony cycle converts each
  food item there into one ration. Staples are drawn down before anything else, so a colony fed from
  a mixed supply line does not eat the rare item first.
- **Cooks.** Each Cook takes one food item from *colony storage* per daytime cycle and turns it into
  three rations.

That second route matters, because **the food a colony's Farmers gather goes into colony storage,
not into the food stock**. Without a Canteen and a Cook, wheat piling up in storage feeds nobody
until you move it to the supply slots yourself.

What counts as food is decided entirely by two item tags, `nerocolonies:colony_food` and
`nerocolonies:colony_food/staple`. NeroColonies hard-codes no food item anywhere, so any farming mod
that follows the common tag conventions feeds a colony with no compat code, and a pack author can
redefine the whole diet without touching Java.

## See also

- [Progression](Progression.md) — the five stages and what moves a colony between them
- [Nerans and professions](Nerans-and-Professions.md) — the people and their trades
- [Buildings](Buildings.md) — what a colony builds, and where
- [Construction](Construction.md) — founders, the build loop and the needs list
- [Roles and defence](Roles-and-Defence.md) — who may do what, and how a colony defends itself
- [Life support](Life-Support.md) — the other half of survival
- [Jobs & research](Jobs-and-Research.md) — job stations and the research tree
- [Exports & outposts](Exports-and-Outposts.md) — selling the surplus, and remote work sites
- [Config](Config.md) — every key named on this page
- [Data storage](Data-Storage.md) — what a colony record holds about a player
