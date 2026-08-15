# Nessy Store JDBC

A durable `ConversationStore`/`Parks`/`Transcript`/`SummaryStore` quartet over
a plain `javax.sql.DataSource` — five dialects, one code path per concern.
See the root README's [Durable, autonomous agents](../README.md#durable-autonomous-agents)
section for the wiring story (`JdbcConversationStore.create`, the Spring Boot
autoconfiguration) and [Supported databases](../README.md#supported-databases)
for the five-vendor table and the `nessy.jdbc.dialect` override. This README
covers the parts specific to this module: the per-dialect type mapping, the
write-once/duplicate-signal story, and the isolation-level and row-lock
wrinkles two vendors needed.

## Dialect resolution

`JdbcDialect.resolve(DatabaseMetaData)` (javadoc has the full table) reads
`getDatabaseProductName()` once, at the connection each store already
borrows to bootstrap its schema, and normalizes it to one of `POSTGRES`,
`MYSQL`, `MARIADB`, `SQLSERVER`, `ORACLE`. Every store class's
constructor/`create` overload also accepts a `JdbcDialect` explicitly,
bypassing the resolver for a driver that lies about its own metadata (or a
caller that already knows) — the Spring Boot starter's `nessy.jdbc.dialect`
property rides this same override.

## Per-vendor schema and type mapping

Five schema resource sets, one directory per dialect
(`org/jwcarman/nessy/store/jdbc/{postgres,mysql,mariadb,sqlserver,oracle}/`),
selected by the resolved dialect at bootstrap. The two Postgres-specific
column types this module actually needs (`jsonb` for structured payloads,
`text`/short identifiers) map like this:

| Nessy type | Postgres | MySQL | MariaDB | SQL Server | Oracle |
|---|---|---|---|---|---|
| structured payload (`jsonb`) | `jsonb` | `json` | `json` (a `longtext` column with an automatic `json_valid()` CHECK — not a distinct binary type) | `nvarchar(max)` (no native JSON type in this release) | `clob` |
| short identifier/text | `text` | `varchar(255)` | `varchar(255)` | `nvarchar(255)` | `varchar2(255)` |
| version counter | `bigint` | `bigint` | `bigint` | `bigint` | `number(19)` |

`IF NOT EXISTS` reality differs by vendor and is guarded accordingly: MySQL
lacks `CREATE INDEX IF NOT EXISTS` entirely and gets an
`information_schema`-driven `PREPARE`/`EXECUTE` guard instead; SQL Server
lacks both `CREATE TABLE`/`CREATE INDEX IF NOT EXISTS` and gets an `IF NOT
EXISTS (SELECT * FROM sys.tables/sys.indexes …) BEGIN … END` guard around
every statement, run as one `Statement.execute()` call with no `GO`
separators (a `sqlcmd`/SSMS client convention, not real T-SQL); MariaDB and
the Oracle image this module is verified against (23c-class) both accept
`IF NOT EXISTS` on tables and indexes natively. See `JdbcDialect`'s and each
`*/schema.sql`'s own comments for exactly which image versions were verified
— an older, pre-23c Oracle would need the classic `EXECUTE IMMEDIATE` /
catch-`ORA-00955` idiom instead of the native form these schemas use.

## Write-once inserts: unified, not varied

Every "insert unless a row with this key already exists" write in this
module (`nessy_conversation`'s version-0 insert, `nessy_parks`'s idempotent
`park`) used to lean on Postgres's `ON CONFLICT DO NOTHING`, which has no
portable equivalent across the other four dialects. `WriteOnceInsert`
replaces it everywhere — Postgres included — with one mechanism: attempt the
plain `INSERT`, and treat the vendor's own duplicate-key signal as the
documented no-op. A duplicate is recognized by SQLState **and** vendor error
code together, not SQLState alone, because Oracle's `23000` class also
covers a `NOT NULL` violation on an empty-string bind (Oracle treats `''` as
`NULL`) — see `WriteOnceInsert`'s javadoc for the exact per-vendor code table
and why a SQLState-only check would have silently dropped a real write
instead of surfacing it. Under an explicit (non-autocommit) transaction, the
attempt runs inside a `Savepoint` so a caught duplicate-key error — which,
unlike the retired `ON CONFLICT`, is a genuine SQL error — doesn't poison the
rest of the surrounding transaction on Postgres.

## Isolation level and the Oracle row-lock

`JdbcConversationStore#load`'s combined state-and-inbox read asks for
`TRANSACTION_REPEATABLE_READ` on every dialect except Oracle, which supports
only `READ_COMMITTED` and `SERIALIZABLE` and fails outright
(`ORA-17030`) if asked for anything else — `load` resolves the isolation
level per-dialect, after the connection is open, rather than as a fixed
constant. `JdbcStatements#transcriptLastRowForUpdateSql` locks the
transcript's newest row per dialect similarly: Postgres/MySQL/MariaDB share
`ORDER BY version DESC LIMIT 1 FOR UPDATE`; SQL Server locks via `WITH
(UPDLOCK, ROWLOCK)` on a `TOP 1` (no `FOR UPDATE` at all); Oracle cannot
combine `FOR UPDATE` with its own row-limiting clause
(`FETCH FIRST … ROWS ONLY`) — confirmed live as `ORA-02014`, since Oracle
implements row-limiting as an implicit inline view and `FOR UPDATE` refuses
to lock through one — so Oracle's fragment finds the target row's `rowid` in
an unlocked, row-limited inner query first, then locks by plain `rowid`
equality in an outer query with no row-limiting clause of its own. Full
detail, including the SQL text, lives in `JdbcStatements`' own javadoc.

## Testing this module

`./mvnw verify` runs the offline suite only (no Docker needed).
`./mvnw test -Dnessy.excludedGroups=live` (or clearing the exclusion
entirely, `-Dnessy.excludedGroups=`) adds the `container`-tagged suites:
Postgres's own test classes plus one class per vendor
(`MySqlStoreTckTest`, `MariaDbStoreTckTest`, `SqlServerStoreTckTest`,
`OracleStoreTckTest`), each running all four `nessy-store-tck` contracts
against a real Testcontainers instance for that vendor plus a
dialect-resolution pin. See the root README's supported-databases section
for the pinned image versions and the Oracle patience note.
