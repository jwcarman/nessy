# Memory and the Pipeline

**`Memory`** owns what a model call actually sees — the content jurisdiction. It is
*told* every message-grade happening (the user message, the assistant message, and the
batched tool-results message once the last pending call clears — a closed list of
exactly three tellings), and it is *asked*: `Memory#recall(conversationId)` builds the
`Context` the next model call gets.

```java
public interface Memory {
  void remember(ConversationId id, Message message);
  Context recall(ConversationId id);
}
```

Freedom of retention, rule of law at the border: inside, an implementation may
transcribe, summarize, checkpoint, embed, or discard — the harness never audits how it
thinks. At the border, `recall` must return a legal `Context`: a tool-use/tool-result
pair is never split or reordered. `AgentConfig#memory(Memory)` replaces the default
outright.

!!! warning "Tellings are at-least-once"
    A crash between telling and persisting re-tells the same message on recovery, so
    `Memory#remember` must be idempotent. This is the same at-least-once posture
    documented on [The Durable Loop](durable-loop.md).

## `PipelineMemory`: the only `Memory` Nessy ships

`Memory.pipeline(transcript)` is the one composition surface for transcript-backed
memory, and `PipelineMemory` — its product — is the only `Memory` implementation Nessy
ships today. It remembers everything verbatim through a `Transcript` (an append-only,
versioned, per-conversation message log); what `recall` builds from it is a two-part
pipeline: **hydrate, then stages**.

```java
Memory memory =
    Memory.pipeline(
        transcript,                                                        // full hydration
        config ->
            config
                .summarizing(summaries, provider, model, prompt, tailThreshold) // …or fold instead
                .keepRecent(50)                                             // pair-safe clamp
                .transform(redactor)                                        // a throw fails the recall
                .transform(ContextTransformer.optional(annotator))          // self-optionalized
                .transform(PlanTools.transformer(planStore)));              // appending stage
```

The **degenerate pipeline is the floor**: `Memory.pipeline(transcript)` — no
hydrator named, no stages — hydrates with `ContextHydrator.full()` and transforms
nothing: the whole history, every time. Every addition to the chain from there is
strictly opt-in. This is also `AgentConfig`'s no-memory default:
`Memory.pipeline(Transcript.inMemory())`.

### Hydrate — `ContextHydrator`

A hydration strategy produces the *initial* context from durable history:

```java
public interface ContextHydrator {
  Context hydrate(ConversationId id, Transcript transcript);
}
```

Two implementations ship:

- **`ContextHydrator.full()`** — the floor: the whole telling, open-tail-trimmed
  (`transcript.all(id)`, with `TranscriptTrim.withoutOpenTail` applied). A parked
  conversation's raw telling can legitimately end in an unanswered tool-use message, and
  `Context`'s validating constructor rejects that shape, so trimming the open tail is a
  hydrator's border duty.
- **`ContextHydrator.summarizing(summaries, provider, model, prompt, tailThreshold)`** —
  built on `SummarizingHydrator`: renders the folded prefix from a `SummaryStore` as one
  opening `Message.user(text)`, plus only `transcript.tail(id, watermark)` — the tail
  since the summary's own watermark. Once the unsummarized tail grows past
  `tailThreshold`, the next recall folds it into the running summary and advances the
  watermark.

!!! note "A crash between summarizing and saving is cheap re-work, not lost words"
    The transcript is the truth a summary is only ever a cheaper way to re-read. If the
    process dies after summarizing but before `SummaryStore#save` commits, the next
    `recall` simply re-summarizes the same tail and lands on the same watermark — the
    watermark bookkeeping carries no fencing, on purpose, because a lost or clobbered write
    is never a lost word. See [Storage](storage.md) for `SummaryStore` itself.

The seam is open: a custom hydrator (bootstrap from a vector store, a checkpoint, an
external system of record) is legitimate — which is why `TranscriptTrim` is public in
`spi.transcript`.

### Stages — `ContextTransformer`

Everything after hydration is a stage. A stage may mutate the context in any way — clamp
it, redact a message body, elide a stale tool exchange whole, or append new messages:

```java
public interface ContextTransformer {
  Context transform(ConversationId id, Context context);
}
```

`Context` already carries the verb vocabulary a stage body wants: `enrich(ContentBlock...)`
appends exactly one user-role message (the documented carrier for non-human content —
this is how appending stages amend context); `map(UnaryOperator<Message>)` rewrites every
message (the redaction verb); `drop(Predicate<Message>)` removes pair-atomically; and
`elideToolResults(n)` / `keepRecent(n)` / `limitTokens(budget, estimator)` are the pair-safe
clamps built on that kernel. The config's `.keepRecent(n)` verb simply registers
`ctx -> ctx.keepRecent(n)` as a stage at its call position.

Stages run in registration order, each seeing its predecessors' output. Recommended
order — clamp, then mutating stages, then appending ones — keeps amendments unclippable,
but order is the caller's, on purpose.

!!! warning "Every stage is required — fail-closed by construction"
    To the pipeline, every stage is required. A stage that throws propagates straight out
    of `recall`: the turn fails, the durable machinery retries it later, and the model
    never sees a context the stage did not bless. A redactor that failed to strip a
    credit-card number must stop the context from being built at all — that's not a
    default to configure, it's the only behavior the pipeline has.

    Optionality is not the pipeline's concept — a stage optionalizes itself via a
    decorator: `ContextTransformer.optional(delegate)` logs one WARN line naming the stage
    and the conversation id on any exception, then returns the input context unchanged, as
    if the stage were absent. A partial output from a failed stage is never used.

## `Context`'s safe-edit kernel

`Context` owns the pairing invariant's safe edits so raw list surgery never happens in
application code. The trusted kernel is `drop(Predicate<Message>)` (pair-atomic),
`map(UnaryOperator<Message>)` (revalidating), and `enrich(ContentBlock...)`; built on
that kernel are `elideToolResults(int)`, `keepRecent(int)`, and
`limitTokens(long, TokenEstimator)`.

`agent.contextFor(conversationId)` calls the exact same `Memory#recall` the loop's own
model-call executor consults on every send — *exactly what a call made right now would
see*, truthfully and without spending a model call.

## The plan: the pipeline's first shipped stage

The plan facility (`spi.plan`) is the pipeline's first shipped tool-writable,
recall-injected stage: an agent that grants `PlanTools.updatePlan(planStore)` lets the
model maintain its own task list, and adding `PlanTools.transformer(planStore)` to the
pipeline recalls that plan as a checklist on every subsequent turn — unconditionally, for
as long as tasks remain, at the tail of context for maximum recency. See
[Planning](planning.md) for the full contract.

## Where next

- [Planning](planning.md) — the plan facility, the shipped consumer of a `ContextTransformer`.
- [The Durable Loop](durable-loop.md) — why `Memory#remember` must be idempotent.
- [Storage](storage.md) — `Transcript` and `SummaryStore`, the durable backing `PipelineMemory` reads and writes.
