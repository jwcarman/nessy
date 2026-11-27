# Schema migrations: every module's tables through migratory

Status: approved by James, 2026-11-27. Amends the storage mechanics of
`2026-08-21-scoped-store-design.md` and replaces the schema bootstrap described in
`2026-08-13-spring-boot-starter-design.md` and in `Schemas`' own javadoc.

## Problem

Every module that persists something shipped a `nessy-schema.sql` of `CREATE TABLE IF NOT
EXISTS` statements, gathered by `Schemas` and run at startup behind `nessy.initialize-schema`.
That creates tables once and never changes them: alter a column and the script does nothing,
and the mismatch surfaces at query time. `Schemas` called itself "a bootstrap, not a migration"
and named the point at which that stops being acceptable. We are there.

## Decision

Nessy's tables are Liquibase changelogs, run by **migratory** (`org.jwcarman.migratory`), which
exists to solve exactly this: a library that owns tables puts its changelog and one
`META-INF/migratory.properties` in its jar, and migratory finds every such file on the class path,
orders them, and runs them before the application's own Liquibase.

- **Each table-owning module declares its migration.** `nessy-backend-jdbc` (`nessy-backend`),
  `nessy-memory-notebook` (`nessy-notebook`) and `nessy-planning` (`nessy-planning`). No module's
  tables reference another's, so no ordering is declared; `after` is added only when that
  changes.
- **Changelogs are Liquibase XML, one master per module.** `migratory.properties` names the
  module's `db/changelog.xml`, which includes one file per change in order, starting with
  `db/changes/0001-create-tables.xml`. Changes are typed (`createTable`, `createIndex`, ...), one
  changeset per table, with the design comments kept as XML comments. The two things free
  Liquibase has no change for, a check constraint and a partial index, are `<sql>` changes inside
  that XML. A change to a table is a new file and a new include at the end; an included file
  never moves and a released changeset is never edited (migratory's changelog rules).
- **Each module depends on `migratory-spring-boot-starter`**, as migratory's own guide says a
  library should. An application lists nothing.
- **Nessy runs no SQL of its own.** `Schemas` and `nessy.initialize-schema` are removed. An
  application that manages its schema itself sets `migratory.enabled=false` and creates the tables
  however it sees fit.
- **The backends and stores wait for their tables** through Spring Boot's
  `@DependsOnDatabaseInitialization`, for which migratory registers itself as an initializer; the
  auto-configuration never names a migratory type.
- **Tests run migratory too.** Plain container tests use one test fixture in
  `nessy-backend-jdbc`'s test-jar that builds migratory's runner over the discovered, ordered
  migrations; Spring wiring tests add migratory's auto-configuration.

## Consequences

- **No upgrade path from the scripts.** A database created by `nessy-schema.sql` is dropped and
  recreated ("nuke and pave"); there is no baseline changeset. Acceptable before 1.0, and the
  last time it will be.
- **Every Spring application with a `DataSource` now runs Liquibase.** The starter turns Spring
  Boot's Liquibase on, which needs a changelog of the application's own (an empty
  `databaseChangeLog: []` does) or `spring.liquibase.enabled=false`, which leaves migratory
  running.
- **Postgres is the target.** The DDL is still written in ANSI spellings, but nothing holds it to
  another database.
