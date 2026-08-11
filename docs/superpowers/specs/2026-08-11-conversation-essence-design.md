# The Conversation Essence — design amendment

**Date:** 2026-08-11
**Status:** DRAFT — pending review
**Amends:** `2026-08-09-nessy-agent-harness-design-v2.md` (the design of record).
Upon acceptance, the rulings here supersede the corresponding v2 sections; a
ledger of exactly which v2 rulings move is in §12. Everything in v2 not named
there stands.

---

## 1. The realization

What v2 called an *execution engine* is really a **conversation effect
executor**. The fold→perform cycle is not an implementation detail that
engines may vary — it is invariant semantics owned by the core. Engines never
own the loop; they only supply **how each effect is performed**.
The "engine" dissolves into three parts:

1. **The loop** — invariant, written once, owned by the core.
2. **A typed record of effect executors** — one slot per effect variant.
3. **Assembly** — store, memory, observer wiring; "in-process vs durable" is a
   choice of executors and stores, not a reimplementation of semantics.

The whole design reorganizes around one question, asked of every piece:
*whose business is this, really?* Each answer is a jurisdiction:

> **Facts are the record. State is the debt. Memory is the meaning. Texture is
> for whoever's watching. And the loop just asks — model or homework? — until
> there's no homework left.**

## 2. The facts — `ConversationEvent`

The conversation learns only **settled, message-grained facts**. Four variants:

| Fact | Carries | Meaning |
|---|---|---|
| `AgentTold` | content | The outside world addressed this agent — person, webhook, or cron. The entry fact; the only fact no executor produces. |
| `ModelResponded` | message, stopReason, usage | The model's settled contribution: one assistant message, homework included as its tool-use blocks. |
| `ModelCallFailed` | reason | The model call failed in a way re-performing cannot fix (canonically: context outgrew the window). Folds to `FAILED`. |
| `ToolFinished` | call, result | One piece of homework settled — success, failure, or denial. Errors are data the model reads (Factor 9). |

Removed from the grammar (see §12 for dispositions): `TextDelta`,
`ThinkingDelta`, `ThinkingSigned`, `RedactedThinkingArrived`,
`ToolCallRequested`, `ModelTurnEnded` (renamed/reshaped into `ModelResponded`),
`ApprovalDecided`, `Compacted`, `CompactionSkipped`.

**The fact-split law.** An effect gains a failure fact only when failure
changes the conversation's course *differently than success does*; an error the
dialogue itself can absorb rides inside the success fact as data. This is why
`CallModel` has two facts (a response is dialogue; a failed call is fate — no
party remains to read it) while `ExecuteTool` has one (`ToolFinished` carries
`isError`; success and failure have the same consequence shape: a result the
model will read). Go's `{res, err}` forcing function is achieved by sealed
exhaustiveness instead of convention — the fold cannot omit an arm and
compile. `AgentTold` has no failure twin: the outside world cannot fail *into*
a conversation.

**The first law.** *Everything that changes the record is on the record.* The
facts, durably appended in order, ARE the conversation. Replaying them through
the fold reconstructs state exactly; nothing off-record may alter what
replay would produce.

**Grammar flow.** The facts form a small regular grammar; statuses are its
states, effects its transitions:

```
AgentTold
  → ModelResponded ─ clean (no homework) ──────────► COMPLETE
       │        └─ MAX_TOKENS / REFUSAL ───────────► FAILED
       │ homework
       ▼
     ToolFinished (× each call, denials included)
       → ModelResponded …                (loop)
  or ModelCallFailed ──────────────────────────────► FAILED
```

Every fact has well-defined legal predecessors; every path reaches a terminal;
position in the grammar is the saved state. Parking never appears — a park is
a suspension mid-transition, not a fact; the conversation never learns time
passed. The fact-log of a turn that slept three times is identical to one that
ran straight through.

## 3. The effects — two, and why two is the number

```java
sealed interface Effect {
  record CallModel() implements Effect {}      // nullary: state is the payload
  record ExecuteTool(ToolCall call) implements Effect {}
}
```

After folding any fact, the fold answers one question — *what does the
conversation need to proceed?* — and there are only three answers: the model's
next contribution (`CallModel`), homework done (`ExecuteTool`), or nothing
(terminal; the silence that ends the turn).

**The participant test.** Effects address the conversation's parties, and the
parties are the model and the tools. *A new effect variant requires a new
participant in the conversation.* This sentence lives beside the sealed
declaration: adding a variant is not a feature, it is a new party.

Candidates explicitly rejected, each with its principled home:

- **Persistence, event emission, telling Memory** — the loop's invariant
  discipline at every fold. An "effect" the fold must always emit is not a
  decision; it is law, and law lives in the loop.
- **Approval** — folded inside `ExecuteTool` (§4). The gate travels with the
  act.
- **Compaction** — dissolved into Memory (§7). It was view maintenance
  misfiled as a conversation act.
- **Enrichment / RAG** — preparation inside the `CallModel` executor; no
  settled outcome the conversation folds.
- **"Ask the principal mid-turn"** — a tool that parks. Routing it through the
  tool seam means the grant principle applies: asking the human is an
  authority-bearing act — gated, journaled, deniable.
- **Sub-agents, timers, inter-agent messages** — tools, the world's clock, and
  tools.

## 4. Approval folds into the tool executor

`RequestApproval` leaves `Effect`; `ApprovalDecided` leaves the grammar;
`AWAITING_APPROVAL` leaves `ConversationStatus`. The tool-call executor is
built as **gate-then-invoke**: consult the grant's `UsagePolicy`, consult the
`Approver` only when policy requires, then invoke — or yield the denial.

- **The chokepoint gets stronger.** With one tool effect and the gate inside
  its executor, ungated execution is structurally impossible rather than
  conventionally avoided. There is no door that isn't the gate.
- **Denial semantics survive untouched.** The executor yields
  `ToolFinished(call, error("Denied: …"))`; the fold treats it like any
  errored result — error streak and all. A denial is a result the model can
  read and adapt to.
- **"Waiting on a human" is a harness event** (`ApprovalRequested` on the
  listener channel), not conversation state. Audit lives in observations —
  the jurisdiction rule, applied.

## 5. The executor seam

The "registry" of executors is a **typed record** — one slot per effect
family, completeness enforced by the compiler, dispatched by the loop's
exhaustive switch. No `Map`, no `Class` keys, no runtime lookup that can miss:

```java
record EffectExecutors(ModelCallExecutor callModel,
                       ToolCallExecutor toolCall) {}
```

There is deliberately **no shared executor supertype**: nothing abstracts over
executors generically — the loop switches exhaustively — so each slot's
interface takes exactly what it needs and shares only the return shape:

```java
Awaited<ConversationEvent> execute(…)   // one fact, or a park
```

- `ModelCallExecutor.execute(state, memory, observer)` — recalls the finished
  context from Memory, wraps it with settings (system prompt, model, tools),
  consumes the provider's `ModelEvent` stream (translating texture to the
  observer, accumulating the settled message), yields `ModelResponded` or
  `ModelCallFailed`.
- `ToolCallExecutor.execute(call, state)` — gate-then-invoke, yields
  `ToolFinished`.

**One fact per effect.** Multiplicity lives elsewhere by design: a turn with
five tool calls is one `ModelResponded` fact whose fold emits five effects;
streaming is texture, not facts.

**Parking is a termination mode of the contract, universal to it.** An
executor ends *exhausted* (fact yielded) or *parked* (the rest cannot exist
yet — a human or long-running process owes us something). The tool caller is
merely the only executor that parks today; a batch-API model call is a parking
`CallModel` executor tomorrow. Whether parks are tolerated is loop
configuration (the in-process assembly refuses loudly), not executor contract.

**Resume routes to the executor, not the fold.** A resumption is *the
parked executor finishing its yield*: hand the tool executor its `Decision`
and it continues — deny yields the denial result, allow invokes and yields
`ToolFinished`. The loop folds the fact exactly as if it had arrived without
the nap. Run and resume are the same invariant loop differing only in what
produces the first fact. *(Open: the resolution's type — see §13.)*

Sub-agent delegation needs nothing new: a tool whose implementation is another
agent is a tool executor that parks; the sub-agent's turns and facts live in
its own conversation. Agents compose fractally through the tool seam; the
parent's grammar never learns its tool was an agent.

## 6. The loop, the turn, and failure routing

**The loop** (invariant, core-owned): ask the state to fold the fact; consult
the `TerminationPolicy` with the new state — a halt discards the step's
unperformed effects (intents, not obligations) and applies the closure
transition `state.halted(reason)`; otherwise tell Memory any newborn messages
(§7), persist progress, and for each emitted effect ask its executor for the
next fact; repeat until no effects (terminal) or a park. Synchronous by
nature — asynchrony is what callers build around a segment.

**Termination is the loop's brake, not the fold's business.** The policy
decides when to stop *driving* — call ceilings, error streaks — so the loop
consults it, uniformly, after every fold: a law, not a list of hand-picked
check sites. The two kinds of death separate crisply: *intrinsic* fatality
arrives in facts (`MAX_TOKENS`, `REFUSAL`, `ModelCallFailed`) and the fold
handles it — the state can read its own doom; *extrinsic* limits are about
continued driving and belong to the loop. Replay is unaffected: it was always
"facts + same assembly," and a pure policy re-consulted by the same loop
discipline reproduces every halt deterministically.

**The fold lives on the state — there is no Reducer object.** v2's `Reducer`
earned its separate existence by carrying cargo: compaction config, eleven
event arms, delta merging, message construction. This amendment empties it —
four transitions and one policy consult — so the object dissolves and the
automaton moves onto the thing it governs. `ConversationState` is a rich
*immutable* domain object: each fact folds through the state's own method,
returning the `Step` holder (new state + zero or more effects). Determinism,
replay, and sealed exhaustiveness are unchanged — the fold is the same pure
function; it now has a home instead of a house. The fold is *parameter-free* —
`state.fold(event) → Step` — because termination is not its business (the loop
consults the policy; see below), so state holds no config and threads none.
Alongside the fact transitions, state exposes one closure transition,
`halted(reason)`: lands `FAILED`, answers pending calls with abandoned-error
results, and births the flush message for Memory — the same wire-legality
closure every failing path owes. `Effect`/`Step` become core-grammar citizens
beside the state (package placement is plan-level detail). v2's closure claim
strengthens: semantics are no longer an object you could even try to swap.

**Terminology, made crisp:**

- A **turn** is the agent's turn in the dialogue: it starts at a tell and ends
  only at a *clean* model response — no homework. (`StopReason.END_TURN` vs
  `TOOL_USE` already encodes this on the wire.)
- A **call** is one model invocation. A turn contains one or more calls.
  Renames follow: the per-call fact is `ModelResponded` (not
  `ModelTurnEnded` — under this definition that name is false), and
  `ConversationState.turns()` becomes `modelCalls()`.
- A turn is a **logical span, not a synchronous execution**: it may suspend at
  parks and resume in other processes — several **run segments**, one turn.

**The failure-routing law.** *Throw when re-performing might work; yield a
fact when it can't.*

- **Transient** (socket reset, 529, retries exhausted): exception. The fold
  never happened; **status is a continuation pointer** — state still says
  exactly what the loop should do next, so crash recovery and retry-later are
  the same operation: re-drive. Telemetry's jurisdiction; the conversation
  doesn't care.
- **Permanent, conversation-shaped** (context outgrew the window): fact —
  `ModelCallFailed` → `FAILED` with reason. Re-performing is futile; the
  course must change, and the only legal way state changes is a fact through
  the fold. Without this, an autonomous agent's conversation sits pointing
  at work that can never succeed — a silent zombie.
- The classification burden lands on the provider seam, which already
  half-carries it (`RetryingModelProvider` classifies retryable-vs-not).
- Factizing failure opens a door (not built now): the fold could one day
  answer overflow with something smarter than `FAILED`, as a pure testable
  decision.

## 7. Memory — the content jurisdiction

```java
interface Memory {
  void remember(…);        // told: every message-grade happening, in order,
                           //   for the conversation's whole life
  Context recall(…);       // asked: build the finished context for the next call
}
```

> The Memory is told everything that was said, and decides what the model is
> reminded of.

**Told message-grain, and that's principled: wire law lives in the harness.**
The loop tells Memory *legal, wire-shaped messages* at the fold chokepoints —
the user message when `AgentTold` folds, the assistant message when
`ModelResponded` folds, the batched results message when the tool debt clears.
That list is closed, and not by coincidence: the wire dialogue has exactly
three message producers, so Memory has exactly three tellings. It never hears
about pending calls — pending is *debt* (state's business, the shape of an
unfinished turn), not *meaning* (what was said) — and since the debt must
fully clear before the next model call, no recall can ever want a
half-answered transaction: the telling schedule and the transaction-atomicity
rule below agree by construction.
Providers of Memory never learn pairing or batching rules. (This resurrects
the newborn chokepoint with dignity: `MessageAppended` retired as a broadcast;
this is a *directed* feed to the one party whose job depends on message
births.) The assistant message rides `ModelResponded` verbatim — thinking
signatures and tool-use blocks must round-trip exactly; the model's own
contribution is wire-truth, never reconstructed. Message *construction*
otherwise leaves the fold entirely: facts are what happened; messages are
how the read path presents what happened to a model.

**Memory absorbs the read path whole.** Projection and enrichment are
implementation details behind the Memory facade, not core seams: `recall`
returns the *finished*, legal context — seeded, shaped, garnished — and the
model-call executor adds only settings before touching the wire. Memory is a
**subdomain**: it can be beefed up over time (checkpointing, retrieval,
projection strategies, enrichment sources) without the core loop ever
learning, and the loop's simplicity is bought by exactly this abstraction.
The `ContextPipeline`/`Projection`/`ContextEnricher` family retires as core
seams; their ideas live on as Memory-internal vocabulary.

**Freedom of retention, rule of law at the border.** Inside, a Memory may
transcribe, summarize, checkpoint, embed, or discard — the harness never
audits how it thinks. At the border, `recall` must return a legal `Context`:

- **The floor is safe by construction.** Legal messages went in; the trivial
  Memory (keep the list, return the list) cannot produce an illegal context.
  Only synthesizing Memories take on legality risk.
- **The unit of retention is the transaction, not the message.** An assistant
  message carrying tool-use blocks and the results message answering it are
  one atomic unit — keep both or drop both, never split, never reorder across.
  Summaries operate on transaction boundaries; a verbatim tail starts at one.
- **Enforcement is eager**: `Context.of` validates `recall`'s return before
  anything touches the wire. A pair-breaking Memory is a *bug* — it throws,
  loudly, naming the violation (re-performing works once the code is fixed).
  Content that outgrew the window is *fate* — `ModelCallFailed` at the wire.
  Bug → throw and re-drive; fate → fact and fold.

**Compaction dissolves into Memory.** Compaction's facthood rested on the
working set being state; content has left state. A Memory that summarizes
internally and seeds from a checkpoint is *reading the record selectively* —
views don't change the record, so the first law holds trivially. The v2
jurisdiction rule (§10.6) had already exiled compactor spend from the ledger —
the design always treated compaction as not-of-the-conversation; the grammar
kept its paperwork out of habit. When-to-summarize becomes provider strategy
(Memory hears every `ModelResponded`, so measured usage rides the telling);
the *enforcement* boundary for a Memory that engineers badly is
`ModelCallFailed`. A summarizing Memory needs a `ModelProvider` in hand — the
same wiring `AgentBuilder` does for today's `Summarizer`, re-aimed.

**Replay stance (durable engines):** re-driving replays facts, which re-tells
Memory. The contract needs an idempotency ruling — told-exactly-once via
high-water mark, or providers tolerate replay. *(Flagged, not yet ruled.)*

## 8. Turn texture — `TurnEvent` and `TurnObserver`

An API-side sealed vocabulary for the live texture of a turn — meaningful only
to something watching it happen, never folded into anything:

- `TextDelta`, `ThinkingDelta`, a redacted-thinking marker.
- `ToolUseEmitted` — *open, lean keep*: it arrives inside the delta stream,
  and live UIs go dead-air through tool-heavy turns without it.
- No milestones: "tool started/finished" are **system events** (listener
  channel, where `ToolProgress` lives) — they matter precisely when nobody is
  sitting there.

`ModelEvent` (SPI, what providers emit) and `TurnEvent` (API, what apps
observe) are near-twins on purpose: provider and app vocabularies evolve on
different clocks; the thin translation lives in exactly one place, the
model-call executor.

**The `TurnObserver` is required by the model-call executor's signature and
bound per-entry.** Both entry points take one — `tell(input, observer)` and
`resume(token, resolution, observer)` — with `TurnObserver.noop()` as the
default. Texture belongs to whoever is sitting there *now*, and "now" restarts
at every resume; a segment's observer sees deltas from its entry onward, and
anything missed is in the facts. (The name slightly overpromises in the parked
case — it observes the turn *while you hold it* — documented, accepted.)

**Two use cases, one design.** The sitting consumer (interactive: REPL, UI)
passes an observer and reads the outcome. The autonomous agent (webhook, cron)
runs with noop and walks away — its real outputs went out the other doors
while the turn ran: tools acted, facts hit the system channel, state landed in
the store. Same loop, same executors, same facts; the differences are all at
the edges.

## 9. `ConversationState` — the control block

State sheds its messages and becomes what it always wanted to be:

| Field | Kind | Reader |
|---|---|---|
| `id` | identity | everyone |
| `status` | continuation pointer / terminal marker | the loop, §6 refusal contract |
| `pendingCalls`, `pendingResults` | **the debt lane** — homework assigned and handed in, awaiting flush | the fold |
| `modelCalls` | policy accumulator | termination policy |
| `consecutiveErrors` | policy accumulator (circuit breaker) | termination policy |
| `usage` | accounting (sum of `ModelResponded.usage`) | ledger readers |
| `failureReason` | terminal annotation | post-mortems |

Departed: `messages` (→ Memory), `pendingBlocks` (→ turn texture),
`generation` and `lastInputTokens` (→ died with compaction's departure).
Statuses `AWAITING_APPROVAL` and `COMPACTING` retire.

**The tool debt is the only structural state** — the only part holding the
shape of an unfinished turn; everything else is a scalar. And every field,
debt included, is derivable by replaying the facts: state is the
**materialized fold** — a snapshot kept so a durable engine resumes in O(1)
and the fold has a substrate. Log is truth; state is cache.

The fold keeps: the misdelivery guard, the error-streak and call-count
bookkeeping (the dials the policy reads), debt bookkeeping, status
transitions, and the flush decision (when debt clears, the results message is
born and told to Memory). The termination *consultation* is the loop's (§6).

## 10. The facade — settled and unsettled

**Settled principle:** *the outcome of a turn is a reading, not a delivery.*
The segment ends; its outcome exists; a waiting caller reads it, a
fire-and-forget caller discards it, and nothing downstream depends on the
return value being consumed. `Reply` is interactive sugar — a reading of the
completed state's last assistant message — not an obligation every turn owes.

**Settled guardrail:** the entry point must not become *enqueue*. Mailbox
semantics would turn the §6 collision contract from a loud throw into a silent
queue. Callers who want later put a queue in front — their architecture,
visible in their code.

**Unsettled:** whether the facade names both intents Akka-style (`tell` →
outcome ignored, `ask` → `Reply`), with the note that asking is telling plus
reading — there is no `AgentAsked` fact; the conversation only ever learns it
was told. The user has explicitly not signed off; do not build either shape
yet.

## 11. What this simplifies away (inventory)

- `Effect.RequestApproval`, `Effect.Compact` — and with them the two-step
  approval dance and the deferred-effects streaming trick.
- Grammar events: deltas ×4, `ToolCallRequested`, `ApprovalDecided`,
  `Compacted`, `CompactionSkipped`; `ModelTurnEnded` reshaped into
  `ModelResponded`.
- `MessageAppended` and the newborn-announcement diffing (`announceNewborns`) —
  reborn as the directed Memory feed.
- `ConversationState.messages`, `pendingBlocks`, `generation`,
  `lastInputTokens`; statuses `AWAITING_APPROVAL`, `COMPACTING`.
- The `Reducer` object itself, and its delta-merging arms, compaction guards (stale-`Compacted`,
  pending-lane splice, size check), and message construction.
- The engine-owned recursive loop; `translate()`'s 1:1 mirror.
- The `Compactor`/`Summarizer`/`ContextPipeline`/`Projection`/
  `ContextEnricher` family as separate core seams — their jobs move inside
  Memory implementations, behind the facade (§7).

## 12. Amendment ledger — v2 rulings that move

| v2 ruling | Disposition |
|---|---|
| §7 grammar roster (`Effect`, `ConversationEvent` variants) | Replaced by §2–§3 here. Pre-1.0 window: removals are free now, majors later. |
| Approval flow (`RequestApproval`/`ApprovalDecided`, §~9/10.5 chokepoint prose) | Gate folds into the tool executor (§4); chokepoint claim strengthened. |
| Compaction as reducer-decided effect + facts (incl. reducer §17-adjacent guards) | Dissolved into Memory (§7). Jurisdiction rule §10.6 extended to its logical end. |
| Newborn chokepoint / `MessageAppended` (§9.1, §10.8) | Retired as broadcast; reborn as the directed Memory feed (§7). |
| "Journal is a listener; transcript store family retires" | **Partially reversed**: the fact log becomes first-class, load-bearing record (in-memory for tests/CLI, durable for fleets). The journal-as-listener remains valid for *additional* subscribers. The v2 rationale should be re-read at plan time to confirm nothing else leaned on it. |
| `ExecutionEngine` as the implementable seam | Dissolves into loop + `EffectExecutors` + assembly (§1, §5). §6 resume-refusal contract survives, re-homed on the loop. |
| Working set in `ConversationState` | Content leaves state (§9); "working set" as a state concept retires with it. |
| `Reducer` as a standalone object | Dissolves into `ConversationState`'s own fold methods (§6). `Step` survives; `TerminationPolicy` moves to the loop, consulted with the state after every fold. Semantics stay closed — more closed: no object left to swap. |
| Context pipeline (`ContextPipeline`/`Projection`/`ContextEnricher`) as core seams | Absorbed behind the Memory facade (§7); `recall` returns the finished context. |

## 13. Open questions (all small, none load-bearing)

1. **`ToolUseEmitted` in `TurnEvent`** — lean *keep* (live UIs dead-air
   without it).
2. **Multi-call homework sequencing** — the fold emits all `ExecuteTool`
   effects from one `ModelResponded` fold vs. one-per-fold; lean *all at once*
   (opens the parallel-tools door with no grammar change).
3. **Park-resolution typing** — opaque payload vs. per-executor typed
   resolutions; genuinely unresolved.
4. **Memory scoping** — per-conversation instance vs. harness-wide keyed by
   `ConversationId`; lean *keyed* (matches the store idiom; facade hides the
   key).
5. **Facade verbs** (`tell`/`ask`) — explicitly unsettled (§10).
6. **Memory replay idempotency** — high-water mark vs. tolerant providers
   (§7).

*(Resolved since first draft: `recall` **absorbs** the projection/enrichment
pipeline — they are Memory implementation details behind the facade, §7.)*

## 14. Testing posture (unchanged in spirit, simpler in practice)

The v2 promises hold and get cheaper: the fold is tested pure, now with
four arms instead of eleven and no message construction; the invariant loop is
tested once with fake executors (its ordering laws — consult-policy-after-
every-fold, tell-Memory-then-perform, persist-on-every-exit, park handling —
become directly assertable); Memory
implementations are tested against the border contract (`Context` legality,
transaction atomicity) without a model; executors are tested per-slot. The
no-mocking-library promise, prose test style, and no-network `verify` all
stand.
