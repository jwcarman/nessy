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

A computation is addressed by a `ComputationId`, opaque and one-way — a
SHA-256 digest over the work's own coordinates, deterministic (same
coordinates, same id) but carrying no extractable structure. Nothing
anywhere parses one back apart; the caller who needs the coordinates again
already has them as data, on the continuation:

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
sealed interface Outcome {
  record Success(JsonNode value) implements Outcome {}
  record Failure(String message) implements Outcome {}
  record Cancelled(String reason) implements Outcome {}
}
```

`Success` carries its payload data-born — an already-encoded `JsonNode`, not
a raw Java object — because every value that ever flows through it is
*either* a `ToolResult` *or* a `Decision`, never exclusively one, so the
component can't narrow to either alone.

An `Outcome.Failure` is the work's own failure — a tool that returned an
error, an approval denied. A thrown Java exception is reserved for the
computation *infrastructure* breaking, which is a different problem than
the work coming back negative.

`SubstrateComputations` (`nessy-agent`) is the computation store: three
operations, over one [`Substrate`](storage.md) — there is no adapter seam
above it, because the `Substrate` beneath it already is the seam a host
swaps. Each instance is kind-scoped to exactly one purpose for one agent
type — `computation/<agentType>` for executions, `approval/<agentType>` for
approvals — sharing one `outbox/<agentType>` between them:

```java
public final class SubstrateComputations {
  public SubstrateComputations(Substrate store, ObjectMapper mapper,
                                String computationKind, String outboxKind);
  public CreateResult create(ComputationId id, ToolInvocationId invocation,
                      Continuation returnAddress, Optional<Instant> deadline);
  public CompletionResult complete(ComputationId id, Outcome outcome);
  public Optional<PendingComputation> find(ComputationId id);
}
```

A harness holds two instances — one over `computation/<agentType>` behind
`CompletionDesk`, one over `approval/<agentType>` behind `ApprovalDesk` —
never a single shared kind distinguished by a key prefix. Isolation between
agent types is by construction now: two harnesses of different types over
one substrate never share a kind, so neither's worker or reaper ever reads
or skips the other's records.

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

There was never a second implementation to swap in, so there's no adapter
interface pretending otherwise: `Harness`, `HarnessConfig`, `DeliveryWorker`,
and both desks all hold `SubstrateComputations` concretely — see
[Storage](storage.md) for the document shapes it writes.

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
`ToolContext` no longer exposes this pair directly, though: it hands a tool
the opaque execution `ComputationId` instead — `invocation` — the stable
idempotency key a tool wants for logging, correlation, or deduplicating an
external effect under at-least-once redelivery, carrying no extractable
structure a tool could parse back apart.

`ComputationId` itself stays deterministic, derived from the fold's own
coordinates — but opaquely now, a digest rather than a readable string:

```java
public record CallAddress(String agentType, String agentId,
                          String responseId, String callId) {
  public ComputationId approval() { ... }  // SHA-256("approval", type, id, responseId, callId)
  public ComputationId execution() { ... } // SHA-256("execution", type, id, responseId, callId)
}
```

Both derivations digest a length-prefixed encoding of the tuple — `purpose`
(`"approval"` or `"execution"`) plus the four coordinates — through SHA-256,
rendered lowercase hex. The length prefix on every field closes the
concatenation-ambiguity hole a plain delimiter leaves open; `purpose` is the
one differentiator between the two derivations, so an approval and an
execution over the identical remaining tuple never collide. Deterministic
still means recomputable from committed state — that's what gives a redrive
its O(1) lookup and `create` its natural idempotence — but nothing about an
agent's topology is recoverable from an id that leaks into a log, URL, or
callback.

## The delivery: the outbox, built

A delivery is one outbox document — `kind=outbox/<agentType>`, keyed by the
completed computation's own `ComputationId` (deterministic, not a fresh
random key per completion), holding `{ destination, outcome }`. Deliveries
are pending-only: delivering deletes them. See [Storage](storage.md) for
the wire shape.

The deterministic key is what closes the grant-delivery-pending window (see
Honest limits, below): a replayed completion of the same id converges on
the same delivery key instead of minting a second one, and the gate can
check for a pending delivery under that exact key before ever re-asking the
approver.

`DeliveryWorker` is the one consumer. For each pending delivery it:

1. reads the delivery and resolves the destination scope from its
   continuation;
2. loads the scope's state and lets the pure reducer fold the outcome into
   it (`ToolFinished`, or the reducer's own no-op for an already-reconciled
   call);
3. remembers every `Remembrance` the fold implies through the scope's
   `Memory` (remembrance spec §1) — before the next step, never inside it;
4. commits one substrate batch — the CAS state write and the delivery's own
   removal, nothing else — the atomic turn-advance the substrate's `batch`
   exists for;
5. dispatches the transition's effects, after that commit, unchanged from
   every other transition in the shell.

A `remember` that throws aborts the attempt before step 4 ever runs: the
delivery stays pending, and the next heartbeat (or `nudge()`) redrives it —
re-remembering the same keys converges rather than duplicating anything
(`Memory`'s own idempotence law). See [Memory](memory.md) for the full
three-law story.

A CAS miss re-reads and re-handles. A crash anywhere leaves the delivery
pending, and redelivery just re-runs a pure fold — the reducer itself is
the dedup, so a duplicate delivery costs a wasted read, never a duplicate
effect.

`DeliveryWorker` runs one heartbeat thread per harness. `nudge()` runs an
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
scan `computation/<agentType>` documents, decode each, and compare its
deadline. Approvals live in their own `approval/<agentType>` kind, never
scanned here — deadline-less by design, and never reapable.
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
harness.bind(AgentId.of("prod-eu")).observe("please restart prod-eu");
// ... the tool defers; DeliveryWorker has nothing to drain yet ...

ApprovalRequest request = requests.getFirst();
harness.approvals().approve(request.id());
// ... any node, any time later: complete() transfers ownership to a
//     delivery, the worker drains it, and the turn completes ...
```

The instance that dispatched the call never has to still be running. The
transcript reads as one continuous turn regardless of how many processes or
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
- **A single-winner claim is per-harness, not per-cluster.** Within one
  harness, draining a grant's delivery has exactly one winner. Across
  processes sharing the same substrate and agent type, the
  same grant delivery can be drained more than once until an outbox lease
  lands with the first durable (non-in-memory) substrate adapter — parked,
  not built. Until then, a tool's `execute()` can run more than once for
  the same grant, immediate or durable alike; the durable record stays
  single-winner regardless, because that's the transfer batch's CAS, not
  the external side effect.
- **The window right after a grant completes and before its delivery
  drains is now closed.** Presence-means-pending still leaves no residue in
  either computation kind once the transfer batch commits, but the delivery
  itself now sits at a key the gate *can* derive — the completed
  computation's own deterministic id. The gate's absorption check looks in
  both computation kinds and for a pending delivery under that key before
  ever re-asking the approver, so a staleness redrive landing in the old
  "pending window" absorbs instead.
- **The reaper's scan still has a cap** — a bounded key fetch, not the
  unbounded cursor a real fix needs. Approvals no longer share the reaper's
  kind at all (they live in their own `approval/<agentType>` keyspace), so
  the old failure mode — 1000+ pending approvals crowding a shared scan and
  starving every real tool deadline behind them — cannot occur anymore by
  construction. A backlog of deadline-less *tool* computations in the same
  kind as a genuinely overdue one can still, in principle, exceed the cap.
  The real fix — a keys cursor on the `Substrate` seam, so a sweep can page
  rather than being capped at any fixed width — is parked, not built.

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

- [The harness guide](../guides/harness.md) — the builder surface
  that wires a backend behind both desks, and the approval arc end to end.
- [Authorization](authorization.md) — the ladder whose `RequireApproval`
  verdict is what puts a call in front of the approval gate in the first
  place.
- [Storage](storage.md) — the `computation` and `outbox` document shapes
  `SubstrateComputations` and `DeliveryWorker` read and write.
