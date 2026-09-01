# Purpose-Built Stores: Removing `Substrate`

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete the `Substrate` SPI. Every module that persists something owns a table shaped for
its own queries, over plain SQL.

**Why:** One real backend (`JdbcSubstrate`) and one test double, held to a 550-line TCK, for
portability nobody has asked for — DynamoDB was dropped as a constraint. The generic key-value shape
also forces the code to enforce its own design: the notebook loads whole entries and projects to
headings in Java, and `TranscriptMemory` reads up to 500 rows to apply a character budget. Real
columns let the database enforce both.

**Architecture:** `JdbcClient` over a `DataSource`. Each module ships `nessy-schema.sql`; the engine
gathers `classpath*:nessy-schema.sql` and runs them with `ResourceDatabasePopulator`. "In memory"
becomes an H2 `DataSource` rather than a second implementation, so there is exactly one store per
purpose and no fake that can lie.

**Tech Stack:** Java 21+, `spring-jdbc` as a LIBRARY (`JdbcClient`, `ResourceDatabasePopulator`,
`EmbeddedDatabaseBuilder` — no `ApplicationContext`, no Boot), PostgreSQL in production, H2 in
tests, JUnit 5 + AssertJ (no mocking library).

**Scope:** `nessy-spi`, `nessy-engine`, `nessy-memory-notebook`, `nessy-memory-plan`, `nessy-intent`,
`nessy-substrate-jdbc`, `nessy-testing`, `nessy-console`, `nessy-spring-boot-starter`, and three
examples. Does NOT change reminder BEHAVIOUR (the sweeper, arming, retiring the residency rule) —
that is `2026-08-31-reminders-and-the-residency-rule.md`, which runs after this and loses its
storage task.

---

## Decisions already taken — do not relitigate

| decision | why |
|---|---|
| **`spring-jdbc`, not JPA, not Boot** | `JdbcClient` + dialect-aware exception translation with no context. JPA's house rules mandate `BaseEntity`/UUID PKs — wrong for natural composite keys, and Spring Data JPA drags Boot into a layer we keep Spring-optional. |
| **H2 is the in-memory story** | One implementation per store instead of N hand-written fakes, and no conformance battery needed because there is no second implementation to hold to one. |
| **One `nessy-schema.sql` per module, no platform suffix** | MEASURED on H2 2.3.232: `TEXT`, `BYTEA`, `BIGINT`, composite PK, `IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`, `LIMIT ?` all pass. Only `TIMESTAMPTZ` failed; the ANSI `TIMESTAMP WITH TIME ZONE` works on both. **Rule: ANSI spellings, never vendor aliases.** |
| **`nessy-` filename prefix** | Boot's defaults look for `schema.sql`, so ours is never run uninvited — the opt-in comes from the NAME. It also lets our loader select our files and never the application's. |
| **No `SchemaInitializer`, no ServiceLoader** | `classpath*:` already enumerates every module's file, and `ResourceDatabasePopulator` already parses `--` license headers. Three lines, works with and without Spring. |
| **No `COLLATE "C"`** | Its documented purpose was making `keys()` byte-ordered to match `String.compareTo` in `InMemorySubstrate`. Both halves are being deleted; ordering now comes from typed columns. |
| **Two doors, no `EngineStore`** | Claims and reminders share only their owner. Different tables, access patterns, lifecycles. |
| **Pekko keeps its own pool** | Point its HOCON at the same database. Sharing the `DataSource` needs a Scala `SlickDatabaseProvider` — a refinement, not the win. |

## Global Constraints

- **Never suppress a warning.** No star imports.
- **ANSI SQL spellings only.** No vendor aliases, no upserts, no vendor functions, no SQL `now()` —
  time comes from an injected `Clock`, as `JdbcSubstrate` already requires.
- **Write the fact before the state that references it.** Ordering, not transactions.
- **Every store verifies its own table on construction** and fails naming the table, the file, and
  the opt-in flag. With initialization opt-in, "no tables" is the expected first-run failure and the
  message must answer itself.
- **Every store test runs the SAME bootstrap as production.** A test that hand-writes DDL stops
  proving the schema.
- Exception-assertion lambdas contain exactly ONE throwing invocation (S5778); assert emptiness
  before all/none-match assertions (S5841).
- **Before every commit:** `./mvnw license:format -Plicense && ./mvnw spotless:apply`
- **Scoped build while iterating:** `./mvnw -q -pl <module> -am test`. `./mvnw -q clean verify` ONCE
  before each task's final commit. Run Maven from the repository root.

---

## Task 1: Delete `batch` — it has no callers

`substrate.batch` is called only by `Claims.putAll`, which is called only by its own test.

- [ ] Delete `Substrate.batch(List<Op>)`, the `Op` sealed interface and its three records, the
      op-minting helpers on `DocumentStore`/`SubstrateJournalStore`, `Claims.putAll` and its test,
      the four `SubstrateContract` batch tests, and both backend implementations.
- [ ] Delete the `Claims` javadoc paragraph about collapsing "the orphan window from N to one" —
      there is no N; a turn writes one claim at a time.

**Verify:** `clean verify` green; `grep -rn "\.batch(\|Op\." --include=*.java` finds nothing.

## Task 2: The schema bootstrap

- [ ] `Schemas` in `nessy-engine`: gathers `classpath*:nessy-schema.sql` with
      `PathMatchingResourcePatternResolver`, runs them via `ResourceDatabasePopulator` +
      `DatabasePopulatorUtils.execute`.
- [ ] Javadoc states the limitation plainly: `CREATE TABLE IF NOT EXISTS` is a BOOTSTRAP, not a
      migration. A changed column silently does nothing and fails at query time. Pre-1.0 that is the
      accepted trade; at 1.0 this needs versioned scripts.
- [ ] Opt-in: initialize a `DataSource` the engine CREATED (H2); one it was HANDED only on request.
- [ ] `TestDatabase` in `nessy-testing`: `EmbeddedDatabaseBuilder().setType(H2).generateUniqueName(true)`
      then the SAME `Schemas` bootstrap. Per-class scope by default.

**Verify:** a test builds an H2 database through `TestDatabase` and asserts the expected tables.

## Task 3: Engine internals — claims and reminders

- [ ] `nessy-engine/src/main/resources/nessy-schema.sql`: `nessy_claim(agent_id, turn_id, key,
      payload, PRIMARY KEY (agent_id, turn_id, key))` and `nessy_reminder(key, expires_at, payload,
      PRIMARY KEY (key), INDEX on expires_at)`.
- [ ] `Claims` reimplemented over `JdbcClient`, same three-method surface. `deleteTurn` becomes
      `DELETE WHERE agent_id = ? AND turn_id = ?`.
- [ ] `Reminders` (package-private, new): `remind` / `due(now, limit)` / `cancel`. Earliest first,
      `expires_at <= ?` inclusive. NOT wired to any behaviour yet — that is the follow-on plan.
- [ ] `PekkoHarnessFactory` takes a `DataSource` or none (engine creates H2); it no longer takes a
      `Substrate`.
- [ ] Spawn turn actors on a named, sized dispatcher shipped in a NEW `nessy-engine/reference.conf`
      — `DispatcherSelector.fromConfig(...)`, not Pekko's `default-blocking-io-dispatcher`, which is
      shared with its own file IO and DNS. Store calls run on actor threads; this contains them.
- [ ] Move the `ModelRequest` construction INSIDE the existing `supplyAsync` in `callModelInScope`.
      `memory().recall()` currently reads up to 500 rows AND calls user-supplied code on the actor
      thread, three lines above the hop that exists for exactly this reason.

**Verify:** `clean verify`; the CLI reproduction completes two turns with zero dead letters.

## Task 4: The notebook

- [ ] `nessy_note(agent_type, agent_id, id, hook, body, ordinal, created_at)`.
- [ ] `JdbcNotebook` replaces `SubstrateNotebook`. `headings()` becomes `SELECT id, hook` — the
      design's promise that bodies never reach the model becomes something the query enforces
      instead of something the code remembers.
- [ ] `ordinal` preserves insertion order, which the `Notes(List<Entry>)` blob exists to protect.

## Task 5: The plan

- [ ] `nessy_plan_task(agent_type, agent_id, ordinal, title, status)`.
- [ ] `JdbcPlanStore` replaces `SubstratePlanStore`. `save` remains wholesale: delete the agent's
      rows and insert the new list in one transaction, preserving the replay-safety that made
      wholesale replacement the design.

## Task 6: Intent

- [ ] `SubstrateIntentStore` → `JdbcIntentStore` over its own table.

## Task 7: The transcript — the only permanent data

- [ ] `nessy_transcript(agent_type, agent_id, seq, payload, chars, created_at)`, PK
      `(agent_type, agent_id, seq)`, index on `(agent_type, agent_id, seq DESC)`.
- [ ] `TranscriptMemory.recent` applies its character budget IN THE QUERY. `MAX_MESSAGES_READ = 500`
      exists only because the store could not answer the question; it goes.
- [ ] This is the one task where a mistake loses data. No migration is written (pre-1.0, no external
      users) — say so explicitly in the commit rather than leaving it implied.

## Task 8: Delete `Substrate`

- [ ] Delete `nessy-spi/.../substrate/**` — `Substrate`, `InMemorySubstrate`, `SubstrateSupport`,
      `DocumentStore`, `JournalStore`, `SubstrateJournalStore`, `Versioned`.
- [ ] Extract `Codec` / `CodecFactory` and the pinned `ObjectMapper` FIRST — they are about
      serialization, not storage, and every store still needs them.
- [ ] Delete `SubstrateContract` from `nessy-testing`. Everything else there stays — `ScriptedModel`,
      `MemoryContractTest`, `RecordingApprover`, `RecordingSubscriber`, `ScriptedApprover`.
- [ ] Delete `JdbcSubstrate` and the `nessy-substrate-jdbc` module, or rename it `nessy-jdbc` if
      anything remains in it.
- [ ] Update `NessyAutoConfiguration` (the `Substrate` bean becomes a `DataSource`), `ReplConfig`,
      and chat-cli / chat-web / watchman.

**Verify:** `grep -rn "Substrate" --include=*.java` finds nothing outside history. `clean verify`.

---

## Known hazard, accepted knowingly

**Blocking JDBC on actor dispatcher threads.** `claims().put(...)` runs inside `onModelAnswered` and
`onToolSettled`, and `memory().remember(...)` in three handlers. `TurnActor`'s own javadoc states the
rule this breaks. It is invisible today because `InMemorySubstrate` is a `HashMap`, and **H2 will
keep it invisible** because an in-process write is microseconds — it appears only against a real
Postgres under load.

Task 3's dedicated dispatcher CONTAINS this: turns queue instead of the cluster stalling. It does not
eliminate it. Elimination means `pipeToSelf` plus `Effect().stash()` / `thenUnstashAll()` per write —
one completion message and a guard per handler, not one command per write. Do that only when
measurement shows the turn dispatcher saturating, and record the measurement.

## Self-Review

- Each task leaves the build green on its own. Tasks 4–7 are independent and can be reordered.
- Task 1 is a pure deletion. If anything breaks, the premise was wrong — stop and report.
- Task 3 touches engine concurrency, which the model policy sends to Opus for review.
- Task 7 is the only irreversible one. Do it last, alone, and with its own commit.
