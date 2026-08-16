# Durable Persistence

Everything in [Getting Started](getting-started.md) dies with the JVM: the
harness's defaults are `ConversationStore.inMemory()`, `Parks.inMemory()`,
and a pipeline `Memory` over `Transcript.inMemory()`. `nessy-jdbc` replaces
all of that with a real database, over one `javax.sql.DataSource`, with no
change to how an agent is built or told things.

## Bootstrapping all five doors at once

`JdbcPersistence.create` is the one-call version: it resolves the dialect
once, from one borrowed connection, then bootstraps and hands back all five
JDBC-backed components that need a database — a `JdbcConversationStore`, a
`JdbcParks`, a `JdbcTranscript`, a `JdbcSummaryStore`, and a `JdbcPlanStore`:

```java
JdbcPersistence persistence = JdbcPersistence.create(dataSource, objectMapper);

Harness harness =
    Nessy.harness(anthropic)
        .store(persistence.store())
        .parks(persistence.parks())
        .build();

Agent<String> agent =
    harness
        .agent()
        .name("durable")
        .model("claude-sonnet-4-5")
        .memory(persistence.memory())
        .build();
```

`persistence.memory()` is `Memory.pipeline(persistence.transcript()).build()`
— the durable `Memory`, verbatim retention over the same transcript
`JdbcPersistence` just bootstrapped. `JdbcPersistence` exists purely for
convenience: the six schemas are always stood up together in practice, but
nothing couples them beyond that — each component also works fine
constructed on its own, via its own `create(dataSource, objectMapper)` (or
`create(dataSource, objectMapper, dialect)` to bypass resolution).

`JdbcPersistence`'s `summaries` and `planStore` back the summarizing-memory
and planning facilities respectively; an agent that doesn't use either still
gets the tables bootstrapped, unused, at no cost beyond the schema itself.

## Dialect detection

`JdbcDialect.resolve(DatabaseMetaData)` reads
`getDatabaseProductName()` once and normalizes it to one of `POSTGRES`,
`MYSQL`, `MARIADB`, `SQLSERVER`, `ORACLE` — Hibernate's
`StandardDialectResolver` pattern, borrowed rather than depended on.
`MARIADB` needs an extra look: the MariaDB Connector/J driver reports the
`MySQL` product name for compatibility but stamps its own name into the
version string, so the resolver checks the version string too before
settling on `MYSQL`. `POSTGRES` is also where CockroachDB and Yugabyte land
— both report `PostgreSQL` deliberately, and the resolver takes them at
their word.

Every store's constructor and `create` overload also accepts a `JdbcDialect`
explicitly, bypassing the resolver for a driver that lies about its own
metadata, or a caller that already knows:

```java
JdbcPersistence persistence =
    JdbcPersistence.create(dataSource, objectMapper, JdbcDialect.POSTGRES);
```

An unrecognized product name fails loudly, naming what was reported and the
five it supports — never a silent fallback to Postgres syntax.

## Schema bootstrap

`create` runs idempotent `CREATE TABLE IF NOT EXISTS` (and the equivalent
per-vendor guard where a dialect lacks that syntax) once at startup, safe to
repeat across every process that starts up against the same database. The
constructor form (`new JdbcConversationStore(dataSource, objectMapper,
dialect)`, and the equivalent on each of the other four classes) skips DDL
entirely, for a datasource another process already bootstrapped.

!!! warning "Write-once inserts are unified, not varied per vendor"
    Postgres's `ON CONFLICT DO NOTHING` has no portable equivalent across the
    other four dialects. `nessy-jdbc` replaces it everywhere — Postgres
    included — with one mechanism: attempt the plain `INSERT`, and treat the
    vendor's own duplicate-key signal (SQLState **and** vendor error code
    together, not SQLState alone — Oracle's `23000` class also covers a
    `NOT NULL` violation on an empty-string bind) as the documented no-op.

## What survives a restart

Three doors carry the whole story:

- `ConversationStore` keeps the control block (status, version) and the
  durable inbox.
- `Parks` keeps the registry a callback's token must translate back into a
  conversation and call.
- The pipeline `Memory`, built over a durable `Transcript`, keeps the
  message log — the same pipeline over `Transcript.inMemory()` dies with the
  JVM.

`SummaryStore` and `PlanStore` ride along the same way: durable once backed
by `nessy-jdbc`, in-memory (and gone at restart) otherwise. See
[Storage](../concepts/storage.md) for the full picture of all five doors and
what each one owns.

## In a Spring Boot application

The wiring above is optional there: add `nessy-spring-boot-starter` and
`nessy-jdbc` next to a `DataSource` bean, and the store, parks, transcript,
memory, and plan store are all autoconfigured — the application declares one
bean, the agent. `SummaryStore` is the exception: nothing in
`nessy-autoconfigure` builds one, so a summarizing pipeline still needs an
application-declared `Memory` bean over a hand-built
`JdbcSummaryStore.create(dataSource)`. See [Spring Boot](spring-boot.md).

## Testing this module

`./mvnw verify` runs the offline suite only — no Docker needed. The root
build's default `nessy.excludedGroups` is `live,container`, so both tiers
above start out excluded. `./mvnw test -Dnessy.excludedGroups=live`
un-excludes the whole `container` tier at once — not just Postgres: it adds
Postgres's own test classes *and* one class per vendor (`MySqlStoreTckTest`,
`MariaDbStoreTckTest`, `SqlServerStoreTckTest`, `OracleStoreTckTest`), each
running all five `nessy-tck` contracts against a real Testcontainers
instance for that vendor plus a dialect-resolution pin — the full
five-vendor matrix, needing a Docker daemon. The four vendor classes also
carry `@Tag("vendor")` alongside `@Tag("container")`: CI runs with
`-Dnessy.excludedGroups=live,vendor`, so it exercises the `container` suite
minus the four vendor classes (Postgres only), keeping the five-vendor
matrix a local-only run that doesn't pull four extra images — Oracle's among
them — on every push. Clearing the exclusion entirely
(`-Dnessy.excludedGroups=`) additionally runs the `live` (token-spending)
tier on top of everything else.

## Where next

- [Storage](../concepts/storage.md) — the five SPIs `nessy-jdbc` implements,
  and the TCK contracts a backend has to pass.
- [Spring Boot](spring-boot.md) — most of the same doors, autoconfigured
  from a `DataSource` bean (`SummaryStore` excepted).
- [The Durable Loop](../concepts/durable-loop.md) — why every one of these
  doors is written to be at-least-once safe.
