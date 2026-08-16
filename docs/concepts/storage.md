# Storage

Restart survival rests on seven storage SPIs — `ConversationStore`, `Parks`,
`Transcript`, `SummaryStore`, `PlanStore`, `Notebook`, `SubagentLinks` — each with a
zero-configuration in-memory default and a `nessy-jdbc` implementation behind it. Every
one dies with the JVM by default; every one survives a restart once backed by a real
database.

| Door | Owns | In-memory default |
|---|---|---|
| `ConversationStore` | The control block (status, version) and the durable inbox | `ConversationStore.inMemory()` |
| `Transcript` | The append-only, versioned, per-conversation message log | `Transcript.inMemory()` |
| `Parks` | The registry translating a `ParkToken` back to a conversation and call | `Parks.inMemory()` |
| `SummaryStore` | One conversation's folded prefix (the summarizing hydrator's watermark) | `SummaryStore.inMemory()` |
| `PlanStore` | One conversation's current plan | `PlanStore.inMemory()` |
| `Notebook` | Durable, named notes about a subject | `Notebook.inMemory()` |
| `SubagentLinks` | Which parent `ParkToken` a delegated child conversation answers | `SubagentLinks.inMemory()` |

## `ConversationStore`

Where a session lives between steps — everything that enters a conversation lands in its
inbox first; whoever drives next reads the mail.

```java
Optional<Loaded> load(ConversationId id);
ConversationState save(ConversationState state, Collection<String> drainedInboxIds);
void append(ConversationId id, InboxEntry entry);
```

The control block and the inbox are two different durability shapes sharing one store:
`state` is fenced — compare-and-swap, one writer wins — while the inbox is an append-only
log any number of tells and resolutions can write to concurrently, drained only by the
winning save. `save` throws `StaleStateException` when the stored version has moved since
the caller read it: reload and re-drive. `append` is unconditional, atomic, and never
contended with saves. A `Loaded` bundles a conversation's control block together with
whatever the inbox still holds for it — `load` never mixes one generation's state with
another's inbox.

## `Transcript`

An append-only, versioned, per-conversation message log — the storage primitive
`PipelineMemory` is built on, and the read surface audit and chat history need.

```java
Entry append(ConversationId id, Message message);
List<Entry> all(ConversationId id);
List<Entry> tail(ConversationId id, long afterVersion);
List<Entry> page(ConversationId id, long beforeVersion, int limit);
```

`append` doesn't stutter: appending a message equal to the current last entry returns the
existing entry instead of a duplicate — the at-least-once re-telling rule. The transcript
stores raw tellings, open tails included: `Context` assembly, wire legality, and the
open-tail trim are `Memory`'s border law, not the transcript's — an auditor sees what was
actually told. See [Memory and the Pipeline](memory-and-the-pipeline.md) for how a
hydrator reads it.

## `Parks`

The callback door's own registry: where a parked wait lives between the moment a tool
call hands its token to the outside world and the moment a resume presents that token
back.

```java
void park(Park park);
Optional<Park> find(ParkToken token);
List<Park> forConversation(ConversationId id);
```

`park` is idempotent on token, since the at-least-once loop can retry registration.
Registry entries are never deleted once resolved — the durable record that a token once
named a particular wait, the same keep-forever posture the retired single-use token table
had. Replay protection (refusing to re-execute a call a redelivered resolution names
twice) is the fold's own question, not this registry's to answer. See
[Parks and Callbacks](parks-and-callbacks.md) for the agent-name stamp every `Park`
record carries.

## `SummaryStore`

One conversation's folded prefix: the summary text a `SummarizingHydrator` has already
distilled from the transcript, and the transcript version through which it speaks for the
conversation.

```java
Optional<Summary> find(ConversationId id);
void save(ConversationId id, Summary summary);
```

`save` is last-write-wins, on purpose — no fencing. A lost or clobbered write is never a
lost word: the transcript is the truth a summary is only ever a cheaper way to re-read, so
a crash between summarizing and saving simply means the same tail gets re-summarized on
the next recall, landing on the same watermark.

## `PlanStore`

One conversation's current plan, replaced wholesale on every write. See
[Planning](planning.md) for the tool that writes it and the transformer that reads it
back into context.

```java
Optional<Plan> find(ConversationId id);
void save(ConversationId id, Plan plan);
```

## `SubagentLinks`

Which parent `ParkToken` a delegated child conversation answers — the correlation a
subagent's settlement resumes against. See [Subagents](subagents.md) for the tool that
saves a link when its child parks and the listener that resolves and forgets it once the
child settles.

```java
Optional<ParkToken> find(ConversationId child);
void save(ConversationId child, ParkToken parentToken);
void forget(ConversationId child);
```

## `nessy-jdbc`: one code path, five dialects

`nessy-jdbc` implements this whole quintet over a plain `javax.sql.DataSource`.
`JdbcDialect.resolve(DatabaseMetaData)` reads `getDatabaseProductName()` once, at the
connection each store already borrows to bootstrap its schema, and normalizes it to one
of `POSTGRES`, `MYSQL`, `MARIADB`, `SQLSERVER`, `ORACLE` — a small, enumerable
Postgres-specific surface (a handful of `jsonb` columns and casts, one
row-limiting/locking idiom, the write-once inserts) rather than five parallel
implementations. Every store class's constructor/`create` overload also accepts a
`JdbcDialect` explicitly, bypassing the resolver for a driver that lies about its own
metadata.

```java
ConversationStore store = JdbcConversationStore.create(dataSource, objectMapper);
Parks parks = JdbcParks.create(dataSource, objectMapper);
Transcript transcript = JdbcTranscript.create(dataSource, objectMapper);
Memory memory = Memory.pipeline(transcript).build();

Harness harness = Nessy.harness(anthropic).store(store).parks(parks).build();
Agent<String> agent =
    harness.agent().name("durable").model("claude-sonnet-4-5").memory(memory).build();
```

Restart survival needs three doors: `ConversationStore` keeps the control block and
inbox; `Parks` keeps the registry a callback's token must translate back into a
conversation and call; and the pipeline `Memory` over a durable `Transcript` keeps the
message log — the same pipeline over `Transcript.inMemory()` dies with the JVM.

In a Spring Boot application the wiring above is optional: add
`nessy-spring-boot-starter` and `nessy-jdbc` next to a `DataSource` bean, and the store,
parks, transcript, memory, and harness above are all autoconfigured — the application
declares one bean, the agent.

!!! warning "Write-once inserts are unified, not varied per vendor"
    Postgres's `ON CONFLICT DO NOTHING` has no portable equivalent across the other four
    dialects. `nessy-jdbc` replaces it everywhere — Postgres included — with one mechanism:
    attempt the plain `INSERT`, and treat the vendor's own duplicate-key signal (SQLState
    **and** vendor error code together, not SQLState alone — Oracle's `23000` class also
    covers a `NOT NULL` violation on an empty-string bind) as the documented no-op.

Anything the resolver doesn't recognize fails loudly at resolution time, naming the
reported product and the five supported dialects — never a silent fallback to Postgres
syntax.

| Dialect | Detected from | Verified against |
|---|---|---|
| `POSTGRES` | `PostgreSQL` product name (CockroachDB and Yugabyte report this deliberately and ride the same dialect) | `postgres:17-alpine` |
| `MYSQL` | `MySQL` product name, version string *without* `MariaDB` in it | `mysql:8.0` |
| `MARIADB` | `MySQL` product name *with* `MariaDB` in the version string (the driver-lies-for-compatibility sniff), or `MariaDB` directly | `mariadb:11.4` |
| `SQLSERVER` | `Microsoft SQL Server` product name | `mcr.microsoft.com/mssql/server:2022-latest` |
| `ORACLE` | `Oracle` product name | `gvenzl/oracle-free:23-slim-faststart` |

CockroachDB and Yugabyte ride `POSTGRES`, not a dialect of their own — both report
`PostgreSQL` as their JDBC product name deliberately, for exactly this kind of
wire-compatible detection — untested by this module's own matrix, which pins real
Postgres, not either of them.

## The TCK: certifying a backend

`nessy-tck` ships seven contract classes, one per SPI seam, each an abstract JUnit 5 test
class with exactly one abstract factory method a concrete subclass supplies:

| Contract | Certifies |
|---|---|
| `ConversationStoreContract` | The fenced save and the inbox |
| `ParksContract` | Registration, lookup, idempotent re-registration, survival past resolution |
| `TranscriptContract` | Append-only ordering, the last-row read, paging |
| `SummaryStoreContract` | The summary watermark's save/load |
| `PlanStoreContract` | Save-then-find, wholesale replacement, ordering, empty-save-clears, absence, last-write-wins |
| `NotebookContract` | Round-trip, index-only headings in name order, upsert replacement, forget and its idempotence, subject isolation, last-write-wins |
| `SubagentLinksContract` | Round-trip, last-write-wins on a double save, idempotent forget, an absent find |

```java
class InMemoryConversationStoreTest extends ConversationStoreContract {
  @Override
  protected ConversationStore newStore() {
    return ConversationStore.inMemory();
  }
}
```

`nessy-tck` ships at main scope, not test — a third-party implementer depends on it
directly and extends the contract classes, supplying its own JUnit engine at test scope.
A store implementation that passes every contract in the kit honors every invariant the
loop relies on, whether it ships from this repository or someone else's. `nessy-jdbc`
runs the same seven contracts a second time, once per vendor, against real
Postgres/MySQL/MariaDB/SQL Server/Oracle containers.

## Where next

- [The Durable Loop](durable-loop.md) — why every one of these doors is written to be
  at-least-once safe.
- [Parks and Callbacks](parks-and-callbacks.md) — `Parks` and the agent-name stamp its
  records carry.
- [Memory and the Pipeline](memory-and-the-pipeline.md) — how `Transcript` and
  `SummaryStore` feed `PipelineMemory`.
