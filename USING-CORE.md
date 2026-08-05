# Using Neroland Core

Every Neroland Core API NeroColonies consumes, what it is used for, and where.

**Core floor: `1.10.0`** (`nerolandcore_version` in `gradle.properties`). The manifest dependency
range floors at the compiled Core version on purpose, so the loader refuses a Core too old to have
the APIs this mod compiles against rather than failing later with a `NoSuchMethodError`.

Neroland Core is NeroColonies' **only hard dependency**. Every other Nero mod is optional and
NeroColonies degrades gracefully without them; no third-party mod is depended on at all.

## The table

| Core package / class | Used for | Where |
| --- | --- | --- |
| `config.ConfigSchema`, `config.ConfigValue`, `config.ConfigManager` | The whole config schema — `config/nerocolonies.properties`, hot-reloadable via `/neroland config reload`, with server-authoritative flags per key | `common/src/main/java/za/co/neroland/nerocolonies/config/NeroColoniesConfig.java` |
| `registry.RegistrationProvider`, `RegistrationProvider.RegistryEntry` | Loader-agnostic registration of blocks, items, block entities, entity types and menus | `common/.../registry/NeroColoniesBlocks.java`, `NeroColoniesItems.java`, `NeroColoniesBlockEntities.java`, `NeroColoniesEntityTypes.java`, `NeroColoniesMenus.java`; `forge/.../forge/NeroColoniesForge.java`; `neoforge/.../neoforge/NeroColoniesNeoForge.java` |
| `registry.CoreCreativeTab` | Contributing every item to Core's shared **Neroland** creative tab. NeroColonies has no tab of its own, so five Nero mods still give a player one tab | `common/.../registry/NeroColoniesItems.java` |
| `entity.EntityRegistrationSupport` | Declaring the colonist's attributes through Core's cross-loader entity seam. No spawn placement is registered — a colonist is grown by a colony, never spawned naturally | `common/.../registry/NeroColoniesEntityTypes.java` |
| `machine.AbstractMachineBlockEntity` | The base class for all six block entities: energy buffer, upgrade container, side config plumbing and the shared server ticker | `common/.../block/entity/ColonyBeaconBlockEntity.java`, `ColonyDepotBlockEntity.java`, `JobStationBlockEntity.java`, `OutpostBeaconBlockEntity.java`, `OxygenGeneratorBlockEntity.java`, `ResearchStationBlockEntity.java`; the six block classes that build their tickers (`block/ColonyBeaconBlock.java`, `ColonyDepotBlock.java`, `JobStationBlock.java`, `OutpostBeaconBlock.java`, `OxygenGeneratorBlock.java`, `ResearchStationBlock.java`); and all three loader capability classes |
| `sideconfig.SideConfig`, `SideMode`, `SidePreset`, `Channel`, `RelativeFace`, `SlotGroup` | Per-face item / energy / gas routing on every machine, and the item-slot filtering the `WorldlyContainer` implementations delegate to | `common/.../block/entity/ColonyBeaconBlockEntity.java`, `ColonyDepotBlockEntity.java`, `JobStationBlockEntity.java`, `OutpostBeaconBlockEntity.java`, `OxygenGeneratorBlockEntity.java`, `ResearchStationBlockEntity.java` (the research station uses every one of these except `SlotGroup` — it has no item slots) |
| `upgrade.UpgradeType` | The four upgrade modules — `SPEED`, `EFFICIENCY`, `RANGE`, `CAPACITY` — and reading installed module counts (`RANGE` widens a claim, `CAPACITY` sizes colony storage) | `common/.../registry/NeroColoniesItems.java`, `common/.../block/entity/ColonyBeaconBlockEntity.java`, `common/.../block/entity/OutpostBeaconBlockEntity.java` |
| `upgrade.UpgradeContainer` | The module-item classifier every machine's upgrade container is built with | `common/.../item/ColonyUpgradeItem.java` |
| `gas.GasBuffer`, `gas.NeroGasStorage`, `gas.NeroGases` | The oxygen generator's 16,000 mB tank, its capability surface, and the empty-gas sentinel used on load | `common/.../block/entity/OxygenGeneratorBlockEntity.java`; `gas.NeroGasStorage` also in `forge/.../forge/ForgeColonyCapabilities.java` |
| `energy.NeroEnergyStorage` | The energy capability surface exposed on Forge | `forge/.../forge/ForgeColonyCapabilities.java` |
| `platform.FabricEnergyLookup`, `platform.FabricGasLookup` | Registering the energy and gas capabilities on Fabric | `fabric/.../fabric/FabricColonyCapabilities.java` |
| `platform.ForgeEnergyLookup`, `platform.ForgeGasLookup` | The same on Forge | `forge/.../forge/ForgeColonyCapabilities.java` |
| `platform.NeoForgeEnergyLookup`, `platform.NeoForgeGasLookup` | The same on NeoForge | `neoforge/.../neoforge/NeoForgeColonyCapabilities.java` |
| `economy.CurrencyApi`, `economy.CoreCurrencies` | Selling the export buffer: `CurrencyApi.hasRealProvider()` guards the sale, and `CurrencyApi.deposit(..., CoreCurrencies.CREDITS, ...)` pays the colony's owner. **The only code-level hook in the whole export system.** The client snapshot also carries `hasRealProvider()` so the GUI can say why selling is unavailable | `common/.../colony/ExportBuffer.java`; `common/.../network/ColonySnapshotPayload.java` |
| `progression.ProgressionGates`, `progression.CoreGates` | `ProgressionGates.tryOpen(player, CoreGates.FIRST_COLONY)` when a colony is founded, plus NeroColonies' own two soft gates (`nerocolonies:established`, `nerocolonies:self_sufficient`), which ship as datapack files under `data/nerocolonies/neroland_gates/`. **`tryOpen`, never `open`** — the gate's own requirements are Core's business — and NeroColonies never *requires* a gate to be open, not even its own. Controlled by `gateWritesEnabled` | `common/.../block/ColonyBeaconBlock.java`, `common/.../progression/ColonyGates.java`, `common/.../colony/ColonyTicker.java` |
| `link.NeroLinkRegistry`, `link.LinkModuleInfo`, `link.LinkSnapshotProvider`, `link.LinkActionHandler`, `link.LinkActionResult`, `link.LinkEvent`, `link.LinkAlert`, `link.LinkAlerts` | The whole companion-app surface: five per-player snapshot sections, two actions, four owner-scoped events plus one broadcast, and two rate-limited alerts through Core's per-player alert store. Registered **last** in common init, wholly inside a `try/catch`, and only when `linkModuleEnabled` is on. NeroColonies ships no server of its own — a separate bridge mod reads Core's registry | `common/.../link/ColonyLinkModule.java`, `ColonyLinkSnapshots.java`, `ColonyLinkActions.java`, `ColonyLinkEvents.java`, `ColonyLinkAccess.java` |
| `event.ThresholdEvents` | Publishing colony food, oxygen and morale threshold crossings on Core's shared event bus, scoped to a **colony id** and never to a player. Controlled by `thresholdEventsEnabled` | `common/.../colony/ColonyTicker.java` |
| `data.PlayerDataErasure` | Registering NeroColonies' eraser so one POPIA/GDPR request purges a player across every Nero mod. Registered early, ahead of the store it purges | `common/.../data/NeroColoniesData.java` |
| `worldgen.SpaceTags` | `SpaceTags.isSpace(level)` as the **advisory** signal that a dimension needs life support, used only when no planet mod is installed to answer authoritatively | `common/.../compat/CompatRegistry.java` |

## Things NeroColonies deliberately does *not* take from Core

- **No claim layer.** Core has none, so the claim, access-list and operator-override model is
  NeroColonies' own.
- **No creative tab of its own.** Everything joins Core's shared tab.
- **No HTTP or outbound networking.** Core ships none and neither does this mod; a companion app is
  served by a separate bridge mod.

## See also

- [`wiki/Config.md`](wiki/Config.md) — the config schema built on Core's framework
- [`wiki/Data-Storage.md`](wiki/Data-Storage.md) — the erasure hook in practice
- [`wiki/Exports-and-Outposts.md`](wiki/Exports-and-Outposts.md) — the currency API in practice
- [`wiki/Life-Support.md`](wiki/Life-Support.md) — Core's gas system and the space dimension tag
- [`PRIVACY.md`](PRIVACY.md)
