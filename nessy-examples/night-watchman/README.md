# Nessy Example: Night Watchman

The third example, and the leanest: no web, no database, no Docker — a Spring
Boot app whose only front door is the clock. It demonstrates the
time-triggered agent pattern (spec §1): the trigger event isn't a person or a
webhook, it's `@Scheduled` firing. Each firing wakes the watchman, who
observes, judges, and either stays quiet or acts — and because every firing
tells the *same* conversation, trend judgment across rounds is conversation
state at work, not something the app tracks separately. Bounding what an
endless conversation lets the model see is `Memory.windowed(...)`, wrapped
around an in-memory `TranscriptMemory` — the module's second thing to
dogfood, after the pattern itself.

## The story

The watchman stands rounds in a ship's engine room, reading three gauges —
boiler pressure, bilge level, hull stress — off `EngineRoom`, a seeded random
walk so the story replays the same way every run. The bilge is deliberately
biased upward (`+3.5` per step before the walk's own noise), so a run is
guaranteed its arc — quiet rounds, a trend, an alarm — inside roughly five to
eight minutes at the default cadence, without waiting on real chance. Two
tools are always available and always ready (nothing here parks): `check_vitals`
reads the gauges, `raise_alarm` logs a WARN — loudly and obviously fake, the
same coupon-tool ethos as `chat-web`'s demo tool. The watchman's standing
orders (its system prompt) ask it to compare each round's vitals against its
recent rounds and raise the alarm decisively once something is out of band or
clearly trending there.

## Run it

```bash
ANTHROPIC_API_KEY=… ./mvnw -pl nessy-examples/night-watchman spring-boot:run
```

No Docker, no database, nothing else to stand up — just the log, which is the
UI. Watch it: quiet rounds at first ("all quiet" reports), then a trend, then
an alarm. Ctrl-C ends the watch — the conversation honestly dies with the JVM.
`TranscriptMemory` over an in-memory `Transcript` (which `Memory.windowed`
delegates to) is in-memory by design, the same way the framework's default is;
nothing about this example asks for durability.

## The two properties

- `watchman.cadence` — a Spring cron expression, default `0 * * * * *` (the
  top of every minute). Speed it up to actually watch the arc happen instead
  of waiting a real hour:

  ```bash
  ./mvnw -pl nessy-examples/night-watchman spring-boot:run '-Dspring-boot.run.arguments=--watchman.cadence="*/15 * * * * *"'
  ```

- `watchman.window` — the recall bound, in messages, default `40`.

`ANTHROPIC_API_KEY` is needed at startup, not merely at the first round: the
starter builds the `ModelProvider` bean eagerly from the environment during
context refresh, so a missing key fails the app fast rather than at the first
scheduled firing.

## How the bound works

Bounding recall used to mean a bespoke `Memory` implementation; now it's one
line, wired straight into the agent bean:

```java
.memory(Memory.windowed(new TranscriptMemory(Transcript.inMemory()), window))
```

`Memory.windowed(delegate, n)` is a static factory in `spi.memory`: retention
is whole — `remember` delegates straight through to `TranscriptMemory`, so
nothing is ever discarded from the underlying store. `recall` is where the
bound lives: it clips the delegate's recall via `Context#keepRecent(n)`, which
keeps AT LEAST the last `n` messages, cutting only at a pair-safe boundary (a
tool-use/tool-result pair is never split) — the tail can run one round longer
when the boundary must walk past a tool exchange, and when no pair-safe
boundary exists the context comes back whole. So the watchman's horizon is
roughly its window — it remembers its recent rounds, not its whole life,
which is what lets an endless conversation run forever without growing the
model call unbounded.

## What this example deliberately isn't

Durable — the conversation is JVM-lifetime state, gone on Ctrl-C, unlike
`chat-web`'s Postgres-backed pair. Web-faced — no browser, no HTTP, the log is
the only surface. HITL — no approval gate; both tools are granted
`UsagePolicy.allow()`, so nothing here ever parks waiting on a human. Really
alerting — `raise_alarm` logs a WARN and nothing else; no pager is harmed. It
demonstrates the clock-triggered pattern and the bounded-recall `Memory` seam,
nothing more.
