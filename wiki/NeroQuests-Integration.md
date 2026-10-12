# NeroQuests integration

NeroColonies ships a **quest chapter** for NeroQuests, Neroland's quest-book mod. The chapter is a set
of data files inside the NeroColonies jar: with NeroQuests installed it appears in the quest book;
without it the files are inert. **NeroQuests is optional and never a dependency** — neither mod loads
a class from the other. Everything between them goes through Neroland Core's progression gates and
threshold event bus.

This page lists the chapter, the signals NeroColonies publishes for it (which any mod or datapack may
read), and the two rewards a quest can send back to a colony.

## The chapter: Colony Chronicle

Chapter id `nerocolonies:colony_chronicle`, 22 quests. Every quest after *Beacon Lit* is hidden until
your own `nerocolonies:established` gate is open — that is, until you have founded a colony.

**Personal quests** (one completion per player, about *your* colony):

| Quest | Done when |
| --- | --- |
| Beacon Lit | You found a colony |
| Drawing Board | You craft a Colony Planner |
| Far Reach | You craft an Outpost Beacon |
| Settled Ground | A colony finishes its third structure |
| Settler, Township, City, Metropolis | Your colony reaches Settled, Growing, Thriving, Metropolis |
| First Breath | Your colony completes a cycle with life support holding |
| First Harvest | Your colony completes a cycle with food in store and nobody starving |
| First Breakthrough | Your colony unlocks a research node |
| Open for Trade | Your colony sells its export buffer |
| Outpost Planted | You found an outpost |
| On Its Own Feet | Your colony is housed, fed, breathing and working at once |

**World log quests** (completed once for the whole server, by whichever colony gets there first):
*Charter Signed*, *Growing Township*, *Thriving City* and *Skyline* (the first colony to reach each
stage), *Breathing Room* (a colony's life support fails and comes back), *Full Larder* (a colony
recovers from starvation), *Back to Work* (a colony's morale recovers past the work-stop line) and
*Bustling* (a colony passes twenty-five Nerans).

## Signals NeroColonies publishes

These exist whether or not NeroQuests is installed. Any mod or datapack may read them.

### Record gates (personal milestones)

Player-scope Neroland Core gates under `data/nerocolonies/neroland_gates/`, written once and never
read by NeroColonies itself. They have no `requires` and gate nothing.

| Gate | Opened for | When |
| --- | --- | --- |
| `nerocolonies:established` | The founder | A colony beacon is placed (soft gate; honours its `requires`) |
| `nerocolonies:self_sufficient` | The owner | The colony is housed, fed, breathing and working (soft gate) |
| `nerocolonies:stage_settled` … `stage_metropolis` | The owner and every online member | The colony reaches that stage |
| `nerocolonies:life_support_online` | The owner | First colony cycle with life support OK |
| `nerocolonies:food_secure` | The owner | First colony cycle with food in store and nobody starving |
| `nerocolonies:first_research` | The owner | The colony unlocks a research node |
| `nerocolonies:first_export` | The owner | The colony sells its export buffer |
| `nerocolonies:first_outpost` | The placing player | An outpost is founded |

Gates are written for the player **online or offline**. `gateWritesEnabled=false` switches all of
them off; nothing in NeroColonies changes, because nothing in NeroColonies reads a gate.

### Threshold channels (world signals)

Crossings on Core's threshold event bus, published as `nerocolonies`. The scope is always the
**colony id** — a place, never a player — and `thresholdEventsEnabled=false` switches them off.

| Channel | Fires | Value / threshold |
| --- | --- | --- |
| `nerocolonies:stage` | A colony advances | Stage index 1–4 |
| `nerocolonies:structures` | A colony finishes a structure | Structures built |
| `nerocolonies:oxygen` | Life support fails (falling) or recovers (rising) | 1 or 0 / 1 |
| `nerocolonies:food_stock` | Starvation begins (falling) or ends (rising) | Food in store |
| `nerocolonies:morale` | Work stops (falling) or resumes (rising) | Morale / the work-stop line |
| `nerocolonies:population` | The roster crosses 10, 25 or 50, either way | Population / the rung |
| `nerocolonies:research` | A research node is unlocked | Nodes unlocked |
| `nerocolonies:exports_shipped` | An export sale completes | Sales made |

A crossing never says who. That is why a personal milestone is always a gate and a channel only
ever drives a world-log quest.

## Rewards a quest can send to a colony

A quest pack (NeroColonies' own, or a server's datapack) may name two **hook gates** as a
`mod_hook` reward. When the quest completes, the quest mod opens the gate for the player through
Core; NeroColonies applies the effect to that player's **own colony** (the oldest, if they own
several) and closes the gate again.

| Hook gate | Effect |
| --- | --- |
| `nerocolonies:hook/supply_drop` | A fixed crate — 32 cobblestone, 32 oak planks, 16 oak logs, 16 bread, 4 lanterns — goes into the colony's store. What does not fit is skipped, never dropped |
| `nerocolonies:hook/morale_boost` | Morale rises by 15 points, capped at 100, then drifts back toward the colony's real target at `moraleChangeRate` per cycle |

The effect reaches the colony even while its owner is offline. A player who owns no colony yet keeps
the reward until they found one. The [gallery](Gallery.md) sandbox never receives anything. Hook
gates are records: NeroColonies never checks one to allow or deny anything.

In the shipped chapter, *Beacon Lit* sends a supply drop and *First Harvest* a morale boost.

## Privacy

Gate state lives in Neroland Core's progression store, keyed by the player's game UUID and removed by
Core's erasure hook; NeroColonies stores nothing new for quests. Channels carry a colony id and a
count. Log lines are counts only. See [`../PRIVACY.md`](../PRIVACY.md), *Progression gates*.

## See also

- [Progression](Progression.md) — the five stages
- [Config](Config.md) — `gateWritesEnabled`, `thresholdEventsEnabled`
- [Link module](Link-Module.md) — the other integration surface
- [Home](Home.md)
