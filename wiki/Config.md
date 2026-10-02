# Configuration

Every NeroColonies setting, in `config/nerocolonies.properties`. The file is written on first launch
with each key at its default and a comment describing it, and it is hot-reloadable with
`/neroland config reload`.

**Every gameplay key is server-authoritative**: the server decides and clients are told. The one
exception is `telemetryEnabled`, which is client-local — anonymous crash reporting is a personal
opt-out that a server must never force on or off.

Several keys keep the word "colonist" from before the colony's people were called Nerans. The names
have not changed, so existing config files carry over.

## Crash telemetry

| Key | Type | Default | Server-authoritative | Purpose |
| --- | --- | --- | --- | --- |
| `telemetryEnabled` | boolean | `true` | **no** — client-local | Send anonymous, NeroColonies-only crash reports (Sentry, EU servers): stack trace, mod/MC/loader/OS/Java versions, your other installed mods, this mod's config, an anonymous per-launch session marker. No IP, username, UUID, world data, colony ownership or chat; file paths scrubbed of your account name. `false` opts out of all of it. See [Telemetry](Telemetry.md) and [`../PRIVACY.md`](../PRIVACY.md). Read once at bootstrap, so a change takes effect on restart. |

## Claims and caps

| Key | Type | Default | Range | Server-authoritative | Purpose |
| --- | --- | --- | --- | --- | --- |
| `maxColoniesPerPlayer` | integer | `3` | 0–64 | yes | How many colonies one player may own at once. `0` disables founding new colonies. |
| `maxColoniesTotal` | integer | `200` | 1–10,000 | yes | Server-wide colony cap. The safety net behind the per-player cap; also bounds how much work the colony tick can ever create. |
| `claimRadius` | integer | `48` | 8–512 | yes | Beacon claim radius in blocks. `RANGE` upgrade modules add to this per colony, and so does the growth bonus (`growthMaxClaimBonus`). |
| `minColonySpacing` | integer | `192` | 0–8,192 | yes | Minimum distance between two colony beacons in the same dimension. A placement inside this radius is refused with a translated message rather than silently allowed. |

## Population and performance

| Key | Type | Default | Range | Server-authoritative | Purpose |
| --- | --- | --- | --- | --- | --- |
| `colonistsPerColony` | integer | `48` | 0–256 | yes | Population cap per colony. Housing capacity can never raise the roster above this. The default leaves room for the Metropolis stage thresholds. |
| `founderColonistCount` | integer | `2` | 0–8 | yes | Nerans that arrive with a newly placed colony beacon. They exist without housing (they are what builds the first housing) but still count toward `colonistsPerColony` and `maxLoadedColonists`. `0` disables founders, which also means autonomous construction never starts until you build housing by hand. |
| `maxLoadedColonists` | integer | `300` | 0–5,000 | yes | Global cap on Nerans across all colonies. |
| `colonyTickIntervalTicks` | integer | `100` | 20–12,000 | yes | How often a colony runs its cycle: food, population, trades, production, construction and morale. Colonies are staggered across this interval so N colonies never tick on the same game tick. |
| `colonyTickBudgetMs` | integer | `5` | 1–200 | yes | Millisecond budget for colony processing per game tick. The remainder of a batch is deferred to the next tick rather than blowing the tick time. |
| `aiActiveRadius` | integer | `64` | 0–512 | yes | Distance from an owner or access-list member within which Nerans act at full rate. Beyond it Nerans start new walks far less often, but a walk already under way always finishes. Production carries on regardless. |
| `fixedTimeIsDay` | boolean | `true` | — | yes | In dimensions with no day/night cycle, whether Nerans keep a working day (`true`) or stay home as if it were always night (`false`). |
| `debugFastGrowth` | boolean | `false` | — | yes | **Testing only — leave off.** Runs colony cycles and housing sweeps five times as often and places four times as many blocks per construction cycle, so a test world reaches later stages quickly. |
| `housingScanIntervalTicks` | integer | `600` | 100–24,000 | yes | How often the claim is rescanned for housing blocks to recompute capacity and comfort. |

## Offline catch-up

| Key | Type | Default | Range | Server-authoritative | Purpose |
| --- | --- | --- | --- | --- | --- |
| `catchUpMaxHours` | integer | `24` | 0–720 | yes | Cap on the offline window a colony catches up on when its chunk reloads. `0` disables catch-up entirely. |
| `catchUpEfficiency` | double | `0.5` | 0.0–1.0 | yes | Multiplier applied to what a colony ate and to the construction credit it earned while away. See [Colony basics](Colony-Basics.md#while-nobody-is-there-the-offline-catch-up) for what catch-up covers. |

## Life support and food

| Key | Type | Default | Range | Server-authoritative | Purpose |
| --- | --- | --- | --- | --- | --- |
| `foodPerColonistPerCycle` | integer | `1` | 0–64 | yes | Rations eaten per Neran per colony cycle. `0` makes colonies never hungry. |
| `oxygenMbPerColonistPerCycle` | integer | `20` | 0–10,000 | yes | Millibuckets of oxygen gas burnt per Neran per colony cycle to hold life support. |
| `oxygenGeneratorEnergyPerTick` | long | `40` | 0–1,000,000 | yes | Energy per tick the colony oxygen generator draws while running. |
| `lifeSupportGraceTicks` | integer | `1200` | 0–72,000 | yes | How long life support stays DEGRADED before it is considered FAILED. Failure drives morale decay; it never kills a Neran. |

## Morale

| Key | Type | Default | Range | Server-authoritative | Purpose |
| --- | --- | --- | --- | --- | --- |
| `moraleBase` | double | `50.0` | 0–100 | yes | Morale baseline before any weighted term is applied. |
| `moraleWeightHousing` | double | `20.0` | 0–100 | yes | Weight of the housing-comfort term in the morale sum. |
| `moraleWeightFood` | double | `20.0` | 0–100 | yes | Weight of the food-stock term in the morale sum. |
| `moraleWeightLifeSupport` | double | `30.0` | 0–100 | yes | Weight of the life-support term in the morale sum. |
| `moraleWeightCrowding` | double | `15.0` | 0–100 | yes | Weight of the overcrowding penalty in the morale sum. |
| `moraleWeightHazard` | double | `10.0` | 0–100 | yes | Weight of the planet-hazard penalty in the morale sum. Only ever non-zero when a planet mod is installed and reports a hazardous planet. |
| `moraleChangeRate` | double | `2.0` | 0.01–100 | yes | Points morale moves toward its target per colony cycle. Morale is never snapped. |
| `moraleWorkStopThreshold` | double | `20.0` | 0–100 | yes | Below this morale jobs halt and Nerans idle. Nerans are never removed. |
| `moraleMinMultiplier` | double | `0.25` | 0.0–1.0 | yes | Output multiplier floor at zero morale. Production is a curve down to this, not a cliff. |

## Jobs and exports

| Key | Type | Default | Range | Server-authoritative | Purpose |
| --- | --- | --- | --- | --- | --- |
| `jobSlotsPerColony` | integer | `4` | 0–64 | yes | Base number of simultaneously worked job slots per colony. Research raises it. |
| `jobBaseRateMultiplier` | double | `1.0` | 0–100 | yes | Global scalar on every job's production rate. |
| `exportBufferSlots` | integer | `18` | 1–54 | yes | Slots in the beacon's export buffer. Overflow blocks further export production rather than voiding items. |
| `exportValueMultiplier` | double | `1.0` | 0–1,000 | yes | Scalar on the credits paid when an export entry is sold. |

## Autonomous construction

See [Construction](Construction.md) for what these actually govern.

| Key | Type | Default | Range | Server-authoritative | Purpose |
| --- | --- | --- | --- | --- | --- |
| `constructionEnabled` | boolean | `true` | — | yes | Whether colonies build their own structures from the datapack blueprints. `false` leaves every structure to the player; nothing else changes. |
| `constructionBlocksPerCycle` | integer | `2` | 0–64 | yes | Blocks a colony places per colony cycle while building, before the growth and Builder multipliers. Deliberately small: a colony growing visibly over minutes reads as a colony, one that snaps into existence reads as a command block. |
| `constructionUnsuppliedFactor` | double | `0.25` | 0.0–1.0 | yes | Build-rate multiplier while a structure's materials are **not** in colony storage — the Nerans fabricate from scrap instead, for free but slowly. Put the materials in storage and the same build runs at full speed. `0` means an unsupplied colony never builds at all. Does not apply to the Starter Works of a Founding colony, which always wait for materials. |
| `constructionRequiresColonist` | boolean | `true` | — | yes | Whether a colony needs at least one living Neran to build. This is a roster check, not a proximity one: block placement is colony-cycle logic and never depends on a Neran actually reaching the site. |
| `maxAutoStructures` | integer | `12` | 0–128 | yes | Structures a Founding or Settled colony will build for itself. The cap is doubled at Growing, tripled at Thriving and quadrupled at Metropolis. Each blueprint also carries its own smaller `max`. Hand-placed plans and upgrades are not stopped by it. |

## Stages, growth and breeding

See [Progression](Progression.md) for the stages and the growth formula.

| Key | Type | Default | Range | Server-authoritative | Purpose |
| --- | --- | --- | --- | --- | --- |
| `stageGrowingPopulation` | integer | `6` | 1–1,000 | yes | Nerans a Settled colony needs (with `stageGrowingStructures`) to become Growing. |
| `stageGrowingStructures` | integer | `6` | 1–1,000 | yes | Finished structures a Settled colony needs (with `stageGrowingPopulation`) to become Growing. |
| `stageThrivingPopulation` | integer | `16` | 1–1,000 | yes | Nerans needed to become Thriving. |
| `stageThrivingStructures` | integer | `14` | 1–1,000 | yes | Finished structures needed to become Thriving. |
| `stageMetropolisPopulation` | integer | `32` | 1–1,000 | yes | Nerans needed to become a Metropolis. Keep it at or below `colonistsPerColony`, or the stage cannot be reached. |
| `stageMetropolisStructures` | integer | `24` | 1–1,000 | yes | Finished structures needed to become a Metropolis. |
| `growthMaxBuildMultiplier` | double | `3.0` | 1.0–20.0 | yes | Ceiling of the construction-speed curve. A new colony builds at about 1×; a large one approaches this. |
| `growthMaxClaimBonus` | integer | `32` | 0–512 | yes | Ceiling of the claim-radius bonus a colony earns as it grows, in blocks. Applies from the Growing stage, and never extends a claim into a neighbouring colony's. |
| `breedingEnabled` | boolean | `true` | — | yes | Whether Growing colonies with spare food and beds have children. Arrivals continue either way. |
| `breedingBaseChance` | double | `0.08` | 0.0–1.0 | yes | Chance per colony cycle of a birth for a large colony when conditions allow. A small colony gets a quarter of it, rising with size. |
| `breedingSurplusCycles` | integer | `10` | 1–1,000 | yes | Cycles of food for the whole colony that must be in the food stock before a birth is possible. |
| `immigrationIntervalCycles` | integer | `6` | 1–1,000 | yes | Once a colony is Growing and breeding is on, a newcomer arrives only every this many colony cycles, so free beds go mostly to children. `1` means an arrival every cycle, as before Growing. |
| `breedingMoraleFloor` | double | `50.0` | 0–100 | yes | Morale a colony needs before children are born. |
| `childGrowthDays` | integer | `2` | 0–100 | yes | In-game days a child Neran takes to grow up and take a trade. `0` means grown at once. |

## Buildings and land

See [Buildings](Buildings.md).

| Key | Type | Default | Range | Server-authoritative | Purpose |
| --- | --- | --- | --- | --- | --- |
| `maxBlueprintFootprint` | integer | `32` | 4–48 | yes | Largest footprint, on either axis, the colony will choose to build or let a member plan. Bigger blueprints are skipped. |
| `maxBlueprintHeight` | integer | `40` | 4–64 | yes | Tallest blueprint the colony will choose to build or let a member plan. Taller ones are skipped. |
| `landClearingEnabled` | boolean | `true` | — | yes | Whether a colony clears natural blocks (tag `nerocolonies:clearable`) from a building site. Drops go to colony storage. It never touches `nerocolonies:protected`, a block with a block entity, or another finished structure. |
| `maxConcurrentPlans` | integer | `4` | 0–16 | yes | How many hand-placed building plans a colony may have queued at once. `0` stops new plans being queued. |

## Defence, the cache and names

| Key | Type | Default | Range | Server-authoritative | Purpose |
| --- | --- | --- | --- | --- | --- |
| `guardsAttackEnemyPlayers` | boolean | `true` | — | yes | Whether Guards and guardian animals attack players on the colony's Enemy list. Hostile mobs are always fair game. See [Roles and defence](Roles-and-Defence.md). |
| `guardPursuitMargin` | integer | `16` | 0–128 | yes | How far beyond the claim edge Guards and guardians will chase a target. |
| `cacheStockIntervalCycles` | integer | `36` | 1–10,000 | yes | Colony cycles between Gratitude Cache stockings. See [Gratitude cache](Gratitude-Cache.md). |
| `neranNamesEnabled` | boolean | `true` | — | yes | Whether new Nerans are given a name from the language file's name pool. |

## Outposts

| Key | Type | Default | Range | Server-authoritative | Purpose |
| --- | --- | --- | --- | --- | --- |
| `outpostsPerColony` | integer | `4` | 0–64 | yes | How many outposts one colony may parent. |
| `outpostClaimRadius` | integer | `16` | 4–256 | yes | Claim radius of an outpost beacon. |
| `outpostColonistCap` | integer | `2` | 0–64 | yes | Nerans an outpost may hold. |
| `outpostJobSlots` | integer | `1` | 0–16 | yes | Job slots an outpost may work. |
| `outpostMaxDistance` | integer | `512` | 16–16,384 | yes | Maximum distance between an outpost and its parent colony, same dimension only. |

## Privacy

| Key | Type | Default | Range | Server-authoritative | Purpose |
| --- | --- | --- | --- | --- | --- |
| `accessLogEnabled` | boolean | `false` | — | yes | **Off by default.** When on, a colony records `{player UUID, action, timestamp}` rows for administrative review — never chat, never IP, never coordinates. Rows expire after `accessLogRetentionDays`. See [`../PRIVACY.md`](../PRIVACY.md). |
| `accessLogRetentionDays` | integer | `7` | 1–365 | yes | How long an access-log row is kept before the retention sweep deletes it. |
| `erasureOwnedColonyPolicy` | string | `transfer_to_server` | `transfer_to_server` / `dissolve` | yes | What happens to colonies owned by a player who requests erasure: `transfer_to_server` (the colony keeps running, ownerless — a co-op server is not griefed) or `dissolve` (the colony record is deleted). |

## Ecosystem integration

| Key | Type | Default | Server-authoritative | Purpose |
| --- | --- | --- | --- | --- |
| `gateWritesEnabled` | boolean | `true` | yes | Whether NeroColonies writes progression gates: Core's `first_colony` and its own `nerocolonies:established` when a colony is founded, and `nerocolonies:self_sufficient` when a colony is housed, fed, breathing and working with its owner online. NeroColonies never *requires* a gate to be open; this only controls the writes. |
| `thresholdEventsEnabled` | boolean | `true` | yes | Whether colony food, oxygen, morale, structure-completion and stage threshold crossings are published on Core's event bus for other mods. Scope is a colony id, never a person. |
| `linkModuleEnabled` | boolean | `true` | yes | Whether the companion-app link module is registered. Snapshots are per-player scoped and never enumerate other players. |

## See also

- [Admin guide](Admin-Guide.md) — which of these to reach for, and when
- [Progression](Progression.md) — the stage and growth keys in play
- [Construction](Construction.md) — founders and the autonomous build loop
- [Colony basics](Colony-Basics.md) — what the claim, morale and catch-up keys actually govern
- [Data storage](Data-Storage.md) — the privacy keys in practice
- [Telemetry](Telemetry.md) — `telemetryEnabled`
