# NeroColonies

> Part of the Neroland sci-fi Minecraft mod ecosystem, built on **Neroland Core**.

**Status:** 0.1.0 feature-complete and compile-verified across all six cells; runtime verification is
the remaining stage. Version `0.0.1-alpha.1`.

NeroColonies turns a place into a colony. You plant a **colony beacon**, it claims the ground around
it, and everything after that belongs to the colony rather than to any one block: one shared store of
goods, one population of interchangeable colonists, one morale figure, one research tree, one export
buffer.

## Features

- **Colony beacon and claims** — a per-player and a server-wide colony cap, minimum spacing, an
  overlap check, and an owner plus an access list. The public query surface is **boolean-only**: an
  owner UUID never leaves the server.
- **Colonists** — interchangeable labour units with four fields and no schedule. They path between
  home and workstation, and they are **never deleted as a punishment**.
- **Housing and population** — three habitat tiers, a chunk-budgeted housing sweep that sums capacity
  and comfort, and growth gated on food and life support. Two **founder colonists** arrive with the
  beacon so the loop can start.
- **Autonomous construction** — the colony builds itself from datapack **blueprints**: it picks a
  structure, finds a flat spot inside its own claim, and lays a couple of blocks per cycle. Bring the
  materials and it builds at full speed; leave it alone and it fabricates from scrap at a quarter
  rate. It never overwrites an existing block, never builds outside the claim, and never demolishes
  anything.
- **Life support** — an oxygen generator burning Core gas and grid power, with an
  OK → DEGRADED → FAILED state machine. Failure decays morale; it never kills a colonist. Airless
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
- **Commands** — a player tree, an operator tree, a datapack `reload-check`, and the two
  data-protection commands.
- **Companion app support** — five read sections and two actions through Core's link API, scoped to
  the requesting player's own colonies.
- **Everything is datapack-driven** — jobs, research, housing tiers, export tables and structure
  blueprints are all JSON.

Neroland Core is the only hard dependency. Nerospace, NeroAgriculture, NeroLogistics, NeroEconomy and
Energized Power are optional and detected at runtime; remove them all and the mod still runs.

## Documentation

- [Wiki](wiki/Home.md) — player and operator documentation
- [`PRIVACY.md`](PRIVACY.md) — what is stored, retention, export and erasure, telemetry opt-out
- [`USING-CORE.md`](USING-CORE.md) — every Neroland Core API this mod consumes
- [`CHANGELOG.md`](CHANGELOG.md) — what has shipped so far

## Build targets

- **Minecraft:** 26.1.2 and 26.2
- **Loaders:** NeoForge, MinecraftForge/Forge, Fabric (the "6 cells")
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
./gradlew :neoforge:26.1.2:build :neoforge:26.2:build \
          :forge:26.1.2:build :forge:26.2:build \
          :fabric:26.1.2:build :fabric:26.2:build   # all six
```

See [`AGENTS.md`](AGENTS.md) / [`CLAUDE.md`](CLAUDE.md) for agent and contributor context.
