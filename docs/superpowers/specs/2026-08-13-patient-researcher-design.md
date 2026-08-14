# The Patient Researcher — design

**Date:** 2026-08-13
**Status:** RETIRED, UNBUILT — 2026-08-14. Superseded by the night-watchman
example (2026-08-14-night-watchman-design.md) at user review: the examples
family exhibits the time-triggered pattern instead. A full implementation of
this spec exists on the archived branch `patient-researcher-archive`; its one
framework fix (the JdbcPersistenceAutoConfiguration ordering pin) was
cherry-picked to main independently. The machine-half verbs
(`harness.resume`/`progress`, tool-side parks) remain undemoed — recorded,
not forgotten.
**Builds on:** the durable kernel (2026-08-12, shipped), the DX generation
(2026-08-13, spec'd), and the Spring Boot starter
(2026-08-13-spring-boot-starter-design.md) — this example is written on both
and is the second dogfood for each: the DX API's machine half, and the
starter's console face. Sequenced last: DX generation → starter → this.

---

## 1. Purpose

Prove the durable kernel's headline claim with a runnable artifact: a
conversation driven across process lifetimes, where no JVM lives longer than
seconds and nothing is lost between them. chat-web exercised the *human* half
of the durable story (park → a person approves); this example exercises the
*machine* half, none of which any example touches today:

- a tool that parks **from inside `execute`** (`Awaited.parked` — chat-web
  parks via the approver; the tool-side park has never been demoed),
- `ToolResolution.Completed` — a machine's answer, not a human verdict,
- `harness.resume` called from a **different process** than the one that
  parked,
- `harness.progress(token, …)` — the from-afar narration verb, never yet
  exercised anywhere,
- cron as the driver: whatever process gets to the conversation next, drives.

Success criterion: the demo script (§6) runs end to end with `ask` and `tick`
in separate JVM invocations, and killing any process at any moment loses
nothing.

## 2. The story

You ask a research question. The agent's one tool,
`search_deep_archive(query)`, submits a job to a fake "deep archive" — a row
in the same Postgres with a ripening clock — and parks. The JVM exits,
normally with the conversation `PARKED`. Cron ticks a fresh JVM every minute;
a tick that finds a ripe job resumes with the archive's (fake, deterministic)
result, and the turn runs to completion in whatever process delivered it. A
tick that finds a still-ripening job narrates
`harness.progress(token, "archive scan N% complete")` instead — heard by the
agent's declared `onToolProgressAsync` listener, which logs it.

## 3. Module

`nessy-examples/patient-researcher` (artifactId
`nessy-example-patient-researcher`, deploy-skipped like its siblings). A
**Spring Boot console app** — no web at all: `spring.main.web-application-type:
none`, `spring.main.banner-mode: off` (cron logs stay clean), one
`ApplicationRunner` receiving the verb args, `SpringApplication.exit(...)`
carrying the exit code. A tick is: context boots, runner runs, JVM exits —
seconds. The examples matrix reads: chat-cli (plain + interactive), chat-web
(Boot web + HITL), this (Boot console + autonomous) — and this example is the
**starter's console dogfood**: chat-web proves
`nessy-spring-boot-starter` (its own spec, sequenced before this one)
on the web face; this proves it with no web anywhere.

- DataSource, `ConversationStore`/`Memory` (via `JdbcPersistence`),
  `ModelProvider`, and `Harness` all arrive from the starter's
  autoconfiguration — the example's own config is ONE bean: the agent (one
  tool, `UsagePolicy.allow()` — no human in this loop —
  `.onToolProgressAsync(…)` logging listener). If the starter's spec shifts,
  the fallback is chat-web-style hand wiring; the example's shape survives
  either way.
- `spring-boot-docker-compose` (runtime, optional) starts Postgres on
  `ask`/`tick`/`show` alike, with `spring.docker.compose.lifecycle-management:
  start-only` — a cron JVM that lives seconds must not stop the database on
  every exit; Postgres stays up between ticks.

Dependencies: `spring-boot-starter` (the plain one — no web), `spring-boot-starter-jdbc`,
`spring-boot-docker-compose`, `nessy-spring-boot-starter`, `nessy-core`,
`nessy-model-anthropic`, `nessy-store-jdbc`, `org.postgresql:postgresql`.
Boot BOM confined in-module, exactly the chat-web discipline.

## 4. Three verbs, one jar

Dispatch on `args[0]`:

- **`ask "<question>"`** — mints a conversation, prints its id, `tell`s the
  question with a stdout-logging `TurnObserver`, exits. The normal exit state
  is `PARKED`: the model called the tool, the tool submitted and parked.
- **`tick`** — the cron verb, idempotent and safe to run any time:
  1. `SELECT` ripe jobs (`finish_at <= now()`) → for each,
     `harness.resume(token, new ToolResolution.Completed(result), observer)` —
     the turn continues in *this* JVM, streaming to the log, usually to
     completion. Delivered jobs are deleted.
  2. `SELECT` still-ripening jobs → `harness.progress(token, "archive scan
     N% complete")`, N computed from submitted/finish_at. Tokens the store no
     longer knows
     (settled concurrently) return false and the row is deleted — progress is
     narration, dropping it is legal.
  3. Exit.
- **`show <conversation-id>`** — prints status and transcript via
  `agent.snapshot(id)` (DX §3), the DX generation's read dogfooded from a
  second call site.

## 5. The archive

Example-owned table in the same database, created idempotently by the example
at startup (same `CREATE TABLE IF NOT EXISTS` discipline as the framework
schemas, but the example's own file — the framework knows nothing about it):

```sql
CREATE TABLE IF NOT EXISTS archive_jobs (
  token     text        NOT NULL PRIMARY KEY,
  query     text        NOT NULL,
  submitted timestamptz NOT NULL,
  finish_at timestamptz NOT NULL
);
```

The tool INSERTs `(token, query, now(), now() + interval '2 minutes')` and
parks on that token. The "result" is derived deterministically from the query
at delivery time (a canned finding string quoting the query) — fake and
obviously so, the coupon-tool ethos. The park token doubles as the job id:
the token *is* the correlation contract, which is the kernel's own claim
(`Harness.progress` javadoc) made load-bearing.

## 6. The demo script (the acceptance test by hand)

1. `docker compose up -d`; `ask "what did the deep archive hold about the
   voyage of the Nessie?"` — watch the turn stream, the tool submit and park,
   the JVM exit. `show <id>`: status `PARKED`.
2. `tick` immediately — an "archive scan N% complete" line appears (the
   progress verb, narrating from a process that never saw the ask).
3. Wait out the ripening; `tick` again — the resume streams the rest of the
   turn: tool result delivered, model concludes, `COMPLETE`.
4. `show <id>` — the finished answer.
5. The autonomous version is one crontab line:
   `* * * * * cd …/patient-researcher && ./tick.sh`
   (a thin wrapper around the jar; the README ships it).
6. Kill any of these processes at any point and rerun — nothing is lost;
   re-delivery is idempotent (consumed tokens re-drive, they don't replay).

## 7. Testing

One container-tagged `@SpringBootTest` (non-web), scripted-provider bean
override behind the `test` profile (the chat-web smoke's exact pattern — no
key, no network), real Postgres via Testcontainers with
`spring.docker.compose.enabled=false`, calling the verb implementations
directly (the verbs are methods on a component; the `ApplicationRunner` is
only dispatch):

- `ask` parks: state `PARKED`, one `archive_jobs` row, token matches the
  parked call.
- ripen by `UPDATE archive_jobs SET finish_at = now()`; `tick` resumes: turn
  completes, row deleted, `snapshot` shows `COMPLETE` and a transcript whose
  final assistant message quotes the delivered finding.
- `tick` before ripening emits progress (recorded by a declared listener) and
  consumes nothing.
- a second `tick` after completion is a quiet no-op.

Offline reactor `verify` stays green with no Docker and no key.

## 8. Deliberately not built

A real external service or webhook (the clock-ripened row IS the external
world, minimally), any web surface (console only — that is the point),
retry/backoff policy around the model call (chat-cli already shows
`RetryingModelProvider`), multi-agent routing (still single-agent per
harness), a TUI for watching ticks (the log is the UI), o11y wiring (chat-web
owns that demonstration; no actuator dependency here), and a no-framework
durable example (this spec's earlier draft; consciously traded for dogfooding
the starter's console face — chat-cli remains the framework-free example).

## 9. Open questions

1. Module/agent naming — `patient-researcher` rhymes with the kernel's
   "durable patience"; rename freely at review.
2. Whether `tick.sh`/`ask.sh` wrappers run `./mvnw -q -pl … spring-boot:run
   -Dspring-boot.run.arguments=tick` (zero build step, slower per tick) or
   `java -jar` the repackaged boot jar (instant ticks after one
   `./mvnw package`). Lean: the boot jar — spring-boot-maven-plugin is
   already in the module, and a crontab line pointing at a jar is the honest
   production shape.
