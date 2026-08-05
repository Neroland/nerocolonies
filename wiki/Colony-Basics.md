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

A successful placement also puts **`founderColonistCount` colonists (default 2) on the ground next to
the beacon, immediately**. They are the seed of the autonomous build loop — see
[Construction](Construction.md).

Founding also asks Neroland Core to open its `first_colony` progression gate. It *asks* — the gate
has its own requirements and NeroColonies does not force past them, and nothing in this mod ever
requires a gate to be open. The write is a signal to the rest of the ecosystem and can be switched
off with `gateWritesEnabled`.

## The claim

A claim is a **square** centred on the beacon, `claimRadius` blocks in each horizontal direction —
97 blocks across at the default 48 — and unlimited vertically. `RANGE` upgrade modules in the beacon
widen it.

Claims are permissive by design. NeroColonies claims exist so that a colony can be run, not so that
the world can be fenced off: **unclaimed ground is always buildable by anyone**, and a claim only
ever refuses somebody inside it.

## Who may do what

Three tiers, and no more:

- **The owner** — the player who placed the beacon. One UUID on the colony record.
- **Access-list members** — up to 64 UUIDs the owner adds. A member may do everything an owner may,
  except dissolve the colony.
- **Operators** — permission level 2 or better, which overrides both of the above.

A colony can also be **ownerless**: after a data-erasure request under the default policy the owner
slot is handed to the server, and the colony keeps running with operators able to administer it. See
[Data storage](Data-Storage.md).

Capture, contest and faction interaction are explicitly out of scope: a claim is an owner, a list
and an operator override.

## Dissolving a colony

**Sneak and break the beacon, as the owner or an operator.** Anybody else — and anybody not
crouching — finds the block simply refuses to break. The sneak is the confirmation prompt: a colony
record is far too expensive to lose to a stray pickaxe swing.

Dissolving drops the colony's whole store (working stock *and* export buffer) at the beacon, deletes
the colony record, and takes its access-log rows and its research with it. Its outposts go too — an
outpost has no independent existence.

A colony whose beacon disappeared without a break event (an explosion, a world edit) is caught by
the retention sweep instead: see [Admin guide](Admin-Guide.md).

## Colonists

A colonist is an **interchangeable labour unit** and deliberately nothing more. It has exactly four
persistent fields:

- the colony it belongs to;
- its home position;
- its workstation position;
- the job it is assigned to.

No names, no personalities, no schedules, no build requests, no relationships and no inventory of
their own. That is a structural decision rather than a matter of restraint: there is nowhere to put
such a detail. A pleasant side effect is that a colonist carries **nothing player-shaped at all** —
it does not even know who owns its colony.

Colonists use vanilla goal AI only: float, walk to the workstation or the current build site, walk
home, stay inside the claim, look around. They **never break blocks, never place blocks themselves,
never open doors, never attack, and are never made hostile by anything in this mod** — there is no
target selector on the entity at all. A colony's own construction is colony-tick logic, not something
a colonist does with its hands; the builder walking to the site is presentation
([Construction](Construction.md)).

With no owner or access-list member within `aiActiveRadius` (default 64 blocks) a colonist goes
*quiet*: its NeroColonies goals run on one tick in four and its navigation stops. Pathfinding is the
expensive part of a colonist, and that is exactly what stops.

### Colonists are never deleted as a punishment

This is worth stating plainly because it is the single most important rule in the mod. Starvation
and life-support failure decay morale. Morale collapse stops work and leaves everyone idle. **None
of those three ever removes a colonist.** The only path by which a colonist leaves is losing the
housing that seats it (see below), because a bunk that no longer exists cannot be slept in.

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

Capacity is how many colonists the block seats; comfort (0–1) is its weight in the morale housing
term. A cramped pod can seat the same number of people as a proper module and still feel worse to
live in.

**The scan is budgeted.** Reading every block in a 97-block-wide claim on a cadence would be the
most expensive thing this mod does, so the sweep is a cursor over the claim's chunks: two loaded
chunks per slice, over a band from 6 levels below the beacon to 12 above, with slices 20 ticks
apart. Only when a full cycle closes are the totals committed — a half-finished sweep never makes
capacity flicker — and the colony then rests for `housingScanIntervalTicks` (default 600) before
starting again.

Unloaded chunks are **skipped, never loaded**. A colony whose claim is half-unloaded reports the
housing it can actually see, which is also the housing its colonists could actually reach. Housing
whose tier needs research the colony has not unlocked is not counted at all.

## Population growth

The rules are short:

- **Founders bootstrap.** `founderColonistCount` colonists arrive with the beacon and are held on the
  roster *regardless of housing* — without them nothing could ever start, because housing is what
  lets colonists arrive and building housing is what colonists do. They are a floor, not an
  exemption: they still count toward both caps and take exactly the same survival treatment as
  everybody else. See [Construction](Construction.md).
- **Housing is the cap.** Above the founder floor the colony grows toward
  `min(housing capacity, colonistsPerColony)`, one colonist per colony cycle. Building housing is the
  whole of the population game — there is no birth rate to tune, and the colony builds most of it
  for you.
- **Survival is the gate.** Nobody arrives while life support has failed or the food store is empty.
  A colony in trouble stops growing before it starts shrinking. Replacing a lost *founder* is the one
  exemption, because a colony with nobody left cannot fix the very problems the gate is testing for.
- **Losing housing shrinks the roster.** Surplus colonists leave, newest first, so a colonist who
  has been settled and working is the last to go — never below the founder floor.

A server-wide ceiling, `maxLoadedColonists` (default 300), bounds the total across every colony.

New arrivals appear within six blocks of the beacon on any standable spot; if there is nowhere to
stand, the colony simply tries again next cycle.

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

### The two consequences

- **An output multiplier.** A smooth curve from `moraleMinMultiplier` (default 0.25) at zero morale
  to 1.0 at full. Production is a slope, not a cliff.
- **A work-stop threshold.** Below `moraleWorkStopThreshold` (default 20) jobs halt and colonists
  idle. Individual jobs may also set their own higher `morale_floor`, which blocks that one job
  without stopping the colony.

Those are the *only* consequences. Nothing is destroyed, nobody is removed, and nothing is lost that
cannot be recovered by fixing the cause.

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

Everything is one aggregate step: a colony away for the full 24 hours costs the same to catch up as
one away for a minute.

The alternative — ticking every colony on the server forever — was rejected on three counts:

- **Cost.** It would make the mod's worst case the number of colonies ever founded rather than the
  number currently being played.
- **Exploit surface.** If offline colonies produced at full rate there would be no reason ever to
  visit one, and every reason to found as many as the cap allows and walk away.
- **Honesty.** A colony that keeps producing while unloaded has to invent its inputs, because the
  machines that would have supplied them were not running either.

The 0.5 yield is the compromise: coming back to a colony that has done *something* is a reward, and
it is always strictly worse than having been there. Set `catchUpMaxHours` to `0` to disable
catch-up entirely, in which case colonies only ever produce while loaded.

## The colony cycle

Each colony runs its cycle every `colonyTickIntervalTicks` (default 100), offset by its own id so
two hundred colonies never land on the same game tick. In order:

1. **life support** — the colony's physical situation, before anything reacts to it;
2. **food** — intake from the beacon's supply slots, then the cycle's consumption;
3. **population** — growth gated on the two above;
4. **jobs** — production, inside the shared per-tick budget;
5. **construction** — the colony's own building work, after jobs so builders are drawn from whoever
   production did not need ([Construction](Construction.md));
6. **morale** — last, because it is a reaction to everything above;
7. **threshold events** — published only on an actual crossing, scoped to a colony id.

On top of that, `colonyTickBudgetMs` (default 5 ms) caps the total colony work done in any one game
tick. A colony that is due when the budget is spent stays due and runs on a later tick — it is never
skipped.

## Feeding a colony

Food goes in the **beacon's six supply slots** — by hand, by hopper or by pipe — and the colony tick
converts it into an abstract ration count. Staples are drawn down before anything else, so a colony
fed from a mixed supply line does not eat the rare item on its way to the export buffer.

What counts as food is decided entirely by two item tags, `nerocolonies:colony_food` and
`nerocolonies:colony_food/staple`. NeroColonies hard-codes no food item anywhere, so any farming mod
that follows the common tag conventions feeds a colony with no compat code, and a pack author can
redefine the whole diet without touching Java.

Each colonist eats `foodPerColonistPerCycle` (default 1) rations per cycle. Set it to `0` and
colonies are never hungry.

## See also

- [Construction](Construction.md) — founders, and how a colony builds itself
- [Life support](Life-Support.md) — the other half of survival
- [Jobs & research](Jobs-and-Research.md) — what a colony actually does all day
- [Exports & outposts](Exports-and-Outposts.md) — selling the surplus, and remote work sites
- [Config](Config.md) — every key named on this page
- [Data storage](Data-Storage.md) — what a colony record holds about a player
