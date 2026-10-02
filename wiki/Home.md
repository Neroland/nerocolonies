# NeroColonies Wiki

Player- and contributor-facing documentation for **NeroColonies**, the settlement layer of the
Neroland sci-fi Minecraft mod ecosystem. Built on **Neroland Core**.

NeroColonies turns a place into a colony. You plant a **colony beacon**, it claims the ground
around it, and everything after that is the colony's rather than any one block's: one shared store
of goods, one population of **Nerans**, one morale figure, one research tree, one export buffer. Job
stations do not run their own recipes — the colony's own cycle drives them, on one budget, so twenty
stations stay a design choice rather than a server problem.

**The colony grows through five stages.** Two founders arrive with the beacon. You supply the
materials for the four Starter Works — a lodge, a farm, a lumber yard and a mine head — and from then
on the colony chooses its own buildings, clears its own ground and hands out trades to the people
who arrive. Your lever is supply, not command: leave a Settled colony alone and it still builds,
slowly, from scrap; bring the materials and the same structure goes up four times faster. When you
do want a say, the Colony Planner lets you put a building exactly where you want it.

**Nerans have a day.** They work a trade, eat at midday, gather in the evening and sleep at home.
Farmers harvest, Foresters fell and replant, Guards patrol, and the colony's share of each trade's
goods arrives whether or not anyone is watching.

**A colony has roles.** Its owner can promote Chiefs, admit Allies and mark Enemies. Guards and
guardian animals fight hostile mobs and, if the server allows it, marked Enemies — and nobody else.

The failure curve is deliberately gentle and it always stops short of destruction. Life support
that fails decays morale; morale that collapses stops work and leaves Nerans idle; an unpowered job
station is slow rather than stopped. **No Neran is ever removed as a punishment and nothing a colony
produced is ever silently voided.** A colony that has gone wrong is a problem to solve.

## Contents

### Playing

- [Colony basics](Colony-Basics.md) — founding, claims and spacing, dissolving, housing and the
  housing sweep, population, morale, feeding, and what happens to a colony while nobody is there.
- [Progression](Progression.md) — the five stages, the Starter Works, the growth curve, births and
  children, and what to check when a colony has stopped growing.
- [Nerans and professions](Nerans-and-Professions.md) — the Neran's day, status symbols, names,
  doors and stuck recovery, and the twelve trades.
- [Buildings](Buildings.md) — how a colony chooses and places buildings, the Colony Planner,
  upgrades, land clearing, and a reference table of every shipped building.
- [Construction](Construction.md) — founders, the build loop, supplied versus fabricated builds, and
  the needs list.
- [Roles and defence](Roles-and-Defence.md) — Owner, Chief, Ally and Enemy, who may do what, guards
  and guardian animals, and the retribution rule.
- [Gratitude cache](Gratitude-Cache.md) — the colony's gifts to its owner.
- [Life support](Life-Support.md) — the oxygen generator, Core's gas system, the
  OK → DEGRADED → FAILED state machine, and exactly what a dimension being airless means with and
  without a planet mod installed.
- [Jobs & research](Jobs-and-Research.md) — job stations, the throughput formula, job slots, the
  job board, the research station and the research node graph.
- [Exports & outposts](Exports-and-Outposts.md) — the export buffer as a plain item capability,
  selling for credits, the export tables, and planetary outposts.

### Running a server

- [Commands](Commands.md) — the `/nerocolonies` command tree.
- [Admin guide](Admin-Guide.md) — the operator's view: performance levers, broken datapacks, the
  retention sweep.
- [Config](Config.md) — every configuration key, its default, its range and what it does.
- [Gallery](Gallery.md) — a creative-mode showcase of every building and trade.
- [Data storage](Data-Storage.md) — what NeroColonies persists, erasure, retention and export, in
  practical terms.
- [Telemetry](Telemetry.md) — opt-out crash reporting: what it sends, what it never sends, and how
  to switch it off.

### Extending

- [Content format](Content-Format.md) — the datapack JSON schemas for jobs, research, housing,
  exports, blueprints and professions, with worked examples, and what happens to bad content.
- [Link module](Link-Module.md) — what a Neroland companion app can see and do.

## Requirements

- **Neroland Core** — required, and the only hard dependency. NeroColonies uses Core's registration
  seam, machine base and side config, config framework, energy and gas systems, upgrade modules,
  currency API, progression gates, threshold event bus, space dimension tags,
  entity registration seam and data-erasure hook.
- **Everything else is optional.** With no planet mod installed every dimension is breathable, so
  life support machinery builds and runs but has nothing to hold back; with no economy mod installed
  exports still accumulate but cannot be sold. NeroColonies never *requires* a progression gate to
  be open, and it hard-depends on no third-party mod.

## Privacy

A colony record holds its owner's Minecraft game UUID and the UUIDs on its access list, and a
separate store holds the UUIDs on its Chief and Enemy lists. They are kept only while the colony
exists, and one erasure request removes a player from all of them. Commands answer with counts and
the public query surface answers boolean questions ("is this claimed?", "may this player build
here?"); neither returns an identity. The one place member names are shown is the beacon's Roles
tab, to somebody who may manage that colony's members. An optional access log is **off by default**.
Research is colony-local, not personal. See [Data storage](Data-Storage.md) for the practical
version and [`../PRIVACY.md`](../PRIVACY.md) for the formal statement. Crash reporting is opt-out,
PII-free and covers this mod's own crashes only ([Telemetry](Telemetry.md)).

## See also

- [Changelog](../CHANGELOG.md)
