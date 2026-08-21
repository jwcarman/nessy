# Durable Computation

Every deferred question in Nessy — an approval, a slow tool, a callback from
outside the process — answers through the same primitive: a **slot**. This
page names the primitive first, then follows it up into the agent layer that
rides it.

## The slot

A slot is addressed by a `ComputationId`, a deterministic string derived
from the work's own coordinates — never a fresh random id per attempt:

```java
public record ComputationId(String value) {
  public static ComputationId of(String value) { ... }
}
```

Determinism is the point. A recovery re-fire, a re-driven scope, and an
external system answering days later all derive the *same* id from the same
coordinates, so they all find the *same* slot instead of minting duplicates.

A slot has a small lifecycle — at most one transition from `PENDING` to a
terminal state, and no terminal-to-terminal transition:

```java
public enum ComputationStatus {
  PENDING, SUCCEEDED, FAILED, CANCELLED
}
```

`ComputationStatus` is deliberately thin. There is no `RUNNING`, no
`WAITING_FOR_CALLBACK` — those belong to whatever produces the work, not to
the fact of whether it's done. The industry has a name for this shape: a
durable promise, one instance of the broader pattern usually called
**durable execution**. Nessy's version is a slot: create it, await it,
complete it, once.

What the slot completes with is a value, not an exception:

```java
public sealed interface Outcome {
  record Success(Object value) implements Outcome {}
  record Failure(String message) implements Outcome {}
  record Cancelled(String reason) implements Outcome {}
}
```

An `Outcome.Failure` is the work's own failure — a tool that returned an
error, an approval denied. A thrown Java exception is reserved for the
computation *infrastructure* breaking (the backend's connection pool is
down), which is a different problem than the work coming back negative.

The backend SPI is four operations, and the one worth reading closely is
`await`:

```java
public interface DurableComputationBackend {
  CreateResult create(ComputationId id);
  AwaitResult await(ComputationId id, Continuation continuation);
  CompletionResult complete(ComputationId id, Outcome outcome);
  Optional<ComputationStatus> status(ComputationId id);
  List<Continuation> continuationsOf(ComputationId id);
}
```

`await` is atomic: it answers with exactly one of `Registered()` — the
continuation is durably attached before anyone can race it — or
`AlreadyCompleted(outcome)` — the answer was already sitting there. There is
no window where a check-then-register sequence could let a completion slip
past an unregistered waiter; that race is exactly what the standalone
`get()` + `registerContinuation()` shape would have opened. Registering an
equal continuation twice is one registration, which is what lets a recovery
re-fire and a legitimate re-drive both call `await` without double-booking.

`complete` is the one flip. The first caller wins and gets `COMPLETED`;
every later caller — a duplicate delivery, a slow retry — gets
`ALREADY_TERMINAL` and changes nothing. And completion can happen on an id
no slot has been created under yet: it births the slot already terminal.
This closes a real race — a tool can hand an external system a deterministic
address *before* the slot exists, and a fast completer who answers before
anyone ever called `await` simply wins; the later `create` reports
`created=false`.

## Continuations: data, not code

What fires when a slot completes is not a callback closure — those die with
the process that created them. It's data:

```java
public record Continuation(String type, String data) {}
```

The backend stores continuations as opaque `(type, data)` pairs. A
**continuation dispatcher** maps registered types to handlers:

```java
public final class ContinuationDispatcher {
  public void register(String type, ContinuationHandler handler) { ... }
  public void fire(List<Continuation> continuations, Outcome outcome) { ... }
}
```

The primitive knows nothing about agents. Agent resumption is just the one
handler the agent layer happens to register.

## Where an agent's calls become addresses

`CallAddress` is where the primitive meets a running turn. The executor —
the one party that provably holds the scope — stamps it onto `ToolContext`
before a tool runs:

```java
public record CallAddress(String agentType, String agentId, String callId) {
  public ComputationId approval() {
    return ComputationId.of("approval:%s:%s:%s".formatted(agentType, agentId, callId));
  }
  public ComputationId execution() {
    return ComputationId.of("tool:%s:%s:%s".formatted(agentType, agentId, callId));
  }
}
```

One tool call can pose two separate durable questions over its life, each
its own slot: `approval:` asks "may it run?" and `tool:` asks "what did it
return?" The kind prefix keeps them from ever colliding — a call can be
gated by an approval and separately deferred by the tool itself without the
two slots stepping on one another. Every party holding the same three
coordinates re-derives the same address, which is what lets an external
system dedup a redelivered webhook against it.

## The two desks

Two doors complete slots, and they complete different kinds:

- **`ApprovalDesk`** completes `approval:` slots with a `Decision` —
  `approve(id)` or `deny(id, reason)`. Denying is a *successful*
  adjudication; the question was answered "no."
- **`CompletionDesk`** completes `tool:` slots with a `ToolResult` —
  `complete(id, result)` or `fail(id, reason)`.

Both desks are thin: `complete()` on the backend, then `dispatcher.fire()`
on whatever continuations were registered. Neither desk holds state of its
own — the backend is the state, so an approval clicked on one node resumes
a scope parked by another with no coordination between them beyond the
shared backend.

## Suspension is invisible

The agent layer contributes exactly two continuation handlers, and they
answer two different intents:

**`ScopeResumption`** (`RESUME_SCOPE`) carries a tool-result payload — the
scope coordinate plus the full `ToolCall`. When a `tool:` slot completes,
this handler binds `(AgentType, AgentId)` fresh and delivers the outcome as
an ordinary `ToolFinished` event, exactly as if the tool had just returned.
From the phase's point of view there is no difference between a tool that
answers in 200ms and one that answers three days later on a different node
— both are a pending call that eventually produces `ToolFinished`. There is
no parked phase in the state machine, no `ParkToken`.

**`ScopeRedrive`** (`REDRIVE_SCOPE`) carries only the scope coordinate — a
poke, not a payload. When an `approval:` slot completes, this handler calls
`redispatch()` on the scope, which re-fires whatever `ExecuteTool` effects
are still outstanding. The gate meets the re-fired call again, reads the
now-decided slot, and proceeds — the decision itself travels through the
gate's own slot read, not through the continuation.

`RESUME_SCOPE` and `REDRIVE_SCOPE` stay two types because they carry two
intents: one delivers an answer, the other says "go check again."

### Redrive semantics

`redispatch()` has two guards worth naming, because both are deliberate,
not incidental:

- **Idle short-circuits.** If the scope's phase is already `Idle` there is
  nothing outstanding to re-fire, so `redispatch()` returns immediately —
  no hollow "re-fired nothing" narration.
- **`ExecuteTool` only.** A redrive re-fires pending tool effects, never a
  pending `CallModel`. A stalled model call is the staleness-based recovery
  arm's job, not redrive's — a stale `ModelFinished` carries no correlation
  id, so re-firing `CallModel` from redrive could commit a stale model
  response into a turn that has since moved on.

Redrive is at-least-once, same as ordinary recovery: a decided approval is
not gated by staleness, and `ToolCallId` correlation absorbs any duplicate
completion that a second re-fire produces.

## Two arms, not three

An earlier draft of this design modeled three computation shapes —
completed, attached-to-this-JVM, and durable — mirroring `Future` and
`CompletionStage`. That third middle case collapsed under review. Under a
push-shaped tool executor and virtual threads, "locally awaitable between
dispatch and delivery" is every executor's default posture, not a distinct
category worth its own type.

What actually differs is two axes, not one shape:

- **Does completion arrive in-process or out-of-band?**
- **Is redoing the work safe?**

A slot is *needed* only for out-of-band completion. Recovery-by-retry is
*allowed* only when the work is redo-safe. That's why streaming model calls
never get a slot at all: they're in-process (the same JVM that dispatched
them receives the response) and redo-safe (a crash mid-call just costs
tokens on the retry; `ToolCallId`-keyed idempotence at the commit point
handles the rest). A model call parking would buy nothing — there's no
external party to wait on, and re-running the call is strictly cheaper than
the plumbing a slot would need.

Approvals are the opposite on both axes: they're out-of-band (a human, on
their own schedule) and redo-hostile (you cannot safely "retry" asking a
person to decide again as though nothing happened). That's a slot for both
reasons — which is exactly why `DurableParkDemo` and
`AutonomousApprovalDemo` park on tool calls and approvals, never on a model
turn.

## Worked example: a call survives its own instance dying

`DurableParkDemo` proves the shape end to end. A turn asks to restart
production; the tool declares `Awaited.deferred()` — it won't answer now.
The executor creates the `tool:` slot and awaits it:

```java
agents.get().observe("please restart prod");
pump.pumpUntilQuiet();

var slot = ComputationId.of("tool:approver:demo:c1");
assertThat(store.load().phase()).isInstanceOf(Phase.AwaitingTools.class);
assertThat(backend.status(slot)).contains(ComputationStatus.PENDING);
```

The `DefaultAgent` instance that dispatched the call is discarded — nothing
holds it open. Hours later, any node calls `CompletionDesk.complete`:

```java
desk.complete(slot, ToolResult.ok("approved by jcarman"));
pump.pumpUntilQuiet();

assertThat(store.load().phase()).isEqualTo(new Phase.Idle());
assertThat(backend.status(slot)).contains(ComputationStatus.SUCCEEDED);
```

Completion fires `ScopeResumption`, which binds a *fresh* `DefaultAgent` —
one that has never seen this scope before — and delivers the tool result as
an ordinary event. The transcript reads as one continuous turn: the model
never knew any instance died in the middle.

`AutonomousApprovalDemo` runs the same shape one layer up, through
`Nessy.autonomous()` and the two desks together — see
[Autonomous Agents](../guides/autonomous-agents.md) for that walkthrough.

!!! warning "At-least-once, always"
    Every path through this primitive — recovery re-fire, redrive, an
    external system's retried webhook — can redeliver. Idempotence is not
    optional: tools correlate by `ToolCallId`, the phase fold dedups by
    completion identity, and the state store's version CAS absorbs a lost
    race. Nothing here promises exactly-once *execution* of a callback,
    only exactly-once completion of the slot it answers.

## Where next

- [Autonomous Agents](../guides/autonomous-agents.md) — the builder surface
  that wires a backend behind both desks, and the approval arc end to end.
- [Authorization](authorization.md) — the ladder whose `RequireApproval`
  verdict is what puts a call in front of the approval slot in the first
  place.
- [The Four Tiers](the-four-tiers.md) — where the durable backend sits as
  the shared substrate beneath a host.
