# NeroColonies

**Settle the planets you reached — drop a beacon, ship in supplies, and let colonist NPCs grow a self-sustaining off-world settlement that runs while you're away.**

NeroColonies is the **sci-fi colony & settlement** mod of the Neroland ecosystem — a lightweight colony layer on top of the Neroland space arc, deliberately *not* a deep village-management sim. Claim a site on a discovered planet, anchor it with a colony beacon, ship in supplies, and watch automated colonists grow a settlement that produces rare off-world exports. Colonies are an automation and progression sink, not a micromanagement game — establish and upgrade them, then leave them ticking on the server.

Built on **Neroland Core**, so its power/upgrade-module framework, currency and reputation APIs, claim/permission layer, progression gates, `c:` compat tags, and shared data-erasure hook are shared with the rest of the lineup. *(Planned — in design; not yet released.)*

---

## What you build

1. **Colony command block.** The beacon that anchors and governs a colony. Placing it claims a configurable radius and registers a new colony record — its block entity holds core state (claim bounds, roster, morale, research) backed by level-attached saved data, so the colony survives chunk unload and server restart. A GUI reports status and config, and it integrates with Core's claim/permission layer for placement and teardown rights.
2. **Colonist NPCs.** Automated worker entities that spawn against housing capacity, register on the job board, and pathfind between home, workplace, and storage. They're interchangeable labour units — not persistent personalities — carrying a job, a home, and a contribution to morale and output. AI tick rate and population are config-bounded and idle down when no owner is nearby.
3. **Life support.** On planets Nerospace flags as non-breathable, a colony must run an **oxygen generator** — a Core-powered machine burning fuel/power to keep a "life support OK" state on the colony. Drop it and morale falls, then colonists stop working, then the colony idles: a graceful failure curve, never instant death.
4. **Food production chains.** Colonists consume food from colony storage each cycle at a config-driven, per-colonist rate. Food is farmed by colonist jobs or shipped in — off-world food is harder, making it a real logistics decision. Any food-tagged item counts (Core compat tags).
5. **Housing levels.** Upgradeable dwellings — each research-unlocked tier raises colonist capacity and comfort, feeding morale. The colony scans housing within its claim and sums it into a capacity stat.
6. **Colony morale.** A per-colony 0–100 stat that modulates output. Good housing, steady food, and intact life support raise it; shortages, overcrowding, or life-support loss lower it. Low morale throttles work but never deletes colonists.
7. **Automated jobs.** The production engine — a job board of work slots, colonists filling them, each running a datapack-defined recipe on the server tick: inputs from storage in, outputs to storage or the export buffer out. Throughput scales with assigned colonists and morale.
8. **Research trees.** Colony-local progression. Spend accumulated colony resources at a research station to unlock higher housing tiers, more job slots, better oxygen efficiency, and new export recipes. The tree lives in datapacks so packs can retune it.
9. **Colony exports.** High-value surplus flagged for export accumulates in an export-buffer interface. NeroLogistics shipping drains and routes it home or to market; NeroEconomy prices and sells it via Core's currency API. Export tables are datapack-defined and research-gated.
10. **Planetary outposts.** Small forward bases that extend a colony's reach — resource nodes, relays, or staging points — tied to a parent colony, sharing its claim context with reduced colonist and job capacity, letting you spread across a planet incrementally.

## Built to run while you're away

- 🛰️ **Persistence is a feature** — colonies tick on the server and produce passively within fuel, food, and morale limits, rewarding returning players.
- ⚙️ **Performance-first** — colony ticks are batched and throttled per-colony, colonist AI is capped and reduces when no owner is near, and server config bounds colony count, colonist count, claim radius, and tick budget.
- 🎛️ **Tune or disable anything** — production rates, oxygen fuel burn, food consumption, morale decay, research costs, and export buffer size are all server-config driven; job recipes, research nodes, housing tiers, and export tables are datapack-overridable.
- 🤝 **Shared-world fairness** — config governs minimum spacing between colonies and per-player/per-faction caps so a few players can't blanket a planet.

## Privacy (POPIA / GDPR)

NeroColonies records **player-linked data** for gameplay and anti-grief: colony ownership and **access logs** (who entered, who changed jobs, who pulled from storage). This is kept to the minimum — **UUID + action + timestamp only**, never names, chat, IP, or location beyond the colony. Access logs auto-expire on a configurable, short default retention window and purge automatically. Admin/player commands **export** a player's colony records and **erase** them on request, routed through Core's shared data-erasure hook so one request purges you across every Neroland mod. Non-essential logging is **opt-out**, and anything optional defaults to off. Any crash telemetry stays anonymous and opt-out — version strings only, never personal data or world state.

## Why it fits the ecosystem

- 🧩 **Built on Neroland Core** — one power/upgrade framework, one currency and reputation layer, one claim/permission system, one progression arc, and shared `c:` material tags. NeroColonies ships in its own creative tab.
- 🚀 **The payoff for the space arc** — it turns Nerospace's planets into places worth living, reading breathability and dimension data from Nerospace to gate life support. It closes the Earth → industrialise → space → colonies journey (Build #8) and seeds later mods with persistent, contestable off-world assets.
- 🔌 **Interoperates, never hard-depends** — synergy mods are detected at runtime: **NeroAgriculture** feeds colonists, **NeroLogistics** ships supplies and drains export buffers, and **NeroEconomy** prices and sells exports. External mods (Create, AE2, Mekanism, Ad Astra, Energized Power) interoperate through Core's common tags for power, items, and oxygen — no hard dependency on any of them.
- 🧱 **Cross-loader** — NeoForge, Forge, and Fabric on Minecraft **26.1.2** and **26.2**.

## Requirements & compatibility

- **Requires [Neroland Core](https://modrinth.com/mod/nerolandcore)** — install it alongside NeroColonies (it loads first).
- **[Nerospace](https://modrinth.com/mod/nerospace)** is a strong companion — it provides the planets to colonise and the breathability data that drives life support. Without it, colonies degrade gracefully to Earth-only and lose the off-world experience the mod is built around.
- Conventional `c:` tags and loader-native capabilities let Create, AE2, Mekanism, Ad Astra, and Energized Power interoperate for power, storage, and oxygen as the 26.x ecosystem fills in — no hard dependency on any of them.
- **Modpacks are allowed and encouraged** — any platform, no need to ask. Use the official files and credit *NeroColonies by Neroland* with links to this page and the [GitHub repository](https://github.com/Neroland/nerocolonies). Full terms: [LICENSE](https://github.com/Neroland/nerocolonies/blob/main/LICENSE).

## Links

- 📖 **[Wiki](https://github.com/Neroland/nerocolonies/wiki)** — every block, colony system, and setting documented.
- 💬 **[Discord](https://discord.gg/ArPXvYUzJG)** — chat, help, and sneak peeks.
- 🐞 **[Issues](https://github.com/Neroland/nerocolonies/issues)** — bug reports and feature requests.
- 🗒️ **[Changelog](https://github.com/Neroland/nerocolonies/blob/main/CHANGELOG.md)**
- 🟢 **[Also on Modrinth](https://modrinth.com/mod/nerocolonies)**

---

*Created by Neroland. The project logo was made with the help of AI image tools; in-game art is generated by the project's own tooling and refined by hand.*
