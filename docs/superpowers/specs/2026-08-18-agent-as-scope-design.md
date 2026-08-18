# Agent as Scope — a pure reducer and a thin shell

**Date:** 2026-08-18
**Status:** Design of record (proposed). Supersedes the `Agent`/`Conversation` split, the blocking
drive, and the park-as-state model in `2026-08-09-nessy-agent-harness-design-v2.md`.

This design is not constrained by the current implementation. Where the existing code is in the
way, it goes.

## 0. Thesis

There is no such thing as a conversation. There is an **agent bound to an identity**, and its
state is the fold of everything that has happened to it.

Everything else follows from one rule:

> **The reducer is pure. Every side effect lives in a named executor.**

Hold that rule and the design collapses to two data types, one function, and a thin shell.

## 1. The model

### 1.1 Identity, binding, host

An agent instance is a **definition** (system prompt, tools, model) bound to an **`AgentId`**. The
id is the scope. Memory, log, and backlog are all scoped by it, and nothing in the core ever takes
it as a parameter — the instance *is* the scope.

Two orthogonal axes cover every deployment shape:

**Binding** — how the id resolves:

- *Constant* — the agent's own identity. One scope, forever.
- *Per-request* — derived from a session, tenant, or ticket.

**Host** — who drives and who waits:

- *Interactive* — a caller waits; the terminal assistant message is the product.
- *Autonomous* — nobody waits; tool calls are the product, and assistant text is a parenthetical
  note ("I restarted the pod").

|                    | Interactive host | Autonomous host      |
| ------------------ | ---------------- | -------------------- |
| **Constant id**    | CLI              | the autonomous agent |
| **Per-request id** | web chat         | per-tenant monitor    |

The fourth cell is not filler — a per-tenant monitor is one scope per tenant with nobody waiting.
That it falls out for free is evidence the axes are right.

### 1.2 Why "conversation" goes

`Conversation` held an id and delegated every call. It earned nothing as a type, and it forced
every scope to be conceptually a chat session — which the autonomous agent is not. That mismatch
is where the current model turns clunky.

The word does not survive anywhere: not in the type names, not in the event grammar, not in the
store. `ConversationId`, `ConversationEvent`, `ConversationStore`, `ConversationStatus`,
`ConversationSnapshot`, and `ConversationLoop` are all deleted or renamed (§9).

## 2. The pure core

Four types and one function. No I/O, no collaborators, no clock, no randomness.

### 2.1 `AgentEvent` — facts, past tense

```java
public sealed interface AgentEvent {
  record Told(List<ContentBlock> content) implements AgentEvent {}
  record ModelResponded(List<ContentBlock> content, List<ToolCall> calls) implements AgentEvent {}
  record ModelFailed(String reason) implements AgentEvent {}
  record ToolFinished(ToolCall call, ToolResult result) implements AgentEvent {}
}
```

**No id on any event.** The instance is the scope; an event does not need to say which scope it
belongs to when there is only one it could belong to. Outbound narration is stamped with the id at
emission (§8), which is the only place it is genuinely needed.

**The failure asymmetry is deliberate**, not an inconsistency inherited from the old grammar.
`ToolFinished` carries a possibly-failed `ToolResult`, while `ModelFailed` is its own variant,
because the two failures differ in kind. A failed tool call is **in-band**: it goes into the log,
the model reads it, and the model reacts — that is the whole agent loop working correctly. A failed
model call is **out-of-band**: there is nowhere to put it and nobody to read it. The grammar should
say so.

### 2.2 `State` — the fold

```java
public record State(List<Message> log, List<ToolCall> outstanding) {}
```

**`idle` is derived, not tracked**: `outstanding.isEmpty()`. There is no status enum. "Awaiting
model" versus "awaiting tools" is simply *what* is outstanding, read off the state rather than
maintained in a parallel field that can disagree with it.

### 2.3 `Effect` — commands, imperative

```java
public sealed interface Effect {
  record CallModel() implements Effect {}
  record ExecuteTool(ToolCall call) implements Effect {}
}
```

`CallModel` is a **bare marker with no payload**. This is the direct consequence of the purity
rule: putting an assembled `Context` in it would require the reducer to call `recall`, and a
reducer that does I/O is not a reducer. Context assembly belongs to the executor that needs it
(§4.1).

The sealed supertype earns its place because `Step` returns a *list* of effects and a list needs an
element type — not because a grammar is inherently good.

**Two variants is the designed ceiling, not today's count.** There was once a third,
`Effect.Compact`, shipped and tested; it was absorbed into memory during the context-pipeline
rework. That is the pattern: anything a single collaborator needs is solved inside that
collaborator's facade, never by widening the grammar. A third effect would be a deliberate
architectural change.

### 2.4 `Reducer` — the whole of the logic

```java
public record Step(State state, List<Effect> effects) {}

@FunctionalInterface
public interface Reducer {
  Step reduce(State state, AgentEvent event);
}
```

Pure, total, synchronous. The entire decision logic of the framework, testable with values and
nothing else — no mocks, no fakes, no stand-ins. This is the property everything else is arranged
to protect.

The rules are small enough to state in full:

- **`Told`** — append to the log, emit `CallModel`.
- **`ModelResponded`** — append. If it carries tool calls, add them to `outstanding` and emit one
  `ExecuteTool` each. If not, the turn is over and no effect is emitted; the agent is now idle.
- **`ModelFailed`** — append nothing to the log. Emit nothing. The agent is idle, and the failure
  reaches the observer (§8) rather than the model.
- **`ToolFinished`** — append the result, remove the call from `outstanding`. When `outstanding`
  empties, emit `CallModel`.

## 3. The shell

```java
public interface Agent<O> {
  void onObservation(O observation);
  void onEvent(AgentEvent event);
}
```

Two methods, because there are exactly **two inbound lanes**:

- **Continuations** (`onEvent`) — the machinery reporting back. These *are* the drive. They fold
  immediately and never queue behind world traffic.
- **Observations** (`onObservation`) — ambient world facts. They go to the backlog and are absorbed
  only at an idle boundary.

The split is not stylistic. A tool result must not wait behind a thousand stock ticks to be folded.

**One inbound method for events, two outbound methods for effects (§4), and that asymmetry is
correct** — it is fan-in versus fan-out. Events fan *in* to one reducer, which must switch on the
sealed grammar anyway; typed inbound methods would be four one-line delegations that merely move
the switch to the caller. Effects fan *out* to two genuinely different collaborators; there is no
single object that can execute an arbitrary effect, so the dispatch is real work.

### 3.1 The drain invariant

> **Whenever the agent is idle and the backlog is non-empty, poll one observation, absorb it, and
> drive.**

One invariant, not a list of triggers. The events that can make it true — an observation arriving,
a turn ending, a recovery sweep — are consequences. Stated as an invariant, a new trigger cannot be
forgotten.

### 3.2 What the shell does

The shell holds everything the reducer refuses to: the backlog, the renderer, persistence, the
executors, and the observer.

`onObservation(o)`:

1. `backlog.add(o)`.
2. If not idle, return — the drive in flight will drain it.
3. `backlog.poll()`; if empty, return.
4. Render to content blocks; feed `Told` to `onEvent`.

`onEvent(e)`:

1. `Step step = reducer.reduce(state, e)`.
2. Append `e` to the durable log at the expected version (§6).
3. Adopt `step.state()`.
4. Narrate `e` and `step.effects()` to the observer.
5. Dispatch each effect to its executor.
6. If now idle, apply the drain invariant.

### 3.3 `Backlog`

```java
public interface Backlog<O> {
  void add(O observation);
  Optional<O> poll();
}
```

Two operations. There is deliberately no `isEmpty()`: check-then-act races under concurrent adds,
and `Optional` collapses the check and the act.

**The agent knows nothing about coalescing.** Keep-last-per-symbol, sum-per-counter,
keep-everything — all of it lives inside one `add`. Two consequences:

- **Durability is an implementation choice, not a design ruling.** In-memory for CLI, durable for
  the clustered autonomous host, broker-backed if the queue should be the backlog. Same interface,
  same agent.
- **Batching lives in the element type.** Five ticks as one turn is a `Backlog<TickBatch>` whose
  `poll` merges — not a drain-all loop in the shell. The shell stays strictly
  one-observation-per-turn, which keeps the invariant trivial.

### 3.4 `ObservationRenderer<O>`

Lives on the shell, applies at **poll time, not add time** — rendering to `Message` on `add` would
destroy the coalescing key, and a backlog cannot keep-last-per-symbol if it can no longer see the
symbol. It is not on the backlog because storage and presentation are different axes; putting it
there would force every backlog implementation to carry it and stop two agents from rendering the
same tick differently.

`O` therefore exists for exactly two reasons — the backlog polled and the renderer applied.
Nothing in the core ever sees it.

**A renderer that throws discards that observation, narrates the failure to the observer, and
leaves the agent idle to continue draining.** The current code lets it propagate, justified by "the
caller is present to see it" — false here, since absorption happens at an idle boundary and the
autonomous host has no caller. One malformed observation must not wedge a scope.

## 4. Executors — where every side effect lives

```java
public interface ModelCallExecutor { void callModel(AgentId id, State state, Sink sink); }
public interface ToolCallExecutor  { void executeTool(AgentId id, ToolCall call, Sink sink); }
```

`Sink` accepts an `AgentEvent`. The `AgentId` is a **delivery address**, passed alongside the
effect rather than embedded in it — the grammar stays pure data, and an executor that must deliver
days later has the address it needs to persist.

Both are asynchronous: they return immediately and call back. The shell's thread never blocks on a
model or a tool.

### 4.1 `ModelCallExecutor` owns memory

Memory does not appear anywhere in the core. The executor projects `State` into a `Context` —
hydration, summaries, trimming, budget — calls the provider, and delivers `ModelResponded` or
`ModelFailed`.

This is the honest version of "memory hides a lot from the loop": it hides *everything*, by not
being in the loop.

**`recall` must be cheap.** For summarising projection the hot path is: look for a summary record;
if present use it plus the log tail after it, else use the whole log. No model call, ever, on the
path that answers a projection.

**Summarisation runs asynchronously, owned by the projector.** When it notices the tail has passed
threshold it submits a task and returns with what it has. Three requirements, all internal:

- **An injected `Executor`, never a spawned thread.** Tests pass a same-thread executor and the
  whole thing is deterministic — no mocks, no sleeps.
- **In-flight tracking**, cleared on failure as well as success (`whenComplete`, not `thenRun`). A
  leaked entry means that scope never summarises again and its context grows unbounded, silently.
- **Emission to the observer.** Started, finished, failed are ordinary narration.

Two accepted costs, chosen rather than discovered: the context is **deliberately stale** until the
summary lands, so the projector owns a policy for how far past budget it will go before degrading;
and in-flight tracking is **per-JVM**, so two nodes both submit — fixed with a claim-write on the
summary record, or accepted, since a summary is a pure function of a log prefix.

### 4.2 `ToolCallExecutor` owns the gate and the address book

Authorization, tool dispatch, and delivery. Nothing about it reaches the core.

### 4.3 Parks are not a state — they are an address book

**From the reducer's view there is no difference between a tool that returns in 200ms and one that
returns in three days.** Both are an outstanding call that will produce a `ToolFinished`.

So there is no parked state, no `ParkToken` in the grammar, no `AWAITING_INPUT` status, and no
`resume`/`approve`/`deny`/`peek`/`progress` on the agent. `Parks` demotes to what it always was: a
durable map from token to `(AgentId, ToolCall)`, private to `ToolCallExecutor`. A token is a
delivery address, not a lifecycle.

Human-in-the-loop stops being special. The gate parks, a human answers, a `ToolFinished` arrives —
the same path as a slow HTTP call. The approve/deny doors become an ordinary API on the tool
executor, not core surface.

### 4.4 Subagents need no core support

A subagent is a tool that resolves a child agent, drives it, and returns its terminal message as
the tool result. Since it is slow, it parks. That is the entire integration: **subagents are a tool
that returns slowly.** No delegation concept, no link store, no callback router in the core.

## 5. Durability

**The event log is the only durable state.** `State` is `fold(events)`. There is no separate
transcript — the log *is* the transcript, and memory projects it. The current
`ConversationStore`-plus-`Transcript` duality is deleted; it stored the same history twice under
two vocabularies.

Replay cost is a store concern, not a design concern: a store may snapshot folded state
periodically. The design does not know or care.

### 5.1 Nothing writes to memory

`Memory.remember` does not exist. Memory is **read-only projection over the log** — hydrate,
summarise, trim, budget — and the two-method facade collapses to one direction.

A new user message reaches the model by the ordinary path and no other: `onEvent(Told)` reduces,
and the shell appends `Told` to the log. When `ModelCallExecutor` projects, the message is already
there.

For the same reason `CallModel` must **not** carry the rendered message. It is already in the log
the projector reads, so carrying it would state the same fact in two places, put payload back into
a marker argued down to nothing, and create a second path by which content reaches the model — one
through the log, one riding the effect, free to disagree.

**Invariant: append before dispatch.** The shell appends the event to the durable log before
dispatching any effect the reduction produced. Reverse them and the executor projects a log missing
the very message that provoked the call.

## 6. Multi-node

Multi-node is a hard requirement, so in-process residency cannot be the lock.

**The correctness floor is optimistic concurrency**, and it is free under §5. Each `onEvent`
appends at an expected version. Two nodes may fold the same state and both emit `CallModel`; the
second append fails on version conflict, and that node reloads and re-folds. Duplicated work,
never corrupted state. Plain JDBC suffices; single-node embedding is trivial.

The residual cost is duplicated *effects* — a wasted model call, or at-least-once tool execution.
The latter is already the standing contract: a tool that cannot be safely re-run makes itself
idempotent.

**The deployment layer is partitioned ownership.** Observations for an id arrive on a partition
keyed by that id, and the broker guarantees one active consumer per key — Kafka partitions,
RabbitMQ single-active-consumer behind a consistent-hash exchange. This removes the duplication and
costs **zero code in the core**, because the broker does it.

**Rejected: durable leases.** An ownership record with expiry, renewal, and fencing tokens is a
lock manager with clock dependence and its own bug class. Not worth it when §6's floor is already
correct.

**Rejected: durable drive phase.** Writing every transition before dispatching effects doubles
store traffic and makes two nodes rehydrating one drive a real scenario.

### 6.1 Crash mid-drive

The log is intact and consistent; appends are atomic and versioned. What is lost is an in-flight
effect. The scope resumes on the next event — the next tick, or the next thing a user says, at
which point the log is whole.

The gap is a scope that crashes mid-drive and then receives nothing. A recovery sweep — find scopes
with outstanding calls and no progress past a threshold, re-drive — is a **bolt-on, not core**. It
is named here so its absence is a decision.

## 7. Hosts

One core, two hosts, as **distinct public types**. Their APIs genuinely differ — one returns a
stream, one runs forever — and a single type would have half its methods unusable in each mode.

They are hosts over one agent, **not two agent types**. The deciding case is the agent that needs
both: a support agent a human chats with *and* that reacts to a ticket webhook. Two hosts over one
id handle that as a matter of course; two agent types cannot express it.

**Interactive host.** Resolves the instance for the id, feeds the observation, streams the drive
through the observer, releases. Contention is effectively zero — a human does not race themselves —
so the optimistic floor's retry path stays cold, and sticky session routing gives partition-like
exclusivity for free.

The SSE emitter is registered with the **scope's listener registry, not held by the instance**. The
instance is transient and dies between drives; the emitter outlives the request. Held by the
instance, the stream goes silent exactly when the user is still watching.

**Autonomous host.** A long-running partitioned consumer. Drains, drives, nobody waits.

**The synchronous adapter.** Fire-and-forget everywhere is a real ergonomic loss for tests, CLIs,
and embedding. So: a `CompletableFuture` completed by an observer when the scope reaches idle. Not
a second code path — one observer, one reducer, a convenience wrapper. This is also the answer to
"observers are hard to test": the observer is an interface with a recording implementation, and the
adapter turns "the drive finished" into a value you can await deterministically.

**"Which reply is mine" is not solved and does not need to be.** The observer is scope-scoped, so a
second participant sees the first's traffic — correct, because it *is* the shared session.
Correlation is deferred until traffic justifies it.

## 8. Observers and observability

**Observers narrate; they never influence.** A listener that can affect the flow creates
inter-listener ordering dependence and a shadow decision surface competing with the authorization
ladder. Behavior injection has designated seams and all of them are interfaces already: `Reducer`,
`Backlog`, `ObservationRenderer`, both executors, and the grants.

**Multiple observers is normal, not speculative.** §7 alone requires two at once: the synchronous
adapter and the SSE emitter, on the same scope, during the same drive.

What an observer sees is exactly the reducer's input and output — **`(AgentId, AgentEvent,
List<Effect>)`** — stamped with the id at emission. That is a complete, replayable narration of
every decision the framework makes, and it is trivially recordable in a test.

**Observability splits three ways.** Call-level spans go in **decorators around the executors** —
they are interfaces, and only a decorator is on the call stack where context propagation is
possible. Turn-level scope goes in **the shell**, because only the shell knows where a drive begins
and ends. Metrics and trajectory ride **the observer**, since counters genuinely are after-the-fact
narration. Nothing goes in the reducer.

The metrics roster itself belongs to its own design. This spec declares the seams and stops.

## 9. What is deleted

| Deleted                                  | Because                                                      |
| ---------------------------------------- | ------------------------------------------------------------ |
| `Conversation<I>`                        | held an id and delegated; the instance is the scope           |
| `ConversationId`                         | → `AgentId`                                                   |
| `ConversationEvent`                      | → `AgentEvent`, with the id removed from every record         |
| `ConversationStore` + `Transcript`       | one event log (§5); they stored history twice                 |
| `ConversationStatus`                     | `idle` is `outstanding.isEmpty()`                             |
| `ConversationLoop` (blocking)            | → pure `Reducer` + shell                                      |
| `ParkToken` / park state / `ParkedCall`  | an address book, private to the tool executor (§4.3)          |
| `approve`/`deny`/`resume`/`peek`/`progress` on the agent | tool-executor API, not core surface           |
| `RunOutcome`                             | → the synchronous adapter (§7)                                |
| `SubagentLinks`, `CallbackRouter`        | a subagent is a slow tool (§4.4)                              |
| `Harness` as mandatory root              | demoted: collaborator holder plus an optional default resolver |
| `InputRenderer<I>`                       | → `ObservationRenderer<O>`, applied at poll time              |
| `Memory` in the core                     | behind `ModelCallExecutor` (§4.1)                             |
| `Memory.remember`                        | the log is the transcript; memory only projects (§5.1)        |

`Harness` shrinks rather than dies: something must derive scoped collaborators from process-wide
ones, and nessy-core must work without a DI container. But it stops being the required entry point
— Spring scopes a bean, hand-wiring constructs an agent directly, and the default resolver is
batteries, not law.

## 10. Open questions

1. **Recovery sweep** (§6.1) — deferred as a bolt-on. Confirm before implementation, not after.
2. **Duplicate summarisation across nodes** (§4.1) — claim-write, or accept the waste.
3. **Staleness policy** (§4.1) — how far past budget projection degrades rather than returning an
   oversized context.
4. **Definition name vs `AgentId`** — an instance is a definition bound to an id. Subagent stamping
   and routing want the definition's name. Confirm they stay two things.
5. **Migration or replacement** — §9 deletes most of the public API and both stores. Whether this
   ships as a major version or a parallel package is a release decision this spec does not make.
