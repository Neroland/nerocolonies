# Project context for AI coding agents — nerocolonies

> `CLAUDE.md` and `AGENTS.md` are kept **byte-identical**; update both together.

## The mod

- **NeroColonies** — the settlement layer of the Neroland sci-fi Minecraft mod ecosystem, built on
  **Neroland Core**. A colony beacon claims ground and two founders arrive; the player supplies four
  Starter Works, and from then on the colony grows through five stages on its own. Everything
  belongs to the colony rather than to any one block — one shared store, one population of
  **Nerans** (each with a trade), one morale figure, one research tree, one export buffer, one
  needs list, one Gratitude Cache.
- **Player-facing name: Nerans.** The colony's people are *Nerans* in every string a player reads.
  The entity id stays `nerocolonies:colonist`, the classes stay `Colonist*` and the config keys
  stay `*Colonist*`, so existing worlds and configs load. Never rename any of them.
- Mod id: **`nerocolonies`** (matches the registry namespace + every loader manifest). Package root:
  `za.co.neroland.nerocolonies`. Author: **Neroland**.
- Version: **0.3.0-beta.1**, the first beta: the living-colony rework (stages, professions, 51
  blueprints, roles and defence, the Gratitude Cache, the Colony Planner, link schema 2, the
  gallery). `docs/AUDIT-2026-10.md` is the audit that opened it. Verified by the nine-cell build,
  `ecjCheck` and the JUnit suite; **not yet run in a game client**, so never write that it was.
- Targets **MC 26.1.2, 26.2 AND 26.3** on **NeoForge, MinecraftForge/Forge, and Fabric** → the **"9 cells"**.
  **Java 25.** Mappings = official Mojang names (26.x ships de-obfuscated; no Parchment).
- **Neroland Core is the only hard dependency** (floor 1.13.0, the `nerolandcore_version` pin). Nerospace, NeroAgriculture,
  NeroLogistics, NeroEconomy and Energized Power are optional, detected once at init, and interop
  runs through tags and capabilities. No third-party mod is a dependency in any build script.

## Working rules

- **Keep responses concise and direct** — minimal verbosity, minimal formatting.
- **POPIA & GDPR**: keep all logging/telemetry/scripts compliant — only public version strings, never
  personal data; minimise data, set retention limits, support export/erasure and opt-out.
- **NEVER commit or push automatically.** Leave changes **staged**; the developer reviews and commits
  with native git (the source of truth).
- **Use relative paths only** — never hard-code machine-specific absolute paths in committed files.
- **Never run commands against production databases.** Treat any DB command as illustrative.

## Architecture rules that are not negotiable

- **Server-authoritative.** The client renders synced state and never decides a colony outcome. Every
  intent off the wire is re-derived server-side (reach, claim, permission, op code) before it acts.
- **The public ownership surface is boolean-only.** `ColonyApi` answers "is this claimed?", "may this
  player build here?" — it never returns an owner UUID or a player name, and neither does any
  payload, command, snapshot, event, alert, log line or telemetry field. Membership is reported as a
  **count**; the link surface adds only the requester's **own** role.
- **One narrow exception: the Roles tab.** A viewer who may manage a colony's members (owner, Chief
  or operator) is sent the *names* on that colony's Ally, Chief and Enemy lists, resolved on the
  server when `ColonySnapshotPayload` is built, never stored or logged, and never with a UUID on the
  wire. Nobody else is sent names, and nothing else may copy this exception.
- **Every permission question goes through `colony/ColonyPermissions`** (`can`, `check`, `allows`)
  and every role change through `colony/ColonyMembership`, so commands, the GUI and link actions
  cannot disagree. Hierarchy: Owner ⊃ Chief ⊃ Ally; operators act as owner in game, never over the
  link. An Enemy is never a member.
- **Role lists are the only stored player ids besides the owner slot.** Allies are the access list
  on the `Colony` record; Chiefs and Enemies live in `ColonyRoles`. Each list is capped at 64,
  lives as long as its colony, and is purged by the erasure hook in `data/NeroColoniesData` —
  an Enemy listing included. Kill attribution (the retribution rule) and the Colony Planner's
  selection are memory-only: never store or log either.
- **Every `SavedData` accessor goes through `data/SavedDataRecovery`.** A direct
  `getDataStorage().computeIfAbsent(...)` is a review failure.
- **Every `openMenu` call goes through `menu/MenuOpener`.**
- **`Colony` is at the 16-field `RecordCodecBuilder` ceiling and stays at 16 fields.** New
  per-colony state goes in a side store, not on the record: `ColonyStores` (goods),
  `ColonyConstruction` (builds, finished structures, plans), `ColonyRoles` (Chiefs and Enemies) and
  `ColonyLife` (stage, counters, Gratitude Cache, prioritised need, sandbox flag). Every dissolve
  path, erasure under the `dissolve` policy included, must forget all of them at once:
  `Construction.forget` drops the build record, the role lists and the life record, and the goods
  go through `ColonyStores`.
- **A Neran carries only this:** colony id, home, workstation and job, founder flag and arrival
  time, profession id and experience, a nine-slot carry inventory, child flag and birth time, and
  an optional generated display name from the lang-file pool. Nothing player-shaped goes on the
  entity, and a generated name never comes from a player.
- **The colony changes blocks only under the land rules.** Construction, land clearing and the
  Farmer and Forester behaviours break and place blocks, always inside the claim (re-checked per
  cell), never a block in `nerocolonies:protected`, never a block entity, never into a cell a
  living entity occupies, and with every drop going to colony storage. Clearing touches only
  `nerocolonies:clearable`.
- **Only Guards and guardian animals fight, and `ColonyDefence.isValidTarget` is the one rule:**
  hostile mobs (not creepers) and players on the colony's Enemy list, inside the claim plus
  `guardPursuitMargin`. Nothing else is ever targeted.
- **Autonomous planning is the default.** Only the owner and Chiefs place buildings by hand (the
  Colony Planner), every placement is validated on the server, and a plan is paid for and built
  like any other building.
- **Population grows two ways, births first.** From the Growing stage a birth is rolled before
  arrivals each cycle (two adults, one free bed, a food surplus, morale). Arrivals into free housing
  are gated on food and life support, and once a colony is Growing with breeding on a newcomer comes
  only every `immigrationIntervalCycles` cycles (`Population.arrivalDue`).
- **The stuck rescue is the only teleport.** `Construction.rescue` moves a Neran that gave up a
  walk only when it is inside or against a colony structure or the active site with no standable
  neighbour, to the nearest safe spot within 8 blocks in the claim. Never widen it.
- **A claim grows only into free ground.** The growth bonus goes through `ColonyClaims.clampGrowth`
  and never reaches a neighbouring colony's claim.
- **Goods come from the colony tick.** A trade's `outputs` are gathered in `Professions.produce`
  whether or not a Neran is loaded; the visible behaviours in `entity/ai/profession` are extra.
- **No Core gate is ever required.** `progression/ColonyGates` *writes* two soft gates for other
  mods to read and never reads one. `ProgressionGates.tryOpen`, never `open`. Colony stages are the
  mod's own progression: they live in `ColonyLife` and only ever go up.
- **Broadcasts and threshold events carry a colony id, never a person.**
- **The gallery's sandbox colony stays invisible:** it never runs a colony cycle and never appears
  in a link section, event, alert, gate write or threshold event. Never mark a real player as an
  Enemy in it.
- **Graceful failure, always.** Life support loss → morale decay → work stop → idle. Nerans are
  never removed as a punishment (losing housing is the only removal path); produced goods are
  never voided, and a Gratitude Cache roll that does not fit is skipped whole, never half added.
- **The link module is registered LAST in `NeroColoniesCommon.init()`, wholly inside a `try/catch`.**
  Bump `ColonyLinkModule.SCHEMA_VERSION` (now 2) whenever a section's shape changes.

## Build & verify

- Build the cells with the Gradle wrapper, e.g. `./gradlew :fabric:26.2:build` or all nine:
  `:neoforge:26.1.2:build :neoforge:26.2:build :neoforge:26.3:build :forge:26.1.2:build :forge:26.2:build :forge:26.3:build
  :fabric:26.1.2:build :fabric:26.2:build :fabric:26.3:build`. **Never plain `build`.**
- Static analysis: `./gradlew :fabric:26.2:ecjCheck` (the VS Code Problems panel, via `tools/ecj.prefs`).
  The task only FAILS on errors.
- Tests: `./gradlew :neoforge:26.2:test` runs the pure-JVM JUnit suite in `common/src/test/java`
  (146 tests in 12 classes; also part of every `:neoforge:<mc>:build`). `common` is source-only, so
  the NeoForge nodes own test execution. Model tests only: no server or client is started. Put the
  pure half of new logic where a test can reach it.
- A Cowork agent sandbox cannot decompile Minecraft — run builds natively (or via the local gradle MCP)
  on the developer's machine.
- **Verify the cells build before marking a task done.** Never sign off on an uncompiled change.

## Repo layout — flattened cross-loader build

- **The build IS the repo root.** `common/` (shared source spliced into every node), `neoforge/`
  (ModDevGradle), `forge/` (ForgeGradle), `fabric/` (Fabric Loom). Root build files: `settings.gradle`,
  `stonecutter.gradle` (the REAL root build script; Stonecutter repoints `buildFileName` here — the root
  `build.gradle` is inert), `gradle.properties`, `gradlew`, `gradle/`.
- **Version/loader axis = Stonecutter.** Each loader×MC is a real node `:<loader>:<mc>`
  (`:fabric:26.1.2 :fabric:26.2 :fabric:26.3 :neoforge:26.1.2 :neoforge:26.2 :neoforge:26.3 :forge:26.1.2 :forge:26.2 :forge:26.3`). `common` is
  NOT a node — its source is spliced via `rootProject.ext.commonJava` / `commonResources`. Dependency pins
  live in `gradle.properties` as `*_version_<mc>` keys; `mc_versions=26.1.2,26.2,26.3`.
- **Version-specific code in `common/`.** Non-active nodes run `common/` through Stonecutter (`stonecutterProcessCommon`), so shared code uses the same `//? if >=26.3 {` blocks as the loader `src/` trees. Keep the files in the vcsVersion state, and never put a `*/` inside a disabled block. Datapack files whose format differs by version go in `common/src/main/resources-<mc>/`, which is merged over `common/src/main/resources` for every node at or above `<mc>` (`mergeCommonResources`).

## Package map

```text
za.co.neroland.nerocolonies
├── NeroColoniesCommon            the 11-step init(); link module is step 11
├── config/ telemetry/ platform/  Core config, opt-out Sentry, ServiceLoader seams
├── network/                      own channel, snapshot + definitions payloads, intents
├── data/                         erasure registration, SavedDataRecovery, LenientCodecs
├── lifecycle/ServerStateReset    server started/stopped; clears the JVM-lifetime caches
├── colony/                       Colony, ColonyState, ColonyApi, ColonyClaims, AccessLog,
│                                 ColonyTicker, ColonyCatchUp, ColonyStorage/Stores, ExportBuffer,
│                                 HousingScan, JobBoard, Morale, LifeSupport, Population, Research,
│                                 Construction + ColonyConstruction (building, clearing, plans),
│                                 ColonyBuildings (finished buildings and their roles),
│                                 ColonyStage, Growth, ColonyProgress (stages and the curve),
│                                 ColonyNeeds, Professions, ColonyPlanner, GratitudeCache,
│                                 ColonyRoles, ColonyPermissions, ColonyMembership, ColonyDefence,
│                                 ColonyLife (side store), ColonyGallery + GalleryLayout/Court/Kit
├── content/                      ColonyDefinitions + the six datapack record types + effects;
│                                 Blueprint, BlockStateText, StructureNbt, ProfessionDefinition
├── registry/ block/ item/ menu/  registration, blocks and block entities, menus (via MenuOpener)
├── entity/                       ColonistEntity (the Neran), NeranStatus
│   └── ai/                       DayCycle, WalkToGoal + the walk goals, WorkGoal, GuardTargetGoal,
│                                 nav/ (NeranNavigation, TargetResolver, StuckDetector),
│                                 profession/ProfessionBehaviours (the twelve behaviours)
├── client/                       client caches, screens, renderers
├── command/                      NeroColoniesCommands (the /nerocolonies tree, built once in
│                                 common) + GalleryCommand
├── link/                         ColonyLinkModule/Snapshots/Sections/Actions/Events/Access
├── progression/ColonyGates       two soft gates, written and never read
└── compat/                       CompatRegistry, the one reflective Nerospace bridge, and the
                                  JEI/EMI information pages
```

## Conventions (cross-loader)

- **Resources are HAND-AUTHORED in `common/src/main/resources`** — the multiloader does not run datagen.
  Validate JSON after edits. Gameplay content (jobs, research, housing, exports, blueprints,
  professions) lives under `data/nerocolonies/nerocolonies/**` and is loaded at runtime, not baked in.
- **The one exception is the blueprint catalogue**, which is generated: change
  `tools/gen_blueprints.py` and run `python3 tools/gen_blueprints.py` from the repo root (it
  overwrites the blueprint JSON and runs `tools/check_blueprints.py`). A hand edit to a generated
  blueprint is lost on the next run. Every shipped design must stay original; an imported design
  needs a licence that allows redistribution and a row in `CREDITS.md`.
- **Platform seams via ServiceLoader (no Architectury).** Put loader-agnostic code in `common/`; ship one
  impl per loader plus a `META-INF/services` entry. Keep `common/` free of `net.neoforged.*` /
  `net.fabricmc.*` / `net.minecraftforge.*` imports.
- **Resolve every service during construction/`init()`, never lazily mid-tick.**
- Loader entry points: `NeroColoniesFabric` (+ `NeroColoniesFabricClient`), `NeroColoniesForge`,
  `NeroColoniesNeoForge` — each calls `NeroColoniesCommon.init()` during construction, then wires its own
  networking, capabilities and events (`*ColonyEvents`: commands, server started/stopped, a living
  death for the retribution rule, and a player leaving to drop the planner's selection).
- NeoForge/Forge debug tasks use `-PnerocoloniesDebug`; Fabric Loom honours Gradle `--debug-jvm`.

## IDE (VS Code) run & debug

- Workspace: **`nerocolonies.code-workspace`** (single-root `"."`). Import the Stonecutter nodes as **static
  Eclipse projects**: `./gradlew eclipse` (live Buildship/Loom import is disabled —
  `java.import.gradle.enabled=false`). Re-run `./gradlew eclipse` after dependency changes, then reload
  VS Code. Per-node Eclipse project names are `nerocolonies-<loader>-<mc>`.
- **Run/Debug** a cell from `tasks.json` / `launch.json`.

## Wiki — keep `wiki/` updated

- This mod has its own **dedicated wiki** in `wiki/` at the repo root: the player- and
  contributor-facing docs for NeroColonies. It is published to the GitHub wiki by `wiki.yml`, and
  `wiki-guard.yml` blocks private references — **edit `wiki/`, never the `.wiki` repo**.
- **Whenever you add, change, or remove a feature, update `wiki/` in the same change** — treat the
  wiki as part of "done"; code without a matching wiki update is incomplete.
- One page per topic; keep `wiki/Home.md` as the index that links every page, with relative links
  between pages. Validate Markdown via the gradle MCP `markdown_check` (honours `.markdownlint.json`).
- The wiki is **per-mod** — document only NeroColonies here; cross-mod / ecosystem concepts belong in the
  relevant other mod's own wiki.
- The living-colony pages are `wiki/Progression.md`, `wiki/Nerans-and-Professions.md`,
  `wiki/Buildings.md`, `wiki/Roles-and-Defence.md`, `wiki/Gratitude-Cache.md` and `wiki/Gallery.md`.
- `PRIVACY.md` and `USING-CORE.md` at the repo root are part of the same contract: privacy behaviour
  and the Core API surface must stay true of the code. `art/*.md` are the public store pages: keep
  them true as well, and never reference `docs/` or any internal file from them.

## DO NOT

- Commit or push automatically — leave changes staged for the developer.
- Hard-code absolute machine paths in committed files.
- Add loader-specific code to `common/` — use the platform seams.
- Return an owner UUID, a player name or an access list from any public API, payload, command,
  snapshot, event or alert (the Roles-tab roster above is the only exception).
- Store or log a player name, a killer's identity or a planner selection.
- Rename the `nerocolonies:colonist` entity id or the `Colonist*` classes, or say "colonist" in
  player-facing text.
- Grow the `Colony` record past 16 fields.
- Add a hard dependency on any mod other than Neroland Core.
