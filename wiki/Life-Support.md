# Life support

Keeping a colony breathing on a world that has no air of its own.

> **The short version:** an oxygen generator turns power into oxygen gas, the colony burns it every
> cycle, and running out decays morale and nothing else. It never kills a colonist. On a breathable
> dimension the whole system is inert — the machinery still builds and still runs, it simply has
> nothing to hold back.

## The oxygen generator

`nerocolonies:oxygen_generator` is an electrolyser: it spends grid power and fills a 16,000 mB tank
with oxygen at 4 mB per tick, before upgrade modules.

| Property | Value |
| --- | --- |
| Energy buffer | 50,000 |
| Maximum energy transfer | 1,000 per tick |
| Gas tank | 16,000 mB |
| Base output | 4 mB per tick |
| Energy draw while running | `oxygenGeneratorEnergyPerTick` (default 40 per tick) |
| Upgrade slots | 2 |

`SPEED` modules raise throughput; `EFFICIENCY` modules reduce the energy each tick costs. A
generator with a full tank, or with too little energy to pay for a tick, simply stops until there is
room or power again.

Its comparator output tracks the stored oxygen fraction, so redstone can react to a tank running
down.

### Sides

Power comes **in** on every face and never leaves. Upgrade modules come in through the item channel,
so a pipe can restock them. Gas is set to **input *and* output** on every face by default: a colony
that already has an oxygen supply — a tank from a planet mod, a gas pipe network — should be able to
feed this tank rather than being made to duplicate it.

Life support's own draw is deliberately **separate from the side configuration**. Colony upkeep must
not be blocked by somebody having set every face's gas mode to disabled: the side config governs
what leaves through the walls, not what the colony consumes internally.

## Core's gas system

The tank is Neroland Core's gas buffer — identifier-keyed, measured in **millibuckets** — and the
gas it produces is `nerospace:oxygen`.

That id is a shared **value**, not a shared type. Using a planet mod's own gas class would put that
mod's types on this mod's compile classpath and turn a soft dependency into a hard one. Because the
tank speaks Core's gas capability instead, any Core-aware gas system can fill or drain it with no
NeroColonies-specific API at all.

## How the colony finds its generators

Each running generator **files its own position** with its colony's life support on a slow cadence
(every 40 ticks), and re-resolves which claim it stands in every 200 ticks. The colony tick then
drains from the filed generators.

The alternative — having the colony search its claim for generators — would be a block scan on a
hot path. A generator that stops running (no power, broken, chunk unloaded) simply stops refreshing
and its registration expires after 200 ticks. Nothing about this registry is persisted, and nothing
in it is player-shaped.

A generator whose chunk is merely unloaded is skipped rather than dropped: an absent chunk is not
evidence of anything.

## The demand

Per colony cycle:

```text
oxygen required (mB) = oxygenMbPerColonistPerCycle x population x research oxygen multiplier
```

`oxygenMbPerColonistPerCycle` defaults to 20. Every unlocked `oxygen_efficiency` research effect
compounds into the multiplier, which is floored at 0.1 — no amount of research makes life support
free. Efficiency modules on a generator apply at the generator, to its energy cost, not here.

A colony with no population, or a server that has set the figure to `0`, needs nothing.

## The state machine

```text
  OK  --(oxygen shortfall)-->  DEGRADED  --(grace expires)-->  FAILED
   ^                              |                              |
   +---------(oxygen restored)----+------------------------------+
```

- **OK** — demand was met this cycle (or there was none).
- **DEGRADED** — the colony is short and coasting on reserves. This state lasts
  `lifeSupportGraceTicks` (default 1200 ticks — one minute) of accumulated shortfall. The grace
  window exists so that a momentary power cut is not a catastrophe.
- **FAILED** — the grace ran out.

Recovery is **immediate** on the first cycle oxygen is available again, from either state. A colony
that has been rescued should feel rescued.

Grace is session state: it is not written to the world save, so a reloaded colony starts from a
clean grace window and reassesses from what it finds.

## What FAILED actually does

**It decays morale. That is all.**

Failed life support contributes zero to the morale life-support term (against 1.0 for OK and 0.5 for
DEGRADED), which drags the morale target down by up to `moraleWeightLifeSupport` points — 30 by
default. Morale then slides toward that target at the usual rate, and if it falls below
`moraleWorkStopThreshold` the colony's jobs stop and its colonists idle. Population growth is also
gated on life support holding, so a failed colony stops growing.

**No colonist is ever killed, harmed or removed by life-support failure.** Nothing is destroyed and
nothing is lost. The failure curve runs *life support loss → morale decay → work stop → idle* and
stops there, so a colony that has gone wrong is still a colony you can walk back into and fix.

A crossing in either direction is published on Core's threshold event bus (channel
`nerocolonies:oxygen`, scoped to the colony id and never to a person), so other mods — quest mods,
alert systems — can react. Switch it off with `thresholdEventsEnabled`.

## Which dimensions need life support

Two signals, in strict priority order.

1. **A planet mod's own answer.** When Nerospace is installed, its `airless` flag for the dimension
   is authoritative. NeroColonies reads it through a single reflective adapter, resolved once at
   startup: Nerospace is a **soft dependency**, is not on the compile classpath and is not required
   by any manifest.
2. **Core's space dimension tag, as an advisory hint.** With Nerospace absent, a dimension carrying
   the shared Neroland space tag is treated as airless. Another mod's planet dimension may well
   carry that tag, and honouring it costs nothing and makes NeroColonies work with a planet mod it
   has never heard of.

The hint never overrides the adapter: with Nerospace installed, a dimension it says is breathable is
breathable, tag or no tag. There is a single authority for the answer whenever there is one.

### With neither

**Every dimension is breathable.** Life support is never in trouble, whatever the machinery says,
and the oxygen generator is buildable, runnable and simply unnecessary — an Earth-only game can
still plumb one in for the day a planet mod arrives.

That fallback is deliberately not "assume airless". A mod that made vanilla Minecraft unbreathable
because a *different* mod was not installed would be indefensible.

### What is deliberately not consulted

Per-block breathability — a planet mod's oxygen-field system — is **not** used anywhere in
NeroColonies. Colonist life support is our own colony-level system precisely because a planet mod's
oxygen path is written for players and has no NPC route at all. There was never an implementation to
reuse, so this is not duplication.

## Research that helps

| Node | Effect |
| --- | --- |
| `nerocolonies:life_support/recyclers` | oxygen burn x 0.85 |
| `nerocolonies:life_support/hydroponics` | oxygen burn x 0.9, plus the hydroponics job and a morale bonus |

Multipliers compound, floored at 0.1 of the base burn. See
[Jobs & research](Jobs-and-Research.md).

## See also

- [Colony basics](Colony-Basics.md) — morale, population and the colony cycle
- [Jobs & research](Jobs-and-Research.md) — unlocking the efficiency nodes
- [Config](Config.md) — `oxygenMbPerColonistPerCycle`, `oxygenGeneratorEnergyPerTick`,
  `lifeSupportGraceTicks`, `moraleWeightLifeSupport`
