# Changelog

All notable changes to **NeroColonies** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.3.1-beta.1] - 2026-10-02

### Added

- **A drop-off at the beacon: `Give needed items`.** A new colony has no depot, so there was nowhere
  obvious to put the Starter Works' materials. The beacon's screen now has a `Give needed items`
  button above the player inventory, on every tab. It hands the colony everything you are carrying
  that its needs list wants, and no more of each thing than it is short of; the rest stays with
  you. Any member may use it. The button lights up only when you are carrying something on the
  list, each need you can pay towards gets a green pip on the Needs tab, and hovering a need says
  how many you are carrying. Food is not taken: it still goes in the food supply slots. The server
  decides what is taken, from its own needs list and your own inventory.
- **"Any ..." needs can be put first.** A need that stands for a tag (any planks, any logs, food)
  can now be clicked on the Needs tab like any other. Prioritising a tag speeds up every trade that
  gathers something in it. The command and the link action still take a single item.

### Changed

- **NeroColonies has its own creative tab.** Every NeroColonies item is now listed in a dedicated
  **NeroColonies** tab (icon: the Colony Beacon) instead of Neroland Core's shared **Neroland** tab.
  Nothing is added to Core's tab any more. No item, block, recipe, tag or save data changed, and the
  Core floor is unchanged.
- **The colony beacon's screen has a new layout that shows everything.** The seven tabs are now a
  rail down the left instead of two cramped rows, the food supply and module slots sit beside the
  player inventory instead of above it, and the content area is twice as tall. Every tab is laid
  out as caption/value rows with gauges, hints wrap instead of ending in "...", and the panel is
  268 x 238 (was 208 x 236).
  - **Needs** lists every need at once (it used to show two and hide the rest behind "+6 more"),
    as an aligned table, and a tag such as `#minecraft:planks` reads "Any planks".
  - **Colony** now also shows the claim radius, the oxygen generator count, and a Construction
    section with a progress bar or the full reason nothing is being built.
  - **Roles** now shows how full the Gratitude Cache is, and five roster lines instead of two.
  - **Tech** now shows research progress and names the nodes that are ready to research.
  - **People**, **Jobs** and **Trade** show their figures as rows with gauges (storage and export
    buffer included) and their hints in full.
  - A tab that needs attention carries a coloured pip on the rail: failed life support, paused
    arrivals, a need the colony cannot meet alone, research that is ready, a full export buffer.
  - Hovering the food or module slots says what goes in them.
  The six food slots moved from one row to a 3 x 2 block; slot order, the menu's contract and every
  synced value are unchanged, so this is client layout only.

## [0.3.0-beta.1] - 2026-10-02

The living-colony release, and the first beta. A colony now starts with two founders and four
Starter Works that the player supplies, and from there grows through five stages on its own: its
people take trades, clear land, build from a catalogue of 51 blueprints, have children, defend the
claim and leave gifts for their owner. Colonists are called **Nerans** everywhere a player reads;
the entity id `nerocolonies:colonist` is unchanged, so existing worlds load (see *Migration*).
Checked so far by automation only: all nine loader/version cells build, `ecjCheck` passes and the
146 JUnit tests pass. Nothing in this release has been run in a game client yet.

### Added

**Nerans**

- **Twelve trades**, loaded from `data/<ns>/nerocolonies/professions/*.json`: Farmer, Forester,
  Miner, Builder, Hauler, Cook, Toolsmith, Guard, Beastkeeper, Researcher, Quartermaster and Medic.
  A finished building opens places in the trades its blueprint `unlocks` (`per_building` each, up
  to `max_per_colony`), and an adult without a trade takes the open one with the lowest `priority`.
  Numbers and bindings are data; the twelve behaviours are code, and an unknown behaviour id falls
  back to standing at the post.
- **Experience and levels.** Working earns experience; each level above the first (five levels as
  shipped) adds 15% to what the Neran gathers. Changing trade starts again from nothing.
- **Tools.** A trade can name a tool. A Neran is handed one from colony storage, or a Toolsmith
  makes one from a stored iron ingot; it is held in hand, a Neran without it gathers at half rate,
  and it goes back to colony storage on a trade change or on death.
- **Work you can watch.** Farmers harvest ripe crops and replant them, and sow empty farmland from
  stored wheat seeds. Foresters fell logs around their yard and replant a sapling from storage.
  Guards walk the watch posts, Beastkeepers heal the colony's wolves and golems, Medics heal wounded
  Nerans, and Haulers walk between the beacon and the building site. The other trades stand at
  their post. Block changes stay inside the claim and the trade's `work_radius`, and every drop
  goes to colony storage.
- **Work that counts.** Every colony cycle in daylight each tradesperson gathers its trade's
  `outputs` into colony storage with `output_chance`, scaled by morale, level and tool. This runs
  on the colony tick, so a colony produces whether or not anyone is near. Cooks turn stored food
  into food stock and Medics lift morale a little. Output that does not fit in storage is not
  produced; nothing is voided.
- **A daily schedule** read from the dimension clock: work, a midday meal at a granary or canteen,
  an evening at the plaza (or the beacon when there is none), and sleep at home.
- **Status symbols.** A small symbol in front of a Neran's name within 12 blocks says what it is
  doing: working, hauling, no path, hungry, no tool, sleeping, guarding or socialising.
- **Names.** New arrivals get a display name from a pool of 64 in the language file
  (`neranNamesEnabled`). Members may rename a Neran with a name tag; strangers may not.
- **Navigation.** Nerans walk to a standable spot next to their target instead of to the solid
  block itself. A shared stuck detector notices a walk that is going nowhere and works down a
  recovery ladder: plan again, try another side of the target, take a short hop, then leave the
  target alone for 30 seconds and count one stuck event for the colony. The per-colony total is
  shown by `/nerocolonies colony info`.
- **Stuck rescue.** A Neran that gives up a walk while standing inside or right against something
  the colony built or is building, with no standable block beside it, is moved to the nearest safe
  spot within 8 blocks inside the claim. That is the only case: a Neran stuck in open country
  waits out its 30 seconds, so the rescue is never a general teleport.

**Progression**

- **Five stages**: Founding, Settled, Growing, Thriving and Metropolis. A stage never goes back.
  Founding becomes Settled when every Starter Works blueprint has been built once; after that the
  colony needs both a population and a structure count (6 and 6 for Growing, 16 and 14 for
  Thriving, 32 and 24 for Metropolis; six `stage*` config keys). The beacon shows the stage and the
  next milestone, and members online are told when it advances.
- **Starter Works.** The Founder's Lodge, Homestead Farm, Lumber Yard and Mine Head are the only
  things a Founding colony builds, and while it is Founding they are built only from materials the
  player delivers. Nothing is fabricated from scrap, so the first four buildings are the player's
  contribution. They open the Builder, Farmer, Forester and Miner trades.
- **The needs list.** What the colony is short of, worked out fresh each time it is asked for and
  never stored: the unpaid materials of the building under way (or of the next Starter Works), a
  food shortfall against ten cycles of eating, and tools its tradespeople lack. Each line shows how
  much it has, how much it needs and how long its own gatherers would take alone ("not without
  help" when nobody gathers that item, and always for a Starter Works material while the colony
  is Founding). The beacon's new **Needs** tab also shows how long the current building will take
  alone and if supplied now.
- **Needs Board** block. Use it empty-handed to read the list in chat; use it holding an item the
  list wants and that much of the stack goes into colony storage. Food still goes in the beacon's
  supply row.
- **Prioritise a need.** The owner or a Chief may pick one item on the list; the trades that gather
  it work half as fast again (`/nerocolonies colony need prioritise`, the Needs tab, or the link).
- **A growth curve.** A growth score of `population + 2 x structures` runs through a logistic curve
  `g = 1 / (1 + e^(-0.15 x (score - 20)))`. Construction speed is `1 + (growthMaxBuildMultiplier -
  1) x g` (up to 3x as shipped) and, from Growing, the claim radius gains
  `round(growthMaxClaimBonus x g)` blocks (up to 32). The gain is trimmed so a grown claim never
  touches a neighbouring colony's claim.
- **Births.** A colony that is at least Growing, with two adults, a free bed, `breedingSurplusCycles`
  cycles of food in store and morale at `breedingMoraleFloor` or above, rolls
  `breedingBaseChance x (0.25 + 0.75 x g)` each cycle. A child Neran is born, plays near the plaza,
  and grows up after `childGrowthDays` in-game days, when the colony gives it a trade. Births run
  before arrivals in the colony cycle, so one free bed is enough and a child has first call on it
  (`breedingEnabled` turns births off).
- **Slower immigration once a colony can have children.** `immigrationIntervalCycles` (6): once a
  colony is Growing and breeding is on, a newcomer arrives only every that many cycles, and never
  in a cycle a child was born, so the colony grows mostly from within. Before Growing, or with
  breeding off, a newcomer may arrive every cycle as before.
- A `nerocolonies:stage` threshold crossing on Neroland Core's event bus when a colony advances,
  scoped to the colony id like the others.

**Buildings**

- **51 blueprints** (there were 5): the 4 Starter Works, 18 for Settled colonies, 13 for Growing,
  11 for Thriving and 5 for Metropolis.
  - *Settled:* Neran Cottage (three levels), Granary, Canteen, Toolsmith's Forge, Hauler's Depot,
    Watch Post, Well Plaza, Kennel, Needs Board Pavilion, Gratitude Cache Pavilion and Planning
    Hall, plus the five original structures.
  - *Growing:* Longhouse, Market Square, Barracks, Golem Forge, Med Bay, Stables Pasture, Windmill,
    Quarry Terrace, Greenhouse, Workshop Hall and Watchtower (three levels).
  - *Thriving:* Grand Town Hall, Colony Citadel, Hydroponic Spire, Crystal Biodome, Observatory,
    Grand Bazaar, Amphitheatre, Nexus Spire, Sky Bridge, Lighthouse and Harbour.
  - *Metropolis:* Arcology Tower, Skyport, Colossus, Floating Garden and Grand Archive.
- **Blueprint format.** A palette entry may be a full block-state string
  (`minecraft:oak_stairs[facing=north,half=bottom]`), so stairs, slabs, logs and doors are placed
  the right way round. New fields: `stage`, `level`, `upgrade_to`, `unlocks`, `roles`, `capacity`
  and `rotate`. A character mapped to `minecraft:air` is a clear cell. Instead of `palette` and
  `layers` a blueprint may name a vanilla structure file with `structure`
  (`data/<ns>/structure/<path>.nbt`); block-entity contents in the file are ignored. No shipped
  blueprint uses a structure file.
- **Land clearing.** Natural blocks in the way of a building (block tag `nerocolonies:clearable`,
  whose surface part is `nerocolonies:clearable_vegetation`) are cleared first, top down, and their
  drops go to colony storage. Nothing in `nerocolonies:protected`, no block with a block entity and
  nothing outside the claim is ever broken (`landClearingEnabled`).
- **Siting.** A site may be up to two blocks uneven once vegetation is ignored, keeps a two-block
  street to every other finished building, and the building is turned so its front faces the
  beacon when the blueprint allows it.
- **Upgrades.** A standing building whose blueprint names an `upgrade_to` the colony's stage and
  research allow is rebuilt in place as the next level (Neran Cottage and Watchtower, I to III).
  Blocks in the way of the new level are returned to colony storage, except anything in
  `nerocolonies:protected` or with a block entity, which is left where it is.
- **Entrance check.** When a building with an interior is finished, a bounded flood fill checks it
  can be walked into; if not, a doorway is opened in the wall nearest its access spot.
- **Builders.** Each Builder standing at the site adds 50% to the build rate, up to four.
- **Size limits.** `maxBlueprintFootprint` (32) and `maxBlueprintHeight` (40); larger blueprints
  are skipped. The format itself allows 48 by 48 by 64.
- `tools/gen_blueprints.py` generates the whole catalogue from parametric shapes and
  `tools/check_blueprints.py` validates it. `CREDITS.md` records that every shipped design is
  original.

**Roles and defence**

- **Owner, Chief, Ally, Enemy.** An Ally may use the colony's blocks, contribute to its needs and
  rename its Nerans. A Chief may also plan buildings, add and remove Allies and Enemies and
  prioritise a need. Only the owner makes or unmakes Chiefs, renames the colony and shares the
  Gratitude Cache. Operators act as the owner. Each list holds at most 64 players.
- **The rules.** Nobody changes the owner's role or their own. Removing or marking a Chief takes
  the owner. Marking a member as an Enemy removes them from the colony first and has to be
  confirmed. An operator who is online cannot be marked. Making somebody an Ally or a Chief clears
  an Enemy mark.
- **Commands.** `/nerocolonies colony role <ally|chief|enemy> <add|remove|list> <colony> ...`.
  `list` answers with a count, the cap and the caller's own role, never a roster. `<player>` is an
  online player's name or a UUID.
- **Roles tab** on the beacon: every member sees their own role and three counts. A viewer who may
  manage members also sees who is on the lists and can add or remove an online player by name (see
  `PRIVACY.md` for exactly what is sent).
- **Guards.** Guard Nerans, and only Guards, fight: hostile mobs (never creepers) and players on
  the colony's Enemy list, inside the claim plus `guardPursuitMargin` (16). They never attack the
  owner, an operator, a player in creative or spectator mode, another Neran or a guardian animal.
  `guardsAttackEnemyPlayers` turns the player half off.
- **Guardian animals.** A Kennel keeps two wolves and a Golem Forge one iron golem. A Beastkeeper
  replaces a missing one each cycle from colony storage (two bones for a wolf, four iron blocks for
  a golem). They follow the same target rule as the Guards.
- **Roles in the data export.** `/nerocolonies data export` also lists the colonies the caller is
  a Chief of (`chief_of_colonies`) and the colonies that list the caller as an Enemy
  (`enemy_of_colonies`). Still nobody else's id.
- **Retribution.** When a player on a colony's Enemy list kills that colony's owner (directly,
  with a projectile, or through a pet they own), they come off that colony's Enemy list. The
  colony's online members are told that an enemy has claimed retribution, naming nobody. Otherwise
  only the owner or a Chief can lift an enmity, so with PvP off that is the only way.

**Gratitude Cache**

- One cache per colony, 27 slots, kept with the colony rather than in a block: every Gratitude
  Cache block in the claim opens the same contents and breaking one loses nothing.
- With a Gratitude Cache Pavilion and a Quartermaster, the colony rolls the loot table
  `nerocolonies:gratitude/tier_<n>` for its stage (1 for Founding to 5 for Metropolis) every
  `cacheStockIntervalCycles` (36) cycles. A rare roll includes a **Thank-you Note**. A roll that
  does not fit is dropped whole and nothing is added, so no part of it is voided; the "cache is
  full" event and alert go out once, and not again until a roll fits.
- Only the owner may open it, until the owner shares it with the colony's members
  (`/nerocolonies colony cache share`, the beacon, or the link).

**Colony Planner**

- **Colony Planner** item, for the owner and Chiefs. Use it in the air to pick the next building
  the colony has unlocked, sneak-use to turn it, use it on the ground for a particle outline of the
  footprint the server has checked, and use the same spot again within 30 seconds to confirm.
- A plan jumps the colony's own queue, oldest first, and is then paid for and built like any other
  building. Up to `maxConcurrentPlans` (4) wait at once; a plan whose site has been blocked by the
  time its turn comes is dropped.
- **Chief's Planning Table** block and `/nerocolonies colony plan list|cancel` show what can be
  planned and what is queued.

**Companion link (schema version 2)**

- Six new sections: `summary`, `needs`, `buildings`, `professions`, `roles` and `cache`. `roles`
  carries the requester's own role and three counts; `buildings` lists blueprints, never
  positions; `cache` says how full the Gratitude Cache is, never what is in it.
- Two new actions: `prioritise_need` (owner or Chief) and `toggle_cache_sharing` (owner). Both
  check the caller's stored role on every call. `prioritise_need` refuses a `#tag` need as a
  validation error: only a single item can be prioritised.
- Six new member-scoped events (`stage`, `neran_born`, `needs`, `enemy`, `cache`, `guards`) and
  three new alerts (enemies inside the claim, the Gratitude Cache is full, the colony needs help).
  An enemy event carries a count, never who.

**Gallery**

- `/nerocolonies gallery`, for an operator in creative mode: every blueprint built on a lit floor
  around the caller (upgrade chains at their top level), and a court with a stall for each trade,
  an AI course (a door house, a maze that ends in "no path", a claim-edge walk), a guard demo and a
  Gratitude Cache stocked with one roll of every stage's table.
- `/nerocolonies gallery release` lets the held demo mobs go; `/nerocolonies gallery clear` removes
  the blocks, the entities and the records.
- The gallery is one ownerless sandbox colony that never runs a colony cycle and appears in no link
  section, event or alert. It needs its whole footprint loaded, there is one per server, and
  whatever stood above its floor is cut away and not put back by `clear`.

**Blocks, items and config**

- Needs Board, Gratitude Cache and Chief's Planning Table blocks, and the Colony Planner and
  Thank-you Note items. The three blocks and the planner have recipes and JEI and EMI information
  pages.
- 22 config keys for the above, all server-authoritative: the six `stage*` thresholds,
  `growthMaxBuildMultiplier`, `growthMaxClaimBonus`, `breedingEnabled`, `breedingBaseChance`,
  `breedingSurplusCycles`, `immigrationIntervalCycles`, `breedingMoraleFloor`, `childGrowthDays`,
  `maxBlueprintFootprint`,
  `maxBlueprintHeight`, `landClearingEnabled`, `maxConcurrentPlans`, `guardsAttackEnemyPlayers`,
  `guardPursuitMargin`, `cacheStockIntervalCycles` and `neranNamesEnabled`.

**Tests**

- The JUnit suite grew to 146 tests in 12 classes (`:neoforge:<mc>:test`), covering the growth
  curve and stage rule, the breeding gate, needs estimates and ordering, the permission table,
  block-state parsing, blueprint rotation, turning a site to face the beacon, profession levels,
  the day schedule, target resolution, the stuck detector and the lenient codecs.

### Changed

- **Colonists are now Nerans** in every player-facing string. The entity id `nerocolonies:colonist`
  and the config key names (`colonistsPerColony`, `founderColonistCount`, `maxLoadedColonists`)
  are unchanged.
- **A new colony starts in Founding.** It used to start building from scrap at once; now the four
  Starter Works wait for the player's materials.
- **Colonies clear their own sites.** A site no longer has to be empty, flat ground, and buildings
  are turned to face the beacon.
- **The five original blueprints were redesigned** with doors, interiors and new material lists
  (Habitat Pod, Farm Plot, Depot Shed, Oxygen Hut, Research Cabin).
- **Construction speeds up as a colony grows** (the growth curve and Builders on site), on top of
  the existing supplied and unsupplied rates.
- **`maxAutoStructures` is now the cap for a Settled colony**; each stage past Settled adds the
  same again (12, 24, 36 and 48 as shipped).
- **`colonistsPerColony` defaults to 48** (was 24), which leaves room for the Metropolis threshold.
- **Permissions are decided in one place.** Every colony block, the beacon's actions, the
  commands and the link actions ask the same role table. Managing the access list used to be the
  owner's alone; a Chief may now add and remove Allies. A player marked as an Enemy is refused
  even if stale data left them on a list. A refusal says whether the player is unknown to the
  colony or lacks the rank.
- **`/nerocolonies colony access ...` is the older spelling of `role ally ...`** and keeps
  working. `access list` now answers any member (it was owner only).
- **`/nerocolonies colony info`** adds the stage, structures built, stuck events, births and the
  three role counts. `reload-check` also reports professions.
- **The beacon has seven tabs** (Needs and Roles are new). When the labels do not fit on one row
  the strip becomes two shorter rows.
- **Job stations** are staffed by adults with no trade or with a trade that lists that station;
  children never staff one. Tradespeople keep the workplace their trade gave them.
- **Link visibility follows roles.** A companion client sees the colonies its player owns or is an
  Ally or Chief of; a player a colony has marked as an Enemy sees nothing of it. Schema version 1
  sections are unchanged.
- **Erasure under the `dissolve` policy cleans up at once.** The dissolved colony's goods, build
  record, life record and role lists go with the colony record, not at the next server start.
- The erasure log line also counts Chief and Enemy listings removed.

### Fixed

- Nerans were sent to the solid block of their home or workstation, which could not be reached
  from some sides. They now walk to a standable neighbour.
- A finished building with no way in is given a doorway instead of standing sealed.
- Trees, grass and snow no longer stop a colony finding a site: they are cleared.
- Blueprints placed every block in its default state, so stairs, slabs, logs and doors could not
  face the right way. Palettes now carry block states and turn with the building.
- The link module had no `summary` section, so a client that asked for one was given nothing. It
  now answers one.

### Fixed (audit)

From the October 2026 audit (`docs/AUDIT-2026-10.md`, items AUD-01 to AUD-40).

- **Nerans no longer vanish when a colony's chunk reloads.** The colony uses its saved housing
  figure until the first housing sweep after a load has finished, and never grows or shrinks the
  roster before then. Previously every colonist above the two founders was removed on each reload.
- **Nerans far from any member move again.** Quiet mode used to stop them pathing entirely (and,
  for half of them, stopped their goals from ever starting). They now start walks less often, and a
  walk under way always finishes.
- **Construction never builds a block into a Neran.** Cells with anyone standing in them are
  held; Nerans in the way are moved to a spot just outside the footprint, which is also where the
  builder now stands (it used to be sent to the first cell to be built).
- **Nerans open and close wooden doors**, and drop a walk that keeps failing for half a minute
  instead of re-planning it forever.
- Day and night follow the dimension clock with hysteresis, so thunderstorms no longer send Nerans
  home and rain no longer makes them flicker. New `fixedTimeIsDay` config for dimensions without a
  day/night cycle.
- Arrivals spawn only on solid, dry, hazard-free ground outside the structure being built, preferring
  a spot that can walk to the beacon.
- The roster counts Nerans up to 16 blocks past the claim edge (they are walked back rather than
  replaced) and sheds the most recent arrival first, never a founder. Nerans now persist whether
  they are a founder and when they arrived.
- Catch-up after a reload no longer applies the full overcrowding penalty.
- **Privacy:** new colonies are named `Colony N` instead of `<player>'s Colony`; erasure under the
  transfer policy also replaces the colony's name; a one-time pass renames existing colonies still
  carrying their owner's name.
- **Saved data:** one unreadable entry (an item from a removed mod, a bad id) is skipped instead of
  failing the whole file, and an unreadable file is copied to `.dat.corrupt-<time>` before recovery
  starts that store empty.
- Access-log retention now runs every hour, not only at server start.
- Access-list members receive the colony's link events and alerts, not only the owner.
- Crash reports also scrub UUID-shaped strings. Performance tracing is off (nothing used it), and the
  privacy docs no longer promise breadcrumbs or timing data that were never sent.
- The beacon says why arrivals are paused (food, life support, housing full, caps) and why
  construction is idle (no flat site, cap reached, nothing needed, ...).
- JEI and EMI information pages for every colony block.
- Performance: the housing sweep runs inside the colony tick budget, the roster is queried once per
  colony cycle, the server-wide population sum is computed once per tick, Nerans cache their
  colony record, and the beacon reads construction state once per tick.
- New `debugFastGrowth` config (off by default) that compresses timings for testing.
- First automated tests (pure JVM, run by `:neoforge:<mc>:test`).
- Removed dead code; corrected drifted docs.

### Migration

What happens to a world last saved with 0.2.0-alpha.1, the first time it loads:

- **Nerans.** The entity id is unchanged, so every colonist loads as a Neran. The new fields are
  absent from old saves and read as: adult, no trade, no experience, empty carry inventory, not a
  founder, arrived at time zero. Existing Nerans keep whatever name they had (none, unless a
  player named them); generated names go to new arrivals only. The colony hands out trades on its
  next cycle, once it has a building that opens them.
- **Stage.** A colony with no stage on record is placed the first time it is asked: **Settled** if
  it has already built a housing blueprint and a farm blueprint, or has more Nerans than
  `founderColonistCount`; otherwise **Founding**. A colony placed in Founding builds only the
  Starter Works, and only from delivered materials, until all four stand. A colony placed in
  Settled still builds the four Starter Works, from scrap or from supplies: they sort ahead of
  everything else it chooses for itself.
- **Structures built before 0.3** are remembered as counts, not as places. They still count
  towards stage thresholds, the growth curve, each blueprint's `max` and the structure cap, and
  their housing blocks and job stations work as before. They do not open trades, are not meal,
  social or guard-post destinations, cannot be upgraded and are not listed in the link `buildings`
  section.
- **A build in progress.** All five original blueprints changed shape. A build whose saved block
  count no longer matches its blueprint is abandoned on the first cycle (what was placed stays)
  and the colony chooses again.
- **Access list.** Nothing moved: every player on a colony's access list is now an Ally with the
  rights they had. Chief and Enemy lists start empty in a new `nerocolonies:roles` store.
- **Colony names.** A one-time pass renames any colony still called `<owner's name>'s Colony`,
  and any ownerless colony in that pattern, to `Colony N`. A colony renamed by hand is left alone.
- **Saved data.** The colony record itself did not change. Two stores are created on first use:
  `nerocolonies:roles` and `nerocolonies:life` (stage, counters, the Gratitude Cache). The build
  record gains finished-structure positions and the plan queue.
- **Config.** `colonistsPerColony` now defaults to 48. A config that still sets 24 keeps a colony
  below the 32 Nerans Metropolis needs, so raise it by hand if you want that stage. The 22 new
  keys take their defaults. No other default changed.
- **Link clients.** The module reports schema version 2. The five version 1 sections and both
  version 1 actions are unchanged, so a version 1 client keeps working and simply does not see
  the new sections.

## [0.2.0-alpha.1] - 2026-09-24

EMI compatibility. No gameplay, id, tag or config change.

### Added

- **EMI support.** Every NeroColonies recipe uses a vanilla recipe type, which EMI shows on its own, so
  nothing needed a plugin. The build now compiles against the community EMI Unofficial Port (Unstable),
  the only EMI build for Minecraft 26.x, and dev clients load it with `-PwithEmi` (default runs stay
  JEI-only). EMI stays optional.

## [0.1.0-alpha.1] - 2026-09-20

Minecraft **26.3** support, plus the changes previously listed under *Unreleased*.

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
- JEI pins moved to the newest published builds on each Minecraft version: `29.40.0.101` (26.1.2), `30.35.0.223` (26.2) and `31.3.0.18` (26.3). Compile-time API only — JEI remains a soft dependency and the shipped jar gains no hard requirement.

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
- Five owner-scoped events (`life_support`, `morale`, `food`, `exports`, `construction`) and one
  broadcast (`colony_state`). The broadcast reaches every session, so it carries a colony id, a
  dimension and a state — **not even the colony's name**.
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
- **Live and opt-out.** A real NeroColonies Sentry project (EU ingest) is configured, so reports are
  sent unless the player sets `telemetryEnabled=false` in `config/nerocolonies.properties` — a
  client-local key a server can never override. The unconfigured-build guard remains in the code: a
  fork or stripped build whose DSN is back to the placeholder stays a hard no-op and opens no
  connection. See `PRIVACY.md` and `wiki/Telemetry.md` for the full disclosure.

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

### Minecraft 26.3

- **Minecraft 26.3** as a new Stonecutter node on every loader — NeoForge `26.3.0.7-beta`,
  Forge `26.3-66.0.2` and Fabric (fabric-api `0.161.0+26.3`, NeoForm `26.3-1`) — built alongside
  26.1.2 and 26.2, so every release now ships **nine** loader × version jars.
- VS Code run/debug configurations (`.vscode/launch.json`, `.vscode/tasks.json`) gain the three
  26.3 cells; the "Build all" task now builds all nine.
- CI (`multiloader.yml`, `publish.yml`) builds, attaches and publishes the 26.3 jars.
- Requires **Neroland Core 1.13.0** (was `1.10.0`) — the first Core release with a 26.3
  build. The loader range still derives from the pin (`[${nerolandcore_version},2.0)`).

### 26.3 port notes

- Block classes build their codecs through Core's `BlockCodecs` (26.3 removed block-type codecs); `codec()` is kept without `@Override` so one source compiles on every version.
- 26.3 API differences are handled with Stonecutter blocks: `PoseStack#rotate` (was `mulPose`), the new `Prediction` argument on `drop` / `placeItemBackInInventory`, `setPermanentlyInvulnerable`, and similar renames.
- Build: the shared `common/` Java source is now preprocessed by Stonecutter for every non-active node (`stonecutterProcessCommon`), so common code can carry `//? if >=26.3 {` blocks, and `common/src/main/resources-<mc>` overlay folders are merged over the shared resources for matching nodes (`mergeCommonResources`). The active node still compiles the raw `common/` folder.
- Build plugins aligned with Neroland Core: ModDevGradle `2.0.147` (the older 2.0.141 cannot set up NeoForge 26.3), ForgeGradle `7.0.40`, Stonecutter `0.9.8`.
