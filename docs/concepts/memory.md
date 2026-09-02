# Memory

`Memory` is what an agent remembers of its own conversation, and the thing a
model call's context is actually built from.

```java
public interface Memory {
  Context recall(AgentId agentId);
  void remember(AgentId agentId, HistoryMessage message);
  void forget(AgentId agentId);
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

## Summarizing instead of dropping

Everything above throws information away. `recent` drops the oldest until the
rest fit; `keepRecent` drops all but the last few; `elideToolResults` replaces
old tool output with a marker. Each is honestly lossy and right sometimes, and
none preserves what happened — so a long-running agent either carries everything
or forgets the customer's name.

`nessy-memory-summarizing` compresses instead:

```java
Memory memory = SummarizingMemory.create(config -> config
    .transcript(TranscriptMemory.eternal(dataSource, type))
    .dataSource(dataSource)
    .agentType(type)
    .model(model)
    .executor(summarizing)      // where the model call runs
    .summarizeAfter(200)        // messages of uncovered history
    .keepVerbatim(20));         // never summarized
```

**It is a sidecar. The transcript is never touched.** One row per agent says
"everything through sequence N is in this paragraph", and recall stitches that
onto whatever came after:

```
recall = <summary>  +  everything after the sequence it covers
```

The covered messages are never read, which is the saving. And because nothing
is deleted to produce a summary, a bad one costs one row and no history — which
is what makes it safe to compress in the background and to fail without recovery.

The summary arrives as an `AmbientMessage` of kind `summary`, so each
provider adapter marks it the way its vendor prefers. It is not a turn, and it
cannot reach the transcript: `HistoryMessage` is sealed to `UserMessage`,
`ExchangeMessage` and `AnswerMessage`, and nobody *said* a summary.

### Say what to preserve

**This is the setting worth changing**, because what matters is domain knowledge
the framework does not have:

```java
config.systemPrompt(SummarizingMemory.SUMMARIZE + """

    This is a machine's own record of watching one host, not a conversation. Keep
    measurements and how they have MOVED, anything that has recurred across
    rounds, and anything still outstanding. Drop the narration of individual
    rounds.
    """);
```

A support agent wants order numbers kept; a coding agent wants file paths and the
decisions behind them; an agent working in German should summarize in German.

**Start from `SummarizingMemory.SUMMARIZE` rather than from nothing.** The
default is public for exactly that, and the reason matters:

> The summary is its own next input. From the second one onward, the model is
> given the *previous summary* plus what has arrived since — never the whole
> transcript, which is what bounds the cost. So every generation is lossy over
> the last, and a fact mentioned once decays geometrically. **An agent does not
> forget suddenly; it fades.**

That is why the default asks for names, identifiers, decisions, commitments and
open questions rather than a retelling. A prompt that says "summarize the
conversation" does not fail — it produces, after five generations, a paragraph
about there having been a conversation.

`nessy-examples/watchman` is the worked example: it runs forever, so it
summarizes, and `WatchmanPrompt.SUMMARIZE` shows the add-to-the-default shape.

### Compressing happens off the writing thread

`remember` never calls a model. It notices an agent has outrun its summary and
hands the work to the `Executor` you supply — which is required rather than
defaulted, because only your application knows which pool it can spare.

A late summary is not a wrong answer: until the work finishes, recall returns the
previous summary plus more verbatim history than intended. That is a context
slightly larger than planned, which costs tokens, not one that is wrong.

Concurrency costs money, not correctness. Two runs can both compress; the write
is monotonic, so whichever covers less loses and a summary never goes backwards.
That is why there is no lock.

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

## Forgetting

`forget` drops everything remembered for one agent, as though it had never
spoken. It exists because an agent id is not always a long-lived name — a
browser session, one review by a judging agent, a single request — and those
instances have to be able to end. See [`Harness.forget`](../guides/harness.md).

**It is abstract, not a default that does nothing.** A memory that silently
declined to forget would turn a privacy operation into a no-op with no way for
the caller to tell, and "we deleted it" is not a thing to be wrong about. A new
implementation is made to answer the question.

Forgetting an agent that never spoke is silent rather than an error: the end
state is the same either way, and a caller cleaning up should not have to know
which case it is in.
