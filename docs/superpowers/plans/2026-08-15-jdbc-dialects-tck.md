# JDBC Dialects and Store TCK Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `nessy-store-jdbc` runs on Postgres, MySQL, MariaDB, SQL Server, and Oracle — dialect resolved Hibernate-style at bootstrap — and the four store contracts become the published `nessy-store-tck`, executed per vendor over Testcontainers.

**Architecture:** Four tasks — the TCK extraction (pure move, everything green before dialect work starts); the dialect core (resolver + schemas + statement variants, Postgres behavior preserved); the vendor matrix; paperwork. Sequential.

**Spec:** `docs/superpowers/specs/2026-08-15-jdbc-dialects-tck-design.md` — binding.

## Global Constraints

- Offline `./mvnw -q clean verify` green after EVERY task; container suites where named (the matrix task's sweep is LONG — Oracle image is GB-scale; patience, not timeouts).
- **Postgres behavior parity is the standing invariant**: the existing Postgres container tests (all four doors + callback doors + persistence) pass throughout with assertions untouched.
- Before every commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply`, re-stage. No IDE metadata, no suppressions, no star imports, no mocking libraries, prose snake_case, S5778/S5841, Awaitility not sleep.
- Commit trailer: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

### Task 1: `nessy-store-tck` — the contracts move out

**Files:** new module (aggregator + BOM; pom: nessy-core + junit-jupiter-api + assertj, MAIN scope — read what the contracts actually import); the four contract classes MOVED from nessy-core's test-jar to `org.jwcarman.nessy.store.tck` (bodies verbatim; package + javadoc framing updated — the kit's certification promise stated at package-info level); every in-repo consumer retargeted (nessy-core's in-memory tests, nessy-store-jdbc's four contract tests — find all with grep); nessy-core's test-jar keeps non-contract fixtures only.

- [ ] Offline reactor green (proves the retarget complete — a missed consumer fails compile). Store-jdbc's Postgres container suite green untouched.
- [ ] Commit: `refactor: the contracts become a kit — nessy-store-tck ships the certification`

### Task 2: The dialect core

**Files:** `nessy-store-jdbc`: `JdbcDialect` (enum + resolver per spec §2 — product name, MariaDB version sniff, fail-noisy with override; resolved ONCE at each store's `create`/constructor seam with an explicit-dialect overload); five schema resource sets per spec §3 (postgres = today's, byte-preserved); statement variants per spec §4 (limit-one/row-lock, dynamic IN drain, json-cast placeholder, and the WRITE-ONCE UNIFICATION — `ON CONFLICT` deleted everywhere in favor of SQLState-23-family duplicate handling, one code path); the three store classes consume the variant set. `nessy-autoconfigure`: `nessy.jdbc.dialect` property → the override seam (mirror the existing property conventions).

**Tests (offline + existing Postgres containers only — the matrix is Task 3):** resolver unit tests (product-name table incl. the MariaDB sniff, unknown → fail-noisy naming five dialects + override, override wins); duplicate-swallow tests retargeted (the write-once tests must still pin no-op-on-duplicate, now via the unified path); dialect-selection pin in the existing Postgres container tests (resolver picked POSTGRES). Postgres parity: full existing container suite for store-jdbc green, assertions untouched.

- [ ] Commit: `feat: the dialect resolver — hibernate's lesson in thirty lines`

### Task 3: The vendor matrix

**Files:** `nessy-store-jdbc` test tree: four new container-tagged classes — `MySqlStoreTckTest`, `MariaDbStoreTckTest`, `SqlServerStoreTckTest`, `OracleStoreTckTest` (naming per repo voice) — each running ALL FOUR TCK contracts over its Testcontainers module + a resolver-picked-the-right-dialect assertion; pom gains the four Testcontainers modules + vendor drivers test-scope (Boot-BOM-managed where possible — VERIFY which; pin the rest as properties with a comment).

- [ ] Container sweep: `./mvnw -q verify -pl nessy-store-jdbc -am -Dnessy.excludedGroups=live` green across all five vendors (evidence per vendor in the report — surefire counts; note image pull times honestly). Offline reactor green (matrix excluded by tag).
- [ ] Commit: `test: the TCK meets five databases — the fence speaks every accent`

### Task 4: Paperwork

Root README: the supported-databases story (five vendors, resolver, `nessy.jdbc.dialect` override, the honest note that the container matrix is local-only and Oracle is heavy); store-jdbc README section (or creation — check if one exists) with the dialect table; `nessy-store-tck` README (the certification promise, how an implementer runs the kit); Install section row for nessy-store-tck (test-scope usage shown); CHANGELOG `### Added` (dialects, TCK, matrix) + `### Breaking (pre-1.0)` per spec §8. Full offline + full container sweep end to end.

- [ ] Commit: `docs: five accents and a certificate — the store grows up`

---

## Self-Review Notes (already applied)

- Task 1 is a pure move so every later diff is dialect-only — reviewers see mechanics and semantics separately.
- The write-once unification changes Postgres's mechanism (not behavior): Task 2's parity gate plus the retargeted duplicate tests carry the proof; spec §8 states it loud.
- Task 3's per-vendor dialect assertion catches the resolver lying (e.g. MariaDB container resolving MYSQL) exactly where it would hide.
- Oracle/SQL Server schema `IF NOT EXISTS` reality is Task 2's to verify against the containers Task 3 will use — the two tasks share the versions note in their briefs.
