# Admin guide

Running NeroColonies on a server: the levers, the failure modes, and where to look when something
is wrong.

## The config file

`config/nerocolonies.properties`, created on first launch with every key at its default and a
comment describing it. It is **hot-reloadable**:

```text
/neroland config reload
```

That is Neroland Core's command and it reloads every Nero mod's config at once. Edit the file, run
it, and the new values are live — no restart, no world reload.

Two keys are exceptions to "live immediately":

- `telemetryEnabled` is read once at bootstrap, so changing it takes effect on restart.
- `claimRadius` and `outpostClaimRadius` change what *new* placements get and what an existing
  beacon recalculates on its next refresh; a colony's stored radius is its own.

**Every gameplay key is server-authoritative.** Colonies are decided by the server and clients are
told the values rather than choosing them. The single exception is `telemetryEnabled`, which is a
personal, client-local choice a server must never force either way.

The full table is in [Config](Config.md).

## Performance levers

Colonies are a TPS hazard by construction — colonies x Nerans x production — so the mod is built
around bounding that, and these are the dials.

| Key | Default | What it buys |
| --- | --- | --- |
| `colonyTickIntervalTicks` | 100 | How often a colony processes a cycle. Raising it is the cheapest possible saving: colonies do proportionally less work and simply progress more slowly. Colonies are staggered across the interval, so N colonies never share a game tick. |
| `colonyTickBudgetMs` | 5 | Hard cap on colony processing in any one game tick. A colony that is due when the budget is spent stays due and runs on a later tick — it is deferred, never skipped. |
| `maxColoniesTotal` | 200 | The ceiling on how much work can ever exist. The safety net behind the per-player cap. |
| `colonistsPerColony` | 48 | Population cap per colony. Housing can never raise a roster above it. Lowering it below `stageMetropolisPopulation` (32) puts the top stage out of reach. |
| `maxLoadedColonists` | 300 | Server-wide Neran ceiling across every colony. |
| `aiActiveRadius` | 64 | How close an owner or member must be for a Neran to act at full rate. Beyond it Nerans start new walks and new work far less often; a walk already under way always finishes, and production carries on regardless. |
| `housingScanIntervalTicks` | 600 | Rest between housing sweeps. The sweep is already sliced (two loaded chunks at a time, 20 ticks apart) and never loads a chunk, but a longer rest means a colony that has finished building costs almost nothing. |
| `constructionBlocksPerCycle` | 2 | Blocks a building colony places per cycle, before the growth multiplier. Lowering it slows growth; `constructionEnabled=false` stops autonomous building entirely and hands every structure back to the player. A colony that is not building costs little — the site search tests at most eight candidates for each of two blueprints per cycle and rests for ten cycles after a fruitless sweep. |
| `maxAutoStructures` | 12 | How much a Settled colony will build for itself. Doubled, tripled and quadrupled at the three later stages. |
| `maxBlueprintFootprint`, `maxBlueprintHeight` | 32, 40 | The largest building a colony will put up. Lower them to keep the showpieces off a small server. |

If colony processing is being deferred often, the server log carries one aggregate line at debug
level roughly every five minutes — a count of deferrals and the current budget. It is deliberately
one line rather than one per colony: a throttle message that itself spams the log is worse than the
throttling it reports.

**Where to start when colonies are costing you TPS:** raise `colonyTickIntervalTicks` first (it is
free in every sense except pace), then lower `aiActiveRadius`, then lower `maxLoadedColonists`.
Lowering `colonyTickBudgetMs` does not reduce the work, it only spreads it further.

Leave `debugFastGrowth` off. It exists for test worlds and multiplies the work a colony does.

## Roles, enemies and guards

Colony owners manage their own members: see [Roles and defence](Roles-and-Defence.md). What an
operator should know:

- **An operator acts as the owner of every colony**, at its blocks and in every command.
- **An operator cannot be marked as an Enemy** while online, and guards never attack one.
- **`guardsAttackEnemyPlayers`** decides whether Guards, guardian wolves and guardian golems attack
  players a colony has marked. It is on by default. NeroColonies does not read the server's PvP
  setting, so on a server where players should never be harmed over a colony dispute, set this key to
  `false`. Guards then fight hostile mobs only.
- **`guardPursuitMargin`** is how far past a claim's edge a target is chased.
- No command lists who a colony's members or enemies are. The counts are in `colony info`.

## When a datapack is broken

Bad content is never fatal in NeroColonies. A malformed job, a dangling research prerequisite, a
cycle, a blueprint naming a block that is not installed or an id from a mod that is not installed
drops or prunes the offending entry and the rest of the pack still loads. Even a load that fails
outright leaves the server running with no colony content rather than crashing it.

To see exactly what was rejected:

```text
/nerocolonies reload-check
```

It reports how many jobs, research nodes, housing tiers, export entries, blueprints and professions
loaded, and then the same complaints the server log holds, each as **DROPPED** (the definition is
not loaded at all) or **IGNORED** (it loaded, but part of it was skipped), against the resource id
concerned. Nothing in the report is player data — resource ids and codec messages only, and never a
filesystem path.

The startup log also carries one summary line per content load with the same six counts and how many
validation issues there were.

Content is re-read automatically whenever the server's datapacks are reloaded, so an ordinary
`/reload` is all it takes to apply a fix. See [Content format](Content-Format.md).

To look at every building a pack defines without growing a colony to Metropolis, an operator in
creative mode can build the [gallery](Gallery.md). Read its warning first: it cuts away the terrain
it stands on.

## The retention sweep

Once per server session, the first time the colony index is read, NeroColonies runs a bounded
retention pass (access-log expiry also repeats every hour while the server runs). It:

- deletes **access-log rows** older than `accessLogRetentionDays` (default 7);
- deletes **colony records whose beacon block is gone** — the case where a beacon vanished without a
  break event, such as an explosion or a world edit — and forgets the colony's goods, build record,
  role lists and stage record with them;
- deletes **outpost records** whose parent colony no longer exists, or whose own beacon is gone;
- deletes **build records, role lists and stage records** whose colony no longer exists. What those
  colonies built stays standing — NeroColonies never demolishes anything it put up.

It never loads a chunk. A colony whose beacon is in an unloaded chunk is left alone entirely — an
absent chunk is not evidence of anything — and will be reconsidered in a later session.

It logs **counts only**, never which colonies or which players.

`/nerocolonies purge-stale` runs it on demand.

## Resilience

Every list in the saved data loads entry by entry, so one unreadable entry (an item from a removed
mod, say) is skipped and counted in the log rather than failing the file. If a whole file still
cannot be read, the recovery guard copies it to `<name>.dat.corrupt-<time>` beside the original and
starts that store empty, instead of crashing the server on every load. Repair the copy and put it
back to restore it.

There are five files: the colony index, the colony stores, the construction index, the roles store
and the life store. See [Data storage](Data-Storage.md) for what each holds.

## Common questions

**A colony stopped producing and nothing looks broken.** Check morale. Below
`moraleWorkStopThreshold` (default 20) every job halts, building stops, trades gather nothing and
Nerans idle. Open a job station: it reports whether it is active, blocked, or short of workers.
Individual jobs also carry their own `morale_floor`, which stops that one job without stopping the
colony.

**A colony is producing very slowly.** Check power. An unpowered job station runs at 0.35x rather
than stopping, by design — a colony whose cable was cut should get visibly slower rather than fall
silent.

**Export production stopped.** The export buffer is full. It blocks rather than voiding, on purpose.
Drain it with a hopper or pipe, or sell.

**Selling is refused.** No economy mod is installed, so Core has no real currency provider. Core's
built-in fallback does not persist balances, so paying into it would take the goods and give nothing
back — the sale is refused and the goods stay put.

**A colony came back from being unloaded with less than expected.** Offline catch-up covers what the
colony ate, its life support, its morale and some construction credit, capped at `catchUpMaxHours`
(default 24) and applied at `catchUpEfficiency` (default 0.5). It does not cover arrivals, births,
job stations or what the trades gather. Set `catchUpMaxHours` to `0` to disable catch-up entirely.

**A new colony has two Nerans and is doing nothing.** It is Founding, and waiting for the materials
of its Starter Works. The beacon's Needs tab lists them. See
[Progression](Progression.md#the-starter-works).

**A colony is not building anything.** The beacon's Colony tab names the reason; the lines are listed
on [Progression](Progression.md#colony-tab-why-nothing-is-being-built). In order: is
`constructionEnabled` on; has morale stopped work or life support failed; is the roster empty
(`constructionRequiresColonist`); is it Founding and unsupplied; has it reached the structure cap for
its stage or every blueprint's own `max`; and is there anywhere level, loaded and close to the
beacon's height left inside the claim? `/nerocolonies reload-check` says whether any blueprints
loaded at all. See [Buildings](Buildings.md).

**A colony is building extremely slowly.** It has no materials and is fabricating from scrap at
`constructionUnsuppliedFactor` (default 0.25). The Colony tab says `Fabricating …` rather than
`Building …`. Put the blueprint's materials into colony storage and the same build speeds up
immediately.

**A colony is not advancing to the next stage.** It needs the population *and* the structure count
at once. `/nerocolonies colony info` prints both, and the beacon's Needs tab shows the target. See
[Progression](Progression.md#when-a-colony-is-not-growing).

**A colony's food store is empty although its storage is full of wheat.** What Farmers gather goes
into colony storage. It becomes rations when a Cook turns it into meals, which needs a Canteen, or
when somebody moves it into the beacon's supply slots.

**Nerans keep failing to reach somewhere.** `/nerocolonies colony info` prints the colony's stuck
events. A number that keeps rising points at a home or a workplace that cannot be walked to: a
housing block walled in, a station up a cliff. A Neran that gives up tries again after half a
minute.

**Players cannot found a colony.** Check `maxColoniesPerPlayer` (0 disables founding entirely),
`maxColoniesTotal`, and `minColonySpacing` — the last is 192 blocks by default and is measured
horizontally within one dimension. A [gallery](Gallery.md) counts as one colony towards
`maxColoniesTotal`.

## See also

- [Commands](Commands.md) — the full `/nerocolonies` tree
- [Config](Config.md) — every key, default and range
- [Progression](Progression.md) — stages, and why a colony has stopped growing
- [Buildings](Buildings.md) — what a colony builds and where
- [Roles and defence](Roles-and-Defence.md) — roles, enemies and guards
- [Data storage](Data-Storage.md) — what is stored about players, retention and erasure
- [Content format](Content-Format.md) — writing and debugging datapack content
- [Gallery](Gallery.md) — the creative-mode showcase
- [Telemetry](Telemetry.md) — opt-out crash reporting
