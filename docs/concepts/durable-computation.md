# Durable Computation

Every deferred question in Nessy — an approval, a slow tool, a callback from
outside the process — travels the same pipeline: a chain of ownership
transfers, never a future a live process awaits.

```
pending computation ──(result arrives)──▶ delivery drained
delivery drained ──(fold committed)──▶ advanced scope state
```

Nothing waits: the continuation handed at creation is the label, and
delivery means reconciling the result into the fold — not landing in any
intermediate container Nessy owns.

## Continuum owns the lifecycle

Nessy adopted `org.jwcarman.continuum` for the pending record, the deadline,
expiry, and the outbox that used to be hand-rolled over `Substrate`. Nessy
keeps the reducer, the two desks, and the routing between them.

A harness holds two Continuum clients, one per kind, both named
`<kind>/<agentType>`:

| Kind | Client | Carries |
|---|---|---|
| `approval/<agentType>` | `ContinuumClient<Decision, Routing>` | a human decision |
| `tool/<agentType>` | `ContinuumClient<ToolResult, Routing>` | a deferred tool's answer |

Both clients complete with a `Routing` continuation — the scope's
coordinates plus the originating `ToolCall` — so folding a result needs no
lookup back into the fold that started it.

!!! warning "The two stores must have matching durability"
    A harness now writes to two stores: Nessy's own [`Substrate`](storage.md)
    (state, transcripts, memory, intent, backlogs, and the dispatch index
    below) and Continuum's own computation store (approvals and deferred
    tool calls, plus their outbox). **Both must be in memory, or both must
    be durable — never one of each.**

    Mixing them breaks differently in each direction:

    - **Durable computations, in-memory substrate.** A restart wipes scope
      state but not Continuum's pending work. Every surviving delivery
      lands on a scope restored to `Idle`, whose reducer ignores it. A tool
      result that never arrives, and a call that never completes — nothing
      logs an error, because nothing failed in the ordinary sense.
    - **In-memory computations, durable substrate.** A restart wipes
      Continuum's pending work but not the dispatch index that names it.
      The gate finds an index entry for a computation that no longer
      exists and absorbs every redrive as "already in flight," forever.
      Calls hang permanently.

    Silent loss is bad; a permanent hang the absorption machinery actively
    sustains is worse. `InMemorySubstrate` is the only `Substrate` Nessy
    ships today, so the only coherent wiring right now is both in memory —
    which is exactly what `Nessy.harness(...)` gives you by default. A
    durable pairing needs a durable `Substrate`, which does not exist yet;
    Continuum's own `continuum-jdbc` provider is a TCK-certified PostgreSQL
    backend and is not the blocker. `HarnessConfig.finish()` logs a warning
    at startup if exactly one of the two stores looks like the in-memory
    default — a backstop, not a substitute for wiring them together
    correctly.

## The dispatch index: what survives a redrive

Continuum mints its own opaque computation ids — Nessy no longer derives
them by digest. That means Nessy needs its own memory of "this call is
already in flight," so a staleness redrive doesn't ask a human twice or run
a tool twice. `CallAddress` — `(agentType, agentId, responseId, callId)` —
still derives a stable key the same way it always identified a call:

```java
public record CallAddress(String agentType, String agentId,
                          String responseId, String callId) {
  public String indexKey() { ... } // SHA-256 over the four coordinates
}
```

`DispatchIndex` (`nessy-agent`, one document per call, `kind=dispatch/<agentType>`
in `Substrate`) maps that key to a `DispatchEntry(computationId, kind)`, where
`kind` is `APPROVAL` or `TOOL` — whichever Continuum client currently owns the
call. Creating a computation and recording its index entry happen in that
order, never reversed: the return address must be durable before anything
can suspend on it. `RegistryToolCallExecutor`'s gate reads the index before
running the tool, assembling enrichers, or asking the policy at all — a
redrive that reaches a call already indexed absorbs there, silently,
whether it's still pending an approval, already a tool computation, or
already folded and the entry is simply gone.

## Outcomes

A computation completes with a value, not an exception. Continuum's own
`TypedOutcome<T>` — `Success`, `Failure`, `Expired` — is what a delivery
consumer reads; Nessy maps `Failure` and `Expired` onto an ordinary in-band
tool failure, and `Success` onto whatever the kind carries: a `Decision`
for an approval, a `ToolResult` for a tool.

```java
sealed interface Decision {
  record Allow() implements Decision {}
  record Deny(String reason) implements Decision {}
}
```

A denied approval and an expired or failed computation all fold as
`ToolFinished(Failed)` — in-band, exactly like any other tool failure the
model reads and reacts to. A thrown Java exception stays reserved for the
computation *infrastructure* breaking, a different problem than the work
coming back negative.

## The two desks

`harness.approvals()` and `harness.completions()` are the two doors a host
resolves a durable computation through — reachable for as long as the
harness is kept, from any thread, any time:

```java
public ApprovalDesk approvals();   // approve(id) / deny(id, reason)
public CompletionDesk completions(); // complete(id, result) / fail(id, reason)
```

Both desks hold a `ContinuumClient` directly — there is no Nessy-owned
adapter between a desk and Continuum. Completing (or approving, or denying)
nudges the delivery worker afterward, so the fold runs promptly rather than
waiting for the next scheduled drain; the call returns before that fold has
actually landed.

## Delivery

`DeliveryWorker` is the one consumer of both clients' `deliverResults`. For
each delivery it resolves the destination scope from the `Routing`
continuation, folds the outcome through the pure reducer, remembers what
the fold implies, and commits one `Substrate` batch — the scope's state CAS
plus the dispatch index entry's own deletion. Continuum acknowledges the
delivery once the consumer returns normally; a thrown exception releases
the claim for a later retry, backed by Continuum's own lease and backoff
rather than a Substrate-side reaper.

An approval's `Allow` decision is not itself a completion — the tool hasn't
run yet. The worker dispatches the call directly through the same
post-gate door a fresh dispatch uses, using the address the grant's own
continuation carries. There is no re-entry through the policy or the
approver: a call that has already cleared the gate is never re-judged.

Pumping — drain, expire, purge, for both kinds — runs on one shared
scheduler per harness, not a per-harness heartbeat thread. `nudge()` is the
happy-path shortcut on top of that same schedule.

## Deadlines

Every Continuum computation carries a deadline; there is no deadline-less
wait anymore. Approvals get a fixed 7-day deadline, harness-level. A
deferring tool's deadline comes from its own `timeout()`:

```java
Tool.of(SlowJob.class, t -> t
    .description("...")
    .defers((cmd, ctx) -> jobs.submit(cmd, ctx))
    .timeout(Duration.ofMinutes(10)));
```

Unset, a tool computation still gets a default deadline (currently one day)
rather than waiting forever — Continuum requires every computation to carry
one. `timeout()` means **fail at this point**, not retry at this point:
retryable redispatch is not implemented (see [Tools](tools.md)), so an
overdue computation is expired and folded as a failure the model reads,
never resubmitted automatically.

## Worked example: a call survives its own instance dying

```java
harness.bind(AgentId.of("prod-eu")).tell("please restart prod-eu");
// ... the tool defers; DeliveryWorker has nothing to drain yet ...

ApprovalRequest request = requests.getFirst();
harness.approvals().approve(request.id());
// ... any node, any time later: the grant dispatches the call, the tool's
//     own completion arrives through harness.completions(), and the turn
//     completes ...
```

The instance that dispatched the call never has to still be running — as
long as the process it eventually completes on shares the same Continuum
store and the same `Substrate`. Today, with both in memory, that instance
is always the same process; only a durable pairing would let it be a
different one.

## Honest limits

The pipeline promises ownership transfer, never a live thread and never
exactly-once external work.

- **A tool's external work starts before its computation is created.** A
  deferring tool can only reveal that it's deferring by returning
  `Awaited.deferred()` after its own external call has already started, so
  a crash between that start and the `create` call landing loses the
  return address entirely — there is nothing yet to redrive.
- **Retryable redispatch is not implemented.** An overdue tool computation
  is expired, once, and folded as a failure. If your tool's external side
  effect is safe to redrive, that has to be handled outside this pipeline
  today — Continuum's own retryable client exists for exactly this, unused
  by Nessy so far.
- **Durability is all-or-nothing across two stores, and only one half
  exists.** See the warning above.

None of this is exactly-once anywhere it isn't claimed. What's promised —
stable dispatch identity, ownership-transfer delivery, in-band failure on
timeout or denial — is what the pipeline actually gives, today, over two
in-memory stores.

## Where next

- [The harness guide](../guides/harness.md) — the builder surface that
  wires a harness's Continuum clients and the approval arc end to end.
- [Authorization](authorization.md) — the ladder whose `RequireApproval`
  verdict is what puts a call in front of the approval gate in the first
  place.
- [Storage](storage.md) — the `Substrate` half of the pairing: state,
  transcript, and the dispatch index this page describes.
