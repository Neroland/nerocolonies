# Telemetry

NeroColonies can report **its own crashes** to the developer, so bugs get fixed without anybody
having to notice, reproduce and file them. This page says exactly what that means, what it does not
mean, and how to turn it off.

> **Current status: live and opt-out.** NeroColonies has a real Sentry project, so everything below
> is what actually happens: reports are sent unless you set `telemetryEnabled=false`. A build whose
> DSN has been stripped back to the placeholder (a fork, a stripped build) returns before the Sentry
> SDK is touched — **nothing is sent anywhere and no network connection is opened** in that case,
> whatever `telemetryEnabled` says.

## What it is

Opt-out crash reporting via [Sentry](https://sentry.io/) (EU ingest servers), the same wiring every
other Nero mod uses. When it is active, it sends:

- **Unhandled exceptions that originate in NeroColonies code.** The filter is by package: a crash in
  another mod, or in Minecraft itself, is not sent. If NeroColonies is not in the stack trace, it is
  not our bug and it is not our business.
- **Handled failures** the mod chose to report anyway, so an error that was survived is still
  visible as a defect.
- **Fatal log lines** that name NeroColonies without carrying a throwable — scrubbed and truncated.
- A small amount of **timing data** on a 5% sample (the colony tick, the housing sweep), to catch
  performance regressions. Timing and an operation name, nothing else.
- **Breadcrumbs** — a short trail of what the mod was doing before an error. These are non-personal
  by construction and are scrubbed like every other payload; player names, UUIDs and colony
  ownership are never put into one.

## What it never sends

- **No player names, UUIDs, IP addresses, chat or coordinates.** `sendDefaultPii` is off, the Sentry
  user object is cleared on every event, and the machine's hostname is never attached.
- **No colony data.** Not owner UUIDs, not access lists, not access-log rows, not colony names, not
  beacon positions.
- **No world data.** Not your seed, your save name or your dimension list.
- **No file paths from your machine** — your OS account name is scrubbed out of any path, and stack
  frames have their absolute paths stripped, before an event leaves.
- **No session linking.** The session id is random per launch and is not tied to anything across
  launches, so two crashes cannot be connected to the same person.

What *does* travel with a report is public, non-personal context: the mod version, the Minecraft
version, the loader, whether it is a client or a dedicated server, whether it is a development run,
four **server configuration** values (`maxColoniesTotal`, `claimRadius`, `colonistsPerColony`,
`colonyTickIntervalTicks`) — because a crash that only reproduces at non-default settings is
otherwise impossible to chase — and **the ids and versions of your other installed mods**, capped at
300, because most hard crashes in a modded game are conflicts and the mod list is the first thing
that makes one diagnosable. Those are public manifest strings, identifying the mods and not you.

Volume is bounded twice over: events are de-duplicated per session by fingerprint, and capped at
**10 events per game session**.

## Turning it off

In `config/nerocolonies.properties`:

```properties
telemetryEnabled=false
```

This is the **one setting in NeroColonies that is not server-authoritative**. Every other key is
decided by the server; this one is a personal choice, read from your own config on your own machine,
so a server cannot switch your crash reporting back on.

Set it before launching. When telemetry is off, Sentry is never initialised at all — there is no
"collected but not sent" state.

## For the developer

- The DSN lives in `telemetry/NeroColoniesTelemetry` as `DSN` — a public, write-only ingest key,
  safe to ship in the jar: it grants permission to *send* events and nothing else, it cannot read
  issues, and it identifies the project rather than a player. `PLACEHOLDER_DSN` is the guard: while
  `DSN` equals it, `init()` returns immediately and nothing is started. The real DSN has landed, so
  the guard is dormant in released builds — **do not remove it**: it is what keeps a fork, a
  stripped build or a half-configured branch silent instead of crashing on SDK init or reporting
  into somebody else's project.
- `NeroColoniesTelemetry.sendTestEvent(String)` fires one synthetic event to confirm end-to-end
  reporting on a real jar. It returns `false` when nothing was sent — opted out, or an unconfigured
  build. No command exposes it yet; it is called from code. Repeat calls in one session collapse
  into one event thanks to the per-session de-duplication, so restart to test again.
- Development and IDE runs **do** report, so error reporting can be tested end to end, but they are
  tagged `environment=development` and `runtime=development` so they never mix with real releases.
  Release channels map to `alpha`, `beta` and `production` from the mod version string.

## Why it is opt-out rather than opt-in

Because the alternative is not "more privacy", it is "no crash reports". A mod that crashes for 2%
of players on one loader will never hear about it from an opt-in reporter, and those players simply
stop playing. The trade is only defensible if the reporter is genuinely PII-free and genuinely easy
to switch off — which is what the two lists above are for.

None of this is player data under POPIA or GDPR, because none of it identifies a person. The things
that *are* player data live in the world save and are covered by [Data storage](Data-Storage.md) and
[`../PRIVACY.md`](../PRIVACY.md).

## See also

- [Data storage](Data-Storage.md) — the player data that actually is stored, and how to erase it
- [`../PRIVACY.md`](../PRIVACY.md) — the formal statement
- [Config](Config.md) — `telemetryEnabled`
- [Commands](Commands.md)
