# Changelog

All notable changes to **NeroColonies** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

The 0.1.0 build, feature-complete: the foundation wiring, the colony record and claim model, the
colony command block, the datapack content loaders, colonist NPCs and housing, life support, the
colony tick with food and morale, automated jobs and colony storage, research, exports and planetary
outposts, the command tree and privacy surface, the NeroLink module, the compatibility bridges, and
the assets, lang and documentation pass — plus founder colonists and autonomous construction, which
close the loop that makes a colony grow without being told to. Everything below is compile-verified
across all six cells
(`:{fabric,neoforge,forge}:{26.1.2,26.2}:build`) with `ecjCheck` clean on one cell per loader;
runtime verification is the remaining stage.

### Changed

**GUI overhaul — every screen**

- **Every slot now has a frame.** `NeroColoniesScreen` paints an 18x18 recessed well under each slot
  the menu declares, walking `menu.slots` rather than a per-screen list of coordinates, so a well can
  never drift from the slot it belongs to. Groups of slots (supply, modules, the storage grid and the
  player's own inventory) additionally sit in a recessed tray. The screens painted their hull but not
  their slots before this, which left the player looking at floating items on a flat rectangle and
  the player inventory looking detached from the panel entirely.
- **No text can leave the panel.** New `wrappedLabel` / `clampedLabel` / `labelRight` helpers measure
  against the font and fold or ellipsise; every hint, status and datapack-supplied name on all six
  screens goes through one of them. Previously the long hint lines (the research hint, the life-support
  status, the station's five idle reasons) were drawn as plain single-line labels and ran past the
  right edge of the hull.
- **A gauge always has a track.** The trough and its quarter ticks are painted before the fill, so a
  gauge at zero reads as an empty bar rather than as a missing one, and gauges carry a right-aligned
  percentage on their caption line.
- **The beacon's tab strip is a real control.** Tab widths are measured from the font at `init` time
  and spread across the panel with even padding, with distinct idle / hover / selected states and a
  selected tab that opens into the content area below it. The old strip used fixed 33px cells, which
  ran "Colony" and "People" together in English, clipped the selected label, and would have failed in
  most other languages.
- **The beacon panel is 208x236** with the supply and module rows in one labelled band above the
  player inventory, a hint naming the depot as the home for construction materials (they do not go in
  the food row), and the Colony tab's construction readout kept and split into a progress line plus an
  actionable "add its materials to colony storage" line. **No slot index moved** — the menu's slot
  order (3 modules, 6 supply, 27 inventory, 9 hotbar) and its 18-int `ContainerData` are unchanged;
  only slot coordinates did.
- Layout constants for every screen now live on the **menu** and are consumed by the screen, so the
  painted frames and the real slot positions come from one source. The oxygen generator, job station
  and outpost beacon gained a module tray with a caption in the title band; the colony depot gained a
  status row so its unlocked-slot count no longer overprints the first row of its grid; the research
  screen gained a rule between its two panes, a hover state on the list, wrapped node titles and a
  capped cost list, and its pager no longer collides with the player inventory.
- New lang keys: `gui.nerocolonies.slots.modules`, `gui.nerocolonies.value.percent`,
  `gui.nerocolonies.beacon.supply_hint`, `gui.nerocolonies.build.materials_hint`,
  `gui.nerocolonies.access.online_hint`, `gui.nerocolonies.research.cost_more`.

### Added

**Textures — the whole referenced set, generated**

- **17 placeholder textures**, filling every path the mod already referenced: the twelve block faces
  (`colony_beacon`, `outpost_beacon`, `oxygen_generator`, `colony_depot`, `research_station`, the
  four job stations, the three habitat tiers), the four upgrade-module items, and the colonist's
  64x64 entity sheet. Before this, every block and item rendered as the missing-texture checker.
- **`tools/gen_textures.py`** is the entry point — `./gradlew genAssets`, or
  `python tools/gen_textures.py` directly (`--force` to replace the set, `--list` for a dry run).
  It is **additive**: an existing PNG is never overwritten, so hand-drawn replacements survive every
  rerun and the script only fills gaps. It has **no third-party dependency** — PNGs are encoded with
  `zlib` + `struct`, so `genAssets` is green on a bare Python 3 with no Pillow.
- **The art follows the GUI palette**, so a block and its screen read as the same machine: dark hull
  plate (the screens' `0x141C26` panel / `0x2A3A4D` edge) with a per-family accent — colony cyan
  `0x4FB3D9` for the beacons, oxygen cyan `0x6FD3E8` for life support, work green `0x8FD96F` as the
  job stations' shared status strip, depot amber `0xD9A64F`, research violet `0x9F7FE0`. Habitat
  tiers use a lighter panel that brightens per tier. Upgrade modules share one casing and differ
  only in glyph and hue, so they read as a family against the `0x232F3F` slot fill.
- **The colonist sheet is painted against the model's real UV map** — vanilla biped offsets plus the
  6x8x3 suit pack at `texOffs(0, 32)` — rather than as an abstract field, so the helmet visor, chest
  panel, belt, cuffs and boots land on the faces they belong to.
- **Every run reports coverage.** The script scans the mod's own blockstates, models and item
  definitions for `nerocolonies:block/…` / `nerocolonies:item/…` references and the colonist
  renderer for its entity path, then fails if a reference has no painter, a painter has no
  reference, a referenced model file is absent, or a written PNG is missing or empty. Current run:
  17 referenced, 17 painted, 0 orphans.
- Nothing is written into `textures/gui/`: every screen paints procedurally and references no sheet.

**Founder colonists and autonomous construction**

- **Founder colonists.** Placing a colony beacon now puts `founderColonistCount` colonists (default
  2) on the ground next to it immediately, rather than waiting for housing to exist first. They are
  held on the roster regardless of housing capacity — a floor, not an exemption: they still count
  toward `colonistsPerColony` and `maxLoadedColonists`, and they get exactly the same life-support,
  food and morale treatment as anybody else. Replacing a lost founder is the one case exempt from the
  food/life-support growth gate, because a colony with nobody left cannot build the farm or the
  generator that would fix the problem it is being gated on.
- **Autonomous construction.** A colony now builds itself. Every colony cycle a colony with nothing
  under way picks the highest-priority blueprint it is allowed to build, finds a site inside its own
  claim, and lays `constructionBlocksPerCycle` blocks per cycle (default 2) until it is done. The
  player's lever is **supply, not command**: if the blueprint's materials are in colony storage they
  are consumed once and the build runs at full rate; if they are not, the colonists fabricate from
  scrap at `constructionUnsuppliedFactor` (default 0.25), free but slow. The check re-runs every
  cycle, so bringing materials speeds up a build already in progress.
- **Blueprints are datapack content** at `data/<ns>/nerocolonies/blueprints/*.json` — a character
  grid with a palette, a category, a priority, a per-colony cap, an optional research prerequisite
  and an `ItemTarget` material list. Deliberately a hand-authorable text format rather than structure
  NBT. Loaded by `ColonyDefinitions` with the same never-crash `ValidationIssue` treatment as every
  other schema: an unregistered palette block leaves a hole and the rest still builds, a missing
  material only means the blueprint always builds unsupplied, and only a blueprint that would place
  nothing at all is dropped.
- Five starter blueprints ship: **Habitat Pod** (housing, ×6), **Farm Plot**, **Depot Shed**,
  **Oxygen Hut** and **Research Cabin**, all built from blocks that already exist. Housing blueprints
  are only eligible while the colony is short of bunks, so housing tracks population pressure instead
  of sprawling to the edge of the claim.
- **Safety rules, all enforced per block at placement time, not merely when the site was chosen:**
  inside the claim only; only into replaceable blocks, so a player's build is never overwritten;
  loaded chunks only, never loading one; flat ground within a few blocks of the beacon's level; solid
  support under the bottom layer. Building pauses (never cancels, never demolishes) on morale
  work-stop, on life support `FAILED`, on an empty roster when `constructionRequiresColonist` is set,
  and at `maxAutoStructures`.
- **Offline catch-up advances fabrication credit and places no blocks**, capped at four cycles'
  worth, so returning to a colony never triggers a burst of block placement in a chunk that has just
  loaded.
- One otherwise-idle colonist is pointed at the active site as a **builder** — a role on the existing
  `jobId` field, reassigned from scratch each cycle, drawn from whoever the job board did not need.
  It is presentation only: placement is colony-tick logic and never waits for a colonist to arrive.
- Surfaced on the beacon's Colony tab (`Building <name> - 34%`, or `Fabricating …` when unsupplied),
  through three new synced data slots and three new `ColonySnapshotPayload` fields; a completed
  structure fires Core's new colony-scoped `nerocolonies:structures` threshold channel, pushes an
  owner-scoped `construction` NeroLink event, and triggers an immediate housing rescan so a finished
  habitat raises capacity within seconds.
- New saved data `nerocolonies:construction` (through `SavedDataRecovery`, like every other store),
  keyed by colony id and holding blueprint counts plus the in-progress site. Nothing player-shaped,
  so erasure is unaffected. Forgotten on every dissolve path, and swept for orphans.
- New config keys: `founderColonistCount`, `constructionEnabled`, `constructionBlocksPerCycle`,
  `constructionUnsuppliedFactor`, `constructionRequiresColonist`, `maxAutoStructures`.
- New wiki page `wiki/Construction.md`; `reload-check` now reports the blueprint count.

**Commands, admin and the privacy surface — Stage 10**

- A single `/nerocolonies` tree, built once in shared code and registered identically on all three
  loaders. Player level: `colony list`, `colony info [<colony>]`, `colony rename`,
  `colony access list|add|remove`, `data export`, `data erase`. Operator level (permission 2, the
  same as `/neroland`): `colony dissolve|transfer|tp|set-morale|grant-research|sell`, `admin list`,
  `reload-check` and `purge-stale`.
- **`data export` is the data-access path and `data erase` the erasure path**, both acting on the
  calling player only. `erase` routes through Core's shared `PlayerDataErasure` hook, so one request
  purges the caller across **every** installed Nero mod rather than only this one — the same call
  Core's own `/neroland data eraseme` makes from the other end.
- **No command prints an owner or a member.** `admin list` reports colony ids, names, dimensions and
  state; `colony access list` and `colony info` answer with a *count*. That holds for operators too:
  an operator who needs to know who plays where has the server's own player data, not this mod's.
  Output goes to the invoker alone (no operator broadcast), the one exception being the destructive
  `colony dissolve`, which announces a colony name and nothing else.
- **`<player>` is a name or a raw UUID, never a profile-cache lookup.** An access list has to be
  manageable for somebody who is offline, and turning an offline name into a UUID means driving a
  name/UUID correlation lookup from user input. To add somebody who is offline, use their UUID; the
  beacon's own editor stays online-only for the same reason.
- `reload-check` re-reads the colony content if the datapacks changed, reports what survived, lists
  every dropped or ignored definition with its reason, and re-sends the new content to anyone with a
  colony screen open. `purge-stale` runs the retention sweep on demand and reports three counts.
- Brigadier suggestions scoped to the invoker: a player is offered their own colony ids (with the
  colony name as the hint), an operator every colony; research node ids are read greedily because
  they contain `:` and `/`.
- An unexpected failure in any subcommand is caught, reported politely and sent to the opt-out crash
  reporter with the **subcommand name only** — never its arguments, which may name a player.
- `lifecycle/ServerStateReset` — one server-started/server-stopped seam, wired from all three
  loaders, that clears the four JVM-lifetime caches (`JobBoard`, `LifeSupport`, `ColonySync`,
  `ColonyDefinitions`) so a second single-player world does not inherit the first world's stations
  and content, and records the running server for the link module to find.

**The NeroLink module, alerts and progression gates — Stage 11**

- `link/ColonyLinkModule` registers three surfaces with Core's link API, **last** in common init and
  wholly inside a `try/catch`: a broken link module must never take the colony layer down with it.
  `linkModuleEnabled=false` registers nothing and silences every publisher.
- Five read sections — `colonies`, `colonists`, `jobs`, `research`, `exports` — each scoped by one
  rule in one place: **the colonies the requesting UUID owns or is on the access list of**, and never
  widened for permission level. An operator's powers belong to a live command source, not to a UUID
  arriving over a bridge.
- **Membership is a count in every payload.** No section, event, action result or alert ever contains
  another player's UUID, name or position. The only coordinates anywhere are the requester's own
  colony beacons; stations, housing and generators are counts and stable indexes.
- Two actions. `toggle_export` routes every loaded station running one **job** (not one station — a
  station handle would mean sending an app a list of block positions) to the export buffer or back to
  storage, and requires the player to be online because the permission this mod defines is asked of a
  live player. `acknowledge_alert` acks one of your own alerts in Core's store and works offline.
  **`set_job_priority` is deliberately absent**: the job board has no priority model in 0.1.0, and an
  action by that name would invent a mechanic through the back door.
- Four owner-scoped events (`life_support`, `morale`, `food`, `exports`) and one broadcast
  (`colony_state`). The broadcast reaches every session, so it carries a colony id, a dimension and a
  state — **not even the colony's name**.
- Two alerts through Core's per-player store: life support failed (critical) and morale collapsed
  (warning), raised for the colony's owner alone, **rate-limited to once per five minutes per colony**
  so a flapping generator cannot spam a companion client. A colony with no owner raises nothing.
- `progression/ColonyGates` — two soft datapack gates, `nerocolonies:established` (on founding, next
  to Core's `first_colony`) and `nerocolonies:self_sufficient` (housed, fed, breathing and working).
  Both ship as ordinary `data/nerocolonies/neroland_gates/*.json` so a pack can re-scope them.
  **NeroColonies writes them and never reads them** — nothing in this mod is gated, ever.

**Compatibility and interop — Stage 12**

- `nerocolonies:supply_drop_target` block and item tags on the colony beacon and colony depot, so
  NeroLogistics (or anything else) has a marker to aim a delivery at. Everything else needed for
  pipes, drones, AE2 and Create already works: colony storage and the export buffer are plain item
  capabilities with no NeroColonies-specific API to depend on.
- NeroAgriculture interop stays **tags only** — food is recognised through the `colony_food` tag
  family, with no class reference in either direction. NeroEconomy needs nothing bespoke: it
  registers itself as Core's currency provider and the Stage 9 valuation picks it up. Nerospace stays
  behind the one reflective planet adapter. No third-party mod is a dependency, hard or soft, in any
  build script.

**Assets, lang, wiki and privacy docs — Stage 13**

- **Loot tables for all twelve blocks**, plus `minecraft:mineable/pickaxe` and
  `minecraft:needs_stone_tool` tags. Every block is declared `requiresCorrectToolForDrops`, so
  without these they dropped nothing at all when mined.
- **Sixteen crafting recipes**, vanilla ingredients only, with a real tier-up in both chains:
  habitat pod → module → block, and outpost beacon → colony beacon. Nothing references another mod's
  item, so no recipe can dangle.
- Lang completeness pass: every command message, refusal and report now has a translation key. No
  hard-coded English remains in Java.
- `PRIVACY.md`, `USING-CORE.md` and a full `wiki/`: `Home`, `Colony-Basics`, `Life-Support`,
  `Jobs-and-Research`, `Exports-and-Outposts`, `Content-Format`, `Commands`, `Admin-Guide`, `Config`,
  `Link-Module`, `Data-Storage` and `Telemetry`.

**Jobs, colony storage and the export buffer — Stage 7**

- **Job stations produce on the colony tick, not their own.** A station files itself with
  `colony/JobBoard` (the same self-registration pattern the oxygen generator uses for life support)
  and the colony's cycle runs every station it owns inside the one `colonyTickBudgetMs` watchdog. A
  colony's whole production cost is therefore inside the budget that exists to bound it, instead of
  spread across N block-entity tickers.
- One `JobStationBlockEntity` serves all four station blocks: what a station *does* comes from the
  datapack jobs that name its **block id**, so a pack can add a job to an existing station — or point
  one at another mod's block — with no code at all.
- Throughput is `baseRate x colonists x morale x SPEED modules x power`. **Unpowered is slow, not
  stopped** (35% rate), following the same graceful-failure rule as the beacon and life support.
- Job slots are first-fit in a stable position order and capped by `jobSlotsPerColony` plus research;
  colonists are assigned to stations and reassigned when one is broken. A job that needs hands and
  has none simply does nothing.
- `colony/ColonyStores` — a `SavedData` holding each colony's working stock and export buffer, kept
  off the `Colony` record so a 16-field value that is copied every tick does not carry two 54-slot
  item lists. Dissolving a colony drops and forgets its store in one operation.
- `colony/ColonyStorage` — one shared stock per colony, sized by `CAPACITY` modules in the beacon
  (18 slots plus 9 per module). Slots past the gate refuse insertion but still read and extract, so
  removing a module strands nothing.
- New `colony_depot` block: a window onto the colony's stock. Every depot in a claim shows the same
  goods — a depot adds *access*, not capacity — and it has nothing to drop when broken.
- **Colony storage and the export buffer are standard item capabilities.** They are appended to the
  colony beacon's container index space, so the loader capability registrations that already wrap it
  expose them to pipes, hoppers, AE2 and Create with **no NeroColonies-specific API** and no
  per-loader change.
- All-or-nothing crafting: destination room is checked before inputs are consumed, so a full colony
  never quietly eats its own inputs, and nothing this mod produces is destroyed by a race.

**Research trees and the research station — Stage 8**

- `research_station` block and a paged, branch-grouped research screen with locked / available /
  affordable / researched states, cost lines and a spend button. The graph is drawn as an indented
  list rather than a free-form canvas: it comes from datapacks, so its size is unknown at build time
  and this reads correctly at any pack size.
- Spending consumes the node's cost from **colony storage** and 5,000 energy from the station, then
  adds the node id to the colony record. Everything — existence, prerequisites, duplicates,
  affordability, permission — is decided server-side; the client sends an id and nothing more.
- **Research is colony-local**: it lives on the colony record, is shared by everyone with access, is
  therefore not personal data, and is discarded when the colony is dissolved.
- Client sync on NeroColonies' own channel: the research graph and export manifest (cached against
  the content generation) plus the viewing player's colony snapshot, sent **when a player opens a
  colony interface** and after every action. No join hook and no timer — which also means `/reload`
  needs no reload listener, because the generation is compared on every open.
- One serverbound `ColonyIntentPayload` covers research, access changes and selling. Nothing off the
  wire is trusted: reach, claim, permission and op code are all re-derived server-side, and every
  intent is answered with the authoritative snapshot.
- **The access-list editor is in the beacon GUI at last** — and the client is never sent the access
  list, only its size. The owner types a name, the server resolves it and answers with a count. A
  client told who is on a colony's list has been told where those people play; names are resolved
  against online players only.
- Client mirrors (`ClientColonyDefinitions`, `ClientColonySnapshot`) are immutable snapshots in one
  volatile field, cleared on disconnect — now wired on **all three loaders**, not just Fabric.

**Exports and planetary outposts — Stage 9**

- `colony/ExportBuffer` — a bounded, **extract-only** region of the colony store. Jobs flagged
  `export` route their output here; nothing outside the colony may insert, so what is in it is
  exactly what the colony produced for sale. A full buffer *blocks* further export production rather
  than voiding it.
- Valuation through Core's `CurrencyApi` + `CoreCurrencies.CREDITS`, with **exactly one code hook**:
  `ExportEntry.baseValue`. There is no pricing engine here and there will not be one — NeroEconomy
  owns pricing when it exists, and registers itself as Core's provider with no change on this side.
- The sale is guarded on `CurrencyApi.hasRealProvider()`: Core's in-memory fallback does not persist,
  so paying into it would take the goods and give nothing back. With no provider the sale is refused,
  the goods stay put and the player is told why (the log-and-skip rule NeroQuests uses for currency
  rewards). Overlapping export tables resolve to the **highest** value that matches.
- A Sell button and a live credit estimate on the beacon's Trade tab.
- **Per-station output routing.** A job station can be flipped between "to storage" and "to exports"
  from its own screen, on top of whatever the job's JSON `export` flag says. The JSON says what a
  recipe is *for*; the switch says what this colony is doing with it today — without it, whether a
  colony can trade at all would be a datapack's decision rather than a player's.
- `outpost_beacon` block: a small remote claim tied to a parent colony. It shares the parent's
  claim and permission context, has its own `outpostClaimRadius` (widened by a `RANGE` module), and
  has **no research, no morale, no housing and no food store** — its job stations run on the parent's
  colony tick, under the parent's morale, feeding the parent's storage.
- Outpost rules: max `outpostsPerColony`, same dimension, within `outpostMaxDistance`, never inside a
  colony claim or another outpost. The parent is the nearest colony the placer may act on.
  **An outpost cannot graduate into a colony in 0.1.0** — break it and place a beacon.
- An orphaned outpost (parent dissolved) goes inert immediately and is swept by the retention pass.
  It is never silently re-parented to a neighbour, which would be a claim exploit.

**Datapack content — Stage 3**

- Four content types loaded from datapacks at
  `data/<namespace>/nerocolonies/{jobs,research,housing,exports}/**.json`, each keyed by its file
  path so a pack overrides a definition simply by shipping the same id.
- `content/ColonyDefinitions` — lazy, cached, and reload-aware: the cache is keyed on the running
  server's `ResourceManager` instance, so `/reload` is detected in pure common code with no
  per-loader reload listener and no divergence between loaders. A `generation()` counter lets
  anything derived from the content know when to rebuild.
- **Bad content is never fatal.** Malformed JSON, unknown research-effect types, jobs naming an
  unregistered station or an item from an absent mod, exports whose tag resolves to nothing,
  dangling research prerequisites and research cycles are all logged, collected as
  `ValidationIssue`s and dropped or pruned. The rest of the pack still loads.
- Research effects are a dispatched codec (`housing_tier`, `job_unlock`, `job_slots`,
  `oxygen_efficiency`, `export_unlock`, `morale_bonus`). An unrecognised `type` decodes to an inert
  `Unknown` that logs once, so a pack written for a newer NeroColonies never bricks an older jar.
- A baseline content set: three housing tiers (habitat pod → module → block), four jobs (farming,
  hydroponics, refining, fabrication), eight research nodes across four branches (Habitation, Life
  Support, Industry, Trade) and three export entries. All magnitudes are config-scaled, so the JSON
  is shape rather than balance.

**Colonists, housing and population — Stage 4**

- The `colonist` entity: an **interchangeable labour unit** with exactly four persistent fields
  (colony, home, workstation, job) and no others. No names, no personalities, no schedules. It
  carries nothing player-shaped at all and is therefore never in scope for an erasure request.
- Vanilla goal AI only: float, walk to the workstation by day, walk home at night, stay inside the
  claim, look around. Colonists never break or place blocks, never attack, and have no target
  selector.
- Three habitat blocks plus four job-station blocks. Housing is matched by **block id** against the
  datapack housing tiers, so a datapack can declare any block in the game as colony housing with no
  code on either side.
- `colony/HousingScan` — a cursor over the claim's chunks with a per-slice budget; only a completed
  cycle commits its totals, so capacity never flickers. Unloaded chunks are skipped, never loaded.
- `colony/Population` — the roster grows toward `min(housingCapacity, colonistsPerColony)` one
  colonist per colony tick, gated on life support and food. Losing housing shrinks the roster and is
  the only path by which a colonist is ever removed.
- AI tick-down: with no owner or access-list member within `aiActiveRadius`, a colonist's goals run
  on one tick in four and pathfinding is suspended.

**Life support — Stage 5**

- `oxygen_generator` machine: spends grid power to synthesise oxygen into a Neroland Core gas tank
  (`Identifier`-keyed, millibuckets, gas id `nerospace:oxygen`), with side configuration, upgrade
  modules and a comparator output. Because the tank speaks Core's gas capability, any gas pipe or
  tank can fill or drain it with no NeroColonies-specific API.
- Running generators register themselves with their colony's life support, so the colony tick drains
  from a list instead of searching its claim for machines.
- Life-support state machine: `OK → DEGRADED (grace) → FAILED`. Failure drives morale decay and
  nothing else — it never kills a colonist. Recovery is immediate once oxygen returns.
- **Nerospace is a soft dependency**, consulted only for per-dimension breathability and hazard
  through a single reflective adapter on its published `nerospace.api` facade. With Nerospace absent
  every dimension is breathable, no dimension is hazardous, and life-support machinery is still
  buildable and still runs. Core's `SpaceTags` is honoured as an advisory hint when Nerospace is
  absent, so another mod's planet dimension still needs life support.

**Food, morale and the colony tick — Stage 6**

- `colony/ColonyTicker` — colonies tick on a staggered schedule (offset by their own id, so N
  colonies never share a game tick) under a server-wide `colonyTickBudgetMs` watchdog. A colony that
  is due when the budget is spent stays due and runs on a later tick; deferrals are reported as one
  aggregate line, never per colony.
- **Offline catch-up, not always-on ticking.** Colonies tick only while their beacon chunk is
  loaded; on return, the missed window is clamped to `catchUpMaxHours` and applied in one aggregate
  step at `catchUpEfficiency`. Bounded cost, no chunk-loader exploit, and still a reward for coming
  back.
- Food is recognised **entirely through item tags** (`nerocolonies:colony_food`, which ships
  including `#c:foods` and `#c:crops`), never hard-coded item ids — so NeroAgriculture's produce,
  another farming mod's crops and vanilla bread all feed a colony with no compat code. Bulk staples
  are eaten first, so a mixed supply line does not consume the valuable item on its way to export.
- Six food supply slots on the colony beacon, fillable by hand, hopper or pipe.
- `colony/Morale` — 0–100, recalculated every cycle from config-weighted housing comfort, food,
  life support, overcrowding and (with Nerospace) planet hazard, plus research bonuses. Morale moves
  toward its target and is never snapped.
- Morale drives a smooth production multiplier down to `moraleMinMultiplier` and a hard work-stop at
  `moraleWorkStopThreshold`. **The failure curve is life support loss → morale decay → work stop →
  idle, and it stops there. No colonist is ever deleted.**
- Core threshold events published on crossings only, for `nerocolonies:{food_stock,oxygen,morale}`.
  The event scope is a **colony id — never a player** — so other mods (NeroQuests objectives, for
  instance) can react with zero coupling and no personal data crossing the bus.

### Added (Stages 0–2)

**Neroland Core dependency (1.10.0) — Stage 0**

- NeroColonies now builds and runs against **Neroland Core 1.10.0**, its only hard dependency.
  Every loader manifest declares it as required with `ordering = "AFTER"`, and the version floor is
  the compiled Core version so an outdated Core is refused by the loader instead of failing later
  with a missing method.
- Core supplies the registration seam, the shared config framework, the shared creative tab, the
  per-player data-erasure hook, the machine block-entity base (energy buffer + upgrade modules),
  the universal side-configuration framework, the energy capability lookups and the progression
  gates.
- Optional, runtime-detected soft dependencies declared in every manifest (`ordering = "AFTER"`,
  never mandatory): **Nerospace**, **NeroAgriculture**, **NeroLogistics**, **NeroEconomy** and
  **Energized Power**. NeroColonies runs standalone without any of them.
- Fabric access widener entry for the `BlockEntityType` constructor, which is private on 26.1.2 and
  public on 26.2.

**Platform seams — Stage 0**

- `platform/Services`, `PlatformInfo` and `NetworkPlatform`, one implementation per loader behind
  `META-INF/services`. Every seam is resolved during mod construction, never lazily on a tick path.
- `PlatformInfo` answers mod version, development environment, physical side, `isModLoaded`, the
  loaded-mod list and the config directory — public manifest strings and local paths only.

**Configuration — Stage 0**

- New `config/nerocolonies.properties`, hot-reloadable with `/neroland config reload`. The whole
  0.1.0 schema lands at once — claims and caps, population and performance budgets, offline
  catch-up, life support, the full morale weight set, jobs, exports, outposts, privacy and
  ecosystem integration switches. Every gameplay key is server-authoritative.
- `telemetryEnabled` is deliberately **not** server-authoritative: crash-reporting opt-out is a
  per-client choice a server must never force.

**Telemetry — Stage 0**

- Opt-out, NeroColonies-only Sentry crash reporting: `sendDefaultPii=false`, no hostname, no user
  identity, OS-account names scrubbed from file paths, per-session de-duplication and a hard cap of
  10 events per session.
- Ships **inert**: the DSN is still the placeholder, so nothing is sent and no connection is opened
  regardless of the config value, until a Sentry project is configured.

**Networking — Stage 0**

- `network/ColonyNetwork` on its own channel `nerocolonies:main` — a declare-once payload registry
  each loader wires to its own API. It does not reuse Core's channel, whose payload lists are
  drained during Core's own bootstrap. The payload list is legitimately empty at this stage.

**Colony record, claims and permissions — Stage 1**

- `colony/Colony`, an immutable record with a codec: id, name, dimension, beacon position, claim
  radius, owner, access list, timestamps, morale, population, housing capacity, research, life
  support, food stock and outposts. Player-supplied names are sanitised and length-capped on every
  write.
- `colony/ColonyState`, a `SavedData` store on the overworld (`nerocolonies:colonies`) with three
  indexes: by id, by dimension, and a chunk-key index so "which colony owns this block?" is O(1).
- `data/SavedDataRecovery` — the ecosystem's crash-proof saved-data guard. Every accessor goes
  through it; a corrupt file degrades to an empty index instead of an unloadable world.
- A retention sweep that runs **once per server**, dropping expired access-log rows and colonies
  whose beacon block is gone. Unloaded chunks are never treated as evidence.
- `colony/ColonyClaims` — placement validation (per-player cap, server cap, minimum beacon spacing,
  claim overlap), `canBuild`, `canAccess`, and the owner-or-operator dissolve rule.
- `colony/ColonyApi` — the public query surface, **boolean-only**. No method returns an owner UUID
  or a player name.
- `colony/AccessLog` — optional and **OFF by default**. When enabled it records only
  `{player UUID, action, timestamp}`; never chat, never IP, never coordinates beyond a colony id.

**Colony command block — Stage 2**

- `colony_beacon` block, block entity, `BlockItem` and menu. Placing it founds a colony; sneak-
  breaking it as the owner or an operator dissolves one. A refused placement removes the block and
  hands the item back with a translated reason.
- Founding calls `ProgressionGates.tryOpen(CoreGates.FIRST_COLONY)` — `tryOpen`, never `open`, and
  nothing in NeroColonies ever requires a gate to be open.
- Four upgrade module items (speed, efficiency, range, capacity) driving Core's `UpgradeType`
  framework; range modules widen the claim radius live.
- Side configuration on the beacon (energy in, upgrade modules in) plus per-loader energy and item
  capability registration, so cables, pipes, hoppers and third-party automation work with no
  NeroColonies-specific API.
- `menu/MenuOpener` — one guarded door for every `openMenu` call site, so a misbehaving hybrid
  server platform cancels a GUI instead of taking down the server thread.
- Colony beacon screen with five status tabs (Colony / People / Jobs / Tech / Trade), a morale
  gauge, a power gauge, a life-support light and population, food, radius and count readouts — all
  synced through the menu's data slots.
- Every item joins Core's shared creative tab (no mod-owned tab) and the
  `neroland:highlight/{machines,upgrades}` tags.

### Privacy

- The per-player data-erasure hook is registered **early** in common init, before any colony can
  exist. Erasure strips the UUID from every access list, deletes its access-log rows, and either
  transfers owned colonies to the server (default) or dissolves them, per
  `erasureOwnedColonyPolicy`. Transfer is the default so an erasure request cannot be used to grief
  a shared colony.
- Erasure and retention log **counts only**, never identity.

### Notes

- **Textures are placeholder art, generated, not drawn.** Every referenced texture now ships — 12
  block faces, 4 upgrade-module items and the colonist's 64x64 entity sheet — so nothing renders as
  the missing-texture checker any more, but they are programmer art and the real art pass will
  replace them wholesale. See the *Added* entry above for the generator. **All six screens remain
  painted procedurally** — panel, slot wells, trays, gauges, tabs and buttons are all `fill`s — so
  no screen needs a PNG and `textures/gui/` is deliberately empty.
- The access-list editor in the beacon GUI (People tab, owner only) resolves names against **online
  players only** — an offline lookup means consulting the profile cache, which is where names and
  UUIDs are correlated, and doing that from a packet a client can send at will is not a trade this
  mod makes. The offline path is `/nerocolonies colony access add <colony> <uuid>`.
- Client sync is sent when a player opens a colony interface and after every action, not on join and
  not on a timer. A player who never opens a colony screen never receives either payload.
- `JobBoard` and `LifeSupport` hold session state rebuilt from self-registration on load; both are
  now cleared on server stop through `lifecycle/ServerStateReset`, together with the definition and
  content caches, so two worlds in one JVM no longer share them.
