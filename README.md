# NeroColonies

> Part of the Neroland sci-fi Minecraft mod ecosystem, built on **Neroland Core**.

**Status:** version `0.3.0-beta.1`, the first beta. All nine cells build, `ecjCheck` passes and the
JUnit suite (146 tests) passes. It has not yet been run in a game client, so treat it as a beta.

NeroColonies turns a place into a colony. You plant a **colony beacon**, it claims the ground around
it, and two founders arrive. You supply the first four buildings; after that the colony grows on its
own. Everything belongs to the colony rather than to any one block: one shared store of goods, one
population of **Nerans** with trades of their own, one morale figure, one research tree, one export
buffer.

## Features

- **Colony beacon and claims** — a per-player and a server-wide colony cap, minimum spacing, an
  overlap check, and a claim that widens as the colony grows. The public query surface is
  **boolean-only**: an owner UUID never leaves the server.
- **Five stages** — Founding, Settled, Growing, Thriving and Metropolis. A Founding colony builds
  its four **Starter Works** (a lodge, a farm, a lumber yard and a mine head) only from materials
  you deliver. After that it builds, gathers and grows by itself, slowly at first and then faster.
- **Nerans** — the colony's people. Each takes one of twelve data-driven **trades** (Farmer,
  Forester, Miner, Builder, Hauler, Cook, Toolsmith, Guard, Beastkeeper, Researcher, Quartermaster,
  Medic), earns experience in it, carries its tool and keeps a daily schedule of work, a meal, an
  evening at the plaza and sleep. They are **never removed as a punishment**.
- **The needs list** — the colony says what it is short of (building materials, food, tools) and
  how long it would take to gather alone. Hand items over at a **Needs Board** to speed it up, or
  prioritise one need.
- **Housing and population** — a chunk-budgeted housing sweep that sums capacity and comfort,
  arrivals gated on food and life support, and from the Growing stage **children** born when there
  is food, a free bed and good morale; from then on newcomers arrive less often, so the colony
  grows mostly from within. Two **founders** arrive with the beacon so the loop can start.
- **Construction** — the colony builds itself from **51 blueprints**, from cottages and a granary
  to a citadel, an observatory and an arcology tower. It picks a structure, finds a site inside its
  own claim, clears the natural blocks in the way into colony storage, turns the building to face
  the beacon and lays it a few blocks per cycle. Bring the materials and it builds at full speed;
  leave it alone and it fabricates from scrap at a quarter rate. It never builds outside the claim
  and never breaks a protected block or a block entity. Some buildings upgrade in place.
- **Colony Planner** — the owner and Chiefs can place an unlocked building by hand: preview the
  footprint, confirm, and the plan jumps the colony's own queue.
- **Roles and defence** — an owner, **Chiefs** (may plan and manage members), **Allies** (may use
  the colony) and **Enemies**. Guards, kennel wolves and forge golems attack hostile mobs and
  listed Enemies inside the claim, and nobody else.
- **Gratitude Cache** — a Quartermaster stocks a per-colony chest with gifts for the owner from
  loot tables that improve with the colony's stage.
- **Life support** — an oxygen generator burning Core gas and grid power, with an
  OK → DEGRADED → FAILED state machine. Failure decays morale; it never kills a Neran. Airless
  dimensions come from a planet mod through one adapter, and every dimension is breathable without
  one.
- **Food and morale** — food recognised by tag family, never by hard-coded item id, and a morale
  figure computed from weighted housing, food, life-support, crowding and hazard terms. Every weight
  is a config key.
- **Automated jobs** — four job stations driven by datapack job definitions, running on the
  **colony** tick inside one millisecond budget. Unpowered is slow, not stopped.
- **Colony storage and exports** — one shared stock and a bounded export buffer, both exposed as
  **standard item capabilities**, so pipes, hoppers, AE2 and Create work with no mod-specific API.
  Exports sell for credits through Core's currency API when an economy mod is installed.
- **Research** — a colony-local node graph loaded from datapacks, spent from colony storage.
- **Planetary outposts** — small remote claims parented to a colony, feeding its storage.
- **Offline catch-up** — colonies tick only while loaded; on return, elapsed time is applied at a
  reduced rate and capped, so there is no reason to chunk-load a planet for free yield.
- **Commands** — a player tree (including roles, plans and needs), an operator tree, a datapack
  `reload-check`, the two data-protection commands, and `/nerocolonies gallery`, a creative-mode
  showcase of every building and trade.
- **Companion app support** — eleven read sections and four actions through Core's link API (schema
  version 2), scoped to the colonies the requesting player owns or is a member of. People are
  counts, never names.
- **Everything is datapack-driven** — jobs, professions, research, housing tiers, export tables,
  structure blueprints, the Gratitude Cache loot tables and the land-clearing block tags are all
  data.

Neroland Core is the only hard dependency. Nerospace, NeroAgriculture, NeroLogistics, NeroEconomy and
Energized Power are optional and detected at runtime; remove them all and the mod still runs.

## Documentation

- [Wiki](wiki/Home.md) — player and operator documentation, including
  [Progression](wiki/Progression.md), [Nerans and Professions](wiki/Nerans-and-Professions.md),
  [Buildings](wiki/Buildings.md), [Roles and Defence](wiki/Roles-and-Defence.md),
  [Gratitude Cache](wiki/Gratitude-Cache.md) and [Gallery](wiki/Gallery.md)
- [`PRIVACY.md`](PRIVACY.md) — what is stored, retention, export and erasure, telemetry opt-out
- [`USING-CORE.md`](USING-CORE.md) — every Neroland Core API this mod consumes
- [`CHANGELOG.md`](CHANGELOG.md) — what has shipped so far, with migration notes for existing worlds
- [`CREDITS.md`](CREDITS.md) — where the building designs come from

## Build targets

- **Minecraft:** 26.1.2, 26.2 and 26.3
- **Loaders:** NeoForge, MinecraftForge/Forge, Fabric (the "9 cells")
- **Java:** 25
- Mod id: `nerocolonies` · package `za.co.neroland.nerocolonies`

## Layout

The build is the repo root, with a flattened cross-loader structure driven by Stonecutter:

- `common/` — shared, loader-agnostic source spliced into every loader node
- `fabric/` — Fabric Loom
- `forge/` — ForgeGradle
- `neoforge/` — ModDevGradle
- `stonecutter.gradle` — the real root build script; `build.gradle` is intentionally inert

## Building

```sh
./gradlew :fabric:26.2:build          # one cell
./gradlew :neoforge:26.1.2:build :neoforge:26.2:build :neoforge:26.3:build \
          :forge:26.1.2:build :forge:26.2:build :forge:26.3:build \
          :fabric:26.1.2:build :fabric:26.2:build :fabric:26.3:build   # all nine
./gradlew :neoforge:26.2:test         # the JUnit suite
```

See [`AGENTS.md`](AGENTS.md) / [`CLAUDE.md`](CLAUDE.md) for agent and contributor context.
