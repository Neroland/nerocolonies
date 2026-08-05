# Configuration

Every NeroColonies setting, in `config/nerocolonies.properties`. The file is written on first launch
with each key at its default and a comment describing it, and it is hot-reloadable with
`/neroland config reload`.

**Every gameplay key is server-authoritative**: the server decides and clients are told. The one
exception is `telemetryEnabled`, which is client-local — anonymous crash reporting is a personal
opt-out that a server must never force on or off.

## Crash telemetry

| Key | Type | Default | Server-authoritative | Purpose |
| --- | --- | --- | --- | --- |
| `telemetryEnabled` | boolean | `true` | **no** — client-local | Send anonymous, NeroColonies-only crash reports (Sentry, EU servers): stack trace, mod/MC/loader/OS/Java versions, your other installed mods, this mod's config, recent in-game actions, anonymous stability and timing data. No IP, username, UUID, world data, colony ownership or chat; file paths scrubbed of your account name. `false` opts out of all of it. See [Telemetry](Telemetry.md) and [`../PRIVACY.md`](../PRIVACY.md). Read once at bootstrap, so a change takes effect on restart. |

## Claims and caps

| Key | Type | Default | Range | Server-authoritative | Purpose |
| --- | --- | --- | --- | --- | --- |
| `maxColoniesPerPlayer` | integer | `3` | 0–64 | yes | How many colonies one player may own at once. `0` disables founding new colonies. |
| `maxColoniesTotal` | integer | `200` | 1–10,000 | yes | Server-wide colony cap. The safety net behind the per-player cap; also bounds how much work the colony tick can ever create. |
| `claimRadius` | integer | `48` | 8–512 | yes | Beacon claim radius in blocks. `RANGE` upgrade modules add to this per colony. |
| `minColonySpacing` | integer | `192` | 0–8,192 | yes | Minimum distance between two colony beacons in the same dimension. A placement inside this radius is refused with a translated message rather than silently allowed. |

## Population and performance

| Key | Type | Default | Range | Server-authoritative | Purpose |
| --- | --- | --- | --- | --- | --- |
| `colonistsPerColony` | integer | `24` | 0–256 | yes | Population cap per colony. Housing capacity can never raise the roster above this. |
| `maxLoadedColonists` | integer | `300` | 0–5,000 | yes | Global cap on colonist entities alive at once across all loaded colonies. |
| `colonyTickIntervalTicks` | integer | `100` | 20–12,000 | yes | How often a colony processes production, food and morale. Colonies are staggered across this interval so N colonies never tick on the same game tick. |
| `colonyTickBudgetMs` | integer | `5` | 1–200 | yes | Millisecond budget for colony processing per game tick. The remainder of a batch is deferred to the next tick rather than blowing the tick time. |
| `aiActiveRadius` | integer | `64` | 0–512 | yes | Distance from an owner or access-list member within which colonist AI runs at full rate. Beyond it the goal selector runs at a quarter rate and pathfinding is suspended. |
| `housingScanIntervalTicks` | integer | `600` | 100–24,000 | yes | How often the claim is rescanned for housing blocks to recompute capacity and comfort. |

## Offline catch-up

| Key | Type | Default | Range | Server-authoritative | Purpose |
| --- | --- | --- | --- | --- | --- |
| `catchUpMaxHours` | integer | `24` | 0–720 | yes | Cap on the offline window a colony catches up on when its chunk reloads. `0` disables catch-up entirely (colonies then only ever produce while loaded). |
| `catchUpEfficiency` | double | `0.5` | 0.0–1.0 | yes | Multiplier applied to production and consumption during offline catch-up. Below 1.0 so there is no incentive to chunk-load a planet for free yield. |

## Life support and food

| Key | Type | Default | Range | Server-authoritative | Purpose |
| --- | --- | --- | --- | --- | --- |
| `foodPerColonistPerCycle` | integer | `1` | 0–64 | yes | Food items consumed per colonist per colony tick. `0` makes colonies never hungry. |
| `oxygenMbPerColonistPerCycle` | integer | `20` | 0–10,000 | yes | Millibuckets of oxygen gas burnt per colonist per colony tick to hold life support. |
| `oxygenGeneratorEnergyPerTick` | long | `40` | 0–1,000,000 | yes | Energy per tick the colony oxygen generator draws while running. |
| `lifeSupportGraceTicks` | integer | `1200` | 0–72,000 | yes | How long life support stays DEGRADED before it is considered FAILED. Failure drives morale decay; it never kills a colonist. |

## Morale

| Key | Type | Default | Range | Server-authoritative | Purpose |
| --- | --- | --- | --- | --- | --- |
| `moraleBase` | double | `50.0` | 0–100 | yes | Morale baseline before any weighted term is applied. |
| `moraleWeightHousing` | double | `20.0` | 0–100 | yes | Weight of the housing-comfort term in the morale sum. |
| `moraleWeightFood` | double | `20.0` | 0–100 | yes | Weight of the food-stock term in the morale sum. |
| `moraleWeightLifeSupport` | double | `30.0` | 0–100 | yes | Weight of the life-support term in the morale sum. |
| `moraleWeightCrowding` | double | `15.0` | 0–100 | yes | Weight of the overcrowding penalty in the morale sum. |
| `moraleWeightHazard` | double | `10.0` | 0–100 | yes | Weight of the planet-hazard penalty in the morale sum. Only ever non-zero when a planet mod is installed and reports a hazardous planet. |
| `moraleChangeRate` | double | `2.0` | 0.01–100 | yes | Points morale moves toward its target per colony tick. Morale is never snapped. |
| `moraleWorkStopThreshold` | double | `20.0` | 0–100 | yes | Below this morale jobs halt and colonists idle. Colonists are never deleted. |
| `moraleMinMultiplier` | double | `0.25` | 0.0–1.0 | yes | Output multiplier floor at zero morale. Production is a curve down to this, not a cliff. |

## Jobs and exports

| Key | Type | Default | Range | Server-authoritative | Purpose |
| --- | --- | --- | --- | --- | --- |
| `jobSlotsPerColony` | integer | `4` | 0–64 | yes | Base number of simultaneously worked job slots per colony. Research raises it. |
| `jobBaseRateMultiplier` | double | `1.0` | 0–100 | yes | Global scalar on every job's production rate. |
| `exportBufferSlots` | integer | `18` | 1–54 | yes | Slots in the beacon's export buffer. Overflow blocks further export production rather than voiding items. |
| `exportValueMultiplier` | double | `1.0` | 0–1,000 | yes | Scalar on the credits paid when an export entry is sold. |

## Outposts

| Key | Type | Default | Range | Server-authoritative | Purpose |
| --- | --- | --- | --- | --- | --- |
| `outpostsPerColony` | integer | `4` | 0–64 | yes | How many outposts one colony may parent. |
| `outpostClaimRadius` | integer | `16` | 4–256 | yes | Claim radius of an outpost beacon. |
| `outpostColonistCap` | integer | `2` | 0–64 | yes | Colonists an outpost may hold. |
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
| `gateWritesEnabled` | boolean | `true` | yes | Whether founding a colony opens Core's `first_colony` progression gate. NeroColonies never *requires* a gate to be open; this only controls the write. |
| `thresholdEventsEnabled` | boolean | `true` | yes | Whether colony food, oxygen and morale threshold crossings are published on Core's event bus for other mods. Scope is a colony id, never a person. |
| `linkModuleEnabled` | boolean | `true` | yes | Whether the NeroLink companion module is registered. Snapshots are per-player scoped and never enumerate other players. |

## See also

- [Admin guide](Admin-Guide.md) — which of these to reach for, and when
- [Colony basics](Colony-Basics.md) — what the claim, morale and catch-up keys actually govern
- [Data storage](Data-Storage.md) — the privacy keys in practice
- [Telemetry](Telemetry.md) — `telemetryEnabled`
