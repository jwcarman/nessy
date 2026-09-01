# Reminders and the End of the Residency Rule

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A deadline survives the actor that set it. Today an approval's expiry lives only in an
in-memory Pekko timer — `expiresAt` appears in none of `Phase`, `TurnState` or `AgentState` — which
forces the agent to stay resident "days if a tool defers". That residency rule is what let a turn be
killed mid-flight (fixed 52d4a387 by a workaround this plan removes).

**Architecture:** A parked call writes a **reminder** row before it waits. A sweeper polls for
expired rows and sends `Expired(callId)` to the agent's LOGICAL address, which reactivates a
passivated agent. Timeouts are notifications, never silent deletions, so every expiry is observable.
The engine provides its own storage: give it a `DataSource` or nothing, and it builds what it needs.

**Tech Stack:** Java 21+, Pekko Typed cluster sharding, `spring-jdbc` as a LIBRARY (`JdbcClient`,
`ResourceDatabasePopulator`, `EmbeddedDatabaseBuilder` — no `ApplicationContext`, no Boot),
PostgreSQL in production, H2 in tests, JUnit 5 + AssertJ (no mocking library).

**Precedent:** `2026-08-22-durable-deliveries-design.md` §6 specified this exactly (durable
`deadlineAt` + reaper); it shipped in cb4dba87 / 268a41ef and was deleted in 5045087b.
`2026-08-28-actor-composition-design.md` §8a/§8b rule that expiry is a backstop and that the sweep
must REPORT what it did.

**Scope:** `nessy-engine` only. Does NOT migrate claims, transcript, notebook or plan off
`Substrate` — that is the next plan. Does NOT collapse the actor hierarchy; with the residency rule
gone, that becomes an optional simplification judged on its own merits.

---

## Decisions already taken — do not relitigate

| decision | why |
|---|---|
| **Two doors, no `EngineStore`** | Claims and reminders share only their owner. Different tables, access patterns and lifecycles. Both package-private in `nessy-engine`. |
| **`spring-jdbc`, not JPA, not Boot** | `JdbcClient` + exception translation without a context. JPA's house rules mandate `BaseEntity`/UUID PKs, wrong for natural composite keys. |
| **One `nessy-schema.sql`, no platform suffix** | MEASURED on H2 2.3.232: `TEXT`, `BYTEA`, `BIGINT`, composite PK, `IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS` and `LIMIT ?` all pass. The ONLY failure was `TIMESTAMPTZ`; the ANSI spelling `TIMESTAMP WITH TIME ZONE` works on both. **Rule: ANSI spellings, never vendor aliases.** |
| **`nessy-` filename prefix** | Boot's defaults look for `schema.sql`, so ours is never run uninvited — the opt-in comes from the NAME, not from code. It also lets our loader select our files and never the application's. |
| **No `SchemaInitializer`, no ServiceLoader** | `PathMatchingResourcePatternResolver` gathers `classpath*:nessy-schema.sql` from every module; `ResourceDatabasePopulator` runs them and parses `--` license headers correctly. Three lines, works with and without Spring. |
| **No `FOR UPDATE SKIP LOCKED`** | Unverified outside the Postgres family, and the singleton sweeper makes it unnecessary. Add it only with a measurement. |
| **Pekko keeps its own pool** | `database-provider-fqcn` would let it share our `DataSource`, but that is Scala interop for a refinement; pointing its HOCON at the same URL gets the operational win for free. |

## Global Constraints

- **Never suppress a warning.** No `@SuppressWarnings`, no exceptions.
- **No star imports.**
- **Arm the reminder BEFORE the wait begins.** A crash then leaves a reminder for a call that does
  not exist — it fires, finds nothing, cancels itself. The reverse order leaks a wait nothing ends.
- **Idempotence beats atomicity.** `Expired` may arrive twice, or for a call that just settled. The
  agent checks by `callId` and shrugs. Never add a transaction to avoid this.
- **Time comes from an injected `Clock`, never SQL `now()`** — the existing `JdbcSubstrate` rule.
- **A silent reaper cannot be distinguished from a dead one** (§8a). The sweep emits a COUNT metric.
- **Err long** (§8a). A leaked row costs disk; a row expired early breaks a turn.
- Exception-assertion lambdas contain exactly ONE throwing invocation (S5778); assert emptiness
  before any all/none-match assertion (S5841).
- **Before every commit:** `./mvnw license:format -Plicense && ./mvnw spotless:apply`
- **Scoped build while iterating:** `./mvnw -q -pl <module> -am test`. Run `./mvnw -q clean verify`
  ONCE before the final commit. Run Maven from the repository root.

---

## Task 1: Delete `batch` — it has no callers

Fully evidenced: `substrate.batch` is called only by `Claims.putAll`, which is called only by its
own test.

- [ ] Delete `Substrate.batch(List<Op>)`, the `Op` sealed interface and its three records.
- [ ] Delete the op-minting helpers (`DocumentStore.writeOp`/`deleteOp`, `SubstrateJournalStore`'s
      op construction) and now-unused imports.
- [ ] Delete `Claims.putAll` and its test. Delete the javadoc paragraph claiming a batch "collapses
      the orphan window from N to one" — there is no N; a turn writes one claim at a time.
- [ ] Delete the four `SubstrateContract` batch tests and both backend implementations.

**Verify:** `./mvnw -q clean verify` green. `grep -rn "\.batch(\|Op\." --include=*.java` finds
nothing outside history.

## Task 2: Schema bootstrap

- [ ] `Schemas` (package-private, `nessy-engine`): gathers `classpath*:nessy-schema.sql` via
      `PathMatchingResourcePatternResolver`, runs them with `ResourceDatabasePopulator` +
      `DatabasePopulatorUtils.execute`.
- [ ] Javadoc must state the limitation plainly: `CREATE TABLE IF NOT EXISTS` is a BOOTSTRAP, not a
      migration. It handles "the table is absent" and nothing else; a changed column silently does
      nothing and fails at query time. Pre-1.0 that is the accepted trade.
- [ ] `nessy-engine/src/main/resources/nessy-schema.sql` — `nessy_reminder` and `nessy_claim`.
      ANSI spellings only; a comment says why.
- [ ] Opt-in: the engine initializes a `DataSource` it CREATED (H2); one it was HANDED only on
      explicit request. Never touch someone's schema uninvited.

**Verify:** a test runs the DDL on H2 and asserts both tables exist.

## Task 3: `Reminders`, engine-internal

- [ ] `Reminders` (package-private): `remind(key, expiresAt, payload)` / `due(now, limit)` /
      `cancel(key)`. `due` returns EARLIEST FIRST, `expiresAt <= now` inclusive.
- [ ] Over `JdbcClient`. Plain DML — no upsert, no vendor functions, no SQL `now()`.
- [ ] Verify its own table on construction and fail with a message naming the table, the file and
      the opt-in flag. With initialization opt-in, "no tables" is the expected first-run failure and
      the message must answer itself.
- [ ] Tests via `EmbeddedDatabaseBuilder().setType(H2).generateUniqueName(true)` + the SAME
      `Schemas` used in production. Per-class scope for speed.
- [ ] Prose-named tests: nothing is owed by a fresh store; a reminder due now is returned and one
      due later is not; the boundary instant IS due; re-`remind` MOVES rather than duplicates;
      `cancel` on a missing key is silent; `due` respects its limit and ordering; payload
      round-trips byte-for-byte.

**Verify:** `./mvnw -q -pl nessy-engine test`.

## Task 4: The sweep

- [ ] `ReminderSweep`: on a tick, `due(clock.instant(), batch)`; for each, resolve
      `entityRefFor(agentType, agentId)` from the payload and `tell(new NessyMessage.Expired(callId))`;
      then `remind` the same key forward by a backoff interval.
- [ ] **Bump, do not delete on send.** The owner deletes when the call settles; bumping turns a
      stuck row into backoff instead of a per-tick loop (durable-deliveries §6).
- [ ] Emit reminders-fired per pass as a METRIC, not only a log line (§8a) — a dead sweeper is
      otherwise indistinguishable from a quiet one.
- [ ] Cluster singleton: Pekko provides the coordination, so the store needs no lease semantics.
- [ ] Tests: a due reminder produces exactly one tell; a future one none; a fired reminder is bumped
      rather than deleted; the count is reported.

## Task 5: Parked calls arm reminders

- [ ] `NessyMessage.Expired(String callId, Map<String,String> headers)`.
- [ ] `ToolCallActor`: when a phase actor defers, `remind` BEFORE entering the wait; `cancel` when
      the call settles by ANY route — answered, denied, expired.
- [ ] `AgentActor`: relay `Expired` to the turn by `callId`, exactly as `onAnswerToolCall` does.
      An unknown `callId` is a no-op, not an error.
- [ ] `ApprovalActor`/`ExecutionActor`: on start, read the reminder back. Present → re-arm the
      REMAINING time and keep waiting. Absent → ask the approver / run the tool as now. This stops a
      restart silently restarting a term a person was promised.

**Verify:** a deferred approval whose actor is destroyed and rebuilt resumes the remaining time
rather than re-asking.

## Task 6: Retire the residency rule

- [ ] Delete the revival `Wake` and `shuttingDown()` from `AgentActor.onStop`; it returns to
      `Effect().none().thenStop()`. Drop the `CoordinatedShutdown` import.
- [ ] Rewrite `TurnActor`'s class javadoc: its life is no longer "days if a tool defers".
- [ ] Update `passivateIfIdle`'s javadoc: passivating mid-turn is survivable, and the reason is the
      reminder rather than a promise.

**Verify:** the 2026-08-31 CLI reproduction still completes both turns with zero dead letters.

## Task 7: Prove it where it actually broke

- [ ] Engine test: park a call with a deadline, destroy the agent, run the sweep, assert the turn
      ends with the call denied. No race to win — the reminder is a row and the sweep is a call.
- [ ] Confirm `ConversationTest` still passes, and note in its javadoc that it does NOT cover this
      path.

**Verify:** `./mvnw -q clean verify` — the ONE full run, before the final commit.

---

## Known hazard, NOT fixed here

**Blocking JDBC on actor dispatcher threads.** `deps.claims().put(...)` runs inside
`onModelAnswered` and `onToolSettled` — on the actor's dispatcher. `TurnActor`'s own javadoc states
the rule this breaks: *"an actor that blocks its dispatcher starves every other actor sharing it."*
It is invisible today because `InMemorySubstrate` is a `HashMap`, and H2 will keep it invisible
because an in-process write is microseconds. It appears only against a real Postgres under load.

This plan ADDS store calls on those threads (reminders on defer and settle). That is a knowing
trade, not an oversight: fixing it means routing store calls through `deps.blocking()` and turning
synchronous writes into message round-trips, which is a larger change than this plan and wants its
own design. **Record it, measure it in the soak, do not let it be discovered in production.**

## Self-Review

- Each task must leave the build green on its own.
- Task 1 is a pure deletion. If anything breaks, the premise was wrong — stop and report rather than
  quietly restoring it.
- Tasks 5 and 6 touch engine concurrency, which the model policy sends to Opus for review. Do not
  let a Sonnet review stand alone on those.
- Every store test must run the SAME `Schemas` bootstrap as production. A test that hand-writes DDL
  is a test that stops proving the schema.
