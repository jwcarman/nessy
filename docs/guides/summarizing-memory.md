# Summarizing Memory

`ContextHydrator.summarizing(...)` keeps only a bounded tail of a
conversation's transcript verbatim, folding everything older into a running
summary once that tail grows past a threshold. It's the tail API's own
dogfood — a full alternative to `ContextHydrator.full()`, not a stage layered
on top of it.

## Wiring it

```java
Memory memory =
    Memory.pipeline(transcript)
        .summarizing(summaries, provider, "claude-haiku-4-5-20251001",
            "Summarize this conversation.", 20)
        .build();
```

`.summarizing(SummaryStore, ModelProvider, String model, String prompt, int
tailThreshold)` is sugar for `hydrator(ContextHydrator.summarizing(...))` —
the parameters mirror each other exactly. Setting a hydrator twice, by this
verb or `hydrator(...)`, in either order, is an `IllegalStateException`: one
hydration strategy per pipeline.

- `summaries` — the `SummaryStore` the folded prefix lives in.
- `provider`, `model` — the model call that does the folding. This can be
  (and often should be) a cheaper model than the one the agent itself uses.
- `prompt` — the system prompt handed to that fold call.
- `tailThreshold` — how many unsummarized transcript entries accumulate
  before the next recall folds them in.

## What `hydrate` actually does

Each recall loads the current `Summary` (absent means nothing has ever been
folded, so the whole transcript is the tail), then loads
`transcript.tail(id, watermark)` — everything since that summary's own
watermark. Only once that tail's size exceeds `tailThreshold` does it ask the
model to fold the summary and the tail's pair-safe prefix into a new one.

The boundary it folds up to is chosen the same way `Context#pairSafeCut`
chooses one — a genuine user turn, never between a tool call and its answer
— so a tool exchange straddling the threshold is always kept whole,
whichever side of the cut it lands on. The rendered context is the summary,
when non-empty, as one opening user message, followed by the tail's
messages, open-tail-trimmed exactly as `ContextHydrator.full()` trims it —
the same border law applies to every transcript-backed hydrator.

## The watermark

Transcript versions start at `0`, so "nothing folded yet" is tracked
internally as watermark `-1` — one below the first real version — never as a
stored `Summary`; a persisted watermark is always a real transcript version.
The new summary is saved watermarked at the last folded transcript version,
and the tail is reloaded from there on the next recall.

!!! note "A crash between summarizing and saving is cheap re-work, not lost words"
    The transcript is the truth a summary is only ever a cheaper way to
    re-read. If the process dies after summarizing but before
    `SummaryStore#save` commits, the next recall simply re-summarizes the
    same tail and lands on the same watermark. `SummaryStore#save` is
    last-write-wins, no fencing, on purpose — a lost or clobbered write is
    never a lost word.

Two more edge cases return the current summary unchanged, with no save and
no watermark advance:

- The tail carries no pair-safe boundary at all — an all-open-tool-exchange
  tail, vanishingly rare in practice. There's nothing safe to fold, so
  nothing is folded.
- The model's folded text comes back blank. A blank summary is not a
  legitimately empty one — the words it should have folded would be
  silently dropped from every future recall the moment the watermark moved
  past them. Leaving the watermark where it was means the next recall
  simply retries the same fold over the same tail.

## Usage jurisdiction, upheld by construction

This hydrator's own model spend never touches
`ConversationState#usage`: `hydrate` has no access to a `ConversationState`
at all — it is keyed by `ConversationId` alone. The fold call's tokens are
real spend, but they are not the agent's own usage accounting; there is no
seam here for them to leak through by accident.

## In a Spring Boot application

`SummaryStore` is not part of `nessy-autoconfigure`'s persistence
autoconfiguration — no `SummaryStore` bean exists anywhere in that module.
An application that wants a summarizing pipeline builds its own
(`JdbcSummaryStore.create(dataSource)`) and wires it in by hand:

```java
@Bean
Memory memory(Transcript transcript, DataSource dataSource, ModelProvider provider) {
    SummaryStore summaries = JdbcSummaryStore.create(dataSource);
    return Memory.pipeline(transcript)
        .summarizing(summaries, provider, "claude-haiku-4-5-20251001",
            "Summarize this conversation.", 20)
        .build();
}
```

That bean, once declared, satisfies `@ConditionalOnMissingBean` and replaces
the starter's autoconfigured plain-pipeline `Memory`.

## Where next

- [Memory and the Pipeline](../concepts/memory-and-the-pipeline.md) — where
  `ContextHydrator.summarizing` sits relative to hydration and the stage
  chain.
- [Storage](../concepts/storage.md) — `SummaryStore` and `Transcript`, the
  two durable doors this hydrator reads and writes.
- [Spring Boot](spring-boot.md) — the five autoconfigured persistence beans,
  and why `SummaryStore` isn't one of them.
