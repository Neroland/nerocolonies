# NeroColonies Wiki

Player- and contributor-facing documentation for **NeroColonies**, the settlement layer of the
Neroland sci-fi Minecraft mod ecosystem. Built on **Neroland Core**.

NeroColonies turns a place into a colony. You plant a **colony beacon**, it claims the ground
around it, and everything after that is the colony's rather than any one block's: one shared store
of goods, one population of interchangeable colonists, one morale figure, one research tree, one
export buffer. Job stations do not run their own recipes — the colony's own cycle drives them, on
one budget, so twenty stations stay a design choice rather than a server problem.

**The colony builds itself.** Two founders arrive with the beacon and start putting up a habitat with
no instruction from you at all. Your lever is supply, not command: leave a colony alone and it still
grows, slowly, fabricating from scrap; bring the materials and the same structure goes up four times
faster. This is an automation sink, not a management sim — there is no build queue to micromanage and
no colonist to name.

The failure curve is deliberately gentle and it always stops short of destruction. Life support
that fails decays morale; morale that collapses stops work and leaves colonists idle; an unpowered
job station is slow rather than stopped. **No colonist is ever deleted as a punishment and nothing
a colony produced is ever silently voided.** A colony that has gone wrong is a problem to solve.

## Contents

- [Colony basics](Colony-Basics.md) — founding, claims and spacing, the access list, dissolving,
  colonists, housing tiers and the housing sweep, population growth, morale, and what happens to a
  colony while nobody is there.
- [Construction](Construction.md) — founder colonists, the autonomous build loop, supplied versus
  fabricated builds, where a colony may and may not build, and the blueprint datapack format.
- [Life support](Life-Support.md) — the oxygen generator, Core's gas system, the
  OK → DEGRADED → FAILED state machine, and exactly what a dimension being airless means with and
  without a planet mod installed.
- [Jobs & research](Jobs-and-Research.md) — job stations, the throughput formula, job slots, the
  job board, the research station and the research node graph.
- [Exports & outposts](Exports-and-Outposts.md) — the export buffer as a plain item capability,
  selling for credits, the export tables, and planetary outposts.
- [Content format](Content-Format.md) — the four datapack JSON schemas (jobs, research, housing,
  exports) with worked examples, the research effect types, and what happens to bad content. The
  fifth schema, blueprints, lives in [Construction](Construction.md).
- [Commands](Commands.md) — the `/nerocolonies` command tree.
- [Admin guide](Admin-Guide.md) — the operator's view: performance levers, broken datapacks, the
  retention sweep.
- [Config](Config.md) — every configuration key, its default, its range and what it does.
- [Link module](Link-Module.md) — what a Neroland companion app can see and do.
- [Data storage](Data-Storage.md) — what NeroColonies persists, erasure, retention and export, in
  practical terms.
- [Telemetry](Telemetry.md) — opt-out crash reporting: what it sends, what it never sends, and how
  to switch it off.

## Requirements

- **Neroland Core** — required, and the only hard dependency. NeroColonies uses Core's registration
  seam, machine base and side config, config framework, energy and gas systems, upgrade modules,
  shared creative tab, currency API, progression gates, threshold event bus, space dimension tags,
  entity registration seam and data-erasure hook.
- **Everything else is optional.** With no planet mod installed every dimension is breathable, so
  life support machinery builds and runs but has nothing to hold back; with no economy mod installed
  exports still accumulate but cannot be sold. NeroColonies never *requires* a progression gate to
  be open, and it hard-depends on no third-party mod.

## Privacy

A colony record holds its owner's Minecraft game UUID and the UUIDs on its access list. Those never
leave the server: the public query surface answers boolean questions ("is this claimed?", "may this
player build here?") and never returns an identity. An optional access log is **off by default**.
Research is colony-local, not personal. See [Data storage](Data-Storage.md) for the practical
version and [`../PRIVACY.md`](../PRIVACY.md) for the formal statement. Crash reporting is opt-out,
PII-free and covers this mod's own crashes only ([Telemetry](Telemetry.md)).

## See also

- [Build & contributor context](../AGENTS.md)
- [Changelog](../CHANGELOG.md)
