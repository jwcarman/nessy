# Phase 2 — decision record

> **This is not a specification.** It is the raw material for one: every decision taken in the
> Phase 2 design conversation, with its reasoning and what it replaced. The spec itself gets
> written on Fable, per the model policy. Nothing here is built.

**Provenance.** Designed in conversation on 2026-09-06/07, then reconciled with a parallel session
(a proof-of-concept branch) working the same problem. Where the two disagreed, the resolution and the losing
argument are both recorded — a decision without its rejected alternative is a decision that gets
re-litigated.

**Status of the branch this follows.** `pekko-out` is complete: Pekko removed, a durable
PostgreSQL-backed engine in its place, 66 commits, whole-branch review closed. Phase 2 is the next
generation and touches the engine's core; none of it is implemented.

---

## The principles, and what each one deleted

The design was not drawn from a blueprint. It came from six rules applied until they stopped
producing changes. Each one is listed with what it removed, because a principle that deletes
nothing is decoration.

### 1. The outbox exists only for what you cannot do transactionally

An effect is a durable obligation to the outside world. A write to our own database is not one: it
goes to the same database as the agent row, so it joins the same transaction, and the transaction
already guarantees what the outbox was being asked to guarantee.

**Deleted:** `Effect.Remember.Input`, `Remember.Answer`, `Remember.Exchange`, `Effect.Release`, and
with them the entire class of bug where an exhausted memory write folds nothing and leaves an agent
believing its turn was recorded.

**Effects are now four:** `CallModel`, `RunTool`, `AskApprover`, `TakeWork`. Every one of them
reaches for something the agent does not control.

**`Terminate` is not among them, and the principle is what removed it.** Terminating touches nothing
outside: it seals the backlog, and the agent discovers it on its next pull — `Idle` becomes
`Terminated` and that is the whole of it. No obligation, no outside-world call, nothing to retry.
An earlier draft had it as an effect purely by inheritance from `Effect.Forget`, which existed to
delete state and claims. Nothing is deleted now, so nothing is owed.

> **Open, and it follows from the same principle:** is `TakeWork` an effect either? It reads our own
> backlog, and the backlog now lives in the agent document under the same row lock — so taking work
> may be a pure transition of the document rather than an obligation to anyone. If it is, effects
> reduce to three, and every one is literally "call something we do not control": the model, a tool,
> an approver. `TakeWork` earned its place when the backlog was a separate table; that reason is
> gone.

### 2. A fact becomes a message only if it changed what the agent knows

A fact that only describes *how* the agent came to know something stays operational. A 503 on
attempt 2 of 5 did not change what the agent knows. A tool's answer did. An approval denial did.

**Deleted:** any need to filter infrastructure noise out of a context by hand, and the recurring
question of whether retries belong in the transcript. They do not, and the rule says why without
appeal to taste.

### 3. Who authored it, and for whom

The context is not a log. A model treats everything in its context as fact about the conversation
it is having, and it has no channel meaning "this is a note about infrastructure, ignore it." So it
apologises to the user for your database, offers to retry, and carries the apology forward through
every later turn and every summary.

Human-authored, human-facing text is safe to project. Machine-generated operational text never is.
An approver's denial reason reaches the model; a JDBC driver's message does not.

**Consequence:** the turn-failure arm carries a *closed enum*, not free text, so there is no path
from a provider string into a context for anyone to accidentally open.

### 4. State holds ids; content lives beside it

The agent document's shape exists so it does not grow with what its tools do. Content in the
document breaks that in the size dimension; content written under the row lock breaks it in the
time dimension, because the critical section becomes O(tool output).

**Consequence:** payloads are written *before* the lock and referenced *under* it. The only failure
mode is an orphan nothing references, and payload writes are idempotent, so a re-driven turn
rewrites the same payload under the same id and nothing notices.

### 5. The fold decides; the shell acts

`AgentLogic` depends on nothing but `(AgentState, Input)`. No clock, no I/O, no randomness, no
bindings, no knowledge of how the agent was assembled. Replay correctness rests on it, and every
temptation to leak policy inward has been refused.

**Deleted:** a proposal to give the backlog-doorbell input a queue depth, so the fold could shed
load. Shedding is the coalescer's job and summarising is the history provider's; the fold branches
on phase and has no use for a number.

### 6. Make the invariant unrepresentable rather than remembered

Six instances of one concurrency bug shipped on the previous branch because "stop the group" and
"release its siblings" were two actions someone had to remember to pair. They are now one operation
whose input cannot be produced without the other half.

The same rule produced: an exchange as a single value (a call with no answer is unrepresentable),
the terminal backlog state (it has no transition that accepts an offer), and the poison pill's
placement (terminality cannot land mid-turn because it is only ever discovered at a turn boundary).

---

## The abstractions, and why each earns its place

### `Effect` — an obligation to the outside world

`PENDING → RUNNING → (deleted on success | FAILED)`.

- Inserted `PENDING` with `actionable_at = now`.
- A poller takes due rows: `SELECT … FOR UPDATE SKIP LOCKED WHERE actionable_at <= now AND status IN ('PENDING','RUNNING')`.
- `PENDING` becomes `RUNNING` with `actionable_at = now + timeout`, **commits**, and only then is a
  virtual thread spawned. The reverse order loses the marker on a crash; this order is safe because
  an uncommitted row simply stays `PENDING`.
- A `RUNNING` row that is due **has timed out**. The retry policy decides: back to `PENDING` with a
  backoff, or `FAILED` — and `FAILED` always tells the agent, in the same transaction.
- A result, inline or arriving days later, deletes the row.

**One timeout per binding**, meaning the maximum we will wait for this effect to complete. It
replaced four concepts: a flat watchdog, a separate deferral term, a `maxDeferral` ceiling, and a
clamp. The row does not care *who* we are waiting on, only how long.

**Deleted `PARKED`.** Parked and running had identical lifecycle semantics — outstanding, an answer
may come, a lapse is a failure. The status was metadata about who we wait on, which the lifecycle
never used. Its one real job was keeping `deferSiblings` away from a human's deadline, and that is
better solved by releasing siblings by explicit id.

**Deleted the watchdog as a concept.** There is only ever one question — how long until we stop
believing an answer is coming — and a global constant is the wrong place to answer it.

**Delete-on-success is load-bearing, not tidiness.** A single-row delete is first-wins, so it fences
concurrent completion for free: the loser gets zero rows, throws, and rolls the fold back. The
parallel session needed an explicit `attempts` fence precisely because it kept a `COMPLETED` status.

> **Contract, and it must be written next to the fence:** this holds *only* because completion runs
> inside the fold's transaction. The moment completion moves outside it — a two-phase completion, an
> async fold — first-wins silently degrades to both-win.

> **Contract, on the binding's javadoc:** the effect timeout MUST exceed the work's own timeout. A
> still-running call whose row times out is retried *alongside itself*, and a non-idempotent tool
> executes twice. This is a stated contract now rather than a hidden trap.

### `Failure` — a statement about what is known, not about severity

    Permanent   it failed, and the identical request fails identically
    Transient   it failed, and it might not next time
    Unknown     nobody found out whether it failed

Classified in the adapter, because only the model client knows what a 404 means. `Permanent` never
consults the retry policy — no budget makes a rejected request succeed.

The arms are decision-relevant exactly where it matters: a non-idempotent tool must never repeat
`Unknown` and *may* repeat `Transient`, because `Transient` asserts it did not run. A single
attempt budget cannot express that, so the binding carries two knobs:

    retryPolicy         how many chances, how long between        (kind-blind, plain data)
    repeatWhenUnknown   may this be repeated when we don't know?  (boolean, default false)

Default `false` means forgetting to think about it is safe.

**Reconciliation note.** This session had hard-coded "a tool that threw has run, so never retry it."
The other session was right that this is the *conservative resolution of `Unknown` for
non-idempotent work* — a policy, not a taxonomy. Hard-coded, `get_weather` and `send_email` are
treated identically forever. As `Never` on the binding it is the same default and can be opted out
of by a tool author who knows their tool is idempotent.

### `HistoryMessage` — the story, four arms

    UserMessage       the observation
    ExchangeMessage   the assistant's message and every result, as ONE value
    AnswerMessage     the answer
    <failure>         the turn produced no answer

The fourth arm is not a nicety. Without it a failed turn is unrepresentable, and the next context
is `user, user` — which some providers reject outright and the rest read as the assistant having
ignored the user. `HistoryMessage`'s existing javadoc already anticipated it: *"A fourth kind of
event would join these."*

**Written in the fold's transaction**, never as an obligation (principle 1).

**An exchange is assembled only when every call is terminal** — where terminal includes failed and
denied. And **a turn cannot end with a non-terminal call**: ending forces outstanding calls to a
terminal outcome, assembles, and writes. Together these guarantee no exchange is ever permanently
unwritten and no action ever vanishes from the story. Without the second rule, an agent that dies
mid-turn loses the record of tools that already ran — and the next turn runs them again.

**Storage is a stream of messages, not of turns.** A turn is a `GROUP BY`. If a turn were one row,
nothing would be durable until it ended, a crash would lose all of it, and the in-flight state would
accumulate every completed exchange on the hot locked row.

**Observations become facts at TAKE, not at arrival** (from the other session, adopted). An
observation that arrives while the agent is busy sits in the backlog and produces no fact; it
becomes observed only when pulled. So a coalesced-away observation *never becomes a fact at all* —
"we do not record every observation" is literally true rather than a projection rule someone
enforces. Nothing is lost, because the backlog is itself durable state.

### Payloads — the two unbounded things

The store holds exactly what is unbounded: the **observation**, the **assistant message whole**, and
**each tool result separately**. Everything else — assistant text, tool names, the renderer
description, the outcome — is bounded and lives inline in the message.

**The assistant message is stored whole and never split.** Its blocks are ordered and
interdependent: commentary, reasoning, provider blocks, and every tool call. A `tool_use` block's
input *is* part of that message and a provider's signature may cover the whole of it. Tool arguments
are therefore read out of the assistant message at call time, not stored separately.

**History carries refs, not bytes:**

    record PayloadRef(PayloadId id, String mediaType, long size) {}

`size` and `mediaType` are what let a decision happen *without fetching*. An opaque id would collapse
the whole scheme back into a worse version of inlining.

**Reading is two queries, never a join and never N+1:**

1. the message rows — structure, prose, tool names, the `ActionRenderer` description of every call,
   and refs. Small and bounded.
2. the payloads the provider *chose*, in one `WHERE id IN (…)`, returned as a map.

A join would load everything and be strictly worse than inlining. The value is entirely in the
decision made between the two queries.

**Independent retention falls out of this.** Keep the story forever — it is small, and it is what
the model needs. Keep bulky payloads for ninety days. The conversation stays coherent after they are
gone because the structure and the description survive; absence renders a placeholder and is a
*normal* condition, not an error. With content inlined, shrinking storage means deleting the story.

**Not content-addressed.** Content addressing makes an orphan indistinguishable from a legitimately
shared payload without reference counting. Keyed by place in the story instead, an orphan is
identifiable by turn and the sweep is what `Claims.deleteTurn` already does.

> **Naming, unresolved:** the store's unit is "one side of one call", which may contain several
> `ContentBlock`s. Calling that unit a "block" collides with the API's block grammar. `Payload` is
> used throughout this document; the word should be chosen before anything is persisted, because
> renaming a persisted concept later is the archaeology we keep finding.

### Context assembly — two providers replacing `Memory`

- a **history provider** — decides what history goes in, and what stands in for what it elides
- a pipeline of **ambient providers** — background assembled per call and thrown away

`Memory.remember` goes away entirely; the SPI becomes ask-shaped rather than tell-shaped, which is
the seam the graph-retrieval capstone plugs into. Providers are notified of facts so summarisers can
maintain state incrementally rather than re-reading the world.

**A summary is its own type, not an `AmbientMessage`.** This session argued for ambient on lifecycle
grounds — assembled at recall, shown once, thrown away — and lost on two better arguments. Ambient is
*present-tense* (a notebook describes now); a summary is past-tense, standing in for a range. And the
history provider cannot do its stated job without provenance: `SummaryMessage(fromSequence,
throughSequence, blocks)` lets it say "this covers [1..153], read raw from 154." An untyped blob
cannot.

Summaries are never in the history stream, are owned by whoever produces them, and are regenerable —
a failed summary is a cache miss, not lost work, which is why they need none of the durable
machinery.

### The backlog — a state machine in the document

Pure transitions, `(BacklogState, event) → (BacklogState, result)`. The store is only persistence.
`take` yields `Work | Empty | Poisoned` — the natural result type of a pure function rather than a
store's return convention.

**Termination happens at the backlog and nowhere else.** Sealing moves the backlog to a terminal
state carrying `terminatedAt` and drops its contents. The agent is not told and does not need to be:
on its next pull it is handed `Poisoned`, and `Idle` becomes `Terminated`. There is no termination
effect, no cancellation, and no cleanup obligation.
The state has no transition that accepts an offer, so a late arrival is not rejected by a check
someone remembered to write — it is unrepresentable. `AgentTerminatedException` (unchecked, carrying
`terminatedAt`) is thrown thereafter.

> Its javadoc must say **permanent** in as many words. A queue consumer's default on an exception is
> to nack and requeue; without that sentence a terminated agent becomes an infinite redelivery loop,
> and we would have traded silent data loss for a poison message hammering forever.

**The pill's placement is the whole design.** It lives in the backlog, so it is only ever discovered
by a `take`, and a `take` only ever happens at a turn boundary. Terminality is therefore
*structurally incapable* of landing mid-turn: the in-flight turn runs to its natural end, its calls
settle, its exchange assembles and reaches history, and only then does the agent learn it is
finished.

The alternative — a `terminated` boolean checked in the fold or before each effect — lands wherever
it is checked, which is mid-turn, producing exactly the orphaned exchange with tools run and nothing
written down. Same intent, quietly broken invariant.

**`forget` became `terminate`** because `remember`/`forget` were a matched pair and `remember` is
gone; and because lifecycle and retention were tangled in one word. Terminate stops the agent.
Whether facts are deleted is a retention question with a different answer.

### The coalescer — load-bearing, not a feature

`(List<BacklogItem<O>>, incoming) → List<BacklogItem<O>>`, pure, with
`keepAll · replaceBy(key) · dropRepeats(key) · mergeBy(key, fn) · expiring(ttl) · capped(n)`.

From the other session, with two lessons attached:

- **Replace in place, never move to the back.** Otherwise a fast sensor pushes itself ahead forever
  and everything queued behind it is never taken. A livelock only found in production.
- **Time comes from `incoming.arrivedAt()`, never a clock.** Keeps it pure and testable, and means
  expiry is evaluated by the next arrival rather than needing a sweep. This engine has been bitten
  twice by sweeps.

It is not optional now the backlog lives in the document: the document is rewritten whole on every
offer, so a queue of four hundred observations is four hundred rewrites of a growing payload on the
hot locked row. **A backlog without a coalescing policy has an unbounded document by default**, and
the default should be chosen deliberately rather than inherited.

For a chat agent, `keepAll` — every message matters. For a watchman at 1Hz against a 30-second turn,
anything else queues thirty readings of which twenty-nine are worthless before they are taken.

---

## What the model adapter does with a failed turn in history

Branch on whether the turn got anything done:

**No tools ran.** Nothing happened, so the truthful projection is that the observation is simply
unanswered: **merge it forward** into the next user message. Wire-valid everywhere, nothing
synthesised, nothing erased — and it sidesteps the fact that consecutive user messages are not
uniformly accepted.

**Tools ran, then the turn failed.** Real actions happened with real side effects; hiding them is how
the agent sends the second email. Show the exchanges, then **synthesise the closing assistant
message** from the closed enum.

**Never drop the observation.** `HistoryMessage`'s javadoc forbids it: *"History is append-only and
never rebuilt, because rewriting what happened is lying about it."*

The projection is narrower than the fact. The fact carries the provider's reason, the attempt count,
the terminal outcome — for humans. The message carries that no answer was produced, and at most a
category the model can act on: `REFUSED` is genuinely actionable, `TIMED_OUT` arguably, anything
else says only that no answer came.

---

## The ledger of deletions

One new public type. Removed or never built: `PARKED`, the watchdog, `maxDeferral` as a ceiling,
`clampDeferral`, `latestDeferral()`, `Deferred`'s payload, the separate deferral term, the two seal
modes, `Effect.Remember.*`, `Effect.Release`, `Memory.remember`, `forget`, and the queue depth on
the backlog doorbell.

Twelve concepts out, one exception in. That ratio is the evidence the design settled rather than
sprawled, and it is the reason to trust the moving parts: each one survived an attempt to delete it.

---

## Open questions

1. **The word for a payload** — "block" collides with the API's block grammar (above).
2. **Which failure categories reach the model.** `REFUSED` yes, `FAILED` minimal. `TIMED_OUT` is
   undecided.
3. **Whether `Unknown` narrates differently from `Transient`.** The other session reports they route
   identically today; the arm earns its place on the binding policy, not on behaviour.
4. **Is `TakeWork` still an effect?** See the note under principle 1 — the backlog moved into the
   document, which may have dissolved its reason for being an obligation.
5. **Public API sign-off** for `AgentTerminatedException`, `PayloadRef`, `SummaryMessage`, the
   `HistoryMessage` failure arm, and any change to `ExchangeMessage`.

---

## Hazards that must be tested, and cannot be tested by substitution

**Provider blocks must round-trip byte-stably.** Anthropic signs its thinking; Gemini attaches a
continuity token. `ProviderBlock.data` is a `JsonNode` going through Jackson twice — once on receipt,
once out of storage. Different key ordering or whitespace and the signature no longer verifies and
the provider rejects the turn. **This cannot appear in any test with a fake model.** It needs a
round-trip of a real signed block through the codec asserting identical bytes.

**Every hand-written SQL statement needs a PostgreSQL test that writes through it.** The previous
branch shipped an entire durable PostgreSQL engine that could not insert its first agent row —
`Instant` bound directly as a JDBC parameter, which the Postgres driver rejects and H2 silently
tolerates. Twelve tasks, seven fix rounds and a whole-branch Opus review were all green.

The spec's existing argument that H2 is an adequate substrate — because it parses `FOR UPDATE SKIP
LOCKED` — is about **dialect**. That bug was **driver type inference**, which H2 will never catch
however faithful its SQL. And the certification that existed asserted *tables existed*, which is
exactly how it hid.

> **A SQL statement never executed against your production database is a statement you do not know
> works, however green your suite is.** Coverage of the substrate is not interchangeable with
> coverage of the code, and no amount of the latter substitutes for the former.
