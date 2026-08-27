# The turn document

**Status: SUPERSEDED 2026-08-27** by `2026-08-27-actor-runtime-design.md`.

This describes a hand-rolled design — an aggregate root holding pending events and
effects, with a driver, a lease and a dispatch record we would build ourselves. The
actor model supplies all of it, so the design here is not what we are building. It is
kept because the REASONING survives the change of mechanism: the fact/derived split,
the ordering rules, the at-most-once-by-result argument, and the durability tiers all
carried over intact. Read it as the argument that led to the decision, not as a plan.

---

**Original status:** proposed, 2026-08-27. Not approved. Supersedes much of
`2026-08-26-deferral-by-callback-design.md` — see §7.

**Origin:** the watchman soak on 2026-08-26 proved the durable loop end to end
against Postgres. Three questions followed, and all three turned out to be one:
*how do I find the ones that are stalled?*, *how do I know something died versus
waiting?*, and *what wakes the process back up?*

---

## 1. Thesis

**One document per turn, treated as an aggregate root.** It holds the phase, the
pending effects, and the lease. One CAS writes all of it, so it can never disagree
with itself.

```
claim inbox rows → fold them → effects accumulate in the doc → ONE write
                                                             → dispatch
```

No multi-row transaction, no `Substrate.Op` batch, no DynamoDB `TransactWriteItems`.
Single-document CAS, which every backend already has.

Dispatch stays deliberately *outside* the write, because the world does not roll
back. That is why an effect is *marked* dispatched rather than removed, and why a
retry is an *enqueue* rather than a hope.

## 2. The turn

A **turn** is: an observation arrives, a final response is produced. Many model calls
may happen inside one. Between turns the scope is `Idle`.

That matches `TurnOutcome` ("the turn completed and settled on a reply"). It does
**not** match `AgentPhase.AwaitingTools(Message assistantTurn, …)`, where
`assistantTurn` means one model response. **That field is misnamed** — it is the
assistant's *message*, one of several within a turn, and every sentence here is
ambiguous until it becomes `assistantMessage`.

**A turn must be driven to completion by something.** Today nothing owns that:
`drive()` has exactly two callers, `tell()` and itself. A turn whose driver dies
waits until someone speaks to that agent again. The watchman survives only because
cron speaks to it every 60s — an accident of that example, not a property of the
framework.

Two triggers, one obligation:

- `Idle` + something in the inbox → start a turn
- not `Idle` → drive the turn to completion

## 3. Inbox outside, outbox inside

The asymmetry is the design, and it is principled:

- **The outbox** is written by the fold and read by the driver — same party, no
  contention. It belongs *in* the aggregate.
- **The inbox** is written by outsiders (a human clicking deny, a tool result
  arriving) and read by the driver. Putting it in the document would make every
  answer CAS against whatever the driver is doing. It belongs *outside*.

The outside inbox already exists: `continuum_outbox` is exactly that. **This design
needs no new tables.**

The ack of inbox rows is a different write from the document CAS, so they cannot be
atomic — and do not need to be. **Re-folding is already a no-op:** fold
`ApprovalAnswered` once and the call moves `AwaitingApproval → RunningTool`; fold it
again and it meets `RunningTool`, which does not admit it, so it is dropped with a
WARN. At-least-once inbox delivery is safe *because* each phase is strict about what
it accepts. The drop rule was written for stale deliveries and happens to cover
redelivery too.

## 4. The effect lifecycle — where the dispatch record lives

An outbox entry outlives its own dispatch. That is the whole point.

| entry state | meaning | action |
|---|---|---|
| pending, not dispatched | crashed before we ran it | dispatch — nothing happened |
| **dispatched, no outcome** | **it ran; we do not know how it ended** | consult `maxAttempts` |
| absent | the outcome folded and removed it | nothing |

The middle row is the thing we have never had. `RunningTool` today cannot
distinguish "never started" from "ran, had side effects, died" — which is why
`outstanding()` re-fires every tool unconditionally, including `restart_container`
and `prune_images`.

Marking dispatched costs a second CAS. The alternative is treating "pending" as "may
or may not have run", which is simpler but makes every crash look dangerous, so
`maxAttempts = 1` tools would fail conservatively when most never started. Pay the
write.

## 5. Retry

A failure folds; the same write enqueues the next attempt if policy allows. You
cannot fail and lose the retry, or retry without recording the failure.

Entries are immutable — each attempt is its own record, so the history is
append-only and `available_at = now + backoff(attempt)` falls out. This is how
Temporal's history works: each attempt is an event, not a mutated counter.

Two fields on a tool call:

- **`maxAttempts`** — frozen when the call starts
- **`attempts`** — how many have been tried

The idempotency declaration collapses into the first. `maxAttempts = 1` *is* "not
safe to repeat"; a separate boolean is redundant.

**The policy must be frozen into the phase, not looked up at fold time.** A fold that
consults live configuration is not a fold. Freezing keeps a decision reproducible
after someone edits a tool's policy, and makes it visible in Postgres — you can see
why a call gave up.

**Open:** a clean failure enqueues attempt N+1, but a process that *dies* enqueues
nothing and its entry sits marked-dispatched carrying attempt N — so a crashloop
could retry forever without the counter moving. Preferred fix: whoever reclaims a
lapsed lease enqueues N+1 and supersedes the old entry, in the same write.

## 6. At most once, by result — not by id

Route by **address** (`agentId`, `callId`), which the continuation already carries.
Accept the first answer for a call with no result; drop anything after a result, with
a WARN.

This removes two failure modes at once:

- The **early answer** race. Today a late park fold is a *hang*, because admission
  needs the record to exist. Under this rule it is *redundant* — the answer already
  landed, and the park is dropped with a WARN.
- The **dropped park** gap found in review on 2026-08-26: `handOff` cannot tell a
  committed fold from a dropped one.

**Condition:** the `ApprovalRequest` must be built **once per deferral**, not rebuilt
on each ask. `RegistryToolCallExecutor:470` states outright that re-running enrichers
"would build a different" request — so a rewind can put a differently-worded question
in front of a human, and applying an answer from one version to another is a silent,
attributable error.

Scoped precisely: before an approver defers, nobody has seen anything, so rebuilding
is harmless and arguably more current. From the deferral onward the question is
fixed. `SeekingApproval` carries nothing; the frozen request rides from the park on.

**Nothing may depend on the park fold having landed.** The deadline and the frozen
request die with it if dropped, so they can only be things we would *like* to show,
never things the machine needs. `AwaitingApproval` requires the request today; that
requirement must go, or the drop is not safe.

This is a *"these two race conditions annihilate"* argument. It deserves a test, not
a paragraph — if the second half quietly acquires a dependency later, it fails
silently and rarely.

## 7. What this deletes

If answers route by address, **there is no id to hand out.** The callback existed to
tell the world "here is the computation id." The world does not need our id; it needs
an address, and the call has had one since it was created. A tool says *"answer to
`/completions/{agentId}/{callId}`"* — available from `ToolContext`, nothing minted.

Deleted:

- `ComputationCallback`, and both `Deferred(callback, term)` shapes
- `DeferApproval` / `DeferToolCall`
- `DeferringApproval` / `DeferringResult`, and §9a's mandatory cell 1
- the fold-before-callback ordering problem — no id leaks early, because none exists
- `outstanding()`
- `StalenessPolicy` as a *recovery trigger*; it survives only as "when to give up on
  an attempt"

**This supersedes most of Task C1** (`1e0c4239`, `d81c5150`, `5b760093`, shipped
2026-08-26). Not an argument against C1 — forcing the ordering question is what
produced this — but C2/C3/C4 must not be built on C1's shape.

## 8. The lease

A field on the document. Holding it **is** being the driver. Taken when driving
starts, renewed while actually working, **released when the turn parks or reaches
`Idle`.**

A parked turn holds no lease and occupies no thread. Deferral is a *state
transition*, not a thread blocked on a socket: the effect creates the record, folds,
and returns. What is left behind is a row, not a stack frame. A three-day approval
wait is owned by its own deadline. **If we ever find ourselves renewing a lease
across a park, we have rebuilt a blocking wait with extra steps.**

Two timers, two questions — Temporal's split between a workflow-task lease (short,
held by whoever is making progress) and an execution timeout (long, covering waits).
One `StalenessPolicy` has been doing both jobs.

**The lease retires three problems at once:** who drives a stalled turn; how you find
stalled turns (they announce themselves by failing to renew — no scan, no projection,
no substrate query door); and per-scope fold contention, measured at 5 of 17 folds
retrying on 2026-08-26, which stops existing rather than improving, because two
drivers can no longer race for one document.

## 9. Fan-out

A claimed batch spans many agents and turns. **Parallel across scopes, sequential
within one** — and within a scope, one load, fold every event in order, one write.

Fold in `created_at` order within a scope: causally-related rows differ in creation
time, and arbitrary order can hand a phase an event from its own future.

Everything for one turn can fold at once. No earlier fold can strand a later row: the
phase leaves `AwaitingTools` only when *every* call has a result, so the last fold is
the one that completes the turn. A `ToolFinished` cannot reach `AwaitingApproval` in
the same batch, because the tool could not have run before the approval was answered
and folded in an earlier pass.

Accumulate effects across the whole group and dispatch after the single write.
`advance()` throws if a turn-completing transition carries effects, so losing them
fails loudly — the guard added 2026-08-26 after review.

## 10. Snapshot, not event-sourced

`nessy_document` holds the current phase; `nessy_journal` holds the conversation
transcript (`user-message`, `assistant-message`, `tool-exchange`). **There is no
durable record of `AgentEvent`s.**

So the word *replay* does not apply to this system and should not appear in its
documentation. Recovery is: read the document, dispatch what is pending. Temporal
replays because a workflow is *code*; our phase is *data*. That is an advantage of
the reducer shape, and it is why one document is sufficient.

The gap it leaves is **auditability**, not correctness: you can reconstruct what the
model saw and what is pending, but not the sequence of decisions. Retaining acked
inbox rows would supply that as a retention policy rather than a subsystem.

## 11. Open

1. **DynamoDB's 400KB item limit.** The phase already carries the full assistant
   message; adding pending effects and attempt history grows it. Postgres and Mongo
   will not care. DynamoDB is the ruled second backend and eventually will — this is
   the constraint that would force effects back out into their own rows.
2. **Continuum's role**, if tool calls and approvals become leased entries keyed by
   `(agent, call)`. Continuum still owns cross-process delivery, but the durable
   "this call is outstanding" record moves into the turn document. Clean division, or
   duplication? Worth putting to the Continuum session — same conversation as cancel.
3. **Verify the claim guard.** `SELECT` then `UPDATE continuum_outbox SET claimed_by
   = ?, claimed_until = ? WHERE id = ?` is two statements, safe only if the update
   guards on `claimed_until`. Unverified. Works perfectly on one box; bites the first
   time you run two.
4. **The retry crash path** (§5).
5. **`assistantTurn` → `assistantMessage`** (§2). Small, and this spec depends on it.
6. **Enricher cost** — enrichers run on every approval-gated call, including ones the
   approver answers instantly and whose result nobody reads. Performance, not
   correctness.

## 12. What this does NOT solve

Neither leases, dispatch records, nor `maxAttempts` tells you whether a dead tool had
side effects before it died. If the thread running `restart_container` dies halfway,
nothing here knows whether the container was restarted.

Temporal does not solve it either — it declares it the activity's problem: *"You
should always make your business logic Activities idempotent… Because Activities may
be retried, these functions may be executed more than once"* — and supplies an
idempotency key (Workflow Run ID + Activity ID). Their guarantee is worth stealing
verbatim: *"Temporal guarantees that the Activity will be observed as completed
exactly once. However, the Activity may be executed multiple times."*

`maxAttempts` is our version of that contract. It does not make a tool safe; it lets
the tool's author say whether it is.
