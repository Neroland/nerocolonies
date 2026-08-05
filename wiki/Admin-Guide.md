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

Colonies are a TPS hazard by construction — colonies x colonists x production — so the mod is built
around bounding that, and these are the dials.

| Key | Default | What it buys |
| --- | --- | --- |
| `colonyTickIntervalTicks` | 100 | How often a colony processes a cycle. Raising it is the cheapest possible saving: colonies do proportionally less work and simply progress more slowly. Colonies are staggered across the interval, so N colonies never share a game tick. |
| `colonyTickBudgetMs` | 5 | Hard cap on colony processing in any one game tick. A colony that is due when the budget is spent stays due and runs on a later tick — it is deferred, never skipped. |
| `maxColoniesTotal` | 200 | The ceiling on how much work can ever exist. The safety net behind the per-player cap. |
| `colonistsPerColony` | 24 | Population cap per colony. Housing can never raise a roster above it. |
| `maxLoadedColonists` | 300 | Server-wide colonist entity ceiling across every colony. |
| `aiActiveRadius` | 64 | How close an owner or member must be for a colonist's AI to run at full rate. Beyond it the goals run at a quarter rate and pathfinding stops entirely — and pathfinding is the expensive part of a colonist. |
| `housingScanIntervalTicks` | 600 | Rest between housing sweeps. The sweep is already sliced (two loaded chunks at a time, 20 ticks apart) and never loads a chunk, but a longer rest means a colony that has finished building costs almost nothing. |

If colony processing is being deferred often, the server log carries one aggregate line at debug
level roughly every five minutes — a count of deferrals and the current budget. It is deliberately
one line rather than one per colony: a throttle message that itself spams the log is worse than the
throttling it reports.

**Where to start when colonies are costing you TPS:** raise `colonyTickIntervalTicks` first (it is
free in every sense except pace), then lower `aiActiveRadius`, then lower `maxLoadedColonists`.
Lowering `colonyTickBudgetMs` does not reduce the work, it only spreads it further.

## When a datapack is broken

Bad content is never fatal in NeroColonies. A malformed job, a dangling research prerequisite, a
cycle or an id from a mod that is not installed drops or prunes the offending entry and the rest of
the pack still loads. Even a load that fails outright leaves the server running with no colony
content rather than crashing it.

To see exactly what was rejected:

```text
/nerocolonies reload-check
```

It reports the same complaints the server log holds, each as **DROPPED** (the definition is not
loaded at all) or **IGNORED** (it loaded, but part of it was skipped), against the resource id
concerned. Nothing in the report is player data — resource ids and codec messages only, and never a
filesystem path.

The startup log also carries one summary line per content load: how many jobs, research nodes,
housing tiers and export entries loaded, and how many validation issues there were.

Content is re-read automatically whenever the server's datapacks are reloaded, so an ordinary
`/reload` is all it takes to apply a fix. See [Content format](Content-Format.md).

## The retention sweep

Once per server session, the first time the colony index is read, NeroColonies runs a bounded
retention pass. It:

- deletes **access-log rows** older than `accessLogRetentionDays` (default 7);
- deletes **colony records whose beacon block is gone** — the case where a beacon vanished without a
  break event, such as an explosion or a world edit — and forgets the colony's goods with them;
- deletes **outpost records** whose parent colony no longer exists, or whose own beacon is gone.

It never loads a chunk. A colony whose beacon is in an unloaded chunk is left alone entirely — an
absent chunk is not evidence of anything — and will be reconsidered in a later session.

It logs **counts only**, never which colonies or which players.

## Resilience

Every saved-data read in the mod goes through a recovery guard: a corrupt or unreadable file
degrades to an **empty store**, which is then written clean at the next save, instead of crashing
the server repeatedly on every load. The cost of that degradation is bounded and deliberate, and it
is always better than an unstartable world.

## Common questions

**A colony stopped producing and nothing looks broken.** Check morale. Below
`moraleWorkStopThreshold` (default 20) every job halts and colonists idle. Open a job station: it
reports whether it is active, blocked, or short of workers. Individual jobs also carry their own
`morale_floor`, which stops that one job without stopping the colony.

**A colony is producing very slowly.** Check power. An unpowered job station runs at 0.35x rather
than stopping, by design — a colony whose cable was cut should get visibly slower rather than fall
silent.

**Export production stopped.** The export buffer is full. It blocks rather than voiding, on purpose.
Drain it with a hopper or pipe, or sell.

**Selling is refused.** No economy mod is installed, so Core has no real currency provider. Core's
built-in fallback does not persist balances, so paying into it would take the goods and give nothing
back — the sale is refused and the goods stay put.

**A colony came back from being unloaded with less than expected.** That is the offline catch-up:
capped at `catchUpMaxHours` (default 24) and applied at `catchUpEfficiency` (default 0.5). Set
`catchUpMaxHours` to `0` to disable catch-up entirely.

**Players cannot found a colony.** Check `maxColoniesPerPlayer` (0 disables founding entirely),
`maxColoniesTotal`, and `minColonySpacing` — the last is 192 blocks by default and is measured
horizontally within one dimension.

## See also

- [Commands](Commands.md) — the full `/nerocolonies` tree
- [Config](Config.md) — every key, default and range
- [Data storage](Data-Storage.md) — what is stored about players, retention and erasure
- [Content format](Content-Format.md) — writing and debugging datapack content
- [Telemetry](Telemetry.md) — opt-out crash reporting
