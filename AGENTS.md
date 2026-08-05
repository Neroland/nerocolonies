# Project context for AI coding agents — nerocolonies

> `CLAUDE.md` and `AGENTS.md` are kept **byte-identical**; update both together.

## The mod

- **NeroColonies** — the settlement layer of the Neroland sci-fi Minecraft mod ecosystem, built on
  **Neroland Core**. A colony beacon claims ground; everything after that belongs to the colony
  rather than to any one block — one shared store, one population of interchangeable colonists, one
  morale figure, one research tree, one export buffer.
- Mod id: **`nerocolonies`** (matches the registry namespace + every loader manifest). Package root:
  `za.co.neroland.nerocolonies`. Author: **Neroland**.
- Version: **0.0.1-alpha.1**. The 0.1.0 feature set is **implemented and compile-verified**; runtime
  verification in a dev client is the remaining stage before `0.1.0-beta.1`.
- Targets **MC 26.1.2 AND 26.2** on **NeoForge, MinecraftForge/Forge, and Fabric** → the **"6 cells"**.
  **Java 25.** Mappings = official Mojang names (26.x ships de-obfuscated; no Parchment).
- **Neroland Core is the only hard dependency** (floor 1.10.0). Nerospace, NeroAgriculture,
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
  payload, command, snapshot, event or alert. Membership is reported as a **count**.
- **Every `SavedData` accessor goes through `data/SavedDataRecovery`.** A direct
  `getDataStorage().computeIfAbsent(...)` is a review failure.
- **Every `openMenu` call goes through `menu/MenuOpener`.**
- **`Colony` is at the 16-field `RecordCodecBuilder` ceiling.** New per-colony state goes in a side
  store (see `ColonyStores`), not on the record.
- **Nothing is gated.** `progression/ColonyGates` *writes* two soft gates for other mods to read and
  never reads one. `ProgressionGates.tryOpen`, never `open`.
- **Broadcasts and threshold events carry a colony id, never a person.**
- **Graceful failure, always.** Life support loss → morale decay → work stop → idle. Colonists are
  never deleted; produced goods are never voided.
- **The link module is registered LAST in `NeroColoniesCommon.init()`, wholly inside a `try/catch`.**

## Build & verify

- Build the cells with the Gradle wrapper, e.g. `./gradlew :fabric:26.2:build` or all six:
  `:neoforge:26.1.2:build :neoforge:26.2:build :forge:26.1.2:build :forge:26.2:build
  :fabric:26.1.2:build :fabric:26.2:build`. **Never plain `build`.**
- Static analysis: `./gradlew :fabric:26.2:ecjCheck` (the VS Code Problems panel, via `tools/ecj.prefs`).
  The task only FAILS on errors.
- A Cowork agent sandbox cannot decompile Minecraft — run builds natively (or via the local gradle MCP)
  on the developer's machine.
- **Verify the cells build before marking a task done.** Never sign off on an uncompiled change.

## Repo layout — flattened cross-loader build

- **The build IS the repo root.** `common/` (shared source spliced into every node), `neoforge/`
  (ModDevGradle), `forge/` (ForgeGradle), `fabric/` (Fabric Loom). Root build files: `settings.gradle`,
  `stonecutter.gradle` (the REAL root build script; Stonecutter repoints `buildFileName` here — the root
  `build.gradle` is inert), `gradle.properties`, `gradlew`, `gradle/`.
- **Version/loader axis = Stonecutter.** Each loader×MC is a real node `:<loader>:<mc>`
  (`:fabric:26.1.2 :fabric:26.2 :neoforge:26.1.2 :neoforge:26.2 :forge:26.1.2 :forge:26.2`). `common` is
  NOT a node — its source is spliced via `rootProject.ext.commonJava` / `commonResources`. Dependency pins
  live in `gradle.properties` as `*_version_<mc>` keys; `mc_versions=26.1.2,26.2`.

## Package map

```text
za.co.neroland.nerocolonies
├── NeroColoniesCommon            the 11-step init(); link module is step 11
├── config/ telemetry/ platform/  Core config, opt-out Sentry, ServiceLoader seams
├── network/                      own channel, snapshot + definitions payloads, intents
├── data/                         erasure registration, SavedDataRecovery
├── lifecycle/ServerStateReset    server started/stopped; clears the JVM-lifetime caches
├── colony/                       Colony, ColonyState, ColonyApi, ColonyClaims, AccessLog,
│                                 ColonyTicker, ColonyCatchUp, ColonyStorage/Stores, ExportBuffer,
│                                 HousingScan, JobBoard, Morale, LifeSupport, Population, Research,
│                                 Construction + ColonyConstruction (autonomous building)
├── content/                      ColonyDefinitions + the five datapack record types + effects
├── registry/ block/ item/ menu/  registration, blocks and block entities, menus (via MenuOpener)
├── entity/                       ColonistEntity + goals
├── client/                       client caches, screens, renderers
├── command/NeroColoniesCommands  the whole /nerocolonies tree, built once in common
├── link/                         ColonyLinkModule/Snapshots/Actions/Events/Access
├── progression/ColonyGates       two soft gates, written and never read
└── compat/                       CompatRegistry + the one reflective Nerospace bridge
```

## Conventions (cross-loader)

- **Resources are HAND-AUTHORED in `common/src/main/resources`** — the multiloader does not run datagen.
  Validate JSON after edits. Gameplay content (jobs, research, housing, exports, blueprints) lives under
  `data/nerocolonies/nerocolonies/**` and is loaded at runtime, not baked in.
- **Platform seams via ServiceLoader (no Architectury).** Put loader-agnostic code in `common/`; ship one
  impl per loader plus a `META-INF/services` entry. Keep `common/` free of `net.neoforged.*` /
  `net.fabricmc.*` / `net.minecraftforge.*` imports.
- **Resolve every service during construction/`init()`, never lazily mid-tick.**
- Loader entry points: `NeroColoniesFabric` (+ `NeroColoniesFabricClient`), `NeroColoniesForge`,
  `NeroColoniesNeoForge` — each calls `NeroColoniesCommon.init()` during construction, then wires its own
  networking, capabilities and events (`*ColonyEvents`: commands + server started/stopped).
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
- The wiki is **per-mod** — document only NeroColonies here; cross-mod / ecosystem concepts live in the
  umbrella docs and are referenced by relative path.
- `PRIVACY.md` and `USING-CORE.md` at the repo root are part of the same contract: privacy behaviour
  and the Core API surface must stay true of the code.

## DO NOT

- Commit or push automatically — leave changes staged for the developer.
- Hard-code absolute machine paths in committed files.
- Add loader-specific code to `common/` — use the platform seams.
- Return an owner UUID, a player name or an access list from any public API, payload, command,
  snapshot, event or alert.
- Add a hard dependency on any mod other than Neroland Core.
