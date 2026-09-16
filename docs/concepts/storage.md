# Storage

Nessy is PostgreSQL rows. Each kind of thing lives in a table shaped for how
it is read, and there is no abstraction between the engine and its SQL.

| What | Where | Lives for |
|---|---|---|
| Where an agent is | `nessy_agent_state` | until the agent is terminated, and after |
| The story, one row per message | `nessy_agent_history` | forever, unless you prune it |
| Work an agent owes, with its deadline | `nessy_agent_effect` | until it completes or is given up on |
| What each model call was shown | `nessy_inference_context` | until you prune it |
| One rolling summary per agent | `nessy_summary` | replaced as the story grows |
| Episodes, each with its summary and the summary's embedding | `nessy_episode` | forever, unless you prune it |
| Notes, plan tasks, declared intent | `nessy_note`, `nessy_plan_task`, `nessy_intent` | as their modules decide |
| Background work claimed once | `nessy_lease` | its TTL |

Every module that needs a table ships it in its own `nessy-schema.sql`.

## PostgreSQL, and only PostgreSQL

The queries this engine rests on are PostgreSQL's: `SELECT ... FOR UPDATE`
to serialise an agent, `FOR UPDATE SKIP LOCKED` to claim work, `INSERT ...
ON CONFLICT` to take a lease, `TIMESTAMPTZ` columns. There is no in-memory
fallback and no H2: a fallback would not run a degraded Nessy, it would run
one that fails on the first turn, and saying so at startup is kinder than an
embedded database that looks like it worked. The test suite runs the same
DDL against a real PostgreSQL container.

## Applying the schema

`Schemas` gathers every module's `nessy-schema.sql` from the classpath and
runs them, in one call, safe to repeat:

```java
Schemas.initialize(dataSource);
```

**The name is the opt-in.** Spring Boot looks for `schema.sql`, so Nessy's
file never runs uninvited, and Nessy's loader never runs yours. Call this
yourself, let the starter do it (`nessy.initialize-schema`, on by default),
or feed the files to whatever runs your migrations. `classpath*:` matters:
it enumerates *every* matching resource rather than the first, so a module
added later brings its table with it.

## The engine builds its own access

The factory is handed a `DataSource` and nothing else about the database:

```java
new DefaultHarnessFactory(engine -> engine
        .dataSource(dataSource)
        .inference(provider, options));
```

From it the factory builds the JDBC client, the transaction manager and
every store. There is nothing to assemble and nothing for two callers to
assemble differently. Two things are exposed for reading, and only for
reading: `histories()`, the story as turns, and `inferenceContexts()`, what
the model was shown.

## Rows are Jackson, then whatever you say

Every payload column is bytes: the row encoded by Jackson, then passed
through whatever `Codec<byte[]>` the engine was given.

```java
new DefaultHarnessFactory(engine -> engine
        .dataSource(dataSource)
        .inference(provider, options)
        .storage(gzip.andThen(aesGcm)));
```

That is the seam for compression and for encryption at rest, and it covers
the state, the story, the effects and the recorded inference contexts
alike. It is fixed for the life of the data: rows written under one
transform are unreadable under another, which is the same fact as an
encryption key. In a Boot application a `StorageCodec` bean is picked up,
and the same transform is handed to the Odyssey event stream's journal so
what a listener wrote is protected the way the story is.

## What the state row holds

A phase, a sequence number, a turn id, the backlog of observations waiting,
and the calls outstanding, as one Jackson document a few hundred bytes
long. It does not grow with the conversation: the story is its own table,
and the fold says what to append by returning it. A `version` column moves
by one on every save, so the row is also where an entry's sequence number
comes from, read under the lock.

## The story

`nessy_agent_history` is one row per entry, appended and never rewritten,
keyed by agent and sequence, with the turn it belongs to and a rough token
estimate beside the payload. The estimate is there so a budget can be
applied in the query, a running sum over turns stopping at the oldest that
fits, rather than by loading a conversation to measure it. The provider's
tokenizer is the authority and being wrong is survivable, because a request
refused for length is retried rather than lost.

## What the model was shown

`nessy_inference_context` is written by the engine around every model call:
the whole `InferenceRequest` as rendered, system prompt, summaries, tail,
ambient, tools and options, before the provider is asked, and the outcome
(`answer`, `actions`, `refusal`, `fault`) with a completion time afterwards.
It cannot be reconstructed later: the head summary replaces itself, ambient
changes every call, and a templated prompt is rendered per call. Stored
whole rather than by reference to the story, so a row means something
wherever it is read.

It is on by default and costs one row per call. `recordInferenceContexts(false)`
on the engine turns it off. Read it back through `InferenceContexts`, as
`RecordedInference` values: this is the evidence trajectories, evals and
critics are built from, and the first thing to look at when a call went
wrong.

## Effects

`nessy_agent_effect` carries the work an agent owes and everything needed
to perform it without decoding it: a status, when it is next actionable,
how many attempts it has had, a per-attempt timeout, a hard deadline, the
W3C trace context it was emitted under, and beside the payload a second
blob saying what to tell the agent if the work can never be done. See
[Durable Computation](durable-computation.md).

## Retention

Nothing here deletes. Terminating an agent ends its activity and leaves its
rows; the story and the inference contexts grow until you prune them. That
is a policy your operators own, applied to the tables directly, and the
schema is plain enough to do it in one statement per table.

## See also

- [Memory](memory.md), what a model call is built from
- [Durable Computation](durable-computation.md), what survives a crash, and how
