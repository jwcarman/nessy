# Turn execution: the problems

**Status:** problem statement, 2026-08-27. Deliberately **not** a design.
**Partly answered** by `2026-08-27-actor-runtime-design.md`: problems 2, 3, 4 and 5
are supplied by the actor model and Cluster Sharding; 1, 6, 7, 8, 9, 10 and 11 remain
ours; 12 remains unsolved by anyone. Kept because the problems are still the right
list, and because it records what each one costs if unsolved. Written
after the watchman soak of 2026-08-26 proved the durable loop end to end and then
raised more questions than it answered.

Where a solution is obvious it is noted as a *candidate*, not a decision. The point
of this document is to have the problems stated separately, so we can tell which
ones a proposed design actually solves.

Problems 1–5 are James's, stated on 2026-08-27. Problems 6–12 came out of the same
conversation and are here for him to accept, merge, or throw out.

---

## 1. Ingest and durably store observations on arrival

An observation must be safe the moment it is accepted, before anything decides what
to do with it. Accepting it and then losing it is the one failure a caller cannot
compensate for, because they were told it landed.

**Today:** `tell()` writes to `SubstrateBacklog` (durable) and then calls `drive()`
**on the calling thread**. The write is safe; what follows it is not.

**Breaks if unsolved:** a caller is told "accepted" for work that never happens.

---

## 2. Observations are keyed to a specific agent id

Not a global queue. Every observation belongs to one agent, and ordering matters
within that agent and nowhere else.

**Today:** the backlog is per-scope, so this holds.

**Note:** this makes agent id the partition key for everything downstream — queueing,
leasing, notification. Whatever we build inherits that.

---

## 3. An observation can only be consumed when no turn is active for that agent

This is the constraint that makes it more than a queue. At most one turn per agent at
a time; observations queue behind the running one.

**Prior art:** SQS FIFO `MessageGroupId` (one in flight per group), Kafka
partitioning by key, Temporal's one-execution-per-workflow-id.

**Today:** `drive()` loads the phase, and only drains the backlog if `Idle`. Correct,
but it is a check performed by whoever happens to call `drive()` — there is nothing
preventing two callers doing it at once beyond the CAS on the document.

**Hard part:** "is a turn active" and "may I start one" have to be answered
atomically, or two drivers both see `Idle` and both start a turn.

**Candidate:** the answer is the same fact as problem 4 — acquiring the lease *is*
the exclusivity, so the picker acquires first and then looks at the phase, rather
than querying for eligibility and racing.

---

## 4. Leases: an owner for the agent id

Something must own driving a turn to completion, and ownership must survive the owner
dying. A lock will not do — the holder can crash.

**Today:** nothing. `drive()` has exactly two callers, `tell()` and itself. A turn
whose driver dies waits until someone speaks to that agent again. The watchman
survives only because cron speaks to it every 60 seconds, which is a property of that
example, not of the framework.

**Sub-problems:**
- **What the lease covers.** Driving is seconds; a parked turn awaiting a human is
  days. A lease held across a park is a blocking wait with extra steps. So the lease
  covers the driving window only, and something else owns the wait.
- **Two timers, not one.** "How long may one attempt take" and "how long may the whole
  thing take" are different questions. Temporal separates them (start-to-close vs
  schedule-to-close, plus a heartbeat timeout). `StalenessPolicy` is one global
  duration doing all of these jobs.
- **Renewal.** A long but healthy drive must not look dead. Temporal's answer is
  heartbeats emitted **by the worker**, not by the thing it is calling — which is why
  it works for a remote MCP call too: we heartbeat because we are holding the
  request, not because the server told us anything.

**Breaks if unsolved:** turns stall silently and forever, and nothing notices.

---

## 5. Pub/sub: notify anyone when events happen to an agent

Interested parties — a web request, a UI, another service — should be able to watch
what is happening to an agent id without polling and without being the process
driving it.

**Claim-checked:** the notification carries a reference, not the payload. Subscribers
fetch what they want. Keeps the bus light and keeps transcript content off it.

**Today:** `Agent.subscribe(TurnObserver)` exists but is in-process only, and `ask()`
is subscribe → `tell` → `join`, blocking a request thread in the same JVM as the
driver. That falls over the moment a turn parks for an approval — which is exactly
what the watchman does.

**Sub-problems:**
- **Resumability.** A dropped subscriber must resume, not restart. SSE gives
  `Last-Event-ID` for free *if* there is a durable ordered log to resume from.
- **This implies a journal.** A resumable stream requires exactly the durable,
  ordered, replayable-from-offset event history that problem 10 says we lack. The two
  problems may have one answer.
- **Fan-out to nodes.** A subscriber may be on a different node from the driver.
  Something has to propagate.

---

## 6. Nothing owns driving a turn to completion

Stated separately from problem 4 because ownership is the mechanism and this is the
obligation. Even with leases, something must *notice* that a turn needs an owner.

Three distinct conditions mean "someone should be driving," and today only one has a
periodic backstop:

| condition | watched by |
|---|---|
| backlog non-empty, agent `Idle` → start a turn | nothing periodic |
| a delivery is waiting → advance a turn | `drainApprovals`/`drainTools`, every 5s ✓ |
| driver died mid-turn, nothing incoming → recover | nothing |

**Candidate:** the trigger must be a durable fact that something polls, not a method
call. `nudge()` survives as a latency optimisation, not as correctness.

---

## 7. Dispatched versus never-started is indistinguishable

A phase in `RunningTool` cannot tell "the effect was never dispatched" from "it ran,
had side effects, and died before recording anything." So `outstanding()` re-fires
**every** tool unconditionally — including `restart_container`, `apply_updates`, and
`prune_images`.

**Today:** we durably record *completions* (folds) and never *dispatches*. Temporal
records both as separate history events, which is what lets a replay skip an activity
that already ran.

**Breaks if unsolved:** side-effecting tools run twice after any crash.

---

## 8. Retry is implicit, uniform, and undeclared

`RunningTool.outstanding()` returns `RunTool` for every tool, forever, at a global
5-minute staleness threshold. The framework decides retry on behalf of tool authors
who were never asked, with no way to say no and no bound on attempts.

**Temporal's answer:** retry is a declared per-activity policy with an explicit
non-retryable escape hatch, and the doctrine is stated out loud — *"you should always
make your business logic Activities idempotent… Activities may be executed more than
once."*

**Complication — MCP.** We do not author MCP tools, so they cannot declare anything.
MCP carries behavioural `annotations`, but the spec is explicit that clients **MUST**
treat them as untrusted unless the server is trusted.

**Candidate:** the declaration belongs on the **grant**, not the tool — the host bears
the consequences, and the grant is already where the host says what a tool may do.
Unknown/remote tools default to the conservative answer.

---

## 9. Effects are not durable, and not atomic with the phase

An effect is dispatched in memory *after* the fold commits. Crash in between and it
is simply gone; recovery is `outstanding()` guessing what should have been pending.

**Breaks if unsolved:** every one of problems 6, 7, and 8 stays unsolvable, because
all three need to know what was actually pending rather than what we can re-derive.

---

## 10. There is no durable event history

`nessy_document` holds the current phase as a snapshot; `nessy_journal` holds the
conversation transcript (`user-message`, `assistant-message`, `tool-exchange`). There
is **no durable record of `AgentEvent`s.**

So *replay* — in the Temporal sense — does not apply to this system and should not
appear in its documentation. That is largely fine: our phase is data, not code, so
recovery is "read the snapshot," not "re-execute history."

**What it costs:** auditability. You can reconstruct what the model saw and what is
pending, but not the sequence of decisions — why a call went `Denied` rather than
`Failed`, or what each attempt hit. Under a retry design that gets more noticeable.

**Note:** problem 5 may solve this incidentally, since a resumable subscription needs
exactly this log.

---

## 11. Discovery: finding agents that need attention

Whatever the mechanism, something must answer "which agents need a driver right now?"
cheaply and correctly.

**Hard part:** the phase lives inside an opaque `payload` bytea, so the substrate
cannot filter on it. Time alone is not enough either — an agent that finished cleanly
a week ago looks identical to one stalled since Tuesday. Only *non-`Idle` and
unowned* means anything, and neither half is currently queryable.

**Constraint:** whatever we choose must work on Postgres, DynamoDB (ruled second
backend, and it has no `SELECT … FOR UPDATE`), and Mongo.

---

## 12. Side effects before death are unknowable

If the thread running `restart_container` dies halfway, nothing — not leases, not
heartbeats, not dispatch records — tells us whether the container was restarted.

**This one has no solution**, and it is worth writing down precisely so no design
claims to fix it. Temporal does not solve it either; it declares it the activity's
problem and hands you an idempotency key. Restate and DBOS get closer by journaling
each step's *result* so recovery skips completed steps.

Our deferral path is already Restate-shaped — the durable record *is* the journal,
which is why a parked call re-fires nothing. The exposure is synchronous tools.

---

## Naming hazard

`Substrate` is already a Nessy type — `org.jwcarman.nessy.spi.substrate.Substrate`,
the bytes+journal+batch SPI behind `nessy_document` and `nessy_journal`. If the other
project of the same name becomes a dependency, there are two Substrates in one build.
Worth settling before an import statement settles it.

---

## What is already true and should not be re-solved

- **The backlog is durable** (`SubstrateBacklog`), and per-scope.
- **The answer path is durable and leased.** `continuum_outbox` carries
  `continuation_payload`, `outcome_type`, `available_at`, `claimed_by`,
  `claimed_until`, `attempt_count` — a transactional outbox with claims, backoff and
  attempt counting, running in production all evening.
- **Re-folding is a no-op.** A stale or redelivered event meets a phase that has moved
  past admitting it and is dropped with a WARN. At-least-once delivery is therefore
  safe without atomicity between ack and fold.
- **Deferral does not hold a thread.** The effect records, folds, and returns; what is
  left behind is a row, not a stack frame.
