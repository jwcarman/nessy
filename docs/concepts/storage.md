# Storage

Nessy keeps four kinds of thing, and each one lives in a table shaped for
how it is read.

| What | Where | Lives for |
|---|---|---|
| What an agent is doing | Pekko's `durable_state` | until the turn ends |
| What is waiting to become a turn | `nessy_backlog` | until it is taken and swept |
| Content a turn needs and no longer | `nessy_claim` | the turn |
| A deadline that outlives its process | `nessy_reminder` | until the call settles |
| The conversation | `nessy_transcript` | forever, unless your `Memory` says otherwise |

Notes, plans and intent each get a table too, from whichever module
provides them.

## There is no storage abstraction

There used to be. `Substrate` was one key-value seam — documents and
journals — that everything went through, with a JDBC implementation, an
in-memory double, and a 550-line contract test holding the two together.
It was deleted on 2026-09-01.

It went because a general-purpose store makes its callers enforce its
design rather than their own. The notebook loaded whole entries and threw
the bodies away in Java to project a list of headings, because "give me
the headings" was not a shape a key-value seam could express. The
transcript read a fixed 500-message tail and then applied a character
budget, because a cursor that stops when it has enough was not a shape
either. Both are one statement now: `headings()` is `SELECT note_id, hook`,
so a body cannot reach the model by accident, and the transcript budget is
a newest-first cursor that stops, so `MAX_MESSAGES_READ` is gone.

The portability it bought was for a backend nobody asked for. One real
implementation and one test double, held to a contract, is a lot of
apparatus for a seam with one thing behind it.

## The engine needs it, so the engine provides it

Claims and reminders are engine bookkeeping. Nothing outside the engine
reads either, so neither is an extension point and neither is something an
application should have to wire.

```java
new PekkoHarnessFactory(engine -> engine
        .system(actorSystem)
        .models(models));            // no dataSource: the engine makes its own
```

Hand it no `DataSource` and it builds an in-memory H2 and initializes it.
Hand it one and it uses that — and **does not touch it**:

```java
new PekkoHarnessFactory(engine -> engine
        .system(actorSystem)
        .models(models)
        .dataSource(yourDataSource));
```

The engine initializes only a database it created. Yours is yours.

## Applying the schema

Every module that needs a table ships `nessy-schema.sql` at the root of its
jar. `Schemas` gathers all of them and runs them:

```java
Schemas.initialize(dataSource);
```

**The name is the opt-in.** Spring Boot looks for `schema.sql`, so Nessy's
file never runs uninvited, and Nessy's loader never runs yours. Call this
yourself, or apply the files through whatever runs your migrations.

`classpath*:` matters: it enumerates *every* matching resource rather than
the first, so a jar added later brings its table with it. That is what
Boot's own script initialization does.

## Two rules for the SQL

Both are enforced by a test running the DDL against H2, rather than by
anyone remembering them.

**ANSI spellings only.** `TIMESTAMPTZ` is a PostgreSQL alias that H2
rejects; `TIMESTAMP WITH TIME ZONE` works on both.

**No reserved words as identifiers.** `key` is reserved in H2 and merely
unreserved in PostgreSQL, so `nessy_document.key` would have worked in
production and failed in tests — which is the worse way round.

## What the agent's own document holds

A turn id, a phase, two claim ids, and a token count. Around 260 bytes,
measured on a real agent running real tools against PostgreSQL, and it does
not grow with what the agent does.

That is deliberate. The backlog is a table rather than a list in the
document, so a phase change rewrites four short strings instead of a queue.
Tool arguments and tool results are claimed, so a document never carries a
megabyte of output somebody's tool decided to produce. What is left is only
what answers one question: *what should happen if this process dies right
now?*

## The claim check

A turn needs the message the model asked with, and what each tool answered.
Neither can live on the document — they are the size of whatever a tool
decided to hand back — and neither can live in the transcript, because an
exchange is written **whole**, so for exactly the window a call is in
flight the transcript is designed not to hold it.

So they are claimed, and the agent deals in ids. Claims are deleted by
*turn*, not by key, which matters for more than tidiness: a claim written
just before a crash, before the state naming it was persisted, is an orphan
no key list contains. Deleting by turn sweeps it anyway, because it is in
the turn.

## PostgreSQL

`nessy-store-tests` runs the same certification against a real PostgreSQL 17
container. Deliberately not Alpine — musl's `strcoll` masks collation bugs
that glibc surfaces.

## See also

- [Memory](memory.md) — what an agent remembers, and who decides
- [Durable Computation](durable-computation.md) — what survives a crash, and how
