# The Patient Researcher — design

**Date:** 2026-08-13
**Status:** DRAFT — pending review
**Builds on:** the durable kernel (2026-08-12, shipped) and the DX generation
(2026-08-13, spec'd) — this example is written on the DX generation's API and
is its second dogfood. Sequenced after that generation ships.

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
tick that finds a still-ripening job past its halfway point narrates
`harness.progress(token, "archive scan 50%…")` instead — heard by the agent's
declared `onToolProgressAsync` listener, which logs it.

## 3. Module

`nessy-examples/patient-researcher` (artifactId
`nessy-example-patient-researcher`, deploy-skipped like its siblings). Plain
`main()`, **no Spring** — the examples matrix gains its no-framework-at-all
corner (chat-cli: plain + interactive; chat-web: Boot + HITL; this: plain +
autonomous):

- `PGSimpleDataSource` — no pool; the JVM lives seconds.
- `JdbcPersistence.create(dataSource, mapper)` for the durable pair (DX §7).
- One agent, one tool, `UsagePolicy.allow()` (no human in this loop),
  `.onToolProgressAsync(…)` logging listener.
- Wiring target: ~40 readable lines, main included.

Dependencies: `nessy-core`, `nessy-model-anthropic`, `nessy-store-jdbc`,
`org.postgresql:postgresql`, `logback-classic`. Compose supplies Postgres
(same shape as chat-web's compose, minus the LGTM service; started manually —
no Spring means no `spring-boot-docker-compose`, and `docker compose up -d`
is one README line).

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
  2. `SELECT` still-ripening jobs past halfway → `harness.progress(token,
     "archive scan …% complete")`. Tokens the store no longer knows
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
2. `tick` immediately — "archive scan 50% complete" appears (the progress
   verb, from a process that never saw the ask).
3. Wait out the ripening; `tick` again — the resume streams the rest of the
   turn: tool result delivered, model concludes, `COMPLETE`.
4. `show <id>` — the finished answer.
5. The autonomous version is one crontab line:
   `* * * * * cd …/patient-researcher && ./tick.sh`
   (a thin wrapper around the jar; the README ships it).
6. Kill any of these processes at any point and rerun — nothing is lost;
   re-delivery is idempotent (consumed tokens re-drive, they don't replay).

## 7. Testing

One container-tagged test class, scripted provider (no key, no network), real
Postgres via Testcontainers, calling the verb implementations directly (the
verbs are methods; `main` is only dispatch):

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
world, minimally), Spring anything, retry/backoff policy around the model
call (chat-cli already shows `RetryingModelProvider`), multi-agent routing
(still single-agent per harness), a TUI for watching ticks (the log is the
UI), o11y wiring (chat-web owns that demonstration; this example stays
minimal on purpose).

## 9. Open questions

1. Module/agent naming — `patient-researcher` rhymes with the kernel's
   "durable patience"; rename freely at review.
2. Whether `tick.sh`/`ask.sh` wrappers use `mvn -q exec:java` (zero build
   setup, slower per tick) or a `maven-shade` fat jar (instant ticks, one
   extra build step). Lean: exec-maven-plugin like chat-cli, matching sibling
   conventions; a cron tick that takes four seconds is fine for a demo.
