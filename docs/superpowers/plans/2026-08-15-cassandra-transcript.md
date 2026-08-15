# The Cassandra Transcript Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `nessy-store-cassandra` — a Cassandra-backed `Transcript` (LWT version minting, no-stutter under contention), starter arbitration so it wins over the JDBC transcript when a `CqlSession` exists, and the paperwork that tells the polyglot story.

**Architecture:** Three tasks — the module (impl + contract + concurrency proof under containers), the starter seam (auto-config + arbitration context tests), paperwork. Sequential.

**Tech Stack:** Cassandra Java driver (`CqlSession`, Boot-BOM-managed), Testcontainers Cassandra, Spring Boot auto-configuration.

**Spec:** `docs/superpowers/specs/2026-08-15-cassandra-transcript-design.md` — binding.

## Global Constraints

- TDD with RED/GREEN evidence; offline `./mvnw -q clean verify` green after EVERY task (container tests tagged so the offline build skips them, mirroring nessy-store-jdbc's tagging); container suites where named.
- Before every commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply`, re-stage. No IDE metadata. No suppressions, no star imports, no mocking libraries, prose snake_case test names, S5778/S5841, Awaitility not sleep. `javadoc:javadoc` 0 errors on touched javadoc modules.
- Commit trailer: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

### Task 1: The module — `nessy-store-cassandra`

**Files:** new module (aggregator line in root `pom.xml`, managed entry in `nessy-bom/pom.xml`, `nessy-store-cassandra/pom.xml` — mirror `nessy-store-jdbc`'s pom shape; driver dependency via Boot's managed `org.apache.cassandra` java-driver coordinates — VERIFY the exact managed artifactId against the Boot BOM the parent imports, do not guess), `CassandraTranscript.java` (spec §2 schema, §3 LWT loop — read `JdbcTranscript` AND its `StateCodec`'s message half first; reproduce the message JSON contract; `create(CqlSession, ObjectMapper)` bootstrap, constructor DDL-free), package-info matching the JDBC module's voice.

**Tests:** `CassandraTranscriptTest` implements `TranscriptContract` (nessy-core test-jar) over Testcontainers Cassandra (keyspace created by the test, mirroring how JDBC tests own their schema bootstrap); a concurrency test — N parallel appenders, one conversation, assert versions strictly monotonic/gap-free-from-1, no lost messages, stutter held (two racing identical tellings yield one row); a bounded-attempts test if constructible without a real race (a hand-rolled CqlSession wrapper forcing not-applied — no mocking library, wrap the real session interface).

- [ ] RED: contract + concurrency tests against a stub impl; GREEN: the LWT loop lands. Container suite: `./mvnw -q verify -pl nessy-store-cassandra -am -Dnessy.excludedGroups=live`. Offline reactor green (container tests tagged).
- [ ] Commit: `feat: the transcript learns Cassandra — LWT where the row lock was`

### Task 2: The starter seam

**Files:** `nessy-autoconfigure`: `CassandraTranscriptAutoConfiguration` (`@ConditionalOnClass(CassandraTranscript)`, `@ConditionalOnBean(CqlSession)`, enabled-property family matching `JdbcPersistenceAutoConfiguration`'s, `before = JdbcPersistenceAutoConfiguration.class`; one `@ConditionalOnMissingBean Transcript` bean via `CassandraTranscript.create`); registration in the auto-config imports file; OPTIONAL driver dependency in the autoconfigure pom (mirror how the jdbc store is declared there).

**Tests:** context-runner tests — CqlSession + DataSource both present → `Transcript` is the Cassandra one AND `Memory` still composes over it (the polyglot pin); CqlSession absent → JDBC transcript exactly as today (regression pin); property-disabled → back off; user-declared `Transcript` bean → both back off. Annotation-pin test for the `before =` ordering (mirror the existing `@AutoConfiguration(after=...)` pin test style). Use a hand-rolled/embedded CqlSession stand-in for context tests if a real one is needed — or Testcontainers where the context test genuinely needs a live session (prefer the lightest thing that honestly pins the arbitration).

- [ ] RED/GREEN; offline reactor green; autoconfigure module suite green.
- [ ] Commit: `feat: the starter learns to arbitrate — Cassandra takes the transcript, Postgres keeps the fence`

### Task 3: Paperwork

`nessy-store-cassandra/README.md` (polyglot rationale, schema, LWT-vs-row-lock, the compose service-connection wiring an app would add); root README substrate section one sentence + Install section artifact row; CHANGELOG `### Added` (module, auto-config, the polyglot claim now proven by tests — no Breaking section entries, purely additive). Full offline + container sweeps end to end.

- [ ] Commit: `docs: the polyglot story in writing — one conversation, two stores`

---

## Self-Review Notes (already applied)

- Task 1 owns reading the Boot BOM for the real driver coordinates rather than trusting this plan's prose.
- The message-JSON duplication (not a dependency on store-jdbc) is a spec ruling — Task 1's brief says so, so a reviewer doesn't flag the duplication as a defect.
- Task 2's polyglot context test is the generation's thesis statement; it is named in the spec (§4) and must not be dropped for convenience.
