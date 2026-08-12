# Durable Execution — design

**Date:** 2026-08-12 (kernel rewrite, same day — the first draft grew a message
broker; review whittled it back to physics)
**Status:** DRAFT — pending review
**Builds on:** `2026-08-11-conversation-essence-design.md` and the seams it left
dark: `ParkToken`, `ToolResolution`, `RunOutcome.Parked`,
`ConversationLoop.resume`, `ConversationStore.consumeToken`,
status-as-continuation-pointer.

The headline constraints, because they are the design:

1. **No new facts, no new effects.** The essence grammar is untouched.
2. **Every entry appends; one verb drives.** `tell` and `resume` differ only
   in what they append; driving is a single re-entrant act from the status
   pointer.
3. **Exactly two write disciplines.** A version-fenced core and an
   append-only lane. Everything the first draft called mailbox, claim, lease,
   receipt, dispatcher, or sweeper is either one of these two or deliberately
   not built.

---

## 1. What this is for

The autonomous agent: a conversation that outlives every process, receives
tells from the world (cron, webhook, another agent), parks for HITL approvals
and long-running remote tools, and can be driven **on any node**. The essence
made this nearly free — thin control block, pure fold, save-per-step, status
as continuation pointer. What remains is storage physics and nothing else.

**The litmus that shaped this spec:** *does the world already provide it?*
Webhooks retry; crons re-fire; queues and brokers exist and are excellent.
The harness provides only what the caller's infrastructure cannot: durable
conversation state, safe concurrent writes, and wire-legal folding of
whatever arrives.

## 2. Prior art (surveyed 2026-08-12)

Three schools. Durable-execution engines (Temporal, Restate, Azure Durable
Functions, DBOS): Restate's awakeables are `ParkToken`/`resume` nearly
verbatim; Temporal's signals are durable buffered mail; all of them separate
progress from the durable event lane (heartbeats, custom status) — nobody
queues progress. Actor runtimes (Akka, Orleans, Durable Objects): native
mailboxes, claim problem solved by runtime-managed single activation — at the
cost of operating cluster membership. Agent frameworks (LangGraph, Mastra):
checkpoint + `interrupt()`/resume against Postgres; weak multi-worker claim
stories.

**Where nessy lands:** the DBOS school — durability as a database discipline,
one Postgres, no membership protocol. **What we deliberately did not adopt:**
the mailbox-as-API (Temporal signals, Akka mail). The first draft had one;
review killed it with the litmus above. Its one irreplaceable service —
accepting input for a busy conversation — survives as an append-only lane the
*fold* drains, which is smaller, deterministic, and already had a precedent
lane in the control block.

## 3. The unified entry

```java
conversation.tell(input[, observer])      // appends the world's words, then drives
harness.resume(token, resolution[, observer]) // appends a call's answer, then drives
```

**Appending always succeeds.** A tell is durably appended to the
conversation's lane regardless of status — idle, mid-turn, parked. Nothing is
ever refused; there is no "busy" answer. **Driving is opportunistic**: after
appending, the entry drives if the conversation is quiescent, and otherwise
returns immediately — the active driver's own fold points will consume the
lane. Either way the caller gets back the state as read: *the outcome is a
reading, not a delivery* (essence §10, now doing fleet duty).

What an appended tell **means** is a fold decision, by status:

| Conversation is… | The lane entry becomes… |
|---|---|
| Quiescent (`IDLE`/`COMPLETE`/`FAILED`) | The turn-opener: the drain births one user message, resets the error streak, `AWAITING_MODEL`, `CallModel`. |
| Mid-turn with tool debt | A flush rider: the results message is born as `[tool_results…, interjections…]` — the wire-legal seat for mid-turn words (the Claude Code shape). |
| Mid-turn, ending clean | The next turn's opener: a clean `ModelResponded` folding against a non-empty lane does not `COMPLETE`; it drains and continues. One fold rule is the whole "mailbox." |
| `PARKED` | Durable patience: waits for the resume that will drive past it. |

**Merge-at-drain.** All queued tells drain into **one user message, as
distinct blocks in arrival order** (UUIDv7 lane ids). Three forces settle
this: the wire forbids consecutive user messages; acting on tell 1 while
durably holding tell 2 is the stale-instruction bug ("cancel that" must never
sit unread behind the thing it cancels); and one model call beats N. Each
tell keeps its block boundaries — the model sees N voices, and the
`InputRenderer` owns any labeling. Accounting stays honest: one `AgentTold`
fact per tell as it arrived; one merged message told to Memory at drain.

**Concern isolation is the conversation's job, not the turn's.** Sequential
turns in one conversation share a transcript anyway — they sequence concerns
without isolating them. Genuinely independent matters belong in separate
conversations, and routing a tell to a `conversationId` is the application's
decision at the front door. Within a conversation, everything is one evolving
matter, and completeness beats sequencing.

Mid-turn tells do **not** reset the error streak and do not start a turn —
streak reset is a property of the drain that opens one.

## 4. The two write disciplines

**The fenced core carries correctness.** Status, debt lanes, dials, and a
version. `save(state, expectedVersion)`: a driver may never write to a base
it didn't read — including the zombie case (a driver stalls, another node
re-drives, the zombie wakes and saves late; only the write-time check stops
it). This is plain optimistic locking and it is the load-bearing mechanism.

**The append-only lane carries acceptance.** Lane entries (a told's content;
a resolution and its token) are append-only rows the fence ignores — so a
chatty world can never fence-fail a working driver, which is what makes
always-accept compatible with always-progressing. The lane is drained
*transactionally with the fenced save* of the fold that consumes it.

**The fold stays pure and owns message construction.** `ConversationState`
carries the lane as a loaded view — physically rows, logically a field, read
at load, drained by folds. Both halves of the state debate resolve: the
fenced core stays thin (no content growth under the version), and message
construction never leaves the fold. The lane joins `pendingResults` under the
essence's real rule: *state holds the open turn's unsettled material; Memory
holds everything settled. Lanes drain; records don't.* (Honest note: a
long park can grow the lane; entries are human-scale mid-turn words, and a
durable store may page lane rows without semantic change.)

**No claims, no leases — v1.** With fencing, concurrent drivers are *safe*:
one save wins, the loser discards its segment. Claims (a start-of-segment CAS
on a claimant column) prevent duplicate *spend*, not incorrectness, and races
are rare for cron-and-webhook agents. They can be added later as pure
optimization, one column, no semantic change. Shipping them first was the
broker talking.

## 5. Park and resume

- **`PARKED` joins `ConversationStatus`.** A parked conversation self-
  describes to any ops surface; no driver, no contention, durable patience.
- **State gains the parked lane**: `parkedCalls: List<ParkedCall(token,
  call)>` beside `pendingCalls` — parking moves a call over (a loop-applied
  closure transition, like `halted`); the executor's resumed yield moves it
  back. Token → (conversation, call) is thereby fold-visible and
  fleet-visible.
- **`resume(token, resolution)`** consumes the token (`consumeToken`, already
  shipped — resolutions are at-least-once in every real transport), appends,
  and drives. Contention (a resolution arriving while another entry drives —
  fan-out parks make this real) is the lane absorbing it: the active driver's
  loop consumes resolution entries and routes them to the parked executor's
  `resume`, exactly as the essence ruled — the fold never learns time passed.
- **At-least-once tool physics.** Re-driving a stalled `EXECUTING_TOOL`
  conversation re-performs calls. Documented on `Tool`: a tool that cannot be
  safely re-run makes itself idempotent, or parks and lets its remote side
  dedup by token. One javadoc paragraph, no machinery.

## 6. Signals: progress from afar and the tee

**Mail changes the conversation; signals describe it.** Signals are
best-effort, immediate, never queued (progress behind a blocked consumer is
stale by definition — the industry's unanimous heartbeat lesson).

- `harness.progress(token, message)` — the non-terminal sibling of `resume`:
  *peeks* the token (never consumes), resolves the call from the parked lane,
  emits `ToolProgress` on the receiving node's system channel. Transport home
  is the tool author's business; the token is the whole correlation contract.
- **The in-process tee**: `TurnEvent.ToolCallProgressed(call, message)`. The
  gated executor wraps the `ToolContext` emitter so a tool's `ToolProgress`
  is also narrated to the segment's observer, with the *authoritative*
  `ToolCall` attached (the tool's self-reported id is not trusted for
  narration). Only `ToolProgress` is teed. Two rulings ride along: the tee
  catches-and-logs observer throws (texture never alters the record — a UI
  bug must not become a model-visible tool failure), documented beside the
  model-path's propagate semantics on `TurnObserver`; and progress narration
  arrives on whatever thread the tool emits from, documented likewise.
- The system channel stays **process-local**; fleet-wide aggregation is the
  application's bus behind a declared listener (v2's line, kept).

## 7. Amendment ledger against the essence

| Essence ruling | Disposition |
|---|---|
| "The entry point must not become enqueue" (§10) | **Amended, reason intact.** What it protected — no silent queueing behind a synchronous verb — holds: `tell` appends *and drives when it can*, and always returns a reading. What changes: acceptance is unconditional, because the append is a durable, fold-visible act, not a delivery promise. |
| §6 refusal contract (in-flight statuses refuse `run` loudly) | **Retired in favor of fencing + re-drive.** The invariant (never two *effective* drivers) is kept by the version fence; a crashed turn is no longer a quarantine case but simply re-drivable from the pointer by the next entry. |
| Turn = tell → clean response | **Refined:** a clean response folding against a non-empty lane continues rather than completing. The turn ends at a clean response *with an empty lane* — "no homework" now includes "no unread mail." |
| `tell` returns `RunOutcome` | **Unchanged in shape, widened in meaning:** when the entry drives, it is the drive's outcome; when another driver holds the conversation, it is the state as read. A reading, either way. |
| Parks invisible to grammar; observer per entry; resolutions route to the executor | **Unchanged** — this design is their payoff. |
| Statuses | `PARKED` added; nothing removed. |

## 8. Deliberately not built

The mailbox-as-API (`Mail`, `MailReceipt`, `post`) — the lane is store rows
and fold semantics, not surface. Claims and leases (v1) — fencing carries
correctness; economy can come later as one column. Sweepers — every lane
entry is followed by a driving entry or the conversation is wedged regardless.
Park timeouts (`ParkPolicy`) — real, deferred; wall-clock policy arrives when
a deployment demands it, likely as a sweeper-sibling the app schedules.
Cross-node event fan-out — the application's bus. Brokers in front of `tell`
— the caller's architecture. Model-call parking — the contract shape still
permits it; still unbuilt.

## 9. Open questions

1. **Narration-throw asymmetry** (§6): tee catches-and-logs while the
   model-path propagates. Lean: ship the asymmetry, documented — propagation
   on the model path is cleanly attributed to the caller's own `tell`;
   propagation through the tee misattributes a UI bug as tool failure.
2. **`nessy-store-jdbc` in the same plan** (lean: yes — the two write
   disciplines aren't real until Postgres implements them; Testcontainers,
   excluded from offline `verify` like the live tests).
3. **Drain-policy seam** (sequenced turns within one conversation): not
   built; noted so the first genuine need argues against the
   conversation-as-concern principle on the record.

## 10. Testing posture

The store contract (fenced save, lane append/drain atomicity, token
consume/peek) gets a store-agnostic suite run against in-memory and JDBC
implementations. The loop's new laws — append-always, drive-when-quiescent,
drain-at-the-consuming-fold, merge-at-drain block ordering, mid-turn tells
riding the flush, clean-response-with-mail continuing the conversation,
park/resume round-trips, resolution-during-drive absorption — are fold and
loop tests against the in-memory store, in-process, offline. The fence's
zombie case is directly testable: two loaded states, interleaved saves, the
stale one must fail loudly. Durability of the *contract*, not the disk, is
what the core suite pins.
