# Storage

Nessy keeps an agent in PostgreSQL rows. Each kind of thing lives in a table
shaped for how it is read, and there is no abstraction between the engine and
its SQL. There are two backends: `nessy-backend-jdbc` for PostgreSQL, which
survives a restart, and `nessy-backend-inmemory`, which holds everything,
chapters and leases included, in the process, for a test or a CLI, and loses
it all when the process stops.

| What | Where | Lives for |
|---|---|---|
| An agent, so there is something to lock | `nessy_agent` | until it is terminated, and after |
| What happened to an agent, one row per event, append-only | `nessy_agent_event` | forever, unless you prune it |
| Content: what a message or a tool result actually said, and JSON documents (the events keep two short lines per tool call, and an approval's facts) | `nessy_payload` | forever, unless you prune it |
| Work an agent owes, with its deadline | `nessy_agent_effect` | until it completes or is given up on |
| Work offered to a busy agent, waiting its turn (queued door only) | `nessy_agent_backlog` | until it is claimed or coalesced away |
| Closed chapters of an agent's history, each with the summary that stands in for it once written | `nessy_chapter` | forever, unless you prune it |
| Notes and plan tasks | `nessy_note`, `nessy_plan_task` | as their modules decide |
| Background work claimed once, see [Leases](leases.md) | `nessy_lease` | its TTL |

Every module that needs a table ships it in its own `nessy-schema.sql`.
The story, the outbox, the backlog, the chapters and the leases come from
`nessy-backend-jdbc`; notes and plan tasks come from `nessy-memory-notebook`
and `nessy-planning`.

## PostgreSQL, and only PostgreSQL

The queries this engine rests on are PostgreSQL's: `pg_advisory_xact_lock`
to serialise an agent for the length of one transaction, `FOR UPDATE SKIP
LOCKED` to claim work, `INSERT ... ON CONFLICT` to take a lease, `TIMESTAMPTZ`
columns. The JDBC backend runs on PostgreSQL only, with no H2 or other
embedded database standing in for it. The in-memory backend is a separate
implementation of the same stores, not a fallback for the JDBC one. The test
suite runs the same DDL against a real PostgreSQL container.

## Applying the schema

`Schemas` gathers every module's `nessy-schema.sql` from the classpath and
runs them, in one call, safe to repeat. It creates what is missing and never
alters an existing table, so a changed table means recreating the database:

```java
Schemas.initialize(dataSource);
```

**The name is the opt-in.** Spring Boot looks for `schema.sql`, so Nessy's
file never runs uninvited, and Nessy's loader never runs yours. Call this
yourself, let the starter do it (`nessy.initialize-schema`, on by default),
or feed the files to whatever runs your migrations. `classpath*:` matters:
it enumerates *every* matching resource rather than the first, so a module
added later brings its table with it.

## Two doors, two backends, different tables

`nessy-backend-spi` defines `DirectBackend` and `QueuedBackend`;
`nessy-backend-jdbc` implements both over one `DataSource`, and
`nessy-backend-inmemory` implements both in-process for a test or a CLI
with nothing behind them but this JVM.

A `DirectBackend` exposes five stores: `events()`, `payloads()`, `locks()`,
`chapters()` and `leases()`. `JdbcDirectBackend` touches `nessy_agent_event`,
`nessy_payload`, the advisory lock, `nessy_chapter` and `nessy_lease`, and
nothing else — the direct door never names `nessy_agent`, `nessy_agent_effect`
or `nessy_agent_backlog`, so a caller that only ever builds a direct backend
is not handed tables it will never write a row to. A `QueuedBackend` exposes
those five and adds `agents()`, `effects()` and `backlogs(...)`:
`JdbcQueuedBackend` adds `nessy_agent` (a record that an agent was told to
end), `nessy_agent_effect` (the outbox a dispatcher polls) and
`nessy_agent_backlog` (work offered to a busy agent).

```java
JdbcQueuedBackend backend =
    new JdbcQueuedBackend(dataSource, transactionManager, codecFactory);

DefaultQueuedHarnessFactory factory =
    DefaultQueuedHarnessFactory.of(engine -> engine
        .backend(backend)
        .provider(providerId, provider)
        .inference(providerId, InferenceOptions.of("claude-sonnet-5-5")));
```

A Spring Boot application never builds a backend by hand: `nessy-backend-jdbc`
on the classpath with a `DataSource` bean gives you `JdbcBackendAutoConfiguration`,
which contributes the `DirectBackend`, `QueuedBackend` and `Leases` beans, and
each door's own auto-configuration turns those into a `DefaultDirectHarnessFactory`
or `DefaultQueuedHarnessFactory`. See [Spring Boot](../guides/spring-boot.md).

## Reading the story back

Both doors' factories, `DefaultDirectHarnessFactory` and
`DefaultQueuedHarnessFactory`, expose a read side:

```java
TurnHistories histories = factory.histories();
TurnHistory story = histories.forAgent(agentType, agentId);
List<Turn> turns = story.turnsFrom(0);
```

In a Boot application the `TurnHistories` bean is contributed by
`QueuedHarnessAutoConfiguration`, so it exists when the queued door is
configured.

## Usage, read from the story

Every event that records an inference carries the usage its provider reported: an answer, a
request for actions, a refusal, a failure, and an attempt that was retried. `UsageReports` is a
projection over those events:

```java
UsageReport report = usageReports.of(agentType, agentId);
for (ModelUsage model : report.byModel()) {
  // model.model(), model.inferences(), model.input(), model.output(), ...
}
```

It reads the stored events, so it counts every inference the engine recorded and gives the same
answer after a restart. That makes it fit for cost accounting; narration is not, because it is
announced once and a listener can miss it. Models are never added together: each `ModelUsage`
is one model id. A kind that no inference reported stays not reported, never zero, and
`unreported()` counts inferences whose provider reported nothing.

An agent's story is in one door's store. On JDBC both doors read the same tables, so the
projection reads the first store that holds the agent and never two.

## Content is Jackson, then whatever you say

Every column that holds content is bytes: the value encoded by Jackson, then
passed through whatever `Codec<byte[]>` the store was built with. A store built
from a Boot application's `CodecFactory` bean gets whatever the
`StorageCodecConfigurer` bean appended:

```java
@Bean
StorageCodecConfigurer storage() {
  return original -> original.andThen(gzip).andThen(aesGcm);
}
```

That is the seam for compression and for encryption at rest, and it covers
every piece of content Nessy stores:

| Content | Column |
|---|---|
| What happened to an agent | `nessy_agent_event.payload` |
| What a message or a tool result said, or a JSON document | `nessy_payload.content` |
| Work an agent owes, and what to tell it if that work can never run | `nessy_agent_effect.payload`, `failure_payload`, `failed_attempts` |
| Input waiting its turn | `nessy_agent_backlog.payload` |
| A chapter's summary | `nessy_chapter.summary` |
| A note's hook and body | `nessy_note.hook`, `nessy_note.body` |
| A plan task's title | `nessy_plan_task.title` |

`nessy_agent_backlog` is the table the schema flags as holding raw user text
ahead of an event, so it is one a storage transform must never skip. The notebook
and the plan take the same `CodecFactory` as the backend, as a constructor
argument (`new JdbcNotebook(dataSource, TYPE, codecs)`), and a Spring Boot
application passes the context's `CodecFactory` bean to them. The in-memory
backend encodes its events, payloads, effects and chapter summaries the same
way; its backlog and the record of failed attempts are held as objects, since
nothing it holds outlives the process.

Everything else is stored as itself, and is what a query needs to find, order
or fence a row, or is plumbing:

- identifiers: the agent type and agent id on every table, a note's id, a
  lease's kind and holder, the hash that addresses a payload, and its `kind`;
- sequence numbers, turn bounds and positions: `seq`, `from_turn`,
  `through_turn`, `after_turn`, and the ordinal of a backlog item, a note and a
  plan task;
- timestamps and counters: `created_at`, `written_at`, `arrived_at`,
  `updated_at`, `closed_at`, `summarized_at`, `terminated_at`, `deadline`, `actionable_at`,
  `expires_at`, `attempts_made`, `timeout_millis` and `takeovers`;
- statuses: an effect's `status` and a plan task's `status`, which is one of
  three fixed words;
- the `starts_turn` flag on an event;
- `trace_context` on an effect, the W3C trace parent of the turn it belongs to.

An application's own tables are its own: Nessy neither creates them nor
encodes them. The codec is fixed for the life of the data: rows written under
one transform are unreadable under another, which is the same fact as an
encryption key.

## What an agent's row holds

`nessy_agent` is small on purpose: `agent_type`, `agent_id`, `created_at`,
and `terminated_at`, set once and never cleared. It is not what is locked:
`pg_advisory_xact_lock` is taken against a hash of
`(kind, agent_type, agent_id)` and needs no row of its own to be true of.
The queued door still needs a durable place to record that an agent was
told to end, since ending cannot be delivered mid-turn and has to be
remembered until the agent is next idle.

Where an agent actually *is* — idle, inferring, or waiting on a named set of
outstanding calls — is not stored anywhere as a row. It is rebuilt by
replaying the events since the agent's last turn started
(`AgentEvents.sinceLastTurnStarted`) from `nessy_agent_event`, which is why
that table has no update statement in its schema: reconstitution is a loop
over recent history, not a read of a snapshot. See [Agent as Scope](agent-as-scope.md).

## The story

`nessy_agent_event` is one row per event, appended and never rewritten,
keyed by agent type, agent id and `seq`, with a `starts_turn` flag marking
where each turn began. The flag is there so replaying an agent's last turn
is a lookup rather than a scan: a partial index over `starts_turn` picks out
the handful of rows per agent that matter to that query, however long the
conversation.

Events carry content by reference. What a message said, or a tool returned,
lives in `nessy_payload`, addressed by the SHA-256 hash of its own content, taken
before the storage codec's transform, and scoped to the agent that produced it. The events hold a reference
to it. That buys idempotence — an effect retried after a failure writes the
same row rather than a second copy — and it makes removing an agent's
payload rows one statement over one table, with nothing shared out from
under another agent. Identical content in two agents is stored twice, and
that is the trade.

Each model call also puts the parts of its request into `nessy_payload`: the prompt,
the tools offered, the options, and each memory, state and ambient section,
and its event holds the references. The summaries shown are named by the last turn they cover.
A part that is the same as on an earlier call is the same
reference, so the insert changes nothing and no second row is written. What a call costs the
store is those inserts, one statement for each part, and most of them find their row already there.

Every part a model was shown, other than the summaries and the turns, is kept, by reference,
with the call; the summaries and the turns shown are named by the turns they cover and are not
stored again. A part is stored once per agent for each distinct content, and the rows are kept:
nothing in the engine deletes them (see [Retention](#retention)). A source whose
output differs on every call, such as a retrieval result or a clock with second resolution, adds
one row per model call, about the size of that section. The in-memory store keeps these parts on
the heap. Whatever a memory, state or ambient source returns, and the values of vendor
properties, are stored: an application that must not keep some data must not return it from a
source.

The reference depends on the content as the value codec writes it, and not on the storage
transform. A transform that never writes the same bytes twice, such as AES-GCM with a fresh
nonce, still gives the same content one reference and one row. The stores that take the value
codec and the transform as two arguments hash between the two; a store given one factory that
already includes a transform hashes after it. The references are stored unencrypted beside the
events, so payloads with equal content have equal references, and a reader of the event table
can tell that content equals a guess without decrypting it.

A payload holds either message blocks or a JSON document, and its `kind`
column says which, `BLOCKS` or `DOCUMENT`. `Payloads.put` keeps blocks and `Payloads.putDocument`
keeps a document; a document's reference is the hash of the document as the
value codec writes it, so the same document is one reference and one row, with its fields in the order
it has them. Asking for blocks where a document is kept, or the reverse,
throws an `IllegalStateException` that names the reference and what is there,
and `getDocument` on a reference with nothing behind it throws too. Documents
go through the storage codec like every other stored byte.

That statement is not everything the agent said. For each tool call the
events also hold two lines of text, what the call would do and what it
returned, each at most 1,000 characters, and they hold other text: a
failed call's message, also at most 1,000 characters, and a denial's reason and
why a turn failed, which are not bounded. The facts an approver was shown are on
the events that record the decision, the deferral or the failure; they are not
cut, and nothing in the engine deletes them, so an application that must not
keep some data must not put it in an approval request's facts. The summaries
of the agent's chapters are in `nessy_chapter`. An agent's content is in
those three places, its payload rows, its events and its chapters. Nothing
in the engine deletes any of it today.

## Effects

`nessy_agent_effect` carries the work an agent owes and everything needed
to perform it without decoding it: a status, when it is next actionable
(`actionable_at`, which serves both a first attempt's due time and a
running attempt's watchdog time), how many attempts it has had
(`attempts_made`), a per-attempt timeout (`timeout_millis`) and a hard
deadline that never moves, the W3C trace context of the turn it belongs to,
and beside the payload a second blob, `failure_payload`, saying what to
tell the agent if the work can never be dispatched at all. See
[Durable Computation](durable-computation.md).

What an agent is waiting on is read from these rows, from the agent's story
and from its payloads. Nothing extra is stored for it. A row that is parked
now says a call is waiting. The story holds the model's request for the call,
with its action line, and, once the approver defers, the facts the approver
left on the deferral event. The payloads hold the call's arguments. Nessy does
not store the approval request itself; it is rebuilt from the row, the story
and the payloads. The table holds live work only: a finished call's row is deleted, so a read never lists finished
work. See [The Harness](../guides/harness.md#what-is-waiting-and-answering-it).

## Chapters

`nessy_chapter` has one row per closed chapter, keyed by agent type, agent id
and `from_turn`, with `through_turn`, `after_turn` (where the agent's previous
chapter ended, or zero), `closed_at`, and `summary` and `summarized_at`,
which are null until the summary is written. A chapter's bounds are never
changed. Its summary is written once, by an update that only touches a row
whose summary is still null. The summary is bytes through the storage codec;
whether it has been written is whether the column is null, which needs no
decoding to ask.

Two constraints keep an agent's chapters contiguous: the primary key, and a
unique `(agent_type, agent_id, after_turn)`. A chapter is stored only if the
agent's closed chapters still end where the caller said they did, so two
processes that cut the same turns differently cannot both succeed, and
neither leaves a gap or an overlap. The `Chapters` interface in
`nessy-backend-spi`, reached as `backend.chapters()`, is how the engine reads
and writes this table. See [Context](context.md#chapters).

## Retention

Nothing here deletes. Terminating an agent ends its activity and leaves its
rows; the story and its payloads grow until you prune them. That is a
policy your operators own, applied to the tables directly, and the schema
is plain enough to do it in one statement per table.

## Where next

- [Context](context.md), what a model call is built from
- [The Harness](../guides/harness.md#what-is-waiting-and-answering-it), reading what an agent is waiting on
- [Durable Computation](durable-computation.md), what survives a crash, and how
