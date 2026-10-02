[![NeroLink App — Beta](https://img.shields.io/badge/NeroLink_App-Now_in_Beta-60d4e8?style=for-the-badge)](https://nerolandmc.net/nerolink/#beta) [![Explore the Neroland ecosystem](https://img.shields.io/badge/Explore-The_Neroland_Ecosystem-1a5a6c?style=for-the-badge)](https://nerolandmc.net/ecosystem/)

> 📱 **NeroLink App Beta — your Neroland world on your phone.** Check energy, alerts and machines live, claim quest rewards and search your storage without logging in. **[Join the beta at nerolandmc.net →](https://nerolandmc.net/nerolink/#beta)**
>
> 🌌 **Explore the Neroland ecosystem.** See how NeroColonies fits together with the rest of the Nero mods — every mod, wiki and changelog in one place. **[View the ecosystem at nerolandmc.net →](https://nerolandmc.net/ecosystem/)** · [NeroColonies on the website](https://nerolandmc.net/mods/nerocolonies/)

---

# NeroColonies

**Settle the planets you reached — drop a beacon, ship in supplies, and let your Nerans, the colonists of Neroland, grow a self-sustaining off-world settlement that runs while you're away.**

NeroColonies is the **sci-fi colony & settlement** mod of the Neroland ecosystem — a lightweight colony layer on top of the Neroland space arc, deliberately *not* a deep village-management sim. Claim a site on a discovered planet, anchor it with a colony beacon, ship in supplies, and watch your Nerans grow a settlement that produces rare off-world exports. Colonies are an automation and progression sink, not a micromanagement game — establish and upgrade them, then leave them ticking on the server.

Built on **Neroland Core**, so its power/upgrade-module framework, currency and reputation APIs, claim/permission layer, progression gates, `c:` compat tags, and shared data-erasure hook are shared with the rest of the lineup. *(Now in beta: 0.3.0-beta.1.)*

---

## What you build

1. **Colony command block.** The beacon that anchors and governs a colony. Placing it claims a configurable radius and registers a new colony record — its block entity holds core state (claim bounds, roster, morale, research) backed by level-attached saved data, so the colony survives chunk unload and server restart. A GUI reports status and config, and it integrates with Core's claim/permission layer for placement and teardown rights.
2. **Nerans.** The people of your colony. Two founders arrive with the beacon, more move in as housing allows, and a growing colony has children. Each Neran has a home, adults take a trade and a workplace, and they pathfind between home, work, the canteen and the plaza, opening doors on the way and backing off from a path that is blocked. AI tick rate and population are config-bounded and idle down when no member is nearby.
3. **Life support.** On planets Nerospace flags as non-breathable, a colony must run an **oxygen generator** — a Core-powered machine burning fuel/power to keep a "life support OK" state on the colony. Drop it and morale falls, then Nerans stop working, then the colony idles: a graceful failure curve, never instant death.
4. **Food production chains.** Nerans consume food from colony storage each cycle at a config-driven, per-Neran rate. Food is farmed by Neran jobs or shipped in — off-world food is harder, making it a real logistics decision. Any food-tagged item counts (Core compat tags).
5. **Housing levels.** Upgradeable dwellings — each research-unlocked tier raises Neran capacity and comfort, feeding morale. The colony scans housing within its claim and sums it into a capacity stat.
6. **Colony morale.** A per-colony 0–100 stat that modulates output. Good housing, steady food, and intact life support raise it; shortages, overcrowding, or life-support loss lower it. Low morale throttles work but never deletes Nerans.
7. **Automated jobs.** The production engine — a job board of work slots, Nerans filling them, each running a datapack-defined recipe on the server tick: inputs from storage in, outputs to storage or the export buffer out. Throughput scales with assigned Nerans and morale.
8. **Research trees.** Colony-local progression. Spend accumulated colony resources at a research station to unlock higher housing tiers, more job slots, better oxygen efficiency, and new export recipes. The tree lives in datapacks so packs can retune it.
9. **Colony exports.** High-value surplus flagged for export accumulates in an export-buffer interface. NeroLogistics shipping drains and routes it home or to market; NeroEconomy prices and sells it via Core's currency API. Export tables are datapack-defined and research-gated.
10. **Planetary outposts.** Small forward bases that extend a colony's reach — resource nodes, relays, or staging points — tied to a parent colony, sharing its claim context with reduced Neran and job capacity, letting you spread across a planet incrementally.
11. **Stages and Starter Works.** A colony grows through five stages: Founding, Settled, Growing, Thriving and Metropolis. It starts with two founders and four Starter Works (a lodge, a farm, a lumber yard and a mine head) that are built only from materials you deliver. Once they stand, the colony gathers, builds and grows by itself, slowly at first and then faster as its population and building count climb.
12. **Trades.** Twelve data-driven professions (Farmer, Forester, Miner, Builder, Hauler, Cook, Toolsmith, Guard, Beastkeeper, Researcher, Quartermaster and Medic), each opened by its building. Nerans carry the tool of their trade, earn experience and levels, harvest and replant crops, fell and replant trees, and keep a daily schedule of work, a midday meal, an evening at the plaza and sleep.
13. **The needs list.** The colony tells you what it is short of (building materials, food, tools) and how long its own gatherers would take alone. Hand items over at a **Needs Board** to speed it up, or prioritise one need so the colony works on it first.
14. **51 buildings.** From cottages, a granary and a canteen, through watchtowers, a windmill and a market square, to a Grand Town Hall, a Colony Citadel, an Observatory, a Nexus Spire, an Arcology Tower and a Skyport. Nerans clear the land first, buildings turn to face the beacon, and some upgrade in place. The owner and Chiefs can also place a building by hand with the **Colony Planner**.
15. **Roles and defence.** Only the owner can use a colony until they name **Allies** (who may use it) or **Chiefs** (who may also plan buildings and manage members). Mark a player as an **Enemy** and the colony's guards, kennel wolves and forge golems attack them inside the claim, along with hostile mobs and nobody else. An enemy stays one until the owner or a Chief removes them, or until they kill the colony's owner.
16. **Gratitude Cache.** A chest the colony's Quartermaster stocks with gifts for the owner over time, from loot tables that improve with every stage, and now and then a thank-you note.

Operators can run `/nerocolonies gallery` in creative mode to see every building, every trade and the colony AI in one place.

## Built to run while you're away

- 🛰️ **Persistence is a feature** — colonies tick on the server and produce passively within fuel, food, and morale limits, rewarding returning players.
- ⚙️ **Performance-first** — colony ticks are batched and throttled per-colony, Neran AI is capped and reduces when no owner is near, and server config bounds colony count, Neran count, claim radius, and tick budget.
- 🎛️ **Tune or disable anything** — production rates, oxygen fuel burn, food consumption, morale decay, research costs, and export buffer size are all server-config driven; job recipes, professions, blueprints, research nodes, housing tiers, export tables, and the Gratitude Cache loot tables are datapack-overridable.
- 🤝 **Shared-world fairness** — config governs minimum spacing between colonies and per-player/per-faction caps so a few players can't blanket a planet.
- 📱 **Check in from anywhere** — the mod exposes each colony's stage, needs, buildings, trades and Gratitude Cache to a paired NeroLink companion app, with people shown as counts, never names.

## Privacy (POPIA / GDPR)

NeroColonies records **player-linked data** for gameplay and anti-grief: colony ownership and an optional **access log** that is **off by default**. When a server switches it on, it records only a few fixed actions from a closed list of seven: founding a colony, opening its beacon, granting or revoking access, and dissolving it. This is kept to the minimum — **UUID + action + timestamp only**, never names, chat, IP, or location beyond the colony. Access-log rows auto-expire on a configurable, short default retention window (7 days) and purge automatically. Admin/player commands **export** a player's colony records and **erase** them on request, routed through Core's shared data-erasure hook so one request purges you across every Neroland mod. Non-essential logging is **opt-in**: anything optional defaults to off. Colony **role lists** (Allies, Chiefs and Enemies) are stored as player UUIDs only, for the life of the colony, and are erased by the same request; the names behind them are shown only to the colony's owner and Chiefs (and server operators) on the beacon's Roles tab and are never stored. Any crash telemetry stays anonymous and opt-out — version strings only, never personal data or world state.

## Why it fits the ecosystem

- 🧩 **Built on Neroland Core** — one power/upgrade framework, one currency and reputation layer, one claim/permission system, one progression arc, and shared `c:` material tags. NeroColonies' items appear in the shared Neroland creative tab.
- 🚀 **The payoff for the space arc** — it turns Nerospace's planets into places worth living, reading breathability and dimension data from Nerospace to gate life support. It closes the Earth → industrialise → space → colonies journey (Build #8) and seeds later mods with persistent, contestable off-world assets.
- 🔌 **Interoperates, never hard-depends** — synergy mods are detected at runtime: **NeroAgriculture** feeds Nerans, **NeroLogistics** ships supplies and drains export buffers, and **NeroEconomy** prices and sells exports. External mods (Create, AE2, Mekanism, Ad Astra, Energized Power) interoperate through Core's common tags for power, items, and oxygen — no hard dependency on any of them.
- 🧱 **Cross-loader** — NeoForge, Forge, and Fabric on Minecraft **26.1.2**, **26.2** and **26.3**.

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
