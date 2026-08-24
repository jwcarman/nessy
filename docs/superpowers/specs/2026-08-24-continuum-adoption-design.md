# Continuum adoption

**Date:** 2026-08-24
**Status:** draft for review — §9 decisions ruled; spec awaiting review
**Supersedes:** the storage mechanics of `2026-08-20-durable-computation.md`
and the derivation half of `2026-08-23-computation-identity-design.md`

Nessy adopts `org.jwcarman.continuum` 0.1.0 for durable computations.
Continuum owns the lifecycle — pending record, deadline, expiry, memoized
outcome, and the outbox. Nessy keeps the reducer, the desks, and routing.

The one thing adoption genuinely breaks is **dispatch memory**, and the fix
stays inside the class that already owns it.

## 1. What adoption breaks

`Phase.AwaitingTools.outstandingEffects()` emits one `Effect.ExecuteTool`
per id in `pending`, and `pending` means "not yet finished" — so a staleness
redrive re-emits execution for calls already in flight, by design. A
deferred call waiting on a human sits in `pending` for hours and is
re-emitted on every stale `drive()`.

Two mechanisms stop that from doing damage, and **both work only because ids
are derivable**:

1. `RegistryToolCallExecutor.gate` calls `pendingComputation(address)`,
   which re-derives `address.approval()` and `address.execution()` and finds
   the computations already present — absorbing before the policy, the
   approver, or the tool runs again.
2. `ComputationApprover.adjudicate` notifies the human only when
   `created.created()` is true; a CAS conflict on the deterministic id means
   "already asked."

The SHA-256 digest is functioning as a memory of "already dispatched" that
costs no storage, because it is recomputable from coordinates the caller
already holds. Continuum mints opaque ids, so that memory disappears — and
Continuum cannot answer the question either, having no lookup by
continuation. Without a replacement, every redrive mints a fresh approval
computation, re-notifies the human, and on resolution fans out a second
grant that runs the tool twice. The reducer's ToolCallId dedup
(`AwaitingTools.handle`, spec §2.5) catches the duplicate *fold*, never the
duplicate *execution*.

**The fix is an index, owned by the policy.** `CallAddress`'s coordinates —
`agentType`, `agentId`, `responseId`, `callId` — stop being a derivation of
the computation id and become a key into a Substrate document store that
`ComputationDeferredToolCallPolicy` keeps. `onDeferred` and
`ComputationApprover` write the entry; `pendingComputation` reads it. The
gate, the approver's notify guard, and the reducer's dedup all keep working
exactly as they do today.

Rejected alternative: recording the association on `Phase.AwaitingTools` and
teaching `outstandingEffects()` to skip in-flight calls. It is a larger,
more invasive change — a new `AgentEvent` arm, a new phase component, and a
wire-shape change — to put execution bookkeeping into the reducer's
vocabulary, where it does not belong. It offers no atomicity advantage: the
computation lives in Continuum, so any Nessy-side write is a second store
either way, with an identical crash window.

## 2. Scope

**Continuum owns** the lifecycle and the outbox for every computation.
Nessy's outbox is deleted.

Keeping Nessy's outbox was considered and rejected. The stated reason for
keeping it — that grant delivery needs one-shot semantics — is false today:
`deliverGrant` computes the delete op, runs the tool inside
`executeGrantedToolNow`, and commits the delete only in the result's fold
batch, so a crash between execution and commit already re-runs the tool.
`ToolCallExecutor`'s own javadoc names this: "the honest crash property...
the external side effect to be re-run — at-least-once." Continuum's outbox
is strictly better machinery: leases that expire, attempt counts, and
per-delivery backoff, against Nessy's optimistic version-CAS with an in-JVM
`claiming` set and no backoff at all.

**Nessy keeps** the reducer, the fold, the desks, `ComputationApprover`,
`ScopeRouting`, and its `Substrate` for state, transcript, memory, intent.

**Two stores, two transaction domains, nothing spanning them.** No
transaction seam is built (§4).

## 3. Kinds and clients

Two kinds per agent type, both on the non-retryable two-type client:

| Kind | Client | Expiry pump |
|---|---|---|
| `approval/<agentType>` | `ContinuumClient<Decision, Routing>` | `failExpiredComputations` |
| `tool/<agentType>` | `ContinuumClient<ToolResult, Routing>` | `failExpiredComputations` |

Kinds stay per-agent-type (ruled). A Continuum client is minted per kind and
each pump drains one kind, and `Harness` is already per `AgentType`, so the
existing scoping carries over unchanged and one busy agent type cannot
starve another. `Kinds` therefore survives as the kind namer — it stops
naming Substrate kinds and starts naming Continuum ones.

**Retryability is not implemented.** `ContinuumClient` expires an overdue
computation flat out as `Expired(RETRY_DISALLOWED, ...)`, delivered through
the normal path, which is all Nessy needs.

This is not a capability loss. `RetrySemantics.RETRYABLE` has **no
production users**: it is opt-in, `ToolConfig` defaults to
`NON_RETRYABLE`, and the only site in the repo that sets `RETRYABLE` is a
single `nessy-api` test. Dropping it deletes `reapRetryable`, the whole
`Retry<D>` wiring, `RetryableContinuumClient`, and the runtime branch in
`reapOne` that decodes `retrySemantics` out of the continuation to choose
bump-or-fail.

`RetrySemantics` therefore leaves the public API entirely, along with
`ToolConfig.retrySemantics(...)` and `Tool#retrySemantics()`. Keeping a
public enum whose one non-default value does nothing would be the same dead
vocabulary as `Outcome.Cancelled` (§6). Nessy has no users, so the breaking
change costs nothing.

**If retryability is wanted later**, it arrives as an addition, not a
redesign: a third kind on `RetryableContinuumClient<ToolResult, Routing,
Routing>` with its own `retryExpiredComputations` pump and a `Retry` whose
handler calls `executeGrantedToolNow`. Continuum already owns the attempt
counting and deadline extension that `reapRetryable` hand-rolls today, so
that future version is smaller than what is being deleted. `Routing` serves
as the dispatch payload too — for Nessy, "how to redispatch" and "where to
deliver" are the same coordinates, and the idempotency key Continuum asks
callers to embed is already there as `responseId` plus `call.id()`.

`Tool#timeout()` is unaffected: it still sets the computation's deadline. It
now means "fail at this point" rather than "retry at this point."

**Continuation payload** (`Routing`) is what `ScopeRouting` already encodes
minus the retry fields: `agentType`, `agentId`, `responseId`, `call`. It
travels with every delivery, so routing home needs no lookup.

**No dispatch payload.** With no retryable kind, Continuum's second opaque
slot goes unused. Nessy has no dispatch payload today either, so nothing is
lost and no new payload record is introduced.

## 4. Delivery is at-least-once

`AwaitingTools.handle(ToolFinished)` yields `Transition.ignore()` when the
call is not in `pending`; `Idle` and `AwaitingModel` ignore `ToolFinished`
outright. `ToolFoldRemembrance.remember` is gated on
`!transition.isIgnored()`. A redelivered completion after a committed fold
is therefore absorbed with no duplicate remembrance.

**No transaction seam is built.** The atomic `[state write + outbox delete]`
batch disappears with the outbox and nothing replaces it. Two designs were
considered with the Continuum authors and both withdrawn: a shared JDBC
connection fails because `Substrate.batch` *is* the commit boundary, so the
state write commits before any acknowledgment could join it — relocating the
crash window rather than closing it — and it would retroactively change the
meaning of every swallowed `SQLException` in both codebases, since
PostgreSQL aborts the whole transaction on any statement error and neither
uses savepoints. Composable acknowledgment ops would be a breaking change to
Continuum's provider SPI to solve a problem the reducer's dedup already
solves.

## 5. The dispatch index

A Substrate document store owned by `ComputationDeferredToolCallPolicy`.

- **Key:** the call coordinates from `CallAddress` — `agentType`, `agentId`,
  `responseId`, `callId`. `CallAddress` keeps its digest helper, now
  producing an index key rather than a `ComputationId`.
- **Value:** the `ComputationId` that was created, and which kind it belongs
  to, so `pendingComputation` can return it without probing both clients.
- **Written by:** `ComputationDeferredToolCallPolicy.onDeferred` and
  `ComputationApprover.adjudicate`, immediately after the Continuum
  `create` returns.
- **Read by:** `pendingComputation`, which becomes a single read replacing
  today's four store queries.
- **Overwritten** when a call moves from approval to execution. A granted
  approval whose tool then defers produces a second computation under the
  *same* key, since the key is the call, not the computation. The entry
  means "the computation this call is currently in flight under," and the
  execution id replaces the approval id. This is why the value carries its
  kind.
- **Deleted by:** the fold. `DeliveryWorker.foldOps` already builds a
  `Substrate.batch` containing the state write; the index delete joins it,
  so the entry and the phase advance move together. Both are Nessy's, so
  this batch stays intact — it is the *outbox* delete that leaves it (§4).

The entry's lifetime therefore spans a call's first dispatch to its fold,
which subsumes all three of today's arms: approval-pending,
execution-pending, and the completed-but-undrained window that
`deliveryPending` covers.

**Crash window.** The ordering is create-then-index, so a crash between
leaves an orphaned computation and no index entry, and the redrive creates a
second computation. The reverse ordering must not be used: an index entry
with no computation behind it would absorb every redrive forever and hang
the call.

An orphaned **tool** computation is harmless — its eventual delivery folds
into a call no longer pending and the reducer ignores it. An orphaned
**approval** is not (§11.3), so the grant consumer must validate before
acting: if the index entry for the call is absent or names a different
computation, the grant is stale and is acknowledged without running the
tool. This guard is load-bearing, not defensive.

**No phase or wire change.** `AwaitingTools` keeps its shape, `AgentEvent`
gains no arm, and stored phase documents are unaffected.

## 6. What deletes

- `SubstrateComputations`, `OutcomeCodec`, `PendingComputation`,
  `CreateResult`, Nessy's `CompletionResult`. (`Kinds` survives, renaming
  Continuum kinds rather than Substrate ones — §3.)
- Nessy's `Outcome` — including `Outcome.Cancelled`, which has **no
  producer anywhere**; every reference is a switch arm or the codec's decode
  path. Continuum's `Success`/`Failure`/`Expired(ExpiryKind, message)`
  replaces it, which also promotes the
  `Outcome.Failure("TIMEOUT_NON_RETRYABLE")` magic string to a first-class
  outcome with a typed reason.
- `CallAddress.approval()` and `.execution()` — the two purpose-tagged
  `ComputationId` derivations. The digest helper itself survives, now
  producing the dispatch index key (§5).
- `DurableDecisions` — **the whole class, not just `toAdjudication`**:
  `toAdjudication` was already dead in production (only tests called it,
  because `DeliveryWorker` maps outcomes itself via `toToolOutcome`), and its
  now-caller-less `granted`/`denied` helpers go with it. `DurableDecisionsTest`
  goes too.
- `Continuation` — whittled down to zero production callers once
  `ScopeRouting` went.
- `RetrySemantics`, `ToolConfig.retrySemantics(...)`, `Tool#retrySemantics()`
  and the `retrySemantics`/`timeoutMillis` fields `ScopeRouting` writes into
  the continuation payload (§3).
- The grant-versus-completion multiplexing: `toToolOutcome` returning
  `Optional.empty()` to mean "this is a grant" disappears, because the
  approval kind has its own consumer.
- From `DeliveryWorker`: the outbox `DocumentStore`, `deliveryPending`, the
  `claiming` set, `SCAN_LIMIT`, `REAP_KEY_SCAN_LIMIT`, `reapOnce`,
  `reapOne`, `reapRetryable`, and the heartbeat thread.
- `DefaultAgent.redispatch()`, along with `DefaultAgentRedispatchTest` — the
  method already had zero production callers, and its test goes with it
  rather than surviving as dead-code coverage.
- The dead `invocation`/`alsoCommit` parameters `RegistryToolCallExecutor`
  threaded down to `run()` but never read —
  `ToolCallExecutor#executeGrantedToolNow` narrows to `(call, address)` to
  match, and the `alsoCommit` ops-seam door on
  `ComputationDeferredToolCallPolicy#onDeferred` (and the atomicity guarantee
  it gave a path nothing in `src/main` reached any more) goes with it.

## 7. Pumping

Continuum owns no threads. Per kind: `deliverResults`, an expiry pump, and
`purgeExpiredResults`. Every instance may run every pump; leases and
`SKIP LOCKED` make overlap correct.

**One shared `ScheduledExecutorService` with a small pool runs them all**
(ruled) — six tasks per agent type, `scheduleWithFixedDelay` so a slow batch
cannot stack overlapping runs. Fixed-delay is what Continuum's own guidance
recommends.

**What actually ships is one small pool per `Harness`, not one pool shared
across every agent type in a process.** `Harness.of` mints its own
`ComputationScheduler` (`new ComputationScheduler()`), which owns its own
two-thread `ScheduledExecutorService` — the sharing is real within a single
harness (its six pumps, and `nudge()`'s submitted drains, all land on that
one pool), but two harnesses for two different agent types in the same
process each get their own pool. This replaces `DeliveryWorker`'s per-
`Harness` daemon heartbeat one-for-one — thread count per harness is fixed
at two rather than growing with that harness's own load — but thread count
**still scales with the number of agent types**, which was the original
goal's whole point and is not delivered as shipped.

A caller-supplied scheduler — a `HarnessConfig` setter analogous to
`.substrate(Substrate)`, so callers can point several harnesses at one
`ComputationScheduler` — is the only shape that would deliver the original
claim. That is new public API surface and awaits the project owner's
decision (design-authority rule); it is not implemented by this task.

Platform threads, not virtual ones. This supersedes an earlier lean toward a
hand-rolled `Thread.ofVirtual` loop, and sidesteps the JDBC driver
thread-pinning question that lean carried (fixed by JEP 491 in JDK 24, but
not worth depending on).

The approval consumer receives `(Routing, TypedOutcome<Decision>)`:
`Success(Allow)` runs the tool through `executeGrantedToolNow`,
`Success(Deny(reason))` and `Failure(message)` fold a tool failure, and
`Expired(kind, message)` folds a timeout failure. The tool consumer receives
`(Routing, TypedOutcome<ToolResult>)` and folds.

Both kinds expire the same way — `failExpiredComputations` — so there is one
pump shape, not two.

`DeliveryWorker` keeps its fold half — `deliverCompletion`,
`foldGrantedResult`, `foldOps`, `dispatchEffects`, `routingCall` — now
driven by `deliverResults` consumers rather than by scanning an outbox it
owns.

**`nudge()` survives in spirit.** `ApprovalDesk.approve` currently nudges
the worker so a decision folds immediately rather than on the next
heartbeat, which is what makes interactive approvals feel responsive. After
`complete()` the desk submits one `deliverResults` pass to the shared
scheduler rather than running it on the caller's thread. Today's nudge is
synchronous, so `approve()` can block for as long as a granted inline tool
takes to run (§11.2); submitting instead returns immediately, which is the
better behavior for an approval arriving from a UI or an HTTP handler.

**Approvals now carry a deadline — 7 days, harness-level (ruled).**
Continuum requires one, and today approvals never expire. This is a real
behavior change and the right one: an approval waiting forever is a leak,
and expiry arrives through the normal delivery path as
`Expired(RETRY_DISALLOWED, ...)`. A per-tool override is deferred until
something needs it. The approval kind is non-retryable regardless — you wait
for a human, you do not re-ask them.

## 8. Testing

- Redelivery absorption: fold a `ToolFinished`, redeliver, assert the
  transition is ignored and no remembrance is written.
- Gate absorption against the index: dispatch a call, redrive, assert the
  tool runs once and the approver is asked once — for a pending approval, a
  pending execution, and the completed-but-undrained window.
  `AbsorptionTest` and `GrantDeliveryPendingWindowTest` already cover these
  three cases and should survive, repointed at the index; note both drive
  the `redispatch()` door, which §6 deletes, so they move to `drive()`.
- Approval expiry: a new case — no equivalent exists, since approvals could
  not expire.
- `continuum-memory` backs computations in tests; state and transcript stay
  on `InMemorySubstrate`.
- No live-model tests change.

## 9. Decisions (ruled)

Per the design-authority rule, named explicitly rather than buried. All
three are settled:

1. **The dispatch index** (§5) — a new Substrate kind and its stored record,
   internal to `nessy-agent`. Nothing public. Ruled: the executor layer
   already owns "do not dispatch this twice"; only its lookup changes.
2. **`RetrySemantics` leaves the public API**, with
   `ToolConfig.retrySemantics(...)` and `Tool#retrySemantics()`.
   Retryability is not implemented (§3). Ruled: zero users, so the breaking
   change costs nothing, and adopting Continuum's retryable client later is
   an addition rather than a redesign.
3. **Approvals expire after 7 days**, harness-level. Ruled. Per-tool
   override deferred.

No adapter type is needed: `ContinuumClient` *is* the wrapper
`SubstrateComputations` was, so `ApprovalDesk` and `CompletionDesk` hold
clients directly and nothing sits between.

`ToolContext`, `CallAddress`, `ToolInvocationId`, `Adjudication`, and
`Transition` keep their shapes.

## 10. Deliberately not done

- **No `JdbcSubstrate`.** State and transcript stay in-memory, so Continuum
  is wired to `continuum-memory` for this change — see §11.1, this is a
  safety constraint, not a scoping preference.
- **No `ContinuumRepository` over `Substrate`.** Continuum's storage
  contract needs a secondary index, three time-range scans, and a
  transaction whose size scales with continuation count. `Substrate.keys`
  is unfiltered and unordered; such an adapter would be a downgrade of
  Continuum, not an integration.
- **No multi-continuation use.** Exactly one continuation per computation,
  per the standing ruling. Continuum's fan-out generality stays unused, so
  its transaction-size ceiling never binds.
- **No host sweep.** The staleness sweep remains "a bolt-on, not core."
- No metrics or observability work.

## 11. Risks

### 11.1 Half-durability — the one that governs rollout

**The rule is not that `continuum-jdbc` is unfit.** It is a TCK-certified
PostgreSQL provider, and Continuum's half of durability ships today. The
real rule is narrower and symmetric: **the computation store and the
substrate must have matching durability** — wire both to `continuum-memory`
and `InMemorySubstrate`, or both to `continuum-jdbc` and a durable
`Substrate`. Mixing them breaks in one direction silently and in the other
permanently (below). Nessy's missing half is a durable `Substrate` — that is
what blocks the durable pairing today, not any defect in Continuum or
`continuum-jdbc`.

If the two were mismatched, `DeliveryWorker.readState` falls back to
`State.initial()` — `Idle` — when a scope's state is missing, while
`Idle.handle(ToolFinished)` yields `Transition.ignore()`. **Every surviving
computation would deliver into an amnesiac scope that silently drops it.**
The tool result vanishes, the call never completes, and nothing logs an
error.

Today this cannot happen: computations and state share one in-memory store
and die together, which is consistent. Pairing Continuum with a durable
substrate — the day one ships — is what would create the mismatch, if the
two knobs were ever turned independently.

The mismatch is symmetric, and the other direction is worse:

| Computations | State + index | Failure |
|---|---|---|
| durable | in-memory | Deliveries land on scopes restored to `Idle` and are silently ignored. Results vanish; calls never complete. |
| in-memory | durable | The index survives naming a computation that no longer exists, so the gate absorbs every redrive forever. Calls **hang permanently** — the "index entry with no computation" case §5 forbids. |

Silent loss is bad; a permanent hang that the absorption machinery actively
sustains is worse.

**Constraint:** wire Continuum to `continuum-memory` until a durable
`Substrate` exists. That makes this change a pure structural refactor with
no durability change, which is the right scope anyway. Moving to
`continuum-jdbc` is gated on `JdbcSubstrate` landing in Nessy, not on any
change to this spec or to Continuum.

**This must not rely on a doc alone.** `HarnessConfig.finish()` gains a
startup check: if exactly one of the two stores is an in-memory
implementation, log loudly. Detection is `instanceof` against
`InMemorySubstrate` and Continuum's in-memory repository, which is crude —
it cannot judge a third-party `Substrate` — but it catches the realistic
case, which is someone wiring `continuum-jdbc` and forgetting the substrate
is still volatile. A warning rather than a throw, matching how Continuum's
own auto-configuration handles the equivalent situation.

Additionally, `DeliveryWorker` logs when a delivery folds against a scope
with no stored state. That is the actual moment of loss today, and it was
previously indistinguishable from an ordinary duplicate-delivery ignore;
`readState` now returns `Optional<State>` so the caller can tell the two
apart before falling back to `State.initial()`.

#### Regression this migration introduces: disjoint computation state per harness

Before this migration, `SubstrateComputations` gave two `Harness` instances
built over the same `Substrate` shared, structural computation state — the
computations lived in the same store the scopes did, so anything reading
that `Substrate` saw the same approvals and tool computations regardless of
which harness instance produced them.

As shipped, `HarnessConfig.finish()` mints a fresh `Continuum` — and its
`InMemoryContinuumRepository` — on every call, with no override seam. Two
harnesses built over the same `Substrate` now have **disjoint** computation
state: each harness's `Continuum` is a private, in-process map that no other
harness, and no other process, can see. `host/ThreeRuntimeProcessLossTest`
was deleted for exactly this reason — not because its mechanism changed, but
because the property it tested (computation state surviving a "restart"
modeled as a second harness over the same store) is now architecturally
false.

Concretely: **multi-host and restart durability for both computation kinds
is currently architecturally impossible**, regardless of what `Substrate` a
caller supplies. This costs nothing today — `InMemorySubstrate` is the only
shipped `Substrate`, so no deployment could have had multi-host or restart
durability for computations before this regression either — but it becomes
live and consequential the day a durable `Substrate` ships: a caller who
wires `JdbcSubstrate` reasonably expects computations to survive a restart
too, and as shipped they will not.

The fix is a caller-supplied `Continuum` seam on `HarnessConfig` — a new
setter analogous to `.substrate(Substrate)` — so two harnesses (or two
processes) can be pointed at the same durable `Continuum` the way they can
already be pointed at the same `Substrate`. That is new public API surface
and awaits the project owner's decision (design-authority rule); it is not
implemented by this task.

### 11.2 Lease expiry can re-run a slow tool

Continuum claims a delivery under a lease (30s by default) and its docs are
explicit that "the lease must exceed your worst-case consumer time." The
approval consumer runs the tool synchronously inside `deliverResults` via
`executeGrantedToolNow`. A tool slower than the lease is re-claimed by
another pump and **run a second time while the first is still running**.

This is a genuinely new risk. Nessy's current claim is an optimistic
version-CAS plus an in-JVM `claiming` set, which never preempts a slow
in-flight consumer.

The lease is ours per call site — `deliverResults(batchSize, lease, backoff,
consumer)` — so this is a managed tradeoff rather than an inherent hazard.

**A fold does not hold the lease across anything slow.** The consumer reads
state, reduces, remembers, commits one `Substrate.batch`, then calls
`dispatchEffects` and returns. Both effect arms are async by contract:
`ProviderModelCallExecutor.callModel` submits to the executor and, per its
javadoc, "never runs on the dispatching stack," and
`RegistryToolCallExecutor.executeTool` does the same. So a fold that
completes a turn hands the model call off rather than waiting on it — the
lease is never held across streaming inference.

**Lease length, per kind.** The tool kind's consumer only folds, so a short
lease is right. The approval kind's consumer runs tools, so it needs a
longer one. But a lease is a static bound on a dynamic thing: too long and a
genuinely crashed worker's delivery is stuck for that long before anyone
reclaims it; too short and slow tools double-run. No single number is
correct for unbounded work.

**The exposed set is one quadrant.** No computation is created for an
inline tool: `run()` reaches `onDeferred` only on the `Awaited.Deferred`
arm, while `Awaited.Ready` yields `ToolExecution.Immediate`. Crossing that
against approval:

| | No approval | Requires approval |
|---|---|---|
| `Awaited.Ready` | harness `Executor`, no computation, no lease | **runs inline in the grant consumer, holds the lease** |
| `Awaited.Deferred` | tool computation, returns at once | grant consumer returns at once |

So the lease must exceed the runtime of **approval-gated inline tools**, and
nothing else. That is a small, enumerable set, and its members are ones a
human just approved interactively — so a lease of a minute or two is
generous rather than a guess.

The rule states itself in Nessy's own vocabulary: **an approval-gated tool
returning `Awaited.Ready` must complete well inside the approval kind's
lease; slow work returns `Awaited.Deferred`.** That is what deferral is for,
and it makes the lease bound a declared category rather than an arbitrary
number.

Note the §5 index guard does **not** help here: a lease-expiry double-run
uses the same computation id the index already names, so it validates clean.
These are different failure modes with different mitigations.

`Backoff` is ours at the same call site, which incidentally fixes a real
defect: Nessy has no backoff today, so a consumer that keeps throwing
hot-loops on every sweep.

### 11.3 An orphaned approval can run the tool twice — closed

The §5 crash window leaves an approval computation with no index entry, and
the redrive creates a second one. Both are **live approvals**. The human
sees two prompts (today impossible, because `created.created()` suppresses
the duplicate), and if both are approved, both fan out grants and each grant
runs the tool. The reducer dedups the second *fold*, never the second
*execution*.

**As shipped (Task 8):** the guard is identity-checked on all three arms — a
grant, a failure, or an expiry is admitted iff the call's dispatch entry
currently exists and names THIS EXACT computation, not merely an entry of the
right kind. `DeliveryWorker.isCurrentDispatch` is the one predicate all three
arms (`deliverApprovalGrant`, `foldApprovalFailure`, and the tool kind's own
`foldOps` deletion) share.

**History.** Task 3 shipped a weaker form: `ContinuumClient#deliverResults`
handed its consumer only `(continuation, TypedOutcome)`, never the delivery's
own `computationId` (`CompletionDelivery` carried it, but the typed client
layer did not pass it through), so the guard could only ask whether the
call's dispatch entry currently existed and was APPROVAL-kind — a predicate
on the call's **address**, not on the computation, which discriminated
finished calls from unfinished ones rather than real approvals from orphans.
Two of that guard's three gaps closed before Task 8: the `Awaited.Ready`
shape closed because the real grant's own fold deleted the index entry
before an orphan's grant was ever drained, and the deferred-tool shape closed
in Task 4, when `ComputationDeferredToolCallPolicy.onDeferred` started
overwriting the dispatch entry unconditionally to a TOOL entry on every
deferral. The third gap — deny/failure/expiry ran unguarded, so an orphan's
expiry could fold a `ToolFinished(Failed)` over a still-live call and delete
its index entry, silently swallowing the real approval's eventual grant —
could not be closed with an address-only guard, since an orphan's failure or
expiry is indistinguishable from the real one's without the computation id.
Continuum 0.3.0 puts `computationId()` on `TypedDelivery` itself, which is
what makes the identity check above possible; Task 8 also closed a third,
previously-undocumented site of the same shape (found in Task 4's review):
the tool kind's own `foldOps` deleted its dispatch entry unguarded, so a
stale redelivery of an already-superseded tool computation could delete an
entry a newer, still-live tool computation had since overwritten it to name.

### 11.4 Documentation this change owes

`docs/concepts/storage.md` and `docs/concepts/durable-computation.md` must
carry the durability-matching rule as a warning, not a footnote: **the
computation store and the substrate must have matching durability.** Both
failure directions above, named, with the symptom a reader would actually
observe — a tool result that never arrives, or a call that never completes.

`storage.md` currently tells readers to "supply a durable implementation
through `.substrate(Substrate)` to persist." After this change there are two
durability knobs where there was one, and that sentence becomes a trap
unless it names its partner.

### 11.5 An unavailable index store spams approvals

If the index write fails persistently while Continuum is healthy, every
redrive creates another approval computation and notifies the human again,
bounded only by redrive frequency. Low likelihood — both are local — but the
failure is user-visible and unbounded.

**Shipped:** the `DispatchIndex.record` call sites in
`ComputationApprover.adjudicate` and
`ComputationDeferredToolCallPolicy.onDeferred` wrap the call so any
`RuntimeException` — anything other than the `ConflictException` `record`
already retries forever internally — is logged at error, naming the call
site, then rethrown. This does not fix the spam; it stops the spam from
being silent, which is the point: an operator watching logs now sees the
orphaned-computation failure the moment it starts, rather than only inferring
it later from a flood of duplicate approval notifications.

### 11.6 Pool starvation under concurrent slow approvals

`nudge()`'s submitted drain passes and one `Harness`'s own `ComputationScheduler`
six scheduled pumps (§7) share **one small pool** (two threads) — as §7
now corrects, this pool is per-`Harness`, not shared across agent types.
`drainApprovals` runs a granted tool **inline**, on the pool thread, via
`executeGrantedToolNow` (§11.2) — it does not hand the tool off the way the
model-call and deferred-tool effect arms do.

So two concurrent approvals of slow (but `Awaited.Ready`) tools can occupy
both of one harness's pool threads for as long as those tools run. For that
duration, every other pump sharing that same harness's pool — the tool
kind's deliver, and both kinds' expire and purge pumps, five pumps in all —
is starved: nothing drains, expires, or purges on that harness until a
thread frees up. Because the pool is per-`Harness`, the starvation is
confined to that one harness; a second harness for a different agent type
has its own pool and its own two threads, untouched.

This is spec-directed, not a defect: §7 rules the pool small and per-
harness, and §11.2 rules the approval consumer runs `Awaited.Ready` tools
inline. The risk is named here because the threshold is concrete and easy
to hit by accident — **two** concurrent slow approvals is enough to starve
one harness's pool completely, with a default pool size of two.
