# JDBC Dialects and the Store TCK — the fence learns four more accents

**Date:** 2026-08-15
**Status:** APPROVED — 2026-08-15 (owner, overnight authorization:
"dialect detection logic with test suites for the common database
dialects (as you specified) using test containers"; "borrow the logic
from hibernate"; "You should have a TCK or something")

---

## 1. Purpose and the audited gap

`nessy-store-jdbc` is Postgres-flavored; enterprise Spring shops on
MySQL/MariaDB/SQL Server/Oracle bounce at "install Postgres" — the fit
analysis's #1 adoption lever. The audit of the actual code (2026-08-15)
found the Postgres-specific surface SMALL and enumerable:

- DDL: `jsonb` columns (five, across four tables); `text` (fine on
  MySQL/MariaDB, wants `nvarchar(max)` on SQL Server, `clob`/varchar on
  Oracle).
- DML: three `?::jsonb` casts; `entry_id = ANY(?)` (PG array binding);
  the transcript append's last-row read (`ORDER BY … LIMIT 1 FOR
  UPDATE` — `LIMIT` vs `TOP` vs `FETCH FIRST`, and SQL Server locks via
  `WITH (UPDLOCK)` not `FOR UPDATE`); `ON CONFLICT DO NOTHING` on the
  write-once inserts. Everything else is ANSI.

## 2. The resolver — Hibernate's logic, borrowed not imported

A small `JdbcDialect` enum + resolver in `nessy-store-jdbc`
(Spring-free, zero new dependencies): the `StandardDialectResolver`
pattern in miniature. Resolution at bootstrap — the module already
borrows a connection for DDL — via
`DatabaseMetaData.getDatabaseProductName()`, normalized:

- `PostgreSQL` → POSTGRES (CockroachDB/Yugabyte report PostgreSQL and
  ride this dialect deliberately — javadoc says so).
- `MySQL` → MYSQL, **unless** the version string contains `MariaDB` →
  MARIADB (the Hibernate sniff; the MariaDB driver/product pairing lies).
- `MariaDB` → MARIADB.
- `Microsoft SQL Server` → SQLSERVER.
- `Oracle` → ORACLE.
- Anything else → fail-noisy `IllegalStateException` naming the product
  and the five supported dialects, plus the override.
- **Explicit override**: a constructor/`create` parameter on the three
  store classes (one shared resolution, not three), and the starter
  gains `nessy.jdbc.dialect` (`postgres|mysql|mariadb|sqlserver|oracle`)
  for wrappers that lie about metadata.

## 3. Per-vendor schemas

Five schema resource sets (`*-postgres.sql` staying byte-what-it-is
today, plus mysql/mariadb/sqlserver/oracle variants), selected by the
resolved dialect. Type mapping: `jsonb` → `json` (MySQL) /
`longtext`-with-json-validity or plain `longtext` (MariaDB — implementer
verifies what MariaDB's json alias really is) / `nvarchar(max)`
(SQL Server) / `clob` (Oracle); `text` → `nvarchar(max)` (SQL Server) /
`varchar2(4000)`-or-`clob` per column width reality (Oracle — ids and
tokens are short, payloads are clobs; the implementer sizes honestly).
`IF NOT EXISTS` variants: SQL Server and Oracle lack it for tables in
older forms — use the vendor-idiomatic guarded create the containers'
versions accept (implementer verifies against the actual Testcontainers
default versions and documents which).

## 4. Statement variants — few, and one unification

A per-dialect statement set (a `JdbcStatements` record or the enum
carrying the strings — implementer's taste, prose-first), varying ONLY:

- **limit-one + row-lock** (transcript last-row read): PG/MySQL/MariaDB
  `… ORDER BY version DESC LIMIT 1 FOR UPDATE`; SQL Server
  `SELECT TOP 1 … WITH (UPDLOCK, ROWLOCK) … ORDER BY version DESC`;
  Oracle `… ORDER BY version DESC FETCH FIRST 1 ROWS ONLY FOR UPDATE`.
- **inbox drain delete**: `= ANY(?)` → a dynamically-sized
  `IN (?, …, ?)` built per drain (drains are small; PG may keep ANY or
  join the unification — implementer picks ONE shape for all five if
  the dynamic IN is clean enough, which it should be).
- **json cast**: the `?::jsonb` suffix becomes a per-dialect parameter
  placeholder (`?::jsonb` on PG, bare `?` elsewhere).
- **Write-once inserts UNIFY instead of varying**: `ON CONFLICT DO
  NOTHING` is replaced everywhere — including Postgres — by plain
  INSERT with the duplicate-key SQLState family (`23…`, and SQL
  Server's 2601/2627 vendor codes → their SQLStates 23000) caught and
  treated as the documented no-op. One code path, five databases, and
  the write-once semantics live in Java where the javadoc already
  explains them. (If testing reveals a vendor whose duplicate signal is
  genuinely unusable, that vendor gets a variant and the spec's
  unification claim gets an honest footnote.)

The fenced CAS `UPDATE … WHERE id = ? AND version = ?` and every plain
read are ANSI already — untouched.

## 5. `nessy-store-tck` — the contracts become a kit

New module **`nessy-store-tck`**: the four contract suites
(`ConversationStoreContract`, `ParksContract`, `TranscriptContract`,
`SummaryStoreContract`) MOVE from nessy-core's test-jar into a
first-class, published artifact (main-scope classes, package
`org.jwcarman.nessy.store.tck`, depending on nessy-core + the JUnit/
AssertJ API the contracts already use). Javadoc frames the promise: a
store implementation that passes the kit honors every invariant the
loop relies on — the certification story third-party implementers were
missing. nessy-core's test-jar keeps its non-contract fixtures;
in-repo consumers (store-jdbc tests, core's own in-memory tests)
retarget to the TCK module. Breaking (pre-1.0): the contracts leave
the core test-jar.

## 6. The vendor matrix — the TCK run five times

In `nessy-store-jdbc`'s test tree: one container-tagged test class per
vendor (Postgres today's, plus MySQL, MariaDB, SQL Server, Oracle via
Testcontainers' official modules — `mysql`, `mariadb`, `mssqlserver`,
`oracle-free`), each nesting/implementing ALL FOUR TCK contracts over
that vendor's container plus a dialect-resolution assertion (the
resolver picked the expected dialect). Drivers test-scope,
Boot-BOM-managed where Boot manages them (implementer verifies which of
the four drivers Boot's BOM carries and pins the rest as properties).
The callback-doors end-to-end test stays Postgres-only (it proves door
logic, not dialect). *(Amended at Task 4's review, 2026-08-15: this
section originally claimed "CI is unaffected — the workflow runs the
offline build," which was FALSE repo-wide — the workflow's
`-Dnessy.excludedGroups=live` overrides the pom's `live,container`
default, so CI has always run the container suites on Docker-equipped
runners. The correction: CI keeps its de-facto container coverage; the
five-vendor matrix alone is fenced behind an additional `vendor` tag
that CI excludes. The full matrix runs locally, Oracle's image being
the heavyweight, and the README says so truthfully.)*

## 7. Deliberately not in this wave

H2/embedded dev-mode dialect; schema migration tooling (recreate
posture stands); dialect services for application SQL (nessy's own
statements only); DynamoDB/Mongo (separate fit-analysis items); any
starter property beyond `nessy.jdbc.dialect`.

## 8. Breaking (pre-1.0), stated loud

1. The four store contracts move from nessy-core's test-jar to
   `nessy-store-tck` (imports retarget; semantics identical).
2. `nessy_parks`/`nessy_conversation`/`nessy_inbox`/`nessy_transcript`
   Postgres behavior is unchanged, but the write-once inserts now
   detect duplicates via SQLState instead of `ON CONFLICT` — observable
   only in Postgres logs, stated for honesty.
