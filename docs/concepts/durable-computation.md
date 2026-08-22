# Durable Computation

Every deferred question in Nessy — an approval, a slow tool, a callback from
outside the process — travels the same pipeline: a chain of atomic
ownership transfers over the substrate, never a future a live process
awaits.

```
pending computation ──(result arrives; atomic)──▶ pending delivery (outbox)
pending delivery ──(delivered; atomic)──▶ advanced fold (the scope's state)
```

At every committed point the eventual result is owned by exactly one
stage. Nothing waits: the continuation handed at creation is the label, and
delivery means reconciling the result into the fold — not landing in any
intermediate container.

## Presence means pending

A computation is addressed by a `ComputationId`, a deterministic string
derived from the work's own coordinates — never a fresh random id per
attempt:

```java
public record ComputationId(String value) {
  public static ComputationId of(String value) { ... }
}
```

Determinism is the point. A recovery re-fire, a re-driven scope, and an
external system answering days later all derive the *same* id from the same
coordinates, so they all find the *same* computation instead of minting
duplicates.

There is no status field and no terminal record. `PendingComputation` is
the whole of what "presence means pending" stores:

```java
public record PendingComputation(
    ComputationId id,
    ToolInvocationId invocation,
    Continuation returnAddress,
    Optional<Instant> deadline) {}
```

Presence alone is the pending signal. The moment the work completes, the
backend deletes this record and creates the outbox delivery that carries
the result onward — there is nothing left to read back once that has
happened.

What a computation completes with is a value, not an exception:

```java
public sealed interface Outcome {
  record Success(Object value) implements Outcome {}
  record Failure(String message) implements Outcome {}
  record Cancelled(String reason) implements Outcome {}
}
```

An `Outcome.Failure` is the work's own failure — a tool that returned an
error, an approval denied. A thrown Java exception is reserved for the
computation *infrastructure* breaking, which is a different problem than
the work coming back negative.

The backend is three operations:

```java
public interface DurableComputationBackend {
  CreateResult create(ComputationId id, ToolInvocationId invocation,
                      Continuation returnAddress, Optional<Instant> deadline);
  CompletionResult complete(ComputationId id, Outcome outcome);
  Optional<PendingComputation> find(ComputationId id);
}
```

`create` carries the continuation — the return address is durable before
any dispatch, so the register-after-create window that an earlier design
left open cannot occur. It is get-or-create on the deterministic id, which
is what makes a redrive idempotent: re-creating an already-present
computation is a CAS conflict, not a duplicate.

`complete` is the one atomic ownership transfer: it deletes the computation
and creates its outbox delivery, or does nothing. `CompletionResult`
answers `TRANSFERRED` when this call performed that transfer, or
`ALREADY_DONE` when the computation was already absent — completed earlier,
or never created. The two cases are indistinguishable and equally
ignorable under at-least-once delivery, so completing an unknown id is
never an error. Two racing completers land on exactly one winner; the loser
observes absence and moves on.

`DurableComputationBackend` is no longer an adapter SPI a database
implements — it's internal vocabulary. `SubstrateComputations`
(`nessy-agent`) is its default and only shipped implementation, a recipe
over [`Substrate`](storage.md) — see [Storage](storage.md) for the document
shapes it writes.

## Identity: what stays stable across a redispatch

Two ids give a tool invocation a durable identity that survives crashes,
retries, and multiple hosts.

**`ModelResponseId`** is a Nessy-generated id (UUIDv7) minted for each
committed model response. It is generated in the model-call executor, when
the response arrives — never in the reducer, which stays a pure fold: a
generated id inside a fold would break the law that re-handling the same
event yields identical state.

**`ToolInvocationId`** pairs that response id with the provider's own tool
call id:

```java
public record ToolInvocationId(String responseId, String callId) {}
```

A bare provider call id is not contractually unique over an agent's
lifetime; pairing it with the response that minted it closes that hole.
Every tool invocation carries its `ToolInvocationId` — via `ToolContext` —
for logging, correlation, and as a natural idempotency key.

`ComputationId` itself stays deterministic, derived from the fold's own
coordinates:

```java
public record CallAddress(String agentType, String agentId,
                          String responseId, String callId) {
  public ComputationId approval() { ... }  // "approval:type:id:responseId:callId"
  public ComputationId execution() { ... } // "tool:type:id:responseId:callId"
}
```

Deterministic still means recomputable from committed state — that's what
gives a redrive its O(1) lookup and `create` its natural idempotence.

## The delivery: the outbox, built

A delivery is one outbox document — `kind=outbox`, a UUIDv7 key so
`keys("outbox", n)` scans oldest-first, holding `{ destination, outcome }`.
Deliveries are pending-only: delivering deletes them. See
[Storage](storage.md) for the wire shape.

`DeliveryWorker` is the one consumer. For each pending delivery it:

1. reads the delivery and resolves the destination scope from its
   continuation;
2. loads the scope's state and lets the pure reducer fold the outcome into
   it (`ToolFinished`, or the reducer's own no-op for an already-reconciled
   call);
3. commits one substrate batch — journal appends for any committed
   messages, the CAS state write, and the delivery's own removal — the
   atomic turn-advance the substrate's `batch` exists for;
4. dispatches the transition's effects, after that commit, unchanged from
   every other transition in the shell.

A CAS miss re-reads and re-handles. A crash anywhere leaves the delivery
pending, and redelivery just re-runs a pure fold — the reducer itself is
the dedup, so a duplicate delivery costs a wasted read, never a duplicate
effect.

`DeliveryWorker` runs one heartbeat thread per host. `nudge()` runs an
immediate, synchronous drain right after a completion commits — the
heartbeat is the recovery net, never the happy-path latency. There is no
live `ContinuationDispatcher` fire path anymore: continuations are read by
exactly one consumer, the delivery worker, whether that read happens
because something nudged it or because its own heartbeat came around.

## The approval gate

Authorization's `RequireApproval` verdict runs inline, exactly once, before
a tool ever gets a chance to do anything:

```
policy: Allow           → the tool call proceeds
        Deny            → ToolFinished(Failed), inline
        RequireApproval → ask the Approver, inline
```

If the approver decides immediately, the call proceeds or fails right
there — no computation involved at all. If the approver parks, it creates
an approval computation whose **continuation carries the tool call
itself** — routing, invocation id, call name and arguments. That
continuation is the work order: the same data `ApprovalRequest` already
hands the approver, taking one more step.

Granting is completing that computation with `Decision.Allow`. Completion
is the ownership transfer described above — it produces exactly one
delivery, whose destination continuation *is* the call. When the delivery
worker drains it, it dispatches the call directly. There is no fold read,
no re-derivation of the pending computation, and critically no re-running
of the policy or the approver — the gate is never re-entered for a call
that has already cleared it. Denying completes the same computation with a
decision the reducer folds as `ToolFinished(Failed)`, in-band, exactly like
any other tool failure the model reads and reacts to.

Because the sequence only ever moves forward, a grant needs no durable
marker of its own: a granted call is simply work in flight, owned at every
committed point by either its delivery or the tool computation it goes on
to open.

## Retry, deadlines, and the reaper

A durable tool declares two things at registration:

```java
Tool.of(SlowJob.class, t -> t
    .description("...")
    .defers((cmd, ctx) -> jobs.submit(cmd, ctx))
    .retrySemantics(RetrySemantics.RETRYABLE)
    .timeout(Duration.ofMinutes(10)));
```

**`RetrySemantics`** — `RETRYABLE` or `NON_RETRYABLE`, default
`NON_RETRYABLE` — is the tool author's own safety assertion, not a fact
Nessy can verify. Declaring `RETRYABLE` says redispatching this tool's
external side effect with the same `ToolInvocationId` is safe, by
idempotence, by dedup on that identity, or by a provider idempotency key
the tool derives from it. Nessy guarantees stable identity and durable
routing; it never guarantees the external side effect runs exactly once.

**`timeout`** is optional. Set, it stamps a durable `deadlineAt = now +
timeout` onto the computation at dispatch. Unset, the computation has no
deadline and waits indefinitely — legitimate for an approval, which may
sit for days waiting on a person.

The reaper is the delivery worker's second sweep, on the same heartbeat:
scan `computation` documents, decode each, and compare its deadline.
Presence-means-pending is what keeps this scan cheap — the table only ever
holds what's currently parked. Deadline-less computations are skipped; an
overdue one splits two ways:

- **`NON_RETRYABLE` overdue** → the reaper manufactures the completion
  itself: `complete(id, Failure("TIMEOUT_NON_RETRYABLE"))`. That failure
  rides the ordinary delivery pipeline into the fold — there is no special
  timeout path.
- **`RETRYABLE` overdue** → the reaper CAS-bumps the deadline (a lost CAS
  means another worker already bumped or completed it first, so this sweep
  backs off) and redispatches the same `ToolInvocationId`.

A crash between creating a computation and dispatching its work needs no
separate recovery pass: the computation simply times out and retry
semantics take over from there.

## Worked example: a call survives its own instance dying

The shape end to end — a turn asks for something that won't answer now,
the tool defers, the process that dispatched it disappears, and a
completely different process eventually completes the computation:

```java
host.post("prod-eu", "please restart prod-eu");
// ... the tool defers; DeliveryWorker has nothing to drain yet ...

ApprovalRequest request = requests.getFirst();
host.approvals().approve(request.address().approval());
// ... any node, any time later: complete() transfers ownership to a
//     delivery, the worker drains it, and the turn completes ...
```

The instance that dispatched the call never has to still be running. The
transcript reads as one continuous turn regardless of how many hosts or
how much wall-clock time separates the ask from the answer.

## Honest limits

The pipeline promises ownership transfer, never a live thread and never
exactly-once external work. A few edges are worth naming plainly, because
softening them would promise more than the design gives:

- **A tool's external work starts before its transfer batch commits.** A
  deferring tool can only reveal that it's deferring by *returning*
  `Awaited.deferred()`, so the external call it started necessarily runs
  before Nessy can commit the computation that owns its return address. The
  real guarantee is narrower than "durable before dispatch": the batch
  commits before control returns to the pipeline, and a crash between the
  tool's external start and that commit leaves the delivery to be
  redriven — at-least-once, the same contract every tool already signs up
  to.
- **A single-winner claim is per-host, not per-cluster.** Within one host,
  draining a grant's delivery has exactly one winner. Across hosts, the
  same grant delivery can be drained more than once until an outbox lease
  lands with the first durable (non-in-memory) substrate adapter — parked,
  not built. Until then, a tool's `execute()` can run more than once for
  the same grant, immediate or durable alike; the durable record stays
  single-winner regardless, because that's the transfer batch's CAS, not
  the external side effect.
- **The window right after a grant completes and before its delivery
  drains is not yet closed.** In that window neither the approval
  computation nor a tool computation exists, so a staleness redrive
  landing there re-asks the approver — the delivery's key is random, not
  derivable from the call's address. Closing it needs a deterministic
  grant-delivery key or an explicit granted marker; it's parked, not fixed.
  The absorption guarantee that does hold: a pending ask absorbs a redrive,
  and in-flight work absorbs one — only this one already-granted-but-
  undrained instant does not.
- **The reaper's scan has a cap, and pending approvals used to be able to
  starve it.** `keys()` returns lexicographically, and `"approval:"` sorts
  before `"tool:"` — since an approval is deadline-less by design and never
  reapable, 1000+ pending approvals could fill the whole scan and make every
  real tool deadline unreachable, silently, forever. Closing that took two
  changes together: the reaper's own key fetch is now wider than the
  delivery sweep's (fetching bare keys is metadata-cheap, so a generous
  window is a fair trade), and it skips the `"approval:"` prefix before it
  ever reads a computation document. Neither change removes the cap itself —
  it's wider, not gone: a backlog of 20,000+ pending computations, approvals
  or real tool work alike, can still starve a sweep. The real fix — a keys
  cursor on the `Substrate` seam, so a sweep can page rather than being
  capped at any fixed width — is parked, not built.

None of this is exactly-once anywhere it isn't claimed. What's promised —
stable identity, durable routing, at-least-once delivery — is what the
pipeline actually gives.

!!! warning "At-least-once, always"
    Every path through this pipeline — recovery re-fire, a redrive, an
    external system's retried webhook — can redeliver. Idempotence is not
    optional: tools correlate by `ToolInvocationId`, the reducer dedups by
    completion identity, and the state store's version CAS absorbs a lost
    race.

## Where next

- [Autonomous Agents](../guides/autonomous-agents.md) — the builder surface
  that wires a backend behind both desks, and the approval arc end to end.
- [Authorization](authorization.md) — the ladder whose `RequireApproval`
  verdict is what puts a call in front of the approval gate in the first
  place.
- [Storage](storage.md) — the `computation` and `outbox` document shapes
  `SubstrateComputations` and `DeliveryWorker` read and write.
