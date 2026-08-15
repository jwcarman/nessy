# The Durable Loop

A **harness** is the model-independent runtime an agent runs inside — everything that
stays the same when you swap the model or the prompt. An **agent** is an identity: a
model binding, a system prompt, granted tools, declared authority, all running inside a
harness. `Nessy.harness(provider).build()` gives you the harness; `harness.agent()...build()`
gives you an `Agent<I>` — a reusable factory of conversations.

```java
Harness harness = Nessy.harness(anthropic).build();

Agent<String> agent =
    harness
        .agent()
        .name("guardian")
        .model("claude-sonnet-4-5")
        .tools(ToolGrant.grant(new AddTool(), UsagePolicy.allow()))
        .build();
```

`.name(...)` is required at `build()` — not a cosmetic label. It's the durable stamp every
parked call carries and every callback door checks a resolution against (see
[Parks and Callbacks](parks-and-callbacks.md)). Renaming an agent with parks in flight
orphans them.

## The fold

The core is a **fold**. `ConversationState#fold(ConversationEvent)` is pure, synchronous,
and total: given one fact, it returns a `Step` — the next state, the messages born this
fold, and a list of `Effect`s describing what should happen next. It never performs I/O
itself. `EffectExecutors` performs those effects (a model call, a tool call) and feeds
every result back in as a new `ConversationEvent`, so a tool call and a model call are
both just "perform an effect, get a fact back."

`ConversationState` is a plain, serializable record. That single fact is what makes
durability free instead of a special case: pausing is "stop feeding facts," resuming is
"load the state and keep feeding," whether the gap is 200 milliseconds or two days.

## Ready or parked

Not every effect finishes in-process. `Awaited<T>` is the sealed vocabulary for that:

```java
sealed interface Awaited<T> {
  record Ready<T>(T value) implements Awaited<T> {}
  record Parked<T>(ParkToken token) implements Awaited<T> {}
}
```

A tool (or an approver) that must outlive the process returns `Awaited.parked(token)`
instead of `Awaited.ready(result)`. The loop persists the session and moves on; the fact
arrives whenever `agent.resume(token, resolution)` delivers it — from any process,
however much later. `TurnEvent`s narrate the texture in between (streamed tokens, tool
requests, the park itself) to whatever `TurnObserver` is watching, independent of the
fold itself.

`agent.converse().tell(input, observer)` returns a `RunOutcome` — `Completed` or
`Parked` — carrying the settled `ConversationState`. `tell` already appends the input and
drives; `agent.resume(token, resolution)` does the same thing for the other direction a
conversation moves.

## Why replay safety shapes every API

Every real transport — a webhook, a queue, a cron poll retrying after a timeout — is
**at-least-once**. Nessy doesn't fight that; it designs for it:

- `Memory#remember` must be idempotent: a crash between telling and persisting re-tells
  the same message on recovery.
- `Transcript#append` does not stutter: appending a message equal to the current last
  entry returns the existing entry instead of a duplicate.
- A redelivered `resume` translates its token again and appends another resolution entry,
  but the fold's own is-this-call-still-outstanding check drains it quietly rather than
  replaying the tool call — the drive simply reads whatever the first delivery already
  produced.
- A `Tool` whose `execute` cannot be safely re-run makes itself idempotent, or parks and
  lets its remote side deduplicate by token.

!!! warning "At-least-once, not exactly-once"
    Quiet-drain protection against a redelivered resolution is serial, not concurrent: it's
    the fold picking a winner among entries already appended. Two deliveries of the same
    token driven **concurrently** can both observe the call as still outstanding and both
    invoke the tool before the fence settles on which fold wins. This is the same exposure
    `Tool#execute`'s javadoc documents directly — write tools and resolution handlers
    assuming re-delivery is normal, not exceptional.

Two write disciplines carry the state itself: a version-fenced control block (one writer
wins; a stale writer reloads and re-drives, never overwrites) and the inbox, which the
fence doesn't gate, so a chatty world can never fence-fail a working driver. Appending
always succeeds — a `tell` or a resolution is never refused for arriving mid-turn or
while parked; it joins the durable inbox and the next drive, from any node, picks it up.

## Where next

- [Parks and Callbacks](parks-and-callbacks.md) — the doors that answer a parked wait.
- [Storage](storage.md) — what has to be durable for a conversation to actually survive
  a restart.
- [Observability](../guides/observability.md) — the three spans that trace the loop's
  own phases.
