# Agent as Scope — the conversation dissolves

**Date:** 2026-08-18
**Status:** Design of record (proposed) — supersedes the `Agent`/`Conversation` split in
`2026-08-09-nessy-agent-harness-design-v2.md` §17 and the blocking-drive model throughout.

## 0. The one-sentence version

An agent is not a factory of conversations. An agent **is** a conversation — or rather,
"conversation" was never a thing at all: it was a **scope key** plus the durable state bound to
it. What is left, once that is named honestly, is an event-driven fold over two inbound lanes
that emits intents and never blocks.

## 1. The model

### 1.1 What an agent is

Five parts, and nothing else:

1. **A system prompt** — static configuration.
2. **A backlog** — inbound world observations, awaiting absorption.
3. **A memory** — durable, scoped, hiding transcript and summarisation entirely.
4. **An intent emitter** — the only thing that does heavy work.
5. **An observer** — everything the agent does, narrated outward.

Parts 2 and 3 are *bound to a scope key at construction*. That binding is the whole of what
`Conversation` used to mean.

### 1.2 Scope key, binding, host

Two orthogonal axes, and every deployment shape is a cell in the grid.

**Binding** — how the scope key resolves:

- *Constant* — the agent's own name. One scope, forever.
- *Per-request* — derived from an HTTP session, a tenant, a ticket id.

**Host** — who drives and who waits:

- *Interactive* — a caller waits. The terminal assistant message is the product; the drive is
  narrated through the observer as it happens.
- *Autonomous* — nobody waits. Tool calls are the product; assistant text is a parenthetical
  note ("I restarted the pod"). Runs indefinitely.

|                     | Interactive host | Autonomous host       |
| ------------------- | ---------------- | --------------------- |
| **Constant key**    | CLI              | the autonomous agent  |
| **Per-request key** | web chat         | per-tenant monitor    |

The fourth cell is not a gap invented to fill the table — a per-tenant monitor is a real thing,
one scope per tenant with nobody waiting. That it falls out for free is the evidence the axes
are correctly chosen.

### 1.3 Why "conversation" goes away

`Conversation<I>` today holds a `ConversationId` and delegates every method. It earns almost
nothing as a type, and it forces every scope to be conceptually a chat session — which the
autonomous agent is not, and which is exactly where the current model turns clunky.

Under this design the id survives as the **scope key** and stops being a parameter on every
call. `ConversationId` is renamed to reflect that it keys a scope rather than naming a chat.
Nothing else about it changes.

## 2. The agent

### 2.1 Shape

```java
public interface Agent<O> {
  void onObservation(O observation);
  void onModelCall(ModelCallEvent event);
  void onToolCall(ToolCallEvent event);
}
```

Three inbound methods, no outbound ones. The agent is a **reactor**: nothing calls it and waits.
Results leave through the observer and through intents.

### 2.2 Two lanes, one thread

The inbound methods are not peers. They are **two lanes with different priority**:

- **Continuations** (`onModelCall`, `onToolCall`) — the machinery reporting back. These *are*
  the drive. They arrive at discrete state boundaries and advance the fold immediately. They
  never queue behind world traffic.
- **Observations** (`onObservation`) — ambient world facts. They go to the backlog and are
  absorbed only at an idle boundary.

The reason for the split is concrete: a tool result must not wait behind a thousand stock ticks
to be folded. A continuation is the process moving forward; an observation is the world talking.

**The agent folds strictly one event at a time.** The two lanes are a dequeue priority, not
parallelism: within one instance there is exactly one thread of execution, and the fold never
blocks or does I/O beyond a cheap memory append and a cheap recall.

This is an *intra-instance* guarantee, not a cross-node one. Two nodes may each hold an instance
for the same scope and each fold single-threadedly; excluding that is §6's problem, not the
fold's.

### 2.3 The drain invariant

> **Whenever the agent is idle and the backlog is non-empty, poll one observation, absorb it,
> and drive.**

One invariant, not two rules. The two events that can make it true — an observation arriving,
and a turn ending — are consequences, not separate cases. Stating it as an invariant means a
third trigger (a recovery sweep, a park resolving into idle) cannot be forgotten later.

"Idle" is derived, not tracked: **idle means no outstanding intents.** There is no state enum.
`AwaitingModel` versus `AwaitingToolResults` is simply *what kind* of intent is outstanding, and
the fold reads it off the outstanding set rather than maintaining a parallel field that can
disagree.

### 2.4 `onObservation`

1. Add the observation to the backlog. (Coalescing, if any, happens inside `add` — see §5.)
2. If not idle, return. The drive in flight will pick it up when it ends.
3. Poll the backlog. If empty, return — another thread got there first; this is why `poll`
   returns `Optional` and there is no `isEmpty`.
4. Render the polled observation to content blocks via `ObservationRenderer<O>`.
5. `memory.remember(...)` the resulting message.
6. `memory.recall(...)` and emit a model-call intent carrying the assembled context.

### 2.5 `onModelCall`

Remove the intent from the outstanding set. Append the assistant message to memory. Then:

- **Tool calls present** — emit a tool-call intent per call and remain non-idle.
- **No tool calls** — the turn is over. The agent is idle; narrate the terminal message to the
  observer; apply the drain invariant.

### 2.6 `onToolCall`

Remove the intent from the outstanding set and append the result to memory. When the last
outstanding tool call settles, emit a model-call intent with freshly recalled context. A call
that parked instead of returning leaves the agent non-idle with a durable wait outstanding — see
§8.

### 2.7 Rendering, and what happens when it throws

`ObservationRenderer<O>` lives **on the agent**, not on the backlog, and applies at **poll time,
not add time**.

- Not on the backlog, because the backlog's job is storage, ordering, and coalescing — a
  different axis. Putting rendering there forces every backlog implementation to carry it, so
  in-memory and JDBC backlogs could not be swapped freely, and two agents sharing a
  `Backlog<StockTick>` could not render a tick differently.
- Not at add time, because rendering to `Message` destroys the coalescing key. A backlog cannot
  keep-last-per-symbol if it can no longer see the symbol.

`O` therefore exists for exactly two reasons — the backlog the agent polls and the renderer it
applies. Nothing in the fold ever sees it.

**Failure policy — this is a real change.** The current `Conversation.render` javadoc lets a
renderer's exception propagate unwrapped, justified by "a renderer is the application's own
code, failing on its own thread, with the caller present to see it." Under this design that
justification is false: absorption happens at an idle boundary, and under the autonomous host
nobody is present.

So: **a renderer failure discards that observation, is emitted to the observer, and the agent
stays idle and continues the drain invariant.** A single malformed observation must not wedge a
scope. Under the interactive host the caller still learns of it, because the observer is what
the caller is already watching.

## 3. Intents

### 3.1 The emitter

```java
public interface IntentEmitter {
  void emit(Intent intent);
}
```

The emitter is the **only** component that does heavy work, and it always does it off the fold's
thread. It calls back into `onModelCall`/`onToolCall` when work completes. This is the seam that
keeps the fold pure and non-blocking, and it is the seam tests replace with a recording emitter.

### 3.2 The model-call intent is a complete value

The intent carries the **assembled context**, not a reference to where context could be found:

```java
record CallModel(SystemPrompt prompt, Context context, List<ToolSchema> tools, String model)
```

This buys three things:

- A test asserts on a **value**, with no mocking library — which is a standing promise of this
  project that the current blocking loop cannot keep.
- The observer reports exactly what went to the provider, not what it was derived from.
- A retry re-sends what was decided, rather than re-deriving something that may have drifted.

The alternative — the intent naming a scope and the emitter recalling for itself — was rejected
because it makes the intent un-loggable, un-assertable, and non-deterministic under retry, and
because it drags `Memory` into a component that otherwise never touches it.

This is only affordable because §4 moves summarisation off `recall`'s hot path. Without that
ruling, building the intent would mean blocking on a model call in order to decide to make a
model call.

## 4. Memory

`Memory` stays a two-method facade — `remember`, `recall` — and hides transcripts, summaries,
hydration, and compaction from the fold entirely. The fold knows nothing about how context is
assembled, and gains nothing by knowing.

**`recall` is cheap by contract.** For `SummarizingHydrator` the hot path is exactly:

1. Look for a summary record.
2. If present, use it plus the transcript tail after it. If absent, use the whole history.

No model call, ever, on the hot path.

**Summarisation runs asynchronously, owned by the hydrator.** When `recall` notices the tail has
passed the threshold, it submits a summarisation task and returns immediately with what it has.

Three requirements on that, all of them the hydrator's own business and none of them the fold's:

- **An injected `Executor`, never a spawned thread.** Tests pass a same-thread executor and the
  whole thing is deterministic — no mocks, no sleeps. Production passes a pool it owns and can
  shut down. Note `PipelineMemoryConfig#summarizing` already takes five parameters, so the
  executor is a sixth and trips S107; it wants the same bundling record `Agent` already uses for
  `Coordination` and `SelfDescription`.
- **In-flight tracking inside the hydrator.** It holds the set of scope keys it is currently
  summarising and does not submit twice. **Cleared on failure as well as success** — a
  `whenComplete`, not a `thenRun`. An entry that leaks on failure means that scope never
  summarises again and its context grows unbounded with no signal.
- **Emission on the existing `ListenerRegistry`.** Started, finished, failed become ordinary
  observable traffic. The fold still knows nothing; the operator does.

Two accepted consequences, stated so they are chosen rather than discovered:

- **The context is deliberately stale for a turn or more.** The turn that notices summarisation
  is needed still sends the un-summarised context, as does every turn until the summary lands.
  Under a fast autonomous loop that can be several turns. `PipelineMemory` owns the policy for
  how far past the threshold `recall` will go before degrading rather than handing back an
  oversized context.
- **In-flight tracking is per-JVM.** Two nodes recalling the same scope both submit. The cheap
  fix is making the `SummaryStore` write a claim rather than a blind put; the lazy fix is
  accepting a duplicate model call, since a summary is a pure function of the transcript prefix
  and the second write is harmless. Pick one deliberately.

`contextWindow` becomes memory's own configuration — the budget the threshold is measured
against — rather than a field the loop consults.

## 5. Backlog

```java
public interface Backlog<O> {
  void add(O observation);
  Optional<O> poll();
}
```

Two operations. There is deliberately no `isEmpty()`: `isEmpty()`-then-`poll()` is check-then-act
and races under concurrent adds. `Optional` collapses the check and the act into one.

**The agent knows nothing about coalescing.** Keep-last-per-symbol, sum-per-counter,
keep-everything — all of it lives inside one `add` implementation. This is the same move
`Memory` makes for summarisation, and it pays the same dividend.

Two things fall out of it:

- **Durability becomes an implementation choice, not a design ruling.** In-memory for CLI,
  JDBC-backed for the clustered autonomous host, broker-backed if someone wants the queue itself
  to be the backlog. Same interface, same agent, no core change.
- **Batching lives in the element type.** If five ticks should be absorbed as one turn rather
  than five, that is a `Backlog<TickBatch>` whose `poll` merges — not a drain-all loop in the
  agent. The agent stays strictly one-observation-per-turn, which keeps the fold trivial.

## 6. Multi-node

Multi-node is a hard requirement, so residency-as-lock is not available: an in-process registry
entry cannot exclude another node.

The design's position is **two layers, not two alternatives**.

### 6.1 The correctness floor — optimistic concurrency

No ownership. Any node may drive any scope. Correctness comes from compare-and-set on the state
version; a loser reloads and re-folds. This mechanism **already exists** — `StaleStateException`
and the version-retry on state appends are how today's design survives concurrent drives without
a lock, and the redesign must not introduce a lock manager where the current one got away
without one.

The cost is duplicated **intents**: two nodes can both emit a model call (one wasted) or both
emit a tool call (at-least-once execution). The latter is already house policy — "a tool that
cannot be safely re-run makes itself idempotent" is in `Agent`'s own javadoc today, and the
`resume` javadoc already documents the concurrent-delivery exposure in detail.

This floor is a property of the core, with no infrastructure assumptions. Single-node embedding
stays trivial. Plain JDBC is sufficient.

### 6.2 The deployment layer — partitioned ownership

Let the broker be the lock. Observations for a scope arrive on a partition keyed by scope id,
and the broker guarantees one active consumer per key — Kafka partitions, or RabbitMQ
single-active-consumer behind a consistent-hash exchange. Real exclusivity, real horizontal
scaling, and **zero lock code inside Nessy**.

This eliminates the duplication §6.1 tolerates, and it costs no core code because the broker
does the work.

### 6.3 What is explicitly rejected

**Durable leases.** An ownership record with expiry, renewal, and fencing tokens is a lock
manager with clock dependence, liveness tuning, and its own bug class. It is the only option
that avoids duplicate work with no broker, and it is not worth that.

**Durable drive phase.** Writing every state transition before emitting intents would let any
node rehydrate a mid-flight drive. It doubles the store traffic and makes two nodes rehydrating
the same drive a real scenario. Rejected in favour of §6.1 plus §8.

### 6.4 Crash mid-drive

A process dies with intents outstanding. The scope's durable state is intact and consistent —
nothing is half-written, because appends are atomic and versioned. What is lost is the in-flight
intent.

The scope resumes on the next event: another observation arrives, or a park resolves, and the
fold re-derives from stored state. For the autonomous host that is typically milliseconds
(the next tick). For an interactive scope it is when the user next speaks, at which point the
transcript is intact.

The gap is a scope that crashes mid-drive and receives nothing further. A recovery sweep — find
scopes with outstanding calls and no progress past a threshold, re-drive — is a **bolt-on, not
core**. It is listed here so its absence is a decision rather than an oversight.

## 7. Hosts

One core, two hosts. The fold, the intent grammar, `Memory`, `Backlog`, and `Parks` are
identical in both; the hosts differ only in how observations arrive, who owns the scope, and who
consumes the output.

Two **distinct public types**, not one type with a mode flag — their APIs genuinely differ (one
returns a stream, one returns nothing and runs forever), and a single type would have half its
methods unusable in each mode.

They are hosts over one agent, **not two agent types**. The deciding case is the agent that
needs both: a support agent a human chats with *and* that reacts to a ticket webhook or a
nightly sweep. Two hosts over one scope handle that as a matter of course; two agent types
cannot express it.

### 7.1 Interactive host

Resolves the instance for the scope key, feeds the observation, streams the drive through the
observer, and releases. The caller's product is the terminal assistant message.

**Observer lifetime is the sharp edge.** The agent instance is transient and dies between
drives; an SSE emitter outlives the HTTP request and must survive that. So the emitter is
registered with the scope's `ListenerRegistry`, which owns its lifetime — *not* held by the
instance. Otherwise the stream goes silent the moment a drive ends, which is precisely when the
user is still watching.

**Contention on an interactive scope is effectively zero** — a human does not race themselves —
so the optimistic floor's retry path is cold, and sticky session routing (standard web practice
regardless) gives partition-like exclusivity for free.

### 7.2 Autonomous host

A long-running consumer, partitioned by scope key per §6.2. Drains the backlog, drives, and
nobody waits. Assistant text is narrated to the observer as commentary; tool calls are the
product.

### 7.3 The synchronous adapter

Fire-and-forget everywhere would be a real ergonomic loss. `RunOutcome tell(...)` is a gift to
tests, CLIs, and anyone embedding the library in a `main` method, and SSE does nothing for any
of them.

So: a **`CompletableFuture` completed by an observer when the scope reaches idle**, wrapped for
convenience. Not a second code path — one observer, one fold, a convenience wrapper. This is
also what the test suite will want, and it is the answer to the concern that an
observer-centric design is hard to test: the observer is an interface with a recording
implementation, and the adapter turns "the drive finished" into a value you can await
deterministically.

**"Which reply is mine" is not solved, and does not need to be.** The observer is scope-scoped,
so a second participant on the same scope sees the first's traffic — which is correct, because
it *is* the shared session. Per-observation correlation is deferred until real
multiple-callers-into-one-scope traffic exists to justify it.

## 8. Parks — unchanged

`Parks` is untouched, and becomes *the* durability story rather than one of three. The unifying
rule:

> **Short waits are in-memory. Long waits park.**

A model call or a tool call expected back in seconds is an outstanding intent held by a live
instance. Anything that might outlive the process parks — durably, through `Parks`, exactly as
today — and **its resolution arrives as an observation**.

That last clause is the simplification. Today `resume`/`approve`/`deny`/`peek`/`progress` live
on `Agent` precisely *because* they are cross-conversation: a `ParkToken` arrives from a webhook
with no conversation context, and `Parks` translates token to conversation before anything is
appended. Under this design that translation happens **before** an instance is resolved: the
token names a scope, the host resolves that scope's agent, and the resolution enters through
`onObservation` like anything else. The callback doors stop needing a special cross-scope
surface.

The `WrongAgentException` stamp check moves to the resolution step and keeps its current
semantics.

## 9. Migration

| Today                            | Becomes                                                            |
| -------------------------------- | ------------------------------------------------------------------ |
| `Agent<I>` (factory)             | split: `Agent<O>` (scope-bound reactor) + host/resolver             |
| `Conversation<I>`                | gone — its id becomes the scope key                                 |
| `ConversationId`                 | renamed to a scope key; semantics unchanged                         |
| `ConversationLoop.run` (blocking) | the fold — pure, non-blocking, `(event) → intents`                 |
| `InputRenderer<I>`               | `ObservationRenderer<O>`, applied at poll time                      |
| `RunOutcome tell(...)`           | §7.3 synchronous adapter over the observer                          |
| `TurnObserver` / `ListenerRegistry` | unchanged; now the primary output path                           |
| `Memory`, `Parks`, `ConversationStore`, authorization, tools, subagents | unchanged                     |

Public API breaks hard. The SPI mostly does not — which is the point of taking this approach
over a full actor-system rewrite: the substrate is mature and already does the right things, and
authorization and parks are the two most carefully-reasoned subsystems in the codebase. They are
adapted, not reopened.

## 10. Open questions

1. **Recovery sweep** (§6.4) — deferred as a bolt-on. Confirm that is acceptable before
   implementation rather than after.
2. **Duplicate summarisation across nodes** (§4) — claim-write or accept the waste. Needs a
   ruling.
3. **Staleness policy** (§4) — how far past the threshold `recall` degrades rather than handing
   back an oversized context.
4. **Scope key naming** — `ScopeId`? `AgentKey`? It is `ConversationId`'s successor and the name
   should stop implying chat.
5. **Intent grammar sealing** — the sealed-grammar etiquette in the design of record applies.
   Confirm the grammar is exactly `CallModel` and `CallTool` before locking it; anything a single
   `Memory` or `Backlog` implementation needs must *not* appear there.
