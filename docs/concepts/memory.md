# Memory

`Memory` is what an agent remembers of its own conversation, and the thing a
model call's context is actually built from.

```java
public interface Memory {
  Context recall(AgentId agentId);
  void remember(AgentId agentId, HistoryMessage message);
}
```

Two methods. `recall` is what the model is shown; `remember` is what the
engine writes as a turn progresses.

## The memory owns history

The engine does not assemble a context and hand it to you for approval. It
asks `recall` and sends what comes back. Everything that decides what a
model sees — a window, a summary, retrieval, a policy about what to drop —
is a `Memory` implementation, and there is no second place to put it.

That is why the interface is two methods rather than twenty: the moment it
grows a `trim` or a `summarize`, the framework is deciding, and half of
memory strategy is exactly the part frameworks get wrong.

## An exchange is written whole

`remember` takes a `HistoryMessage`, and `HistoryMessage` will not let you
write half an exchange. An assistant turn that called tools and the results
answering it go in **together**, as one `ExchangeMessage`:

```java
memory.remember(agentId, new ExchangeMessage(asked, results));
```

This is not tidiness. It is what makes re-driving after a crash always safe:
a transcript never holds an assistant turn naming calls that were never
answered, so asking the model again from what *is* recorded is always a
correct continuation. For exactly the window a call is in flight, the
transcript is designed **not** to hold it — the asking message and the
results live in [claims](storage.md#the-claim-check) until they can go in
together.

## The shipped implementation

`TranscriptMemory` is one row per message in `nessy_transcript`, appended
and never rewritten.

```java
TranscriptMemory.eternal(dataSource, AgentType.of("assistant"));
TranscriptMemory.recent(dataSource, AgentType.of("assistant"), 40_000);
```

`eternal` recalls everything. `recent` recalls the newest messages up to a
character budget — a cursor that reads newest-first and **stops** when it
has enough, rather than reading a fixed tail and trimming it afterwards.

If you configure no memory, the engine uses `recent` and says so loudly at
startup. That default exists so an agent does not eventually stop, not
because it is a good memory, and the difference is worth being told once.

## Shaping the context

`nessy-memory-pipeline` wraps a memory in ordered stages, each of which may
transform the context on the way to the model:

```java
public interface ContextTransformer {
  Context transform(AgentId agentId, Context context);
}
```

```java
Memory memory = MemoryPipeline.of(
        TranscriptMemory.recent(dataSource, TYPE, 40_000),
        pipeline -> pipeline
                .stage(NotebookTools.notes(notebook))
                .stage(PlanTools.plan(plans)));
```

A stage is where an ambient fact belongs — the notes this agent has kept,
the plan it is holding — because it is contributed to the *context*, not
written into the transcript. The transcript stays a record of what actually
happened.

## Notes and plans

`nessy-memory-notebook` gives an agent notes it keeps and recalls by
heading. `headings()` is `SELECT note_id, hook`, so a body cannot reach the
model by accident — the agent asks for a note when it wants one.

`nessy-memory-plan` gives it a plan it holds across turns. The model resends
the **whole** list on every update rather than patching it, which is both
what models are trained to do and what this engine needs: a durable re-drive
is at-least-once, so a replayed wholesale write stores the identical list.
Idempotent by construction, with no task ids to reconcile.

The deliberate contrast between the two is not style. A note is too large to
resend, so the notebook pays for addressability with minted ids; a plan is
not, so it gets idempotence for free.

## Writing your own

Anything that honours the two methods is first-class. A vector store, Redis,
a bespoke schema, a summarizing window — the engine cannot tell, and does
not ask.

Two things to keep:

**Write an exchange whole.** If your implementation can persist half of one,
recovery can show a model a conversation that never happened.

**`recall` may do I/O, and it will be called on a virtual thread**, not on
an actor's thread. Take your time.

## Where next

- [Storage](storage.md) — the tables, and applying the schema
- [Durable Computation](durable-computation.md) — why writing whole matters for recovery
- [The Harness](../guides/harness.md) — configuring a memory
