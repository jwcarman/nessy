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
| `approval/<agentType>` | `ContinuumClient<Approval, ApprovalRouting>` | an approver's answer |
| `tool/<agentType>` | `ContinuumClient<ToolResult, Routing>` | a deferred tool's answer |

Both clients complete with a routing continuation — the scope's
coordinates plus the originating `ToolCall` — so folding a result needs no
lookup back into the fold that started it.

!!! warning "The two stores must have matching durability"
    A harness now writes to two stores: Nessy's own [`Substrate`](storage.md)
    (state, transcripts, memory, intent, and backlogs) and Continuum's own
    computation store (approvals and deferred tool calls, plus their
    outbox). **Both must be in memory, or both must be durable — never one
    of each.**

    Mixing them breaks differently in each direction:

    - **Durable computations, in-memory substrate.** A restart wipes scope
      state but not Continuum's pending work. Every surviving delivery
      lands on a scope restored to `Idle`, whose reducer ignores it. A tool
      result that never arrives, and a call that never completes — nothing
      logs an error, because nothing failed in the ordinary sense.
    - **In-memory computations, durable substrate.** A restart wipes
      Continuum's pending work but not the scope's own phase, which still
      names the computation a status is `AwaitingApproval`/`AwaitingResult`
      over. Nothing will ever deliver against that id again — the call
      waits forever, and nothing logs an error, because nothing failed in
      the ordinary sense.

    Silent loss is bad either way. Both in memory is what `Nessy.harness(...)` gives
    you by default. Both durable is `.substrate(new JdbcSubstrate(ds))`
    with `.continuum(...)` handed a `continuum-jdbc`-backed Continuum over
    the same database — `DurableResumeTest` in `nessy-agent` is that
    pairing, verbatim. `HarnessConfig.finish()` warns at startup when it
    can tell the tiers differ: a substrate other than `InMemorySubstrate`
    with no Continuum supplied, the hang direction. A supplied Continuum is
    not inspected — the harness cannot see its repository — so the caller
    who supplies one owns the match. A backstop, not a substitute for
    wiring them together
    correctly.

## A call's lifecycle is in the phase

Continuum mints its own opaque computation ids — Nessy no longer derives
them by digest — and, unlike an earlier design, Nessy keeps no side index
of "this call is already in flight." Every tool call inside `AwaitingTools`
carries its own `CallStatus`, and two of its five states name a parked
computation directly:

```java
sealed interface CallStatus {
  record Pending() implements CallStatus {}                             // approval sought, no answer yet
  record AwaitingApproval(ComputationId approval) implements CallStatus {} // Continuum holds the ask
  record Running() implements CallStatus {}                             // approved; the tool is executing
  record AwaitingResult(ComputationId tool) implements CallStatus {}    // Continuum holds the result
  record Finished(ToolResultBlock result) implements CallStatus {}      // an outcome, success or failure
}
```

**The phase is the map.** A call waiting on a computation records that
computation's id in its own status, is resolved by that computation's
delivery, recognizes the delivery by the id, and is never re-fired while it
waits. A delivery that names an id the call's current status doesn't hold —
an orphan, a duplicate, an answer against a call already `Finished` — is
dropped with a `WARN` log naming the scope, the call, the computation, and
the status the phase actually found; nothing is released for redelivery.
That WARN, not a silent absorb, is what a mismatched delivery looks like
today.

A quiet `AwaitingTools` re-fires on staleness the same way any other phase
does: every `Pending` call is asked again, every `Running` call is run
again, and `AwaitingApproval`/`AwaitingResult` calls are left alone —
Continuum holds those and will deliver. That re-fire is the whole of what
used to need a separate index: the phase already remembers which calls are
someone else's problem right now.

## Outcomes

A computation completes with a value, not an exception. Continuum's own
`TypedOutcome<T>` — `Success`, `Failure`, `Expired` — is what a delivery
consumer reads; Nessy maps `Failure` and `Expired` onto an ordinary in-band
tool failure, and `Success` onto whatever the kind carries: an `Approval`
for the approval kind, a `ToolResult` for the tool kind.

```java
sealed interface Approval {
  record Approved(Optional<String> reference) implements Approval {}
  record Denied(String reason, Optional<String> reference) implements Approval {}
}
```

A `Denied` approval and an expired or failed computation all fold as an
in-band failure the model reads and reacts to. A denial that finishes a
call is committed to the transcript like any other outcome — the fold
narrates both `ToolCallDecided` and `ToolCallCompleted`, so a human
reviewing the turn later sees the refusal, not a gap. A thrown Java
exception stays reserved for the computation *infrastructure* breaking, a
different problem than the work coming back negative.

## Audit: what the core owes, and what it does not

Evidence, identity, votes, ledgers, and retention belong to the approver
subsystem — the thing that talked to the humans, ran the policy engine, or
called the risk service. Pulling that into Nessy's core would make it a
worse audit log than the thing that did the work. What the core owes is
only what nothing outside it can produce:

1. **The question, as asked** — the `ApprovalRequest` JSON, built at the
   moment of the call from state only the harness has.
2. **A handle whose completion resumes the agent** — `defer()`'s id.
3. **The resumption** — the fold that runs the tool once the answer lands.
4. **The clock** — Continuum's own deadline on the parked computation.

And one join: the answer's `reference` (`Approval.Approved`/`Denied`'s own
field), pointing from the fold's record to the subsystem's. The subsystem
holds — or hashes — the request document it received, so "what did the
approver see when it said yes?" is answerable from *its* storage; the desk
shows the *same* document from Nessy's, through `harness.approvals()
.request(agentId, callId)`. The desk is the one door that must not take an
anonymous yes, because nothing stands behind it (see
[The harness guide](../guides/harness.md#writing-an-approver)).

## The two desks

`harness.approvals()` and `harness.completions()` are the two doors a host
resolves a durable computation through — reachable for as long as the
harness is kept, from any thread, any time:

```java
public ApprovalDesk approvals();     // approve(id, principal, note) / deny(id, principal, reason)
public CompletionDesk completions(); // complete(id, result) / fail(id, reason)
```

Both desks hold a `ContinuumClient` directly — there is no Nessy-owned
adapter between a desk and Continuum. Completing (or approving, or denying)
nudges the delivery worker afterward, so the fold runs promptly rather than
waiting for the next scheduled drain; the call returns before that fold has
actually landed. `ApprovalDesk` takes a principal and a note because it is
the one door with no subsystem behind it — when a person answers there
directly, nobody else is collecting evidence, so it refuses to fold in an
anonymous yes. Both fold into the answer's `reference`.

## Delivery

`DeliveryWorker` is the one consumer of both clients' `deliverResults`. For
each delivery it resolves the destination scope from the routing
continuation, folds the outcome through the pure reducer, remembers what
the fold implies, and commits one `Substrate` batch — the scope's state CAS
alone; there is no second document to keep in step. Continuum acknowledges
the delivery once the consumer returns normally; a thrown exception
releases the claim for a later retry, backed by Continuum's own lease and
backoff rather than a Substrate-side reaper.

**Neither consumer ever runs a tool.** The approval consumer's whole job is
to fold `ApprovalAnswered` into the scope; if that answer was an approval,
the fold itself is what emits `RunTool`, dispatched afterward on the
harness's own executor — never inside the delivery's lease. That is why the
approval kind's lease can be short (30 seconds): it only ever pays for
writing one fact, never for the work a human approved. The tool kind's
consumer is the same shape — it folds `ToolFinished`, nothing more.

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
// ... the approver defers; DeliveryWorker has nothing to drain yet ...

ComputationId id = requests.getFirst();
harness.approvals().approve(id, "demo", "");
// ... any node, any time later: the answer folds, RunTool dispatches, the
//     tool's own completion arrives through harness.completions() if it
//     also defers, and the turn completes ...
```

The instance that dispatched the call never has to still be running — as
long as the process it eventually completes on shares the same Continuum
store and the same `Substrate`. With both in memory, that instance is
always the same process; with a durable pairing it can be a different
one — a fresh process over the same database picks up the delivery and
finishes the turn.

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
- **Durability is all-or-nothing across two stores.** See the warning
  above; both halves exist, and the harness only warns about the mismatch
  it can see.

None of this is exactly-once anywhere it isn't claimed. What's promised —
stable dispatch identity, ownership-transfer delivery, in-band failure on
timeout or denial — is what the pipeline actually gives, over two
in-memory stores by default and over two durable ones when both are
supplied.

## Where next

- [The harness guide](../guides/harness.md) — the builder surface that
  wires a harness's Continuum clients and the approval arc end to end.
- [Authorization](authorization.md) — the ladder whose `RequireApproval`
  verdict is what puts a call in front of the approval gate in the first
  place.
- [Storage](storage.md) — the `Substrate` half of the pairing: state,
  transcript, and the dispatch index this page describes.
