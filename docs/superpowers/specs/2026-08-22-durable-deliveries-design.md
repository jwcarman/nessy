# Durable Deliveries — asynchronous request/reply with durable ownership

**Date:** 2026-08-22
**Status:** Ratified (James, in conversation, 2026-08-22)
**Naming ruling (2026-08-22, same day):** "parcel" was the teaching metaphor and
never ratified vocabulary — retired. The thing is a **delivery** (destination +
outcome, pending in the outbox); the worker is the **DeliveryWorker**. The
ratified nouns were already sufficient: outbox (the place), delivery (the
thing), fold (the landing).
**Origin:** synthesis of an external design review (ChatGPT findings, "Nessy
Durable Tool Execution", reviewed 2026-08-22) with the shipped design of
record. Adopted: the ownership-transfer pipeline, retry semantics, durable
deadlines, create-carries-continuation. Rejected: opaque computation ids
(recovery needs the derived lookup), a mandatory deadline (approvals wait
indefinitely by design), a separate inbox container (the fold IS the landing),
multicast continuations (nothing has two recipients).
**Amends:** `2026-08-20-durable-computation.md` — §12 (atomic await) is
RETIRED with `await()` itself; preamble ruling 6 (complete-unknown creates
terminal) is REVERSED; preamble ruling 4 (deterministic ids) is AMENDED to
derive over `ModelResponseId`. The one-flip law survives as the atomicity of
the ownership transfer. Also amends the substrate spec
(`2026-08-21-scoped-store-design.md`) §6.5/§6.6: computations become
presence-means-pending; the outbox recipe is BUILT by this reform.

---

## 1. The frame

A durable tool invocation is asynchronous request/reply with durable routing —
not a future that a live process awaits. The result may arrive seconds or days
later, possibly in a different process. The design is a chain of **atomic
ownership transfers** over the substrate:

```
pending computation ──(result arrives; atomic)──▶ pending delivery (outbox)
pending delivery ──(delivered; atomic)──▶ advanced fold (the scope's state)
```

At every committed point, the eventual result is durably owned by exactly one
stage. An outbox row is a **delivery**: an outcome plus its
durable destination. There is no waiting anywhere — the continuation handed at
creation is the label, and delivery means *reconciling the delivery into the
fold* (the tool call meets its result in `AwaitingTools`), not landing in any
intermediate container.

## 2. Identity

- **`ModelResponseId`** — a Nessy-generated id (UUIDv7) for each committed
  model response. Generated in the **model-call executor** when the response
  arrives — never in the reducer, which stays a pure fold — carried on
  `ModelOutcome.Responded`, and stored as a component of
  `Phase.AwaitingTools`. Re-handling the same event is deterministic; only the
  committed id ever escapes (dispatch follows save, per existing law).
- **`ToolInvocationId`** = (`ModelResponseId`, provider `ToolCall.id`) — the
  logical identity of one tool invocation. Stable across every redispatch and
  replay; handed to **every** tool invocation (via the tool context) for
  logging, correlation, and idempotency keys. Retryable-or-not is policy;
  identity is universal.
- **`ComputationId` stays deterministic — derived from the fold**:
  `tool:agentType:agentId:responseId:callId` (approvals:
  `approval:agentType:agentId:responseId:callId`). Ruling 4 amended, not
  repealed: "deterministic" means recomputable from committed state, which is
  what gives redrive its O(1) lookup and `create` its natural idempotence.
  The provider-uniqueness hole (provider call ids are not contractually
  unique over an agent's lifetime) is closed by `ModelResponseId`, not by
  abandoning derivation.

## 3. The backend

```java
interface DurableComputationBackend {
  CreateResult create(ComputationId id, ToolInvocationId invocation,
                      Continuation returnAddress, Optional<Instant> deadline);
  CompletionResult complete(ComputationId id, Outcome outcome);
  Optional<PendingComputation> find(ComputationId id);
}
```

- **`create` carries the continuation.** The return address is durable before
  any dispatch — the register-after-create window is unexpressible.
  `create` remains get-or-create on the derived id (idempotent redrive).
- **Exactly one continuation.** It is a required scalar component — a durable
  reply-to address (`type`, routing data), never executable. The old
  `List<Continuation>` multicast surface and its set-dedup die; a future
  fan-out need would be N deliverys at delivery, never N continuations.
- **`await()` does not exist.** Nothing waits. `AwaitResult`, the
  atomic-await law (§12 of the durable spec), `Registered`/`AlreadyCompleted`,
  and the CAS-append continuation loop all die with it.
- **Presence means pending.** There are no terminal computation records and no
  status field. `complete(id, outcome)` performs one substrate `batch`:
  DELETE `computation/{id}` + CREATE the delivery. Completing an **absent** id
  returns a benign already-done result (the computation completed earlier, or
  never existed — indistinguishable and equally ignorable under at-least-once
  result delivery). Ruling 6 is reversed: completion never creates records.
- **The one-flip law survives as CAS on the delete**: two racing completers —
  exactly one wins the ownership transfer; the loser observes absence.

## 4. The delivery (outbox, now built)

`kind=outbox`, one document per delivery, UUIDv7 key (creation-ordered scans),
payload `{ destination: Continuation, outcome: Outcome }` — serialized via
Jackson annotations on the wire records, no hand-built JSON. Deliveries are
pending-only: delivering deletes them. Substrate spec §6.6's layout and
self-draining reasoning apply as written; its "lands with the first durable
adapter" deferral is superseded — the delivery pipeline ships now, on the
in-memory substrate, and JDBC inherits it.

## 5. Delivery is fold-advance

The delivery worker, per delivery:

1. read the delivery; resolve the destination scope from the continuation;
2. load state; `phase.handle(ToolFinished/decision event)` — the pure reducer
   reconciles the pending call with its outcome;
3. one substrate `batch`: **[ journal appends for committed messages,
   CAS-write of the advanced state, DELETE delivery ]** — the atomic
   turn-advance the substrate's `batch` was specified for; this lane retires
   the duplicate-message replay residue;
4. dispatch the transition's effects (commit-before-dispatch, unchanged law).

- CAS miss → re-read, re-handle, retry (single-writer-per-scope law).
- `Transition.ignore()` (already-reconciled call — the reducer IS the dedup)
  → the batch is just the delivery delete.
- A crash anywhere leaves the delivery pending; redelivery re-runs a pure fold.
- The user-observation backlog is untouched: engine events never queue in it;
  their pending form IS the pending delivery.
- The live `ContinuationDispatcher` fire-path retires; continuations are read
  by exactly one consumer — the delivery worker. In-process completions nudge
  the worker after commit; polling is the recovery net, never the happy-path
  latency.

## 5a. The approval gate (ruled 2026-08-22, closing the grant gap)

The policy runs **inline, exactly once, before the tool ever gets a chance to
do anything** — approval is a gate in a forward-moving sequence, never a state
downstream code reconstructs.

```
ExecuteTool
  → policy (inline)
      Allow           → proceed to the tool call
      Deny            → ToolFinished(Failed), inline
      RequireApproval → ask the Approver, inline
          immediate Decision → proceed / fail — no computation involved
          approver parks     → create the approval computation whose
                               CONTINUATION carries the tool call itself
                               {routing, invocation id, call name + args};
                               suspend
grant → complete(approval, allow) → delivery{destination: that continuation}
      → the worker reads the continuation — it HAS the call — and dispatches
        it directly; no fold read, no re-derivation, no policy
deny  → delivery → fold-advances ToolFinished(Failed)
```

- **The continuation is the work order.** `ApprovalRequest` already hands the
  approver `(address, call, context)`; the call riding the approval
  continuation is the same data taking one more step. The reply-to of a grant
  is the dispatcher, with the work attached.
- **Consumption is atomic in both arms**: an immediate tool's delivery is
  consumed by the result's own fold-advance batch (run tool, then
  `[journal appends, state CAS, delete delivery]`); a durable-completing
  tool's delivery transfers ownership in one batch
  (`[create tool computation, delete delivery]`) before external dispatch.
  Invariant 5 holds at every committed point.
- **Nothing re-enters the gate.** The staleness redrive never re-emits
  `ExecuteTool` for a call that has gone durable (either computation present —
  both deadline-governed); liveness past the gate belongs to the reaper, per
  the §6 ownership split. The only legitimate gate re-entry is a crash
  *before* the approval computation commits, which the deterministic id makes
  an idempotent no-op.
- Because the sequence only moves forward, "granted" needs no durable marker:
  a granted call is simply *work in flight*, owned by its delivery or its
  tool computation.

**Honesty amendments (2026-08-22, post-implementation review — probe-verified):**

- **"Before external dispatch" is not literally achievable for `Awaited`-shaped
  tools**: a tool reveals deferral only by *returning* `Awaited.deferred`, so
  `execute()` necessarily runs before the transfer batch can commit. The real
  guarantee: the batch commits before control returns to the pipeline, and a
  crash between the tool's external start and the batch leaves the delivery to
  be redriven — at-least-once, per the Tool contract. Invariant 1 reads
  accordingly: the computation and return address are durable before any
  *completion* can be lost, not before the external work begins.
- **Winner scope**: within one host, delivery of a grant has exactly one
  winner (the worker's claim). Across hosts, a grant delivery may be drained
  more than once and the tool's `execute()` invoked more than once — for
  immediate AND durable tools alike — until the §6.6 outbox lease lands with
  the first durable adapter. The durable record is single-winner regardless
  (the transfer batch's CAS); only the external side effect is at-least-once,
  which the spec already promises is the contract ("never exactly-once
  external side effects").
- **The grant-delivery-pending window**: between the grant's completion batch
  (approval computation deleted, delivery created) and the worker draining it,
  neither computation exists, so a redrive in that window re-asks the approver
  (the delivery is keyed randomly and not derivable from the address).
  Closing it needs a ruling — a deterministic grant-delivery key or an
  explicit granted marker — PARKED on the decision list; until then the
  absorption guarantee is: pending-ask and in-flight-work absorb; the
  completed-but-undrained instant does not.

## 6. Retry, deadlines, and the reaper

- **`RetrySemantics`** (`RETRYABLE` / `NON_RETRYABLE`) is declared at tool
  registration, default `NON_RETRYABLE`. `RETRYABLE` is the tool author's
  assertion that redispatch with the same `ToolInvocationId` is safe — how
  (idempotence, dedup, provider idempotency keys) is the tool's business.
  Nessy guarantees stable identity and durable routing, never exactly-once
  external side effects.
- **Deadline is durable, optional state**: a tool may declare a timeout;
  dispatch stamps `deadlineAt = now + timeout` into the computation. No
  timeout → no deadline → waits indefinitely (approvals legitimately wait
  days; approval expiry/escalation belongs to the roadmap's park-lifecycle
  governance, not here).
- **The reaper** is the host worker's second sweep (same heartbeat as delivery
  delivery): scan `keys("computation")`, decode, compare deadlines — cheap
  BECAUSE presence-means-pending keeps the table O(currently-parked).
  - `RETRYABLE` overdue → CAS-bump the deadline (multi-host arbitration for
    free: losers re-read and skip) and redispatch the same
    `ToolInvocationId`.
  - `NON_RETRYABLE` overdue → manufacture the completion:
    `complete(id, Failure(TIMEOUT_NON_RETRYABLE))` — the indeterminate
    outcome rides the normal pipeline into the fold. No special timeout path.
- **The deadline is the crash-recovery net**: crash-after-create-before-
  dispatch needs no separate recovery pass — the computation times out and
  retry semantics apply. Redispatch ownership splits cleanly: the staleness
  redrive keeps model calls and immediate tools; durable-tool redispatch is
  exclusively deadline-driven.

## 7. Invariants

1. A durable tool is never dispatched before its computation and return
   address are durable.
2. Every redispatch of a logical invocation reuses its `ToolInvocationId`.
3. `RETRYABLE` is the tool author's safety assertion; Nessy never
   auto-retries `NON_RETRYABLE` work.
4. Presence of a computation record means the result is pending; presence of
   a delivery record means delivering is pending; neither has terminal residue.
5. Completion and delivery are each one atomic substrate batch; at every
   committed point the result is owned by exactly one stage.
6. No live JVM, thread, callback, or future is required for a result to
   eventually advance its scope.
7. The reducer stays pure; ids are generated in executors and carried in
   events.

## 8. What dies

`await()`, `AwaitResult`, `CreateResult.created`-driven pickup logic,
`ComputationStatus`, `ALREADY_TERMINAL`, terminal computation records, the
continuation list and its set-dedup, the atomic-await CAS loop, the live
`ContinuationDispatcher`/`ContinuationHandler` fire path, and the
`SubstrateComputations` status-flip machinery. The scoped-store spec's parked
"terminal-computation retention" question dissolves — there is nothing to
retain.

## 9. Tests the reform must carry

Ownership-transfer atomicity under injected conflict at both hand-offs
(computation→delivery; delivery→fold); process-loss (create+dispatch in one
runtime, complete in a fresh one, fold advances in a third); concurrent
completion (one winner); duplicate outbox delivery (reducer absorbs, delivery
consumed once); retry identity (same `ToolInvocationId` across redispatch);
non-retryable timeout traveling the normal pipeline; deadline-less
computations never reaped; the full grant arc end-to-end (park → grant →
tool executes without any policy re-ask → result folds), including grant
survival across a process loss between completion and dispatch.
