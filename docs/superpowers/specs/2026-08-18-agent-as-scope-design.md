# Agent as Scope — phases, a thin shell, and memory that owns history

**Date:** 2026-08-18
**Status:** Design of record (accepted; §9 executed by the distillation plan, 2026-08-20). Supersedes the `Agent`/`Conversation` split, the blocking
drive, and the park-as-state model in `2026-08-09-nessy-agent-harness-design-v2.md`.

This design is not constrained by the current implementation. Where the existing code is in the
way, it goes.

> **Revision note.** The first draft of this document (commit `84ee530f`) was built around a pure
> `Reducer` over an event log, with `AgentMemory` demoted to a read-only projection. That framing was
> wrong in several places and is retracted here. §10 records every reversal, including the ones
> where this document previously argued the opposite case on merit.

## 0. Thesis

There is no such thing as a conversation. There is an **agent bound to an identity**, and its
state is a small, explicit phase.

Three rules carry the design:

> **1. Decisions are pure.** Deciding what happens next touches nothing outside its arguments.
>
> **2. History belongs to `AgentMemory`.** The core never owns, stores, or reconstructs what was said.
>
> **3. The instance is the scope.** No core-facing method takes an `AgentId`.

Hold those three and the design collapses to a sealed phase machine, one shell, and two executors.

**What is deliberately *not* a rule:** event sourcing. State is not a fold over a durable log, the
core keeps no transcript, and nothing replays. An earlier draft made purity a doctrine and derived
an event log, a mandatory transcript, and a read-only `AgentMemory` from it. Those consequences were
worse than the property they protected (§10.1).

## 1. The model

### 1.1 Identity, binding, host

An agent instance is an **`AgentType`** — the recipe: system prompt, tools, model, wiring — bound
to an **`AgentId`**. The id is the scope: memory, state, and backlog are scoped by it, and nothing
in the core ever takes it as a parameter — the instance *is* the scope (§3.5).

**The type is code; the id is data** (ruled 2026-08-20, closing §11's question). The recipe lives
in the deployment artifact — every node running the same build can rebuild the instance; the id
lives in stores and durable addresses. "Attach an agent to an id from anywhere" works because any
node already has one half and is handed the other. Consequently **every durable address carries
the pair `(type, id)`, never the id alone** — a desk entry or a sweep row naming only `tenant-42`
cannot be rebuilt in a process hosting three types. The builders (§7.1) make the pair cheap: each
built host is one type's front door with type-fixed factories, so `bind(id)` inside a host is
unambiguous, and a small type registry (name → host) routes desk deliveries and subagent
resolution across types.

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
| **Per-request id** | web chat         | per-tenant monitor   |

The fourth cell is not filler — a per-tenant monitor is one scope per tenant with nobody waiting.
That it falls out for free is evidence the axes are right.

### 1.2 Why "conversation" goes

`Conversation` held an id and delegated every call. It earned nothing as a type, and it forced
every scope to be conceptually a chat session — which the autonomous agent is not. That mismatch
is where the current model turns clunky.

The word does not survive anywhere: not in the type names, not in the event grammar, not in the
store. `ConversationId`, `ConversationEvent`, `ConversationStore`, `ConversationStatus`,
`ConversationSnapshot`, and `ConversationLoop` are all deleted or renamed (§9).

## 2. The core

Four small types and one method. No I/O, no collaborators, no clock, no randomness.

### 2.1 `AgentEvent` — facts, past tense

```java
public sealed interface AgentEvent {
  record Observed(List<ContentBlock> content)             implements AgentEvent {}
  record ModelFinished(ModelOutcome outcome)              implements AgentEvent {}
  record ToolFinished(ToolCall call, ToolOutcome outcome) implements AgentEvent {}
}

public sealed interface ModelOutcome {
  record Responded(List<ContentBlock> content, List<ToolCall> calls) implements ModelOutcome {}
  record Failed(String reason)                                       implements ModelOutcome {}
}

public sealed interface ToolOutcome {
  record Returned(ToolResult result) implements ToolOutcome {}
  record Failed(ToolError error)     implements ToolOutcome {}
}
```

**No id on any event.** The instance is the scope; an event does not need to say which scope it
belongs to when there is only one it could belong to. Outbound narration is stamped with the id at
emission (§8), which is the only place it is genuinely needed.

**Invariant: every effect has exactly one completion event.** `CallModel` completes as
`ModelFinished`; `ExecuteTool` completes as `ToolFinished`. `Observed` is the sole inbound fact,
because the world is not answering anything. Success and failure are a *sealed outcome inside* the
completion, never separate events.

This is a reversal (§10.4). An earlier draft had `ModelResponded`/`ModelFailed` as two events and
`ToolFinished` as one, defended on the grounds that tool failure is in-band and model failure is
out-of-band. That difference is real, but it is a fact about **how a phase handles an outcome**,
not about **what happened**. The grammar describes events; phases assign meaning. Keeping the
policy out of the grammar also makes §2.4's ceiling honest: a third effect now visibly costs a
third completion event.

### 2.2 `Phase` — state that carries its own data

```java
public sealed interface Phase {
  record Idle()          implements Phase {}
  record AwaitingModel() implements Phase {}
  record AwaitingTools(Message assistantTurn,
                       Set<ToolCallId> pending,
                       List<Message> gathered) implements Phase {}

  Transition handle(AgentEvent event);
}
```

An earlier draft asserted that status is derived, not tracked — `idle` was
`outstanding.isEmpty()`, and there was no phase type at all (§10.3). That was wrong on a plain
fact: **a model call in flight puts nothing in `outstanding`**, so "awaiting model" and "idle" are
indistinguishable under that scheme. The shell's idle test then admits a second observation while
a model call is outstanding, and a burst of ten observations fires ten concurrent model calls on
one scope.

The sealed form is stronger than a status enum beside a data field, and it answers the original
objection rather than accepting it: the earlier fear was a status drifting out of agreement with
the outstanding set, and here **there is no second field to disagree with**. Pending calls exist
only inside `AwaitingTools`. "Idle with outstanding calls" is not a bug to review for; it is
unrepresentable.

Dispatch is phase-first, which makes the phase × event matrix explicit. Cells that cannot legally
occur are stated rather than implied:

- A completion arriving in a phase that did not request it is **stale** — discarded, narrated, not
  folded. Under §6 two nodes may both emit `CallModel`, so duplicate completions are expected
  traffic, not a hypothetical.
- `Observed` cannot reach any phase but `Idle`, because lane 2 polls the backlog only when idle
  (§3.3). Reaching one is a programming error, not a runtime condition.

**Chosen axis.** Phase-first dispatch scatters a single event's handling across phases: "what
happens on `ToolFinished`" means reading three cases rather than one. That is the expression
problem, and the axis is chosen deliberately — the event grammar is a designed ceiling (§2.4)
while phases plausibly grow. Were events the growing axis, event-first dispatch would be right.

### 2.3 `State`

```java
public record State(Phase phase, long version) {}
```

`version` is the optimistic-concurrency token (§6). It belongs to the state, not to the phase, and
`handle` never sees it — the shell owns it.

**Sizing.** `State` is not uniformly small: `AwaitingTools` carries one turn's assistant message
and its gathered results until the turn completes (§2.5). It is bounded by a **single turn**,
never by history. `AgentStateStore` therefore needs a payload column (JSON or blob), not two
scalars.

### 2.4 `Effect` — commands, imperative

```java
public sealed interface Effect {
  record CallModel()              implements Effect {}
  record ExecuteTool(ToolCall call) implements Effect {}
}
```

`CallModel` is a **bare marker with no payload**, and the reason is no longer purity for its own
sake: the executor asks `AgentMemory` for the context itself (§4.1), so there is nothing to carry.
Putting an assembled `Context` in the effect would create a second path by which content reaches
the model — one through memory, one riding the effect, free to disagree.

**Two variants is the designed ceiling, not today's count.** There was once a third,
`Effect.Compact`, shipped and tested; it was absorbed into memory during the context-pipeline
rework. That is the pattern: anything a single collaborator needs is solved inside that
collaborator's facade, never by widening the grammar.

**Rejected: a `Remember` effect.** Committing to history is not an effect and must not join the
grammar. Every effect is an *asynchronous request whose result returns as an event*; committing is
synchronous, produces no event, and must **succeed before** any effect is dispatched. Modelling it
as a list element would turn that ordering requirement into an implicit positional rule — the exact
bug class this design keeps generating (§10.8) — and would force a `RememberFailed` event into a
frozen grammar. A failed commit should abort the transition, which is what an exception does for
free.

### 2.5 `Transition` — what to become, what to commit, what to fire

```java
public record Transition(Phase next, List<Message> commit, List<Effect> effects) {
  public static Transition to(Phase next, Effect... effects) { … }
  public static Transition ignore(AgentEvent stale)          { … }
  public Transition commit(Message... messages)              { … }
}
```

Three components, because a transition genuinely decides three things. Returning them as a value —
rather than emitting through a context object handed to `handle` — keeps I/O structurally
impossible rather than merely absent: a method given only values has nothing to call. A context
parameter degrades that guarantee to "nobody has added the wrong method yet," and every serious
defect in this design has come from something acquiring a second path to do its job.

**The unit of history is a completed exchange, not an event.** A model turn carrying tool calls and
the results answering it form one indivisible unit: the provider requires the `tool_use` turn to be
followed immediately by a turn carrying **all** corresponding results. Committing the assistant
turn when it arrives would leave memory holding a dangling `tool_use` turn, and any `recall()` in
that window returns a conversation the provider rejects.

So `AwaitingTools` **holds the assistant turn back** and accumulates results, committing both
together only when `pending` empties:

```java
record AwaitingTools(Message assistantTurn, Set<ToolCallId> pending, List<Message> gathered)
    implements Phase {

  public Transition handle(AgentEvent event) {
    return switch (event) {

      case ToolFinished(var call, var outcome) -> {
        if (!pending.contains(call.id())) yield Transition.ignore(event);  // duplicate or stale
        var left = minus(pending, call.id());
        var all  = plus(gathered, Message.toolResult(call, outcome));      // failure renders in-band
        yield left.isEmpty()
            ? Transition.to(new AwaitingModel(), new CallModel())
                        .commit(assistantTurn, Message.toolResults(all))
            : Transition.to(new AwaitingTools(assistantTurn, left, all));
      }

      case ModelFinished ignored -> Transition.ignore(event);              // stale duplicate turn
      case Observed ignored -> throw new IllegalStateException("lane 2 polls only when Idle");
    };
  }
}
```

Two details that are load-bearing:

- **Correlate by `ToolCallId`, never by `equals` on the whole call.** Identical inputs to the same
  tool would otherwise collide. Keying by id additionally makes **at-least-once delivery idempotent
  for free**: a redelivered completion finds its id already gone and is ignored. §6 makes duplicate
  tool delivery the standing contract, so this is required, not defensive.
- **Accumulating in the phase does not cost incremental durability**, because the phase *is*
  persisted state (§5.2). `store.save` runs on every completion, so `gathered` is durable as it
  grows; a crash mid-fan-out resumes with results intact and re-runs only what had not returned.
- **Provider continuity tokens ride the content blocks, never `ToolCall`.** `ToolUseBlock(call,
  signature)` and `ThinkingBlock` carry opaque provider-issued signatures (Gemini's thought
  signatures; Anthropic's thinking signatures) that must be replayed verbatim on the next request or
  the provider rejects the history. They survive here because the held-back assistant turn is built
  **from the response's content blocks** — `Message.assistant(content, calls)` takes the blocks as
  delivered, signatures included — and `AgentMemory` replays them through `recall()`. An implementation
  that reconstructs tool-use blocks from the bare `calls` list instead of taking them from `content`
  silently drops every signature and breaks Gemini on the following turn. `ToolCall` itself stays
  signature-free: executors execute tools; only replay-to-model needs continuity, and that is a
  history concern (§5.1).

The remaining phases:

```java
record Idle() implements Phase {
  public Transition handle(AgentEvent event) {
    return switch (event) {
      case Observed(var content) ->
          Transition.to(new AwaitingModel(), new CallModel())
                    .commit(Message.user(content));
      case ModelFinished ignored, ToolFinished ignored -> Transition.ignore(event);
    };
  }
}

record AwaitingModel() implements Phase {
  public Transition handle(AgentEvent event) {
    return switch (event) {
      case ModelFinished(Responded(var content, var calls)) when calls.isEmpty() ->
          Transition.to(new Idle()).commit(Message.assistant(content));

      case ModelFinished(Responded(var content, var calls)) ->
          Transition.to(new AwaitingTools(Message.assistant(content, calls), ids(calls), List.of()))
                    .emit(calls.stream().map(ExecuteTool::new).toList());

      case ModelFinished(ModelOutcome.Failed ignored) ->
          Transition.to(new Idle());          // out-of-band: nothing to commit; observer only

      case ToolFinished ignored, Observed ignored -> Transition.ignore(event);
    };
  }
}
```

Testing is value comparison, with no mocks, fakes, or stand-ins:

```java
assertThat(new AwaitingTools(turn, Set.of(a, b), List.of()).handle(finished(a)))
    .isEqualTo(Transition.to(new AwaitingTools(turn, Set.of(b), List.of(resultOf(a)))));
```

## 3. The shell

```java
public interface Agent<O> {
  void observe(O observation);     // → backlog, then drain.
  void drive();                    // the drain invariant as a method.
}
```

Two public methods — and the continuation door is deliberately not one of them:

- **Observations** (`observe`) — ambient world facts. They go to the backlog and are absorbed only
  at an idle boundary.
- **`drive()`** — drain: while the phase is `Idle` and the backlog yields an observation, absorb
  one. `observe` ends by calling it, and the recovery sweep (§6.1) uses it on stalled scopes.
- **Continuations** arrive through the `Sink` the shell hands each executor **at dispatch** — a
  reference to the implementation's private apply method, alive for exactly one dispatch. An
  executor reports back on its own thread and the event applies immediately; serialization is the
  store's version CAS (§3.2), not a queue.

Keeping the sink off the interface is the same ruling as the park token (§4.3): a delivery door is
a **capability**, handed point-to-point to the party that needs it, never published on a surface
everyone sees. An application cannot fabricate a `ToolFinished`, because the method to deliver one
is not expressible in its vocabulary.

The lane priority costs no machinery: a continuation applies the moment it arrives, and an
observation cannot be absorbed at all until the phase returns to `Idle` — the priority is a
consequence of the phase grammar, not of queue discipline.

### 3.1 The drain invariant

> **Whenever the agent is idle and the backlog is non-empty, poll one observation, absorb it, and
> drive.**

One invariant, not a list of triggers. The events that can make it true — an observation arriving,
a turn ending, a recovery sweep — are consequences. Stated as an invariant, a new trigger cannot be
forgotten.

### 3.2 Concurrency — the store is the lock

**There is no concurrency machinery in the shell.** No claim flag, no continuation queue, no
scheduled loop. Two rules replace all of it:

> **Executors are asynchronous, always.** The `Sink` is never invoked on the stack that dispatched
> the effect; delivery arrives on the executor's own thread — a virtual thread wrapping a blocking
> HTTP call is the normal shape. This holds in every deployment, the CLI included: there is no
> synchronous wiring mode.

> **Serialization is the version CAS, everywhere.** Concurrent applies — sibling tool results in a
> fan-out, or two nodes driving one scope — are resolved by `save` at an expected version. The
> loser wastes one in-memory `handle`, reloads, and re-handles. Intra-process contention and
> multi-node contention are the same case handled by the same mechanism (§6).

The fan-out race, walked: `pending = {b, c}`, both results land at once. Each handles from the
loaded state and computes a non-empty remainder, so **neither commits anything to memory** — the
unit is held back (§2.5). One save wins; the loser reloads, sees the true remainder, and re-handles
— now correctly last, committing the unit. The held-back commit is what makes the retry clean.

**Every `AgentStateStore` enforces versioning — the in-memory one included.** A store may not
assume single-threaded callers; the CAS is the concurrency model, so a store that skips it in the
name of simplicity removes the lock. The in-memory store is a `compareAndExchange` on a reference,
which is exactly as hard as it sounds.

**Why the agent carries no state field.** `apply` loads, handles, and saves; the store is the only
authority. A cached field shared across callback threads would need its own synchronization and
could regress under racing writes — the load is cheap (in-memory or one indexed row) and buys the
whole problem away. This is also the transient-instance model being honest: an instance that dies
between requests never trusted a field anyway.

**What died here, three times.** This section previously held (1) a CAS claim with a dirty-flag
protocol, (2) a `synchronized` drive with a `working` boolean, (3) a scheduled trampoline on an
injected executor. Each was retired in turn (§10.2). The trampoline's last justification was
flattening *synchronous* executor re-entry — so the fix was to stop supporting synchronous
executors, not to keep machinery whose only client was a wiring style nobody needed.

**Tests** use a pumped executor: tasks queue, the test pumps until quiet. Deterministic, mock-free,
and it exercises the real asynchronous contract instead of a same-thread special case that no
longer exists.

### 3.3 The drain loop

```java
@Override public void observe(O observation) { backlog.add(observation); drive(); }

@Override public void drive() {
  while (store.load().phase() instanceof Idle) {
    Optional<O> next = backlog.poll();
    if (next.isEmpty()) return;
    next.flatMap(this::render).ifPresent(this::apply);
  }
}
```

**The backlog is never bypassed.** `observe` only ever calls `add`; `poll` happens exclusively at
the idle boundary above. The round trip looks redundant when the backlog is empty and is
load-bearing when it is not: bypassing would jump the new observation ahead of queued work, would
deny the backlog its chance to coalesce, and would create a second path by which observations reach
a phase. With a coalescing policy, `add` followed by `poll` may legitimately return something other
than what was just added — that is the policy working correctly.

**A lost race returns the observation to the backlog.** Two threads can pass the idle check
together; both poll, both apply, one save loses. The loser's observation is re-added — not dropped,
not forced — where the coalescing policy decides its fate again. This is also why `Observed`
reaching a non-`Idle` phase is a shell bug, not a legal race: the conflict is caught at `save` and
resolved by re-adding, never by handing a non-idle phase an observation.

### 3.4 Applying one event

```java
private void apply(AgentEvent event) {
  while (true) {
    State state = store.load();
    Transition t = state.phase().handle(event);        // pure — classify before committing
    if (t.isIgnored()) { observer.ignored(event); return; }
    t.commit().forEach(memory::remember);              // whole units only, in order
    try {
      store.save(new State(t.next(), state.version()));   // CAS on the version we loaded — the store bumps
    } catch (StaleStateException e) {
      continue;                                        // someone else advanced — re-handle
    }
    observer.observed(event, t.effects());
    t.effects().forEach(this::dispatch);
    return;
  }
}
```

**The `save` contract, exactly:** the caller passes the state it *loaded*; the store persists it at
`version + 1` if and only if the stored version still equals `state.version()`, else throws. The
caller never computes the next version — two implementers reading "throws on conflict" will
otherwise invent two off-by-one conventions.

```java
private void dispatch(Effect effect) {
  // the sink rides the dispatch: handed by the one party that provably holds its target
  switch (effect) {
    case CallModel ignored          -> model.callModel(this::deliver);
    case ExecuteTool(ToolCall call) -> tools.executeTool(call, this::deliver);
  }
}
```

Two orderings here are invariants, not step numbers:

> **Decide before committing.** `handle` runs first so a duplicate or stale completion can be
> classified and dropped *before* anything is written. Committing first would write a redelivered
> tool result into history and make the phase's idempotence useless.

> **Commit before dispatch.** `ModelCallExecutor` calls `memory.recall()`, so the exchange must be
> in memory before the effect goes out. Reverse them and the model does not see what provoked the
> call.

A retry after a lost save re-runs `handle` against the fresh state; a transition that committed and
then lost can write its messages twice. That is the same benign-duplicate class as the crash window
(§5.2), and it is rare by construction — the common conflict, sibling fan-out results, commits
nothing until the last one (§3.2).

### 3.5 Everything is pre-scoped

No core-facing seam takes an `AgentId`:

```java
public interface Memory {
  void remember(Message message);        // a no-op is legal — discard-most memories exist
  Context recall();                      // hydrate, summarise, trim, budget
}

public interface AgentStateStore {
  State load();
  void save(State state);                // throws on version conflict
}

public interface Backlog<O> {
  void add(O observation);
  Optional<O> poll();
}
```

Binding is what construction *does*. Cross-scope access stops being a bug caught in review and
becomes unrepresentable — for a per-tenant monitor that is an isolation boundary obtained from the
type system.

**`TurnObserver` is a construction collaborator, not a registration.** The instance is transient;
every delivery constructs a fresh one, so the factory that binds the scope also supplies its
observer — the sync adapter's waiter, the live SSE emitters for the scope, the audit subscriber —
composed with `TurnObserver.composite(...)`. There is no listener registry in the core: "find the
observers for this scope" is part of binding, exactly like finding the scoped `AgentMemory`. And
"register before observing" stops being a rule to remember — construction precedes `observe`,
structurally.

Two consequences that must be written down or they will be got wrong:

- **Each seam gains a factory.** Something must derive the scoped collaborator from a process-wide
  one — `MemoryFactory { Memory bind(AgentId id); }` and its siblings. This is the whole remaining
  job of `Harness` (§9). Integrators implement the factory; Spring users get it as a scoped bean.
- **Scoped facades must be stateless views.** Instances are transient. A scoped `AgentMemory` that
  caches a summary in a field loses it when the instance dies, and a high-frequency observation
  stream thrashes it. All caches and pools live in the process-wide factory; anything that must
  survive instances or nodes — the summarisation claim (§4.1), the park desk (§4.3) — lives in a
  store. A scope-keyed in-memory map of live state is a second lock vocabulary and is rejected
  wherever it appears (§3.2, §4.1, §7).

### 3.6 `Backlog`

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

### 3.7 `ObservationRenderer<O>`

Lives on the shell, applies at **poll time, not add time** — rendering on `add` would destroy the
coalescing key, and a backlog cannot keep-last-per-symbol if it can no longer see the symbol. It is
not on the backlog because storage and presentation are different axes; putting it there would
force every backlog implementation to carry it and stop two agents from rendering the same tick
differently.

`O` therefore exists for exactly two reasons — the backlog polled and the renderer applied. Nothing
in the core ever sees it.

**A renderer that throws discards that observation, narrates the failure to the observer, and
leaves the agent idle to continue draining.** One malformed observation must not wedge a scope, and
in the autonomous host there is no caller present to see a propagated exception.

## 4. Executors — where every side effect lives

```java
public interface ModelCallExecutor { void callModel(Sink sink); }
public interface ToolCallExecutor  { void executeTool(ToolCall call, Sink sink); }

@FunctionalInterface
public interface Sink { void deliver(AgentEvent event); }   // handed per dispatch, lives one dispatch
```

Both are pre-scoped (§3.5) and asynchronous **by contract, not by convention**: they return
immediately, and the `Sink` is never invoked on the stack that dispatched the effect (§3.2). An
executor wrapping a blocking client hands the call to a virtual thread and delivers from there.
The shell's thread never blocks on a model or a tool, and delivery never recurses into the shell.

**The sink is a dispatch parameter, not a constructor collaborator** (ruled 2026-08-20, reversing
§10.8). A sink is handed only by a party that **provably holds its target**: the shell at dispatch
(same-turn completions), the binder at re-bind (late completions, §4.3). Both are moments after
the agent exists, so no holder, no late binding, and no construction cycle can occur — the
capability's lifetime is exactly one dispatch, which is the address-lifetime rule below applied to
the sink itself.

An executor that must deliver days later persists whatever address it needs internally; the id is
not a parameter on the way in.

**A continuation returns to an address with the same lifetime as itself.** Sub-turn completions —
a model call resolving in seconds, a tool in milliseconds — return to the `Sink`, which is not a
registration but a reachability fact: the in-flight completion's closure over the sink is what
keeps the turn's instance alive, so it cannot dangle. Anything that can outlive the turn — a park, a crash, a sweep — was
never given the `Sink` as its address; it holds a durable one (a desk entry, the phase row) and
re-enters by bind on whatever node it lands on (§4.3), where the fresh instance loads the same
phase from the same store and scope-constant observers are the only correct ones, the original
turn's stream being long closed. Two mechanisms, one rule — and no instance is ever found, because
none is ever lost.

### 4.1 `ModelCallExecutor` owns memory

The executor calls `memory.recall()`, gets a `Context`, calls the provider, and delivers
`ModelFinished`. `AgentMemory` does hydration, summaries, trimming, and budget.

**`recall` must be cheap.** For summarising projection the hot path is: look for a summary record;
if present use it plus the tail after it, else use everything. No model call, ever, on the path
that answers a projection.

**Summarisation runs asynchronously, owned by the projector.** When it notices the tail has passed
threshold it submits a task and returns with what it has. Three requirements, all internal:

- **An injected `Executor`, never a spawned thread.** Tests pass a same-thread executor and the
  whole thing is deterministic — no mocks, no sleeps.
- **Dedup is a claim-write in the summary store, never an in-memory tracker.** The projector
  claims `(id, watermark)` before submitting; one winner, store-enforced, on any node. The task
  writes the summary idempotently keyed the same way and clears the claim; a claim older than a
  threshold is claimable again, so a crashed claimer costs a redo, not a wedge. An in-memory
  in-flight map was rejected as a second lock vocabulary (§3.2 — the store is the lock) with a
  named failure mode: a leaked entry silently stops a scope summarising forever. The claim's
  expiry is not the clock-dependent lease §6 rejects — that ban protects correctness-critical
  ownership, and this claim guards **derived** data (§5.1): an expired claim redone twice wastes
  CPU and cannot corrupt anything.
- **Emission to the observer.** Started, finished, failed are ordinary narration.

One accepted cost, chosen rather than discovered: the context is **deliberately stale** until the
summary lands, so the projector owns a policy for how far past budget it will go before degrading.

### 4.2 `ToolCallExecutor` owns the gate and the address book

Authorization, tool dispatch, and delivery. Nothing about it reaches the core.

**The policy seam always receives an `AuthzContext`** (ruled 2026-08-20). The authorization
ladder's existing contract — `UsagePolicy.evaluate(AuthzContext, effect)` — carries forward
unchanged in shape: context is a required parameter on every policy evaluation, even for policies
that ignore it, because adding a parameter later breaks every implementor while an unused one
costs nothing. `AuthzContext`'s extensibility model is the future-proofing and is preserved
verbatim: an immutable typed-key bag (`get(Key<T>)`, `with(key, value)`) that **enrichers**
populate — principal, declared intent, and whatever tomorrow needs. A new authorization fact is a
new key, never a new method or parameter.

Two members adapt under §9's deletions: `conversationId()` becomes the scope coordinate
`(AgentType, AgentId)` (§1.1), and `state()` — the old control block — goes, because pre-scoped
executors never load `State`; a policy that needs turn context obtains it through an enricher,
which is where cross-cutting context acquisition already lives. The gate remains the single place
authorization happens (§8: observers never influence), and none of this reaches the core: the
grammar has no authorization vocabulary, by design.

### 4.3 Parks are not a state — they are an address book

**From a phase's view there is no difference between a tool that returns in 200ms and one that
returns in three days.** Both are a pending call that will produce a `ToolFinished`.

So there is no parked phase, no `ParkToken` in the grammar, and no `resume`/`approve`/`deny`/`peek`/
`progress` on the agent. `Parks` demotes to what it always was: a durable map from token to
`(AgentId, ToolCall)`, private to `ToolCallExecutor`. A token is a delivery address, not a
lifecycle.

Human-in-the-loop stops being special. The gate parks, a human answers, a `ToolFinished` arrives —
the same path as a slow HTTP call. The approve/deny doors become an ordinary API on the tool
executor, not core surface.

**Parking is a capability of the wiring, not a right of every deployment** — named as a
`CompletionPolicy` hierarchy (`IMMEDIATE ⊂ AWAITABLE ⊂ DURABLE`) by the companion spec
`2026-08-20-durable-computation.md`, whose reconciliation preamble binds: interactive wirings are
`AWAITABLE`, the autonomous host is `DURABLE`, and the park desk is the SQL reference
implementation of that spec's durable-computation backend — a computation whose *work* is
node-agnostic, not merely its wait. **Filtering precedes failing** (adopted from its §14): a tool
that declares it requires `DURABLE` completion is not exposed to the model at all in an
`AWAITABLE` wiring — the registry filters `specs()` by the wiring's policy — and the loud in-band
failure below remains as the backstop for tools that under-declare. The interactive host
binds a **non-parking** tool executor: approvals go through a *rendezvous* handler — block the
virtual thread on a future the human completes (a click in web, an inline `approve? [y/N]` prompt
in a CLI), bounded by a timeout. A tool that attempts to park anyway fails **loudly and in-band**
— `ToolOutcome.Failed` delivered immediately — so the model explains itself and the turn completes.
The reason is not taste: observations are absorbed only at `Idle` (§3.3), so a parked turn in a
conversation **wedges the conversation** — the human types into a backlog that never drains. The
park desk belongs where nobody waits; the autonomous host's wiring carries it, and the same agent
definition runs in both. The cost, stated: rendezvous approval is **node-sticky** — the approve
click must reach the node holding the blocked virtual thread. Sticky sessions provide that, and
the rendezvous timeout bounds the miss; a deployment that cannot route stickily should not choose
rendezvous wiring. A node dying mid-rendezvous loses only the **question**: nothing durable exists
(no desk entry, no token), the pre-dispatch save left the phase as the recovery anchor, and the
user's retry re-fires the call — the gate simply asks again. A stale approve click for the dead
prompt is refused loudly. Being re-askable is rendezvous's licensed failure mode — the human is
present to re-answer — and it is the true dividing line from the desk, which never re-asks because
its audience may be gone. Approval does not weaken at-least-once: an approved tool that executed
before the crash may execute again on re-approval, and makes itself idempotent like every other
tool (§6).

**Parks expire as desk metadata, not as machinery.** Each entry carries `expires_at`, set at park
time from configuration or a per-tool hint. An expired park resolves as the tool *failing slowly*:
the desk delivers `ToolFinished(call, Failed(expired))` through the ordinary out-of-band bind, the
model reads it in-band and reacts, the turn completes. Expiry is evaluated **lazily at re-drive**
(§6.1): the sweep re-fires a pending call and the desk answers from its entry — still valid →
idempotent no-op; expired → deliver the failure and close; absent → execute. A prompt-expiry scan
(`WHERE expires_at < now`) is an optimization knob, not a correctness need. The approve-vs-expire
race settles at the desk in one conditional status flip — `parked → approved` or `parked →
expired`, first writer wins; an expired token is refused loudly at the approval door. Model calls
need none of this: they never park, and the provider client's own timeout already delivers
`ModelFinished(Failed)`.

**Out-of-band delivery is another bind.** The `Sink` is a capability of an *instance* and dies with
it; what survives is the **address** in the desk. A result arriving days later, on any node,
resolves its token to `(AgentId, ToolCall)` and then enters exactly the way every trigger enters:
the binder constructs a fresh instance for the id — collaborators and observers wired at
construction (§3.5) — and, now provably holding the instance, hands the desk its door for this one
delivery: the same point-to-point sink handoff as dispatch, performed at bind time (§4). Nothing
persists a callback; the durable thing is an address, and address → live `Sink` is instance
construction plus the binder's handoff. The version CAS covers two triggers binding concurrently, and the
subagent's parent callback (§4.4) rides the same path.

**The token is delivered, never broadcast.** A park token is a *capability* — whoever holds it can
approve or deny the call — so it moves point-to-point, not over the observer stream. When the
ladder answers `RequireApproval`, the executor parks and hands the token to a **configured
handler** on the tool executor — the seam that emails the approver, posts to Slack, or stores it
for an approval UI. One recipient, chosen by the application at configuration time — and the
**only** party told at all: parking is not narrated (§8). Fan-out narration of tokens would put
live capabilities in front of every observer — including `logging()`, which would write them into
application logs.

**Amendment (2026-08-20, second wave — approval is a fact in the backend).** The paragraphs
above predate the durable-computation reconciliation; where they speak of tokens and a token map,
the companion spec's rulings govern: the address is the deterministic `ComputationId`, and there
is no token. This amendment binds the approval mechanics onto the primitive:

- **Two slots per call, by kind.** One tool call can pose two durable questions over its lifetime,
  and each gets its own deterministic address: `approval:{agentType}:{agentId}:{callId}` ("may it
  run?") and `tool:{agentType}:{agentId}:{callId}` ("what did it return?"). The kind prefix is
  part of the address, so approving a call and then having the tool itself defer never collide.
- **The gate resolves `RequireApproval` against the slot.** The policy stays pure and speaks the
  same verdict on every evaluation (§4.2); the *decision* is adjudication, not judgment, and it
  lives in the backend — the slot is the fact, and the backend is the state. On
  `RequireApproval` the gate consults the approval slot: absent → create, register a re-drive
  continuation, hand the approval request to the configured handler (one recipient, point-to-point
  — the paragraph above already rules this; it now carries the slot id as the capability), and
  suspend (nothing delivered, nothing narrated). Pending → re-`await` (idempotent) and suspend.
  Terminal `Success(Decision.Allow)` → execute the tool in the one place tools execute. Terminal
  `Success(Decision.Deny(reason))` → deliver the denial in-band as an ordinary failed outcome so
  the model reads it and reacts. The approval slot completes with a `Decision` — answering "no"
  is a *successful* adjudication; `Failure` is reserved for the asking machinery breaking, and
  `Cancelled` for expiry when it lands.
- **Resumption is a re-drive, not a payload.** The approval continuation (`REDRIVE_SCOPE`)
  carries only the scope coordinate: on fire, the host re-dispatches the phase's outstanding
  effects unconditionally (staleness does not gate a decided approval). The re-fired call meets
  the gate again, finds the decision, and proceeds — at-least-once, same semantics as recovery.
  `RESUME_SCOPE` (a tool-result payload delivered to the scope) and `REDRIVE_SCOPE` (a poke)
  stay two continuation types because they are two intents.
- **Two desks, two intents.** `ApprovalDesk.approve(id)` / `deny(id, reason)` complete *approval*
  slots with a `Decision`; a separate completion door completes *tool* slots with a `ToolResult`
  (what the Plan-4 desk actually did). One desk answering both questions with one vocabulary was
  the conflation.
- **The `Approver` seam mirrors the deferral seam.** `RequireApproval` routes to a wiring-chosen
  `Approver` whose adjudication is `Granted`, `Refused(reason)`, or `Suspended(slot)`. The
  default, like the deferral default, refuses loudly in-band — approval is a capability of the
  wiring, not a right of every deployment. The rendezvous approver (interactive wirings, above)
  and the slot-backed approver (autonomous host) are two implementations of this one seam.

### 4.4 Subagents need no core support

A subagent is a tool that resolves a child agent, drives it, and returns its terminal message as
the tool result. Since it is slow, it parks. That is the entire integration: **subagents are a tool
that returns slowly.** No delegation concept, no link store, no callback router in the core.

## 5. Durability

**There is no event log and no core-owned transcript.** Two stores, each owning one thing:

| Store             | Owns                                   | Shape                          |
| ----------------- | -------------------------------------- | ------------------------------ |
| `AgentMemory`          | history — what was said                | implementation-defined         |
| `AgentStateStore` | control — phase and version            | one row per scope              |

### 5.1 `AgentMemory` owns history, and may discard it

`remember` is on `AgentMemory`, and **a discard-most memory writes nothing at all**. This restores a
deliberate ruling (2026-08-14) that the first draft of this document reversed without saying so
(§10.1).

`AgentMemory` is free in representation as well as retention: a verbatim table, summary-plus-tail, a
rolling window, a vector store, a hosted memory service. The core never inspects it, never
reconstructs from it, and never requires that it retain anything.

**Summaries are the one thing memory writes for itself.** They are *derived* — a pure function of a
history prefix — so duplicate writes across nodes waste CPU and cannot corrupt anything (§4.1).
Delete every summary and the system is unchanged but slower. This distinction is why §11.2 is
allowed to stay open rather than blocking.

**Audit is not `AgentMemory`'s job.** A discard-most memory is legal, so nothing durable would record
what happened — which is the tension the 08-14 notes flagged and left unresolved. The answer is
that audit was never `AgentMemory`'s consumer: the **observer stream** is (§8). Every event and effect
is already narrated and stamped with the id, so a transcription service subscribes there,
independent of the `AgentMemory` seam.

- **`AgentMemory`** — history *for the model*. Lossy at the implementer's discretion.
- **Observers** — history *for humans*: audit, chat replay, debugging. Always available, never
  imposed on `AgentMemory`.

### 5.2 `AgentStateStore` owns the phase

`load` and `save`, scoped, with `version` for optimistic concurrency. The record is one turn's worth
at most (§2.3), never history.

Versioning is enforced by **every** implementation, the in-memory store included — the CAS is the
system's only lock (§3.2), so a store without it removes the lock. Save-conflict retries can
duplicate a committed message in the same way the crash window can; both land in the same benign
class below.

**The crash window between the two stores is real and accepted.** `apply` commits to memory, then
saves state, with no transaction spanning them. A crash in between leaves an exchange remembered
whose phase did not advance; on the next drive the model may see it twice. That is benign compared
with the alternative ordering — saving first and crashing would advance the phase past an exchange
the model never sees, which is silent context loss. Stated here rather than discovered later.

One shape of that duplicate is asymmetric: a crash after committing a terminal assistant message
but before saving `Idle` leaves history ending in an assistant turn the phase does not know about;
recovery then re-fires `CallModel` and a second answer lands beside the orphan. A `Memory`
projection must tolerate consecutive assistant turns at `recall()` — merge or drop the orphan —
because some providers reject non-alternating history.

## 6. Multi-node

Multi-node is a hard requirement, so in-process residency cannot be the lock.

**The correctness floor is optimistic concurrency.** Each `save` writes at an expected version. Two
nodes may handle the same event and both emit `CallModel`; the second save fails on version
conflict, and that node reloads and re-handles. Duplicated work, never corrupted state. Plain JDBC
suffices; single-node embedding is trivial.

The residual cost is duplicated *effects* — a wasted model call, or at-least-once tool execution.
The latter is already the standing contract: a tool that cannot be safely re-run makes itself
idempotent. Duplicate *completions* are handled in the core, by phase-first dispatch discarding
stale events and by `ToolCallId` correlation (§2.5).

**The deployment layer is partitioned ownership.** Observations for an id arrive on a partition
keyed by that id, and the broker guarantees one active consumer per key — Kafka partitions,
RabbitMQ single-active-consumer behind a consistent-hash exchange. This removes the duplication and
costs **zero code in the core**, because the broker does it.

**Rejected: durable leases.** An ownership record with expiry, renewal, and fencing tokens is a
lock manager with clock dependence and its own bug class. Not worth it when the floor is already
correct.

### 6.1 Crash mid-drive — recovery is `drive()`'s second arm

State is consistent; saves are atomic and versioned. What is lost is an in-flight effect: a phase
that truthfully says `AwaitingModel` while no model call is running. Save-before-dispatch (§3.4)
guarantees the store never *overstates* progress — which is what makes recovery possible at all.

**Invariant: every phase carries enough to reconstruct its outstanding effects.** `AwaitingModel`
implies a bare `CallModel`; `AwaitingTools` re-derives one `ExecuteTool` per pending id from the
full calls held in `assistantTurn`'s tool-use blocks; `Idle` implies nothing. This constrains every
future phase, and is a second, independent reason `CallModel` stays bare — a fat effect could not
be re-derived.

**`drive()` gains the re-fire arm** and becomes, in full, "make this scope make progress":

```
Idle                           → drain the backlog (§3.3)
not Idle, saved recently       → a turn is genuinely running; do nothing
not Idle, stale past threshold → re-derive effects from the phase; re-dispatch them
```

Staleness is elapsed time since the last save — the only signal separating a dead effect from a
slow one — read from the store's `updated_at` (§2.3). The threshold is deployment configuration,
not a safety parameter: an over-eager re-fire wastes a model call or re-runs an idempotent tool,
and the duplicate completion dies as stale (§2.2) or on `ToolCallId` dedup (§2.5). It must exceed
the interactive rendezvous window (§4.3), or an approval prompt reads as dead. The desk's re-park
idempotency (§4.3) makes re-driving a parked scope harmless — no second token, no second
notification.

**When re-driving actually happens: lazily, on the next natural poke, almost everywhere.** A CLI
recovers on its next launch's first `observe` (with the default in-memory store there is nothing to
recover — the crash wiped the phase). A web scope recovers on the user's retry — a frustrated human
is a reliable poker. Only the autonomous host schedules an actual sweep — `WHERE phase <> 'IDLE'
AND updated_at < ?`, then `drive()` per row — and only for scopes stuck *and* silent, since every
queue delivery already ends in `drive()`. The sweep is a **bolt-on, not core**; the arm it calls is
core.

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

**Send and stream are separate doors.** Messages sent while a turn is busy are accepted —
enqueue-only, an immediate 204 — and the web backlog's **merge-all policy** coalesces everything
pending into one user turn, in order, timestamps intact (§3.7 renders at poll time for exactly
this). Three messages typed during a stream reach the model as one turn, so a changed mind is
*visible*; one-by-one delivery would answer stale snapshots, and keep-last would drop intent the
user never retracted. The stream door is "give me the next turn": it binds a fresh instance with a
fresh emitter and calls `drive()`. The client's loop is natural — its stream completes, it has
queued messages, it requests the next stream — so **the drain invariant's executor in web is the
client** (§3.1), the only party that knows where the human is watching.

**Streams are per-turn, not per-session.** Each inbound message opens one response stream; the
observer completes it on `TurnEnded`; the next message opens a new one. The emitter is handed
**directly at bind** — no connection registry, no scope-keyed lookup:

```
POST /chat → new SseEmitter(finiteTimeout)
           → agents.bind(id, sseObserver(emitter))
           → agent.observe(message)
           → return emitter                    // the stream IS the response
onTurnEnded → emitter.complete()
```

Mid-turn continuity needs no machinery because **an in-flight turn keeps its instance alive**: the
executors hold `sink = agent::apply`, a strong reference, so from `observe` to `TurnEnded` the
instance — and the emitter wired into it — is reachable and stable. The instance becomes garbage
exactly when the turn ends, which is exactly when the stream closes.

Interactive turns are **request-bounded by construction**: the interactive host binds a
non-parking tool executor (§4.3), so every turn ends — with an answer, a rendezvous result, or a
loud in-band failure — while its stream is open. Turns that genuinely span days are the autonomous
host's business, where parked completions land in `AgentMemory` with nobody watching, which is parking
working, not a gap.

**Autonomous host.** A long-running partitioned consumer. Drains, drives, nobody waits.

**The synchronous adapter.** Fire-and-forget everywhere is a real ergonomic loss for tests, CLIs,
and embedding. So: a `CompletableFuture` completed from narration —

```java
var waiter = TurnObserver.observe(o -> o.onTurnEnded(future::complete));
var agent  = agents.bind(id, waiter);     // observer supplied at construction (§3.5)
agent.observe(line);
TurnEnded ended = future.get(timeout);    // terminal Phase + failure reason
```

Not a second code path — one observer, one phase machine, a convenience wrapper. The caller's
thread parks on the future while executor virtual threads do the work; a parked or slow turn is
just a timeout, uniform with every other slow tool. Streaming rides the same registration —
`onTextDelta` on the same builder — so wait and stream are one mechanism.

### 7.1 Use-case builders — the front door

Every wiring choice this design pushes to "chosen at bind" needs a place where it actually gets
chosen. Three builders bundle them, one per real use case — and they map onto §1.1's matrix, which
is evidence the cases are the right ones:

| Choice | `cli()` | `web()` | `autonomous()` |
| --- | --- | --- | --- |
| Binding | constant id | per-request | per-key |
| Parking (§4.3) | ✗ — rendezvous: terminal prompt | ✗ — rendezvous: approval endpoint | ✓ — park desk |
| Output | blocking `converse(line)` | per-turn `SseEmitter` (§7) | narration → audit/log |
| Backlog | trivial in-memory | in-memory, merge-all per scope (§7) | durable, coalescing |
| Drain on `Idle` (§3.1) | vacuous | client-driven | instance auto-drains |
| Stores / `AgentMemory` | in-memory defaults | supplied factories | supplied factories |

```java
var agent = Nessy.cli().definition(def).build();
System.out.println(agent.converse("hello"));

var web = Nessy.web().definition(def)
    .binding(req -> AgentId.of(req.sessionId()))
    .memory(memories).stateStore(stores)
    .build();
SseEmitter stream = web.chat(sessionId, message);   // the stream IS the response

var auto = Nessy.autonomous().definition(def)
    .binding(msg -> AgentId.of(msg.tenantId()))
    .memory(memories).stateStore(stores)
    .parks(jdbcParks).backlog(backlogs)
    .build();
queue.subscribe(auto::deliver);
```

**Builders wire existing seams; they never own machinery.** Anything a builder defaults is
overridable; anything a builder can produce, hand-wiring can produce without it — batteries, not
law. That is the test that keeps `cli()` from growing behavior unreachable by construction, and it
is why the builders add zero types to the core: they are front doors onto §3.5's factories.

**"Which reply is mine" mostly dissolves under per-turn streams:** a response-scoped stream cannot
carry anyone else's reply. What remains — a shared scope where a second participant wants to watch
activity they did not initiate — is an ambient-feed feature, deferred until traffic justifies it.

## 8. Observation — `TurnObserver`, restored

Narration keeps the **existing `TurnEvent` grammar and `TurnObserver` machinery** — the sealed
event family, the `TurnObserverConfig` builder, `noop()`, and `logging()`. They are already
written, already tested, and nothing in this design invalidates them. An earlier revision of this
document replaced them with a bare `observed(AgentEvent, List<Effect>)` tuple; that discarded
streaming, tool lifecycle, and the builder in one stroke, and is retracted (§10.6).

**Two vocabularies, two jobs — deliberately not one grammar:**

| | Purpose | Size | Growth |
| --- | --- | --- | --- |
| `AgentEvent` (§2.1) | facts that **drive phases** | 3 variants | frozen ceiling |
| `TurnEvent` | **narration** for humans and tooling | 10+ variants | grows freely |

No phase ever switches on a `TurnEvent`, which is exactly why it can afford to be rich: adding
`ToolCallProgressed` costs the core nothing. Collapsing the two into one grammar would force every
phase to ignore most of it and every narration change to touch the frozen core.

The grammar carries over with two adjustments:

- **`TurnEnded`** — carried `ConversationStatus`, a deleted type; it now carries the terminal
  `Phase` and the failure reason, synthesized by the shell when a transition lands on `Idle` (the
  reason taken from `ModelOutcome.Failed` when that is what ended the turn). This is what makes the
  synchronous adapter (§7) one line —
  `onTurnEnded(ended -> future.complete(ended))` — instead of inferring idleness from event shapes.
- **`ToolCallParked` is deleted.** If a parked call is indistinguishable from a slow call by
  design (§4.3), narrating parked-ness narrates a distinction the design says does not exist — and
  the token it carried is a capability that fan-out would hand to every observer, `logging()`
  included. Every consumer has a better door: approval UX rides the executor's approval handler,
  the waiting caller times out uniformly (a parked call is just a slow call to the waiter too), the
  gate's verdict is already narrated as `ToolCallDecided`, and "why is this scope stuck" is a query
  on the park desk's own API. `TurnEvent` narrates the model's turn, never executor bookkeeping.

The streaming variants — `TextDelta`, `ThinkingDelta`, `RedactedThinking` — are the interactive
host's live-output seam. They already answer how a CLI shows tokens as they arrive; this design
nearly reinvented what it already had.

**Observers narrate; they never influence.** A listener that can affect the flow creates
inter-listener ordering dependence and a shadow decision surface competing with the authorization
ladder. Behavior injection has designated seams and all of them are interfaces already: `AgentMemory`,
`AgentStateStore`, `Backlog`, `ObservationRenderer`, both executors, and the grants.

**Multiple observers is normal, not speculative.** §7 alone requires two at once: the synchronous
adapter and the SSE emitter, on the same scope, during the same drive. §5.1 adds a third, since
audit rides this stream.

**Who emits what** follows §4's ownership lines, stamped with the id at emission:

| Source | Events |
| --- | --- |
| `ModelCallExecutor` (decorator) | `TextDelta`, `ThinkingDelta`, `RedactedThinking`, `ToolCallRequested` |
| `ToolCallExecutor` (decorator) | `ToolCallDecided`, `ToolCallProgressed`, `ToolCallCompleted` |
| the shell | `AssistantSaid`, `TurnEnded`, plus `ignored`/`renderFailed` diagnostics |

**Observability splits three ways.** Call-level spans go in **decorators around the executors** —
they are interfaces, and only a decorator is on the call stack where context propagation is
possible. Turn-level scope goes in **the shell**, because only the shell knows where a drive begins
and ends. Metrics and trajectory ride **the observer**, since counters genuinely are after-the-fact
narration. Nothing goes in a phase.

The metrics roster itself belongs to its own design. This spec declares the seams and stops.

## 9. What is deleted (executed 2026-08-20 — the distillation plan)

| Deleted                                  | Because                                                       |
| ---------------------------------------- | ------------------------------------------------------------- |
| `Conversation<I>`                        | held an id and delegated; the instance is the scope            |
| `ConversationId`                         | → `AgentId`                                                    |
| `ConversationEvent`                      | → `AgentEvent`, with the id removed from every record          |
| `ConversationStore` + `Transcript`       | → `AgentMemory` (history) and `AgentStateStore` (control), §5       |
| `ConversationStatus`                     | → `Phase`, which carries its own data                          |
| `ConversationLoop` (blocking)            | → phase machine + shell                                        |
| `ParkToken` / park state / `ParkedCall`  | an address book, private to the tool executor (§4.3)           |
| `approve`/`deny`/`resume`/`peek`/`progress` on the agent | tool-executor API, not core surface            |
| `RunOutcome`                             | → the synchronous adapter (§7)                                 |
| `SubagentLinks`, `CallbackRouter`        | a subagent is a slow tool (§4.4)                               |
| `Harness` as mandatory root              | demoted to the factory layer of §3.5                           |
| `InputRenderer<I>`                       | → `ObservationRenderer<O>`, applied at poll time               |
| `Reducer` / `Step`                       | → `Phase.handle` returning `Transition`; an SPI nobody implements is a type tax |
| the event log                            | never existed outside the first draft; history is `AgentMemory`'s   |

`Harness` shrinks rather than dies: something must derive scoped collaborators from process-wide
ones, and nessy-core must work without a DI container. But it stops being the required entry point
— Spring scopes a bean, hand-wiring constructs an agent directly, and the default resolver is
batteries, not law.

## 10. Reversals

Decisions this document overturns, recorded so that no ruling is ever silently replaced again. The
first entry is the reason this section exists.

### 10.1 `Memory.remember` is restored — reverses the first draft, not the user

**Reversed:** the first draft's §5.1, "Nothing writes to memory," which made the shell the sole
writer and `AgentMemory` a read-only projection over a mandatory event log.

**Restores:** the ruling of 2026-08-14, recorded as deliberate — *"`remember()` stays on `AgentMemory`;
a discard-most memory writes no transcript at all. The loop does NOT own transcript writes."*

**Why the first draft was wrong:** it forced universal, verbatim, permanent retention. That leaves
nowhere to stand for a memory that must *not* retain (erasure obligations, ephemeral chat), makes a
high-volume monitor append every observation forever, and excludes any memory backed by a foreign
system with its own representation.

**Process note:** this reversal was made without being flagged as one. Everything in this section
exists so that cannot recur.

### 10.2 The drive loop, thrice reversed — then deleted

**Reversed first:** a CAS claim with a "dirty before claim, clear before work, re-check after
release" protocol — over-engineered lock-freedom nobody required.
**Reversed second:** a `synchronized drive()` with a `working` boolean. The monitor made every
inbound call **block** behind an in-flight drive while the spec promised it never would; the flag
only handled same-thread re-entry.
**Reversed third:** a scheduled trampoline on an injected `Executor` — one CAS, a post-clear
re-check, two queues. Correct, but its surviving justification was flattening *synchronous*
executor re-entry, and save-before-dispatch had already made the "callback races the running
drive" story moot: the store's version CAS serializes concurrent applies by itself, intra-process
and multi-node alike.
**Landed on:** no shell machinery at all. Executors are asynchronous by contract, the store's
version CAS is the only lock, and `apply` is load–handle–save–dispatch with a retry (§3.2). Three
mechanisms died protecting a wiring style — synchronous executors — that was then simply removed.

### 10.3 `Phase` replaces derived status — reverses "idle is derived, not tracked"

**Reversed:** the first draft's §2.2, which claimed a status enum is a parallel field that can
disagree with the outstanding set.
**Why:** the claim rests on a false premise. A model call in flight puts nothing in the outstanding
set, so "awaiting model" and "idle" were indistinguishable, and the shell would fire one model call
per queued observation. The sealed form answers the original objection outright: pending calls live
*inside* `AwaitingTools`, so there is no second field to disagree with (§2.2).

### 10.4 One completion event per effect — reverses the defended failure asymmetry

**Reversed:** the first draft's §2.1, which kept `ModelResponded`/`ModelFailed` as two events and
`ToolFinished` as one, and defended the split on merit.
**Why:** in-band versus out-of-band is a fact about how a phase handles an outcome, not about what
happened. The grammar states events; phases assign meaning (§2.1).

### 10.5 The reducer as doctrine is abandoned

**Reversed:** the first draft's thesis, "the reducer is pure; every side effect lives in a named
executor," and the event log, mandatory transcript, and projection-only `AgentMemory` derived from it.
**Why:** 12-factor says own your control flow and keep state out of the loop; it does not mandate a
reducer. The claimed payoff — testing decisions with pure values — survives intact on
`Phase.handle`, and a pumped executor (§3.2) gives a deterministic, mock-free test of the whole
shell. What survives is the discipline (decisions do no I/O), not the vocabulary.

### 10.6 `TurnObserver` is restored — reverses the observer tuple

**Reversed:** this document's own §8 first draft, which replaced the `TurnEvent`/`TurnObserver`
machinery with a single `observed(AgentEvent, List<Effect>)` narration.
**Why:** the tuple could not even serve §7's synchronous adapter (the next phase was not in it, and
"empty effects" is false as an idle test — a partial `ToolFinished` also emits nothing), and it
silently deleted the streaming deltas, the tool lifecycle events, and the builder — working, tested
code. Facts that drive phases and narration for humans are two vocabularies with two jobs (§8).

### 10.7 The construction-held sink is reversed — the band-aid named it

**Reversed:** the ruling that executors "hold their `Sink` from construction," made while taking
the continuation door off the `Agent` interface.
**Why:** it handed a dispatch-lifetime capability to an instance-lifetime holder *before the
target existed* — violating §4's own address-lifetime rule — and the resulting construction cycle
(executor needs sink, sink needs agent, agent needs executors) had to be bandaged with a mutable
one-shot holder (`LatentSink`: bind-once, throws-if-early). The band-aid was the diagnosis: a sink
must be handed only by a party that provably holds its target — the shell at dispatch, the binder
at re-bind. The capability ruling itself survives untouched: `deliver` stays off the public
interface, and the handoff stays point-to-point.

### 10.8 Invariants that exist because this design keeps regenerating one bug

Four separate defects in this design have been the same defect: **an implicit ordering rule, or a
second path to do one job.** They are collected here because the next one will look just as
reasonable.

| Invariant                     | The bug it prevents                                            |
| ----------------------------- | -------------------------------------------------------------- |
| Decide before committing      | a redelivered completion written to history before it is judged |
| Commit before dispatch        | the model projecting history missing the exchange that provoked it |
| Never bypass the backlog      | ordering jumped, coalescing denied, a second path to a phase    |
| `CallModel` carries no context | two paths for content to reach the model, free to disagree     |

### 10.9 The call's addresses are stamped, not discovered

Ruled 2026-08-20 (second wave). A tool that defers may need to tell an external system where to
answer, but it can neither mint an identity (deterministic ids derive from scope it does not
have) nor be told one after the fact. So the executor — the one party that provably holds the
scope — stamps a `CallAddress` (`agentType`, `agentId`, `callId`, as plain strings) onto the
`ToolContext` before the tool runs, and the address owns the two derivations
(`approval:`/`tool:` → `ComputationId`) as the single derivation site. This lives on
`ToolContext`, not on the `ToolCall` record: the record is the model's utterance — serialized
into phases and continuations where scope is redundant and re-derivable — while the context is
the executor's grant to the tool, which is exactly the §10.7 principle: a capability is handed
by a party that provably holds its target. Determinism makes the stamp free everywhere: a
non-durable wiring stamps the same address and simply never materializes a slot under it, and a
re-executed tool (at-least-once) hands the identical address to the external side, which may
dedup on it.

### 10.10 Core dissolves — api and spi become modules

Ruled 2026-08-21 (owner). The distillation left `nessy-core` containing only `api.**` and
`spi.**` — nothing remained to be "core," so the module dissolves into what it already was.
This is §11 q8's "cut-over is subtraction" completing itself: no rename, an evaporation.

The boundary rule, one sentence each, so placement is law rather than debate:

- **`nessy-api`** — the vocabulary everyone shares: messages, the tool and authorization/risk
  grammar, turn events, `Awaited`, `CallAddress`, `CompletionPolicy`, `ToolEvent`, `Intent`.
  "api" does not mean "never implemented" — `Tool` lives here because it is everyone's words,
  spoken and implemented alike. Depends on `nessy-durable` and Jackson only.
- **`nessy-spi`** — seams an outsider implements without ever knowing the machine: the model
  provider SPI, `Memory`, `IntentStore`, and the approver trio
  (`Approver`/`Adjudication`/`ApprovalRequest`). Depends on api.
- **`nessy-agent`** — the machine, the hosts, the shipped kit (`VerbatimMemory`,
  `InMemoryIntentStore`, `IntentTool`, `IntentEnricher`, the slot-backed wiring), and the
  machine's OWN seams: `Sink`, `AgentObserver`, `DeferredToolCallPolicy`, and today's
  `AgentStateStore` all reference `AgentEvent`/`Transition`/`State` — wiring joints, not
  third-party surface. The state store joins `nessy-spi` only when the opaque-payload reform
  (the q8 tension) makes it machine-blind.

Chain: `durable ← api ← spi ← agent`. Personas: tool/policy/enricher authors compile against
`nessy-api` alone; adapter authors against `nessy-spi`; app builders against `nessy-agent`.
The layering guards become per-module Maven law and keep their source-scanning enforcement.

### 10.11 The four tiers — substrate, host, harness, binding

Ruled 2026-08-21 (owner, conversation). The runtime decomposes into four tiers, and the words are
law:

> **substrate** ← **host** ← **harness** ← **binding** ← the agent.

- **Substrate** — the shared durable underlay: the durable-computation backend, state storage,
  memory storage, backlog storage. Passive, storage-shaped, and **possibly shared across many
  hosts** — a JDBC substrate serving ten nodes is the designed deployment; the in-memory
  substrate is the degenerate single-node case of the same concept. In-memory implementations
  are reshaped accordingly: one shared substrate object per host, with **per-id handles as thin
  views** — a handle holds a reference and an id, never the data. Losing a handle loses nothing.
- **Host** — one process's assembly around a substrate (§7): the harnesses, the continuation
  dispatcher and its registrations, the desks (stateless verbs over the backend — which is why
  an approval clicked on node B resumes a scope parked by node A), the executor pool, and the
  delivery doors. Born and dies with its process. Today's autonomous host is deliberately
  single-type; the multi-type upgrade is a `Map<AgentType, Harness>` plus resolver routing —
  the binder/resolver seams already carry `AgentType` in anticipation, and the subagent story
  (§4.4) walks through that door.
- **Harness** — the recipe compiled, one per `AgentType`, id-free and immortal: the model-call
  and tool-call machinery (with ONE shared `ObjectMapper` — never per-scope), the registry with
  its precomputed specs, observers, renderer, posture flags, the staleness policy. §1.1 said the
  `AgentType` is the recipe's name; the `Harness` is the recipe made runnable. The framework's
  own noun lands on a concrete type: a harness is an agent with the scope left blank.
- **Binding** — `harness.bind(id)`: the scope strapped in. A small value of thin handles
  (`id`, memory, store, backlog) plus the transient `DefaultAgent` over it, stamped fresh per
  delivery at negligible cost. Deliberately named *binding*, not *activation*: the virtual-actor
  word would promise at-most-one live instance, and this design's whole point is that it
  promises no such thing — the store's version CAS is the only lock, racing bindings are legal
  and absorbed (§3.2).

**`AgentWiring` dies as a public surface.** It was a ten-component positional record hand-built
in demos — connascence of position in the shell's own front door. The shell becomes
`DefaultAgent(harness, binding)`; the only public assembly doors are the builders (dsl-coherence
law), and nothing outside the machine composes a shell's collaborators by hand again.

**The staleness policy** (§6.1's judgment, named): `StalenessPolicy.isStale(Phase, Instant
lastSaved)` with canonical `after(Duration)`, `after(Duration, Clock)`, and `never()`. The
`Clock` leaves the machine — the policy owns time. Phase-awareness is the point: a scope quiet
because its calls are suspended on approval slots is quiet on purpose, and a policy may say so;
open question 3 lands here when it lands, as an implementation rather than new surface.

**Vocabulary and prior art** (the gloss that buys a newcomer the model in one paragraph):
agent-as-scope is the virtual-actor model with one deliberate deviation — `(AgentType, AgentId)`
is Orleans' `(grain type, grain key)`, the binding is a grain activation *minus the
single-activation guarantee* (we buy safety with CAS and at-least-once idempotence instead,
which is what lets any node answer), the substrate is grain storage. On the durable side: a slot
is what Restate/DBOS call a durable promise, the deterministic `ComputationId` is an idempotency
key, and the umbrella term elsewhere is durable *execution* where this design says durable
computation. "Host" agrees with MCP's own architecture noun. The desk and the doors are nessy's
own words, doing voice work; the precision terms around them are the industry's.

## 11. Open questions

0. ~~Backlog backpressure for the autonomous host~~ — **closed** (ruled 2026-08-20): the
   rejection vocabulary is the exception. The autonomous host's default in-memory backlog is
   bounded (configurable capacity); `add` beyond capacity throws `IllegalStateException`, which
   surfaces at the door that tried to deliver — the caller decides whether to retry, shed, or
   propagate, because backpressure policy belongs to the transport, not the mailbox. `Backlog`
   stays an SPI; a smarter implementation (coalescing, priority, persistent queue) replaces the
   default without new vocabulary.
1. **`Transition` ergonomics** — the three-field shape is settled (§2.5), but the builder surface
   (`to`/`commit`/`emit`/`ignore`) is a sketch and wants one pass for readability.
2. ~~Duplicate summarisation across nodes~~ — **closed**: the claim-write in the summary store is
   the mechanism (§4.1), replacing per-JVM in-flight tracking entirely.
3. **Staleness policy** (§4.1) — how far past budget projection degrades rather than returning an
   oversized context.
4. ~~Stale-state retry policy~~ — **closed**: completions re-handle against fresh state; a losing
   `Observed` re-adds to the backlog (§3.4); duplicates die by stale-discard and `ToolCallId` dedup.
5. ~~`AgentStateStore` payload format~~ — **closed** (ruled 2026-08-20): JSON with a type
   discriminator on the phase; unknown discriminators fail loudly (§2.3).
6. ~~Definition name vs `AgentId`~~ — **closed** (ruled 2026-08-20): they stay two things —
   `AgentType` is the recipe, code-resident and rebuildable anywhere; `AgentId` is the scope, pure
   data; durable addresses carry the pair (§1.1).
7. ~~Recovery sweep~~ — **closed**: recovery is `drive()`'s second arm, effects re-derive from the
   phase, and the scheduled sweep exists only in the autonomous host (§6.1).
8. ~~Migration or replacement~~ — **closed** (ruled 2026-08-20): **`nessy-agent` is the permanent
   user-facing module** — the machine and the front doors (`Agent`, `Phase`, `Transition`, the
   builders, the hosts) — while `nessy-core` distills into the vocabulary and SPIs (messages,
   tools, `Decision`, `TurnEvent`/`TurnObserver`, `Memory`, `AgentStateStore`, `Backlog`, the
   desk). Users depend on `nessy-agent`; provider and store adapters depend on `nessy-core` only
   and **never see a phase**. Names are permanent from day one, so the cut-over is subtraction:
   core sheds the old loop (§9's table is the checklist) and no rename ever happens. The layering
   is enforced by dependency direction — core must never grow a reference to the machine — checked
   mechanically in the build.
