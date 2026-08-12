# Durable Execution — design

**Date:** 2026-08-12
**Status:** DRAFT — pending review
**Builds on:** `2026-08-11-conversation-essence-design.md` (the essence) and the seams it
deliberately left dark: `ParkToken`, `ToolResolution`, `RunOutcome.Parked`,
`ConversationLoop.resume`, `ConversationStore.consumeToken`, and the
status-as-continuation-pointer property. The headline constraint, stated up
front because it is the whole design's shape: **no new facts, no new effects.**
The essence grammar survives untouched; everything durable execution adds is
store contract and facade.

---

## 1. What this is for

The autonomous agent: a conversation that lives on independently of any
process, receiving periodic tells from the outside world — cron, webhook,
another agent — and able to run **on any node** the software runs on. Its
turns park for HITL approvals and long-running remote tools, survive process
death, and resume wherever the resolution lands.

The essence made this cheap without meaning to: a conversation is a thin
control block advanced by a pure fold, saved at every step, whose status says
exactly what happens next. Segments are short-lived; waiting is a durable
park, never a held thread. "Run anywhere" reduces to exactly three problems —
the simultaneous-claim race, the crashed claimant, and the mid-turn tell —
and all three land in the same jurisdiction: **the store is the referee.**

## 2. Prior art (surveyed 2026-08-12)

Three schools, converging hard on the seams the essence already cut:

- **Durable-execution engines** (Temporal/Cadence, Restate, Azure Durable
  Functions, Inngest, DBOS). Restate's *awakeables* are `ParkToken`/`resume`
  nearly verbatim; OpenAI's `requires_action` → `submit_tool_outputs` was the
  same shape server-side. Temporal *signals* are our mail — durable, buffered
  while the workflow is busy, ordered, at-least-once — and `signalWithStart`
  is our post-then-opportunistic-claim. Critically, **every mature engine
  separates progress from the durable event lane** (Temporal heartbeats,
  Azure `setCustomStatus`): nobody queues progress, because progress behind a
  blocked consumer is stale by definition.
- **Actor runtimes** (Akka Cluster, Orleans, Dapr actors, Cloudflare Durable
  Objects). The mailbox is native and the claim problem is solved by
  runtime-managed single activation — elegant, and expensive: cluster
  membership, gossip, failure detection. The runtime referees so the store
  doesn't have to.
- **Agent frameworks** (LangGraph, Mastra). LangGraph's checkpointer +
  `interrupt()` + `Command(resume=…)` is park/resolution/re-drive against
  Postgres; its weak multi-worker claim story is the gap this design closes.
  Tool progress flows through a stream writer *outside* checkpoint history —
  the two-lane rule again.

**Where nessy lands:** the DBOS/Restate school — durability and coordination
as a database discipline, not a cluster runtime. One database, no membership
protocol; the library stays a library. One genuine differentiation: our
progress lane is *push-observable* (system-channel emission) where Temporal's
heartbeats are server-side-only.

## 3. The mailbox

Every conversation has a durable mailbox. The world's two kinds of arrival
are one envelope grammar:

```java
sealed interface Mail {
  record Told(List<ContentBlock> content) implements Mail {}
  record Resolved(ParkToken token, ToolResolution resolution) implements Mail {}
}
```

- **Nothing is ever turned away.** Posting mail is an unconditional, atomic
  append — cheap on any store. It is *driving* that needs coordination, never
  accepting. A tell landing on a busy conversation queues; a resolution
  landing before its turn resumes queues; arrival order is preserved
  (UUIDv7 mail ids — the v2 identifier ruling, paying off again).
- **Mail is not grammar.** A `Told` becomes the `AgentTold` fact at fold
  time; a `Resolved` routes to the parked executor exactly as `resume` does
  today. The fact-log of a mailbox-fed conversation is indistinguishable from
  an interactively-fed one.
- **A parked turn holds its mail.** The turn definition (tell → clean
  response) is a logical span; mail arriving mid-turn — including while
  parked — waits behind the open turn, in order. A `Resolved` for the parked
  call is the exception that *closes* the span, so in practice: resolutions
  unblock, tells wait.

### The claimant drains — the whole dispatcher

Post mail, then *opportunistically try to claim* the conversation:

- **Claim won:** drive right there, on a virtual thread — drain the mailbox
  one envelope at a time (each a fold-and-segment), including mail that
  arrives while driving, then release when the box is empty.
- **Claim lost:** walk away. Whoever holds the claim drains your mail before
  releasing.
- **Nobody comes at all** (mail posted to an unclaimed conversation by a
  crashed poster): a lazy sweeper — or simply the next poster — finds
  claimable conversations with non-empty mailboxes and drives them.

This is Akka's dispatcher rebuilt over a database, with no worker
infrastructure required for the common case. And the in-process story
becomes a degenerate fleet: interactive `tell` = post + claim (always wins,
single process, in-memory store) + drain + read the outcome — observably
identical to today.

## 4. The store is the referee

`ConversationStore` grows from load/save/consumeToken into the coordination
contract. The loop never learns what a node is; every fleet concern is a
store method. Two mechanisms carry two different loads, and neither can do
the other's job:

- **Fenced writes carry correctness.** State carries a version; `save`
  carries the expected version; a stale writer loses loudly. A driver may
  never write to a base it didn't read — including the zombie case (a driver
  GC-pauses past its lease, another node legitimately reclaims, the zombie
  wakes and saves late: only the write-time check stops it). This is plain
  optimistic concurrency, and it is the load-bearing mechanism: remove
  everything else and the system stays *correct*.
- **Claims carry economy.** A segment is not just a state write — it is
  model calls that cost money and tools that act on the world, and
  version-checking detects a race only *after* that spend. A claim is itself
  just optimistic locking on a different column at segment *start* (one
  conditional `UPDATE … WHERE claimant IS NULL`, no locks, no blocking) so
  duplicate work almost never begins. The lease is a TTL on the claim so
  crashes self-heal. Remove claims and races cost dollars and duplicate side
  effects; remove the fence and the system is simply wrong.

| Concern | Contract sketch |
|---|---|
| Torn writes / zombie writers | `save(state, expectedVersion)` — version-fenced CAS; stale writers fail loudly and discard their segment. |
| Simultaneous drivers | `claim(id, lease)` — CAS on an unclaimed conversation; exactly one caller wins, before any spend. The essence's read-then-act §6 refusal check becomes this CAS. |
| Crashed claimant | claims carry a **lease** (expiry + renewal while driving). An expired lease is reclaimable; recovery is re-driving from the status pointer, fenced by the version check. |
| Parked turns | **parking releases the claim.** A parked conversation has no driver and needs no lease — that is the point of parking. Resume re-claims before re-driving. |
| Resume dedup | `consumeToken(token)` (already shipped): resolutions are at-least-once in every real transport; the token is single-use. Progress peeks; only resolution consumes. |
| Mail | append (unconditional), read-in-order, acknowledge-drained. |
| Park bookkeeping | token → (conversation, call), written at park, read by `resume`/`progress`. Load-bearing now; see §7. |

The in-memory store implements all of it trivially (single process, claims
always win, leases never expire). A reference durable implementation —
`nessy-store-jdbc`, Postgres-first — is a new module in plan scope, and the
contract is designed against it.

**At-least-once tool physics.** Re-driving a lease-expired `EXECUTING_TOOL`
conversation re-performs tool calls. That is the fleet's physics, not a bug,
and it becomes part of `Tool`'s documented contract: a tool that cannot be
safely re-run makes itself idempotent (or parks and lets its remote side
dedup by token). One javadoc paragraph, no machinery.

## 5. The two lanes: mail and signals

**Mail changes the conversation; signals describe it.** Mail folds; signals
don't. Mail is durable, ordered, and claimed; signals are best-effort,
immediate, and never queued.

The facade grows a sibling pair, correlated by the same token:

```java
harness.resume(token, resolution)   // terminal: consumes the token, re-drives the turn
harness.progress(token, message)    // interim: peeks the token, emits ToolProgress
```

`progress` validates the token *without consuming it*, resolves the
conversation and call from park bookkeeping, and emits a `ToolProgress` on
the receiving node's system channel. Duplicates are harmless. How the remote
executor transports progress home (webhook, queue) is the tool author's
business — the token is the harness's whole correlation contract.

Progress-as-mail was considered and is disqualified on staleness: mail is
drained between segments, and a parked turn holds the mailbox closed — the
parked call's own progress would queue behind the park producing it,
delivered only at resume, when it is worthless.

**The system channel stays process-local.** `progress` emits on whichever
node received the relay; a fleet-wide dashboard aggregates via the
application's own bus behind a declared listener. Declaring listeners is the
harness's seam; distributing their delivery is not its job (v2's line, kept).

### In-process tool narration (the tee)

The sitting consumer's tool observability gains its missing beat:
`TurnEvent.ToolCallProgressed(ToolCall call, String message)`. The gated
executor tees it — the `ToolContext` it hands a tool wraps the emitter so
`ToolProgress` emissions are *also* narrated to the segment's observer
(authoritative `ToolCall` attached by the executor; the tool's self-reported
id is not trusted for narration). Only `ToolProgress` is teed; everything
else a tool emits passes through untouched. The system channel keeps its copy
— same information, both channels, different consumers, deliberately.

Two rulings this forces (see §9): narration threading (progress arrives on
whatever thread the tool emits from — documented on `TurnObserver`) and
narration-throw semantics (the tee must not let an observer bug masquerade as
tool failure — texture never alters the record).

## 6. The facade

The durable entry is **mail, with a receipt**:

```java
MailReceipt receipt = conversation.post(input);                    // Told
MailReceipt receipt = harness.resume(token, resolution, observer); // Resolved
record MailReceipt(MailId id) {}                       // UUIDv7 — correlate in logs/events
```

- `post` appends, opportunistically claims-and-drains (driving on a virtual
  thread when it wins, walking away when it loses), and returns the receipt
  either way. Prior art is unanimous that fire-and-forget still hands back a
  correlation handle (Temporal's signal handle, OpenAI's run).
- `resume` is the same shape for the same reason: with fan-out, several calls
  can park in one turn, so a resolution can arrive *while another resume is
  already driving* — exactly when `Resolved` must queue as mail. So `resume`
  cannot promise a `RunOutcome`; it posts, claims opportunistically, and
  returns the receipt. The observer binds to the segment when the claim is
  won (the HITL UI still watches its own resume drive live); a caller who
  needs the settled outcome reads the store or listens on the system channel.
  *(Return shape — receipt-always vs a dual outcome type — open, §10.)*
- **Interactive `tell` is unchanged**: `tell(I[, TurnObserver]) → RunOutcome`
  — post + always-winning claim + drain + read, on an in-memory assembly. The
  waiting teller keeps their synchronous read; the autonomous caller uses
  `post` and reads the receipt. Two verbs, one machinery, intent legible at
  the call site. *(Verb name `post` vs `deliver` — open question §10.)*

**Amendment ledger against the essence spec:**

| Essence ruling | Disposition |
|---|---|
| "The entry point must not become enqueue" (§10) | **Amended, reason intact.** The interactive facade stays synchronous and collision behavior stays loud-by-outcome. The durable lane is *explicitly* mail — enqueue by name, not by stealth. What the ruling protected (no silent queueing behind a synchronous verb) still holds: `tell` never queues; `post` never pretends to be synchronous. |
| §6 refusal contract ("in-flight statuses refuse `run` loudly") | **Evolved into the claim protocol.** The invariant it enforced — never two drivers — is kept by CAS instead of by exception. "Claim lost" is the system working, not an error. The crashed-turn story ("inspect or abandon deliberately") becomes lease expiry + re-drive. |
| Parks invisible to the grammar; observer bound per entry; resolutions route to the executor | **Unchanged.** This design is those rulings' payoff. |
| Statuses: `EXECUTING_TOOL` covers parked | **Amended: `PARKED` joins `ConversationStatus`.** The claim rules need it legible: leases apply to in-flight statuses; `PARKED` carries no lease (no driver); resume targets `PARKED` alone. An ops surface reading status must not need park-table joins to see "waiting on the world." |

## 7. State: the parked lane

Park bookkeeping is control-block business — the debt lane already tracks
which calls are owed; parked is a sub-state of owed:

```java
ConversationState(…, List<ToolCall> pendingCalls,
                     List<ParkedCall> parkedCalls,   // new: (ParkToken, ToolCall)
                     List<ToolResultBlock> pendingResults, …)
```

Parking moves a call from pending to parked (a fold-free transition performed
by the loop via a small closure method, like `halted`); resuming moves it
back through the executor's yield. State stays thin — two scalars' worth of
lane — and `resume`/`progress` resolve token → call from state, with the
store's park table as the fleet-visible index of the same information.

## 8. What this activates from the shipped scaffolding

- `RunOutcome.Parked` gets constructed; `ConversationLoop.resume` gets its
  body; `GatedToolCallExecutor.resume` becomes reachable; `consumeToken`
  gets its caller. (The merge-time backlog, redeemed.)
- The loop's missing terminal-status brake gets built — with adversarial
  mail and multi-node timing, "unreachable with in-family executors" is no
  longer an assumption worth carrying.
- The listener-veto sharp edge (a throwing sync subscriber stranding an
  in-flight conversation) gets its real fix: a stranded conversation is now
  just an expired lease — re-drivable.

## 9. Open rulings folded in from the tee discussion

1. **Narration-throw semantics.** The tee catches and logs observer throws —
   texture never alters the record; a UI bug must not become a model-visible
   tool failure. This ships as a *documented asymmetry*: on the model-call
   path a throwing observer still aborts the caller's own `tell` (cleanly
   attributed), on the tool-progress path it is logged and dropped
   (propagation would misattribute). Both semantics land in `TurnObserver`'s
   javadoc. *(Lean recorded; uniform narration-never-throws was the
   alternative — see §10.)*
2. **Narration threading.** `TurnObserver` documents that tool-progress
   narration arrives on whatever thread the tool emits from; observers that
   accumulate state make themselves thread-safe or stay delta-only.

## 10. Open questions

1. **The durable verb:** `post` vs `deliver` vs `send`. Lean: `post` — mail
   vocabulary, no collision with existing verbs, reads at the call site.
2. **Narration-throw asymmetry** (§9.1): tee-only swallow (lean) vs uniform
   narration-never-throws across all observer paths.
3. **Lease mechanics:** duration, renewal cadence, and whether renewal is the
   loop's per-fold side effect or a background heartbeat. Lean: renew at the
   same chokepoint as `save` — one store round-trip, no extra threads.
4. **Sweeper:** does the harness ship a minimal claimable-conversation
   sweeper (a `Runnable` the app schedules), or is "the next poster drives"
   plus app-scheduled sweeping enough for v1? Lean: ship the `Runnable`,
   schedule nothing.
5. **Park timeouts:** a park that never resolves starves its mailbox.
   Whose policy? Lean: a `ParkPolicy` sibling of `TerminationPolicy`,
   consulted by the sweeper (wall-clock is the sweeper's jurisdiction; the
   loop still never learns time passed) — spec'd but minimal in v1.
6. **`nessy-store-jdbc` scope:** schema (conversations, mail, parks, tokens),
   Postgres-first. In plan scope as the reference implementation, or a
   follow-on plan? Lean: same plan — the contract isn't real until a durable
   store implements it.
7. **`resume`'s return shape:** receipt-always (lean — uniform with `post`,
   honest about the queued case; the winning claim still narrates live
   through the bound observer) vs a dual outcome type distinguishing
   drove-to-completion from queued-behind-another-driver.

## 11. Out of scope, on purpose

Cross-node fan-out of the system channel (application's bus); brokers and
queues in front of `post` (application's architecture); model-call parking
(batch APIs — the contract shape still permits it; still unbuilt); mailbox
fairness/starvation beyond FIFO-per-conversation; multi-conversation
transactions.

## 12. Testing posture

The essence's promises extend: the claim/lease/mail contract gets a
store-agnostic TCK-style test suite run against both the in-memory store and
`nessy-store-jdbc` (Testcontainers for Postgres, excluded from the offline
`verify` like the live model tests). The loop's drain discipline is tested
with fake stores whose claims can be scripted to lose, expire, and race.
Park/resume round-trips run entirely in-process against the in-memory store —
durability of the *contract*, not the disk, is what the core suite pins.
