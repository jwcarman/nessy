# Inline inference: a pure core and two harnesses

**Status: DESIGN, not approved. Names marked `TODO — James` are placeholders and must not reach a
plan or a dispatch brief until named. Several items are new public concepts and need explicit
sign-off per the design-authority rule.**

Date: 2026-09-24. Supersedes nothing. Amends nothing. Cites and relies on
`2026-08-28-ingest-and-turns-design.md` §4a.

---

## 1. The question

Nessy can run a durable, resumable, coalescing agent. It cannot do the thing most applications
actually want: **ask a model something, with tools, and get an answer back on the same call
stack.**

`Extractor` is the only blocking door and it cannot use a tool. Everything else requires
PostgreSQL, a schema, an agent type and a harness before a single question can be asked. A
consumer building a request/response agent (one Nessy agent per HTTP request, terminate by hand,
tool calls counted by the application, a `CompletableFuture` keyed by `AgentId`) is not misusing
the API; they are approximating a door that does not exist.

## 2. The axis is not durability

Durability is a consequence, not a property to choose. The question that decides everything is:

> **Is anyone waiting for the answer?**

| because nobody is waiting… | you need |
|---|---|
| there is no connection to hand the result to | persistence |
| work arrives whenever it arrives | coalescing |
| it can take hours | deferral |
| it outlives the process | resumption |
| it arrives from several sources at once | serialization |

Those five are one decision's consequences. The inverse is as tight: when someone *is* waiting, the
connection is the durability, the request is the coalescing boundary, and deferral is impossible by
construction.

This replaces "do I need durability?" (which nobody can answer) with "is someone waiting?" (which
everybody can). It also sorts the existing trigger matrix without further thought: webhook, queue
and schedule are nobody-waiting; HTTP request is someone-waiting.

## 3. A pure core and a harness

```
PURE ── one type. no I/O, no clock, no randomness
  sealed interface AgentState {
      static AgentState idle(Seq at);           nothing since the watermark
      Seq        seq();                         where this state sits
      AgentState apply(AgentEvent event);       default: guards order, then delegates
      AgentState applyAll(List<AgentEvent>);    default: the loop
      Decision   execute(AgentCommand cmd);     the work: events + effects, or Ignore
  }

HARNESS ── everything else
  reconstitute   read from the watermark, replay with apply, arrive at the state
  feed           render + claim-check the work, hand the state a command carrying a ref
  store          append the events and the effects
  execute        run the effects; render + claim-check each result back into a command
  ── durable only ──
  poll the backlog, start turns, decline when busy, claim, lease, defer
```

**Commands arrive from three places, and the harness owns all of them:**

| source | commands | notes |
|---|---|---|
| external | `StartTurn(ref)` · `Terminate` | the work, and the end of it |
| internal | `CompleteInference` · `CompleteToolCall` · `CompleteApproval` | from the harness's own effect execution |

**`StartTurn` carries a reference to the work and is accepted only by `Idle`.** There is no separate
"here is an observation" command: queuing is entirely harness-side, and the harness issues
`StartTurn` when it holds work and the state is idle.

**Claim-checking bookends the core**, and is therefore harness-level in both directions:

```
inbound    O ──render──▶ blocks ──claim check──▶ ref ──▶ StartTurn(ref)
outbound   effect result ──render──▶ blocks ──claim check──▶ ref ──▶ CompleteX(ref)
```

Inline's claim check is a `HashMap`. Same seam, no storage, no cliff — the discipline costs the
inline harness nothing and the core cannot tell the difference.

**No command and no event carries a payload.** Not the observation, not the model's blocks, not a
tool's result. Every one of them is claim-checked by the harness and the command carries the
reference (§6, and the 2026-08-28 ruling: *"State holds identifiers, status, and human decisions.
Content lives elsewhere."*). What stays inline is exactly that list:

| in the spine | example |
|---|---|
| identifiers | turn id, call id, seq, payload ref |
| status | which arm, which outcome kind |
| human decisions | approved / denied — explicitly named by the ruling |
| counts | tokens |

Everything else is a ref. The free-text exceptions — failure messages and denial reasons — are the
known leak path and are governed by §6.2's rule: *name what went wrong, never the values involved.*

The `Idle`-only check is **not** redundant with the harness declining to ask while busy. The harness
not asking is an optimisation; the state refusing is the guarantee. Two nodes can both reconstitute,
both see `Idle`, and both issue `StartTurn` — the state check plus the seq conflict is what makes the
loser harmless.

**Grouped by effect kind, not by outcome kind.** One completion command per effect arm, which
makes the 1:1 invariant visible in the type names:

```
AgentEffect.Infer()      → CompleteInference
AgentEffect.CallTool(…)  → CompleteToolCall(CallId, …)
AgentEffect.Approve(…)   → CompleteApproval(CallId, …)
```

A tool call is two effects in sequence — ask whether it may run, then run it — so it is two
completions.

This is deliberately neither extreme. A single `Deliver(effectId, outcome)` envelope would force
*every* arm to match the wrapper and then unpack to discover whether it cared; eight flat outcome
commands would make every arm's switch eight-armed. Grouping by effect means **an arm knows from the
command alone whether it is concerned** — `Inferring` cares about `CompleteInference` and nothing
else — and only the one arm that cares looks inside.

The inner switch is then real logic rather than routing, which is the test for whether a nesting
earns its place:

```java
// Inferring.execute(CompleteInference c)
case InferenceAnswered         -> record + end the turn
case InferenceRefused          -> record + end the turn
case InferenceFailed           -> record + end the turn
case InferenceRequestedActions -> record + emit CallTool effects
```

Four different consequences, decided in the one place that knows what they mean.

It also settles the tense: **the command is imperative, the payload is a past-tense fact.**
`CompleteToolCall(callId, ToolSucceeded(…))` — "complete this call" is a request that may be
declined; "it succeeded" is what happened. Both words are correct and both are in the right
position.

Dropping the envelope also **improves** deduplication, which was the only thing it bought. At-least-
once delivery is handled by the state, not by an id: a second `InferenceAnswered` arrives when the
agent is no longer inferring, so that arm is not expecting it and emits nothing; a `ToolSucceeded`
for a call already recorded in the current exchange is visible in the state. No seen-set, no lookup
key, no wrapper. This is the third thing state-as-handler absorbs that would otherwise need
machinery — after irreversible termination and declining `StartTurn` when busy.

> **CHECK:** whether `CallId` alone disambiguates tool outcomes when several run in parallel and one
> is redelivered. Probably yes — the exchange records outcomes against call ids — but confirm
> against the real `Exchange` shape rather than assuming.

**Five commands, and the set is the same for every harness.** An earlier draft had `StartTurn`
as a durable-only command issued by the poller, which made the command sets differ. That was an
artefact of a command that should not have existed: the poller's job is to decide *when* to present
work, not to issue a different kind of request. The pure core is not merely shared between harnesses
— it is identically exercised by all of them.

### 3.2 The core is not generic

`<O>` does not reach the pure core at all. The harness renders before issuing the command:

```
harness   O ──render──▶ blocks ──claim check──▶ ref ──▶ StartTurn(ref)
core      never sees O, and never sees the blocks either
```

**Predictions to verify during the refactor:**

- `AgentState<O>` → `AgentState`; `Decision<O>` → `Decision`
- `Backlog<O>`, `BacklogItem<O>`, `Pull<O>`, `ObservationCoalescer<O>` and `ObservationRenderer<O>`
  stay generic and stay harness-side. Coalescing genuinely needs `O` — *"these two alerts are the
  same, keep the newer"* is semantic and cannot be done on rendered blocks
- the existing javadoc says "`<O>` stops at the renderer"; this makes it stop at the harness
  boundary, which is earlier and more complete

**Why this matters more than tidiness: the event stream's types become a closed set.** Today a
stored agent document contains whatever `O` happens to be. With an `O`-free core, every type in the
append-only history is one of Nessy's own sealed types — so event versioning, the one unavoidable
cost identified in §5, shrinks from a general problem to a bounded one you fully control. `O` is
still serialised, in the document's backlog, but that is a row rewritten wholesale rather than a
history that must stay readable forever.

That is the whole architecture. Three pure functions with no dependencies, and one component that
owns every piece of I/O and all the orchestration.

**The two harnesses differ only in what they do with those four verbs:**

| | reconstitute | feed | store | execute |
|---|---|---|---|---|
| inline | a local variable | a `while` loop | nowhere | on the call stack |
| durable | read the stream | the dispatcher | the stream | via the outbox |

The pure part is byte-for-byte the same in both. That is what stops the two from drifting into two
subtly different agent loops, which is the usual fate of this split.

**Reconstitution produces the thing that executes.** Replay the events in order onto `initial()`,
and the state object you arrive at is what handles the command. Not a free function taking state as
an argument — the state *is* the handler, so what a command means depends on which state you are in,
and an `Idle` that cannot be busy and a `Busy` that cannot start a turn are different types rather
than branches.

That is what `AgentState` already is: a sealed interface whose arms answer `observe` and
`terminate` with a `Decision`. This design keeps that shape and narrows what the arms hold
(see *Predicted simplification*, below).

> ⚠️ **Check against the 2026-09-03 ruling**, which recorded "no State pattern". Polymorphic dispatch
> on the state arm is arguably that pattern by another name. Either the ruling meant something
> narrower — most likely that the *backlog* should not be modelled as states — or it needs amending
> here, deliberately, rather than being contradicted in passing.

**There is no "fold".** Reconstitution is a loop, not a concept:

```java
AgentState state = AgentState.idle();
for (AgentEvent e : events) state = state.apply(e);
```

A helper such as `AgentState.from(events)` is convenience over that loop, not a thing needing a name.
The pure core is **one sealed type with three methods** — this whole conversation called it "the
fold", and there was never anything there to name.

**`execute` returns events and effects only — never the resulting state.** Today
`Decision.Advance` carries `next` *and* `recorded`, which means the new state is reachable two ways:
directly, or by applying the events. If those ever disagree, the state the harness holds does not
match the events that were persisted, and the disagreement surfaces on the *next* reconstitution,
far from the bug.

Dropping `next` makes `apply` the **only** way state changes, so the events are definitively the
truth and there is nothing to diverge from. A harness that wants the new state derives it:

```java
Decision d = state.execute(command);
// store d.events(), d.effects()
state = state.applyAll(d.events());     // inline keeps it; durable discards and re-reads
```

Same principle as dropping stored current state, stored `outstanding`, and the backlog-in-the-state:
never keep or return what is derivable, because two representations eventually disagree.

**The order guard lives in `apply`, not in `applyAll`.** The state knows its own seq, so `apply`
checks the event's against it and refuses anything not strictly after. This guards **every** path —
including the single-event one the harness uses most, when a command's events come back — where a
guard only in `applyAll` would have left it open.

```java
default AgentState apply(AgentEvent event) {
    if (!event.seq().isAfter(seq())) {
        throw new IllegalArgumentException(
            "event at " + event.seq() + " applied to state at " + seq());
    }
    return accept(event);          // the arms implement this
}
```

Out-of-order replay does not fail on its own; it silently produces a state that never existed, and
everything downstream trusts reconstitution. Nothing about `List<AgentEvent>` tells a caller it must
be sorted, and a hand-assembled list, a merged query or a `Set` will not be. `isAfter` also rejects
**re-applying the same event**, which is a real replay hazard and otherwise silent.

The extra method (`accept`) exists so the guard cannot be forgotten by an arm. A check each arm must
remember is a check that will eventually be missed, and the failure mode is a state that never
existed.

It should **not** insist on contiguity. A watermark means starting mid-stream is legitimate, and a
gap may be a valid projection rather than a bug. Monotonic is always true; contiguous is not.

**`idle` takes a position.** Reconstitution starts at the watermark, not at zero, so an agent with an
empty tail must still know what seq to append at. Threading the position alongside the state instead
would reintroduce exactly the "two things that must agree" this design has spent its length
removing.

**`TurnId` is probably not separate.** Per the existing note — *"`turn` is the seq of the observation
that opened it, so a turn still needs no identifier of its own"* — an accepted `StartTurn` takes its
turn id from the seq its event lands at, and reconstitution learns the open turn's id from that same
event. Only the position has to be supplied.

**The membership test:** *can the inline harness do this?* Reconstitute, execute, store, route —
yes, all four, trivially. Backlog, coalescing, scheduling, deferral, claims, leases — no. This
stops the boundary being a matter of taste.

**"Look for more work when the turn completes" is harness, not state.** An inline harness does not
do it at all. Latency is preserved by having the durable harness self-trigger on the turn's terminal
event rather than waiting out a poll interval — an optimisation in the harness, not semantics in the
pure part.

**Predicted simplification:** with turn progress derived from turn events, `AgentState` loses
`requestSeq` and `outstanding` and collapses towards `Idle | Busy(TurnId) | Terminal`. `outstanding`
is today stored *and* derivable from the last `Exchange`; that duplication goes away rather than
being tested for.

### 3.1 Termination

The command is `terminate`. The event is `Terminated`. The state is `Terminal`.

Reconstitution has **no special case for it**:

```
read the watermark ──▶ [Terminated]        and nothing else, ever
idle(watermark) ──apply(Terminated)──▶ Terminal
```

The watermark points *at* the `Terminated` event, so a dead agent replays exactly one event and
costs one row. Reconstitution is always "read the watermark, replay from `Idle`" — there is no
sentinel value, no lifecycle flag, and no branch.

**`Terminal` refuses every command, loudly.** Not `Decision.ignore()` — silently swallowing a
command sent to a dead agent is the failure that costs somebody an afternoon. And because `apply`
moves only on events and `Terminal` emits none, it is unreachable-from by construction: termination
cannot be undone by anything arriving late, and that guarantee is a property of the type rather than
a rule the surrounding code must remember.

Note this supersedes the mechanism the current code uses, where a *sealed backlog* is what makes
termination stick. The backlog needs no such flag once the refusal lives in the state.

**Two moments, not one.** `terminate` does not end things immediately: outstanding calls have rows
and are owed outcomes, so the agent stops accepting new work but finishes the turn in flight.
`Terminal` is reached when the last outcome lands. The design must name both.

> **OPEN:** "loudly" is right for every command except the redelivered outcomes. A `ToolSucceeded`
> arriving after termination is at-least-once machinery working correctly, not a caller error, and
> shouting at it produces alarms from healthy infrastructure. Recommendation: `Terminal` is loud for
> `StartTurn` and `Terminate`, and silent for the completions — consistent with every other
> state, where a redelivered outcome is simply a command the current arm has nothing to do with.

## 4. The pure part is pure, and it is forced four times over

Not a style preference. Four independent things break without it, and only one of them is obvious:

1. **Retry.** A seq conflict re-runs `execute` (§5.2). A side effect there happens twice.
2. **Reconstitution.** `apply` runs over the whole turn on every command. Anything
   non-deterministic in it means the state you rebuild is not the state you had.
3. **The atomicity invariant.** An impure `execute` invites doing the slow work where the commit is,
   and the whole design dies if a transaction is ever held across a 47-second inference. The same
   rule holds in loch: no transaction is open across a derivation.
4. **Alternative drivers stay possible.** Anything that replays -- a different durable harness, a
   workflow engine, a test that re-runs a stream -- needs determinism. Meeting it here costs
   nothing.

*(An earlier draft justified purity by "the command is executed inside the transaction." It is not — §5.2
moved it out, and the argument had to be rebuilt. Retry and reconstitution are the load-bearing
reasons; the transaction is a third.)*

Everything non-deterministic is frozen into an event *before* either function sees it, which is what
makes replay meaningful at all. You cannot replay an inference; you can only record what it
returned.

**The invariant to defend:** the pressure to break this will arrive reasonably — a clock, a config
lookup, a feature flag. The first one costs retry, replay and the shared harness all at once.

**Bounds are a pure predicate over `Turn`:**

- exchange count → model calls
- `exchanges.stream().mapToInt(e -> e.calls().size()).sum()` → tool calls
- `tokens` → budget (**already on the record**)
- wall clock → deadline

Unit-testable with no provider, no database, and identical in both doors by construction rather
than by discipline.

## 5. Commands, events, effects

Terminology: **the decider**, not "event sourcing". We take two properties from ES and none of the
infrastructure. What was previously rejected — aggregates, repositories, projections, snapshotting,
an event-store framework — stays rejected.

| taken | not taken |
|---|---|
| events are truth, state is rebuilt by replay | event-store machinery |
| no stored current state | projections / read models |
| sequence as optimistic concurrency | snapshotting mid-turn |
| | aggregates & repositories |

Event versioning **is** an unavoidable new cost: persisted events outlive the code that wrote them.
Use the house pattern from day one — `@JsonTypeInfo(use = Id.NAME)`, as `Failure` already does — so
the wire keys on declared names rather than class names. Retrofitting this is what everyone regrets.

### 5.1 The invariant

> **A command is decided and its events *and* effects are committed in one transaction, or nothing
> happened at all.**

- no orphan effect — work cannot be scheduled for a transition that did not happen
- no orphan event — "I decided to infer" cannot be recorded without the inference being scheduled
- decisioning is exactly-once, even though execution is at-least-once

The effects table is the outbox. `EffectDispatcher`, `pollInterval` and `maxInFlight` already are
this.

### 5.2 The algorithm — all of it the harness's

```
1. reconstitute   read the agent document (a row) + the current turn's events (a range),
                  replay with apply                                  ── OUTSIDE any transaction
2. execute        pure, fast, no I/O  →  Decision                     ── OUTSIDE any transaction
3. append         events + effects + updated document, expecting seq N
                  ── on seq conflict ──▶ back to 1                   ── the ONLY transaction
```

Then, still the harness's job: execute the effects, and route each outcome back in as the next
command.

**Only step 3 is transactional.** Steps 1 and 2 were originally written into this record as part of
the transaction; they do not belong there. `execute` is pure, so reading at seq N, executing, and
appending conditionally on N is ordinary optimistic concurrency — and it shrinks the transaction to
a single conditional insert with no reads inside it.

**Three requirements the three steps hide:**

- **Retry.** Step 3 can lose a race; `(type, id, seq)` uniqueness makes it fail rather than corrupt.
  Retry costs nothing because step 2 is pure — the same property paying off a second time.
- **Two reads, three writes.** The agent document remains genuinely stored mutable disposition —
  backlog and what is in flight — because disposition is not derivable from turn history. "Never store
  state" is not absolute; it is *never store derived state that can still change*.
- **Idempotency.** At-least-once delivery means "here's the result of call X" can arrive twice.
  Reconstitution holds the whole turn, so `execute` sees an outcome already recorded against that
  effect id and emits nothing. Deduplication falls out of rebuilding; a design tracking running
  state would need a seen-set.

**Known load characteristic:** a thundering herd on *one* agent means N reconstitutions contending
for one seq, and N-1 retries. Coalescing reduces what is written, not the contention to write it.
Fine at realistic rates; the fix if it ever is not is batching in the harness, not a change to this
policy.

**Why process commands inline rather than queueing them.** When the call returns, the decision is
durable — there is no "accepted but not yet decided" limbo to recover or explain. Back-pressure is
felt by the caller rather than hidden in a queue. Errors reach whoever can act on them. And it is
what keeps the two harnesses identical: an inline harness processes commands inline of necessity, so
a durable harness that queued them would diverge immediately.

### 5.3 Commands decline; declines emit nothing

`StartTurn` is accepted by `Idle` and declined by every other state (§3.1 aside: `Terminal` declines
it loudly). A decline emits nothing.

*Only accepted commands append.* Queuing happens in the harness, so the state is asked far less often
than the old polling design implied — but a race between two nodes still produces a decline, and the
stream must not fill with records of nothing happening.

The existing `Pull.Item` javadoc implements the old version of this by hand — *"taking must be
undoable… a caller that refuses simply never persists `remainder`"*. **That requirement
disappears.** Taking was only undoable because it mutated stored state before knowing whether the
agent would accept. With queuing in the harness and acceptance decided by the state, `StartTurn`
either produces events or it does not: there is no remainder to hold and discard. You cannot need to
undo something you never did.

### 5.4 Snapshots

No snapshot of an in-flight turn. Turns are short and now bounded, so replay is cheap *for a
provable reason* — the bounds are what make this safe.

Finding the current turn: derive from the tail (bounded by the turn policy), or `max` on the stream.
A stored pointer is an optimisation to add after measurement, and note that it is derived state
again.

> **Snapshot on completion. Never mid-turn.**

A completed turn has `result != null` and will never be appended to again, so materialising it
cannot drift. This keeps context assembly a range read rather than replaying K turns per inference.

### 5.5 Serialization becomes a constraint

`(agentType, agentId, seq)` with a uniqueness constraint makes the database enforce one-turn-at-a-
time. The actor prevents the wasted work; the constraint prevents the corruption. Two nodes racing
on a stale read is safe.

**Inline has no stream, so no seq, so no serialization.** This must be stated in the javadoc, not
discovered in production. It is one of the four graduation triggers.

## 6. Spine and payload

The rule is already settled — `2026-08-28-ingest-and-turns-design.md` §4a:

> **State holds identifiers, status, and human decisions. Content lives elsewhere.**

Measured there: durable state of `{"state":"idle"}`, 16 bytes, flat across 100+ revisions; the named
heavyweights are tool arguments and results; the mechanic was **write** amplification (every
revision rewrites the whole document, 14× growth).

This design adds a second, independent justification. Event sourcing kills write amplification by
construction — append-only rewrites nothing — and introduces the mirror-image problem:

| | why content must live elsewhere |
|---|---|
| document state (2026-08-28) | **write** amplification |
| the decider (2026-09-24) | **read** amplification — every command replays the turn |

A rule with two independent justifications from opposite mechanics is a real rule. **Nothing to
amend.**

| | contents | read by | when |
|---|---|---|---|
| **spine** | seq, turn, call ids, outcome *kind*, token counts, result, refs | `apply` / `execute` | every command |
| **payload** | observation, model blocks, tool result blocks | context assembly | once per model call |

*If `execute` branches on it, it is spine. If only the model ever sees it, it is payload.*

**Effects reference; they never carry.** An effect row saying "call the model for turn T" stays
small and lets the dispatcher hydrate the context outside the transaction. A materialised context in
an effect row would write the whole prompt on every step, inside the transaction.

### 6.1 What a payload-free spine buys

- **queryable** — "how often does this agent call `issue_credit`?" becomes SQL against a table with
  nothing sensitive in it; replicable to analytics without a compliance review
- **narrow encryption surface** — `StorageCodec` applies to one store instead of everything
- **differential retention** — keep the spine seven years, expire payloads in thirty days

### 6.2 The leak path to close deliberately

`ToolResult.Failure(String message)`, `ToolOutcome.Failed(…, String message)`,
`ToolOutcome.Denied(…, String reason)`, `AgentEvent.CallFailed(…, String message)`.

Free text, in the spine, written by tool authors — and error messages are *the* classic place
business data leaks (`"no account matching 4111111111114821"`), landing in the plaintext,
long-retention, replicated table.

**Rule: a failure message names what went wrong, never the values involved.** Behind a reference is
not an option, because the model must read it. These are the only free-text fields in the spine,
which makes the rule greppable.

Honest caveat: a payload-free spine still carries **behavioural metadata** — which tools ran, how
often, how expensive. That is the audit trail, so it is the feature; but "no business payload" is
not "no information", and the record should say so rather than imply the table is inert.

## 7. Tool results

### 7.1 Typed — and it closes a hole in the governability framework

Today: `ToolResult.Success(List<Block.ToolResultContent> blocks)`. Every tool hand-formats what the
model sees.

The governability analysis says an inbound channel's capacity is bounded by the *type* of what
crosses it, and one unbounded field makes it infinite. We applied this at the entrance and nowhere
else. **Every tool result is another inbound channel, and today every one is unbounded prose.** A
tool reading directory entries whose descriptions an attacker can edit puts that text straight into
a privileged model's context, through a channel nobody counted.

Typed results are the same projection discipline applied to the other inbound channel.

```java
Tool<I, O>          // O is anything Jackson can serialise
```

- **lighter than inputs** — no `outputType()`; Jackson serialises the runtime object
- **escape hatch** — want raw? return `String`. Explicit, and it shows up in the measurement: a
  `String` return has unbounded capacity, readable off the signature rather than found by auditing
  the body. Same trick as loch's manifest — do not forbid, enumerate.
- inherits the configured `ObjectMapper`, so `@JsonValue` types render as they should

**Breaking change to the main door.** `Tool<I>` → `Tool<I, O>`; every tool everywhere changes. Cheap
now, expensive after 0.1.0.

Undecided: whether `ToolResult` becomes generic or `Success` holds `Object` with typing at the
tool's edge.

### 7.2 Rendered

Symmetric counterpart to the existing `ObservationRenderer<O>`:

```
tool returns O
  → render       O → List<Block.ToolResultContent>     default: JSON in a text block
  → claim check  blocks → payload store, ref
  → spine        event carries the ref
  → assembly     ref → blocks → provider
```

The JSON default loses nothing — JSON *is* structure, so typed results still buy the countable
channel and the structured audit. Custom renderers exist for where they genuinely diverge: a
`record Screenshot(byte[] png)` needs an image block, not base64 in prose.

**Store the rendered blocks, not `O`.** What the model was shown is the audit-relevant fact; keeping
`O` to re-render later means the transcript could one day disagree with history because someone
edited a renderer. Same drift argument as events-versus-state.

Rendering runs **outside** the transaction — it is part of executing the effect, not deciding.

### 7.3 Claim-checked automatically

**Tool results and model results are claim-checked by the runtime, unconditionally.** Tools know
nothing about it. This makes §6's rule a property of the runtime rather than a convention a tool
author might forget — it cannot be violated by a tool written next year by someone who has not read
this.

> **Write the payload first, commit the ref second.**

Same database: one transaction, moot. Any other store (S3, loch, elsewhere): the writes cannot be
atomic, and the ordering decides the failure mode.

- payload first → **orphan payload** on a crash: garbage, collectable, harmless
- ref first → **dangling ref**: an event pointing at nothing, unreadable forever

A collector sweeps payloads no event references.

## 8. Governed payloads

**Claim-checking (storage) and surrogates (governance) are independent axes.** Big and sensitive are
not the same question. A public 500 KB catalog dump wants a claim check and no surrogate; a one-line
customer name wants a surrogate and no claim check.

### 8.1 A tool is a source

Loch already has the word for "a channel by which data enters, and what it is worth on arrival".

```java
charter.source("tool.search_directory", Results.TYPE, ctx -> Label.of(INTEGRITY, UNENDORSED));
charter.source("tool.ledger_lookup",    Charges.TYPE, ctx -> Label.of(INTEGRITY, ENDORSED));
```

This is not a workaround for plumbing; it is what a tool *is*, and it completes the manifest. An
auditor asking "how does data get in, and what is each one worth?" gets a whole answer.

Declaring the label next to the tool would feel natural and would **defeat the manifest** — loch's
bet is that labelling is declared in one enumerable place.

### 8.2 Surrogates render as ids — which is the Dual LLM pattern

Jackson serialises `Surrogate<T>(String id)` to its id. So a tool returning a surrogate, bare or
nested, renders as an opaque reference. **No detection is needed; the type system already declared
it.**

> A surrogate in a tool result renders as its id. That is not a limitation — it is the declaration.
> If you wanted the model to read it, you would have returned the value.

The model can still *use* it: pass it to another tool whose parameter is `Surrogate<T>`. That is the
privileged model orchestrating over data it cannot read — Willison's Dual LLM, arriving for free.
guvnor's `IssueCreditTool` taking a claim id is this mechanism, not a toy.

**Never auto-reveal during rendering.** That would be silent declassification at the worst moment,
and a refusal halfway through a JSON document has no good answer.

Forgery is not Nessy's problem: an invented id deserialises fine and fails at the **reveal**,
because loch checks the recorded type against the destination's declaration.

### 8.3 Resolution, and refusal

```
Nessy   resolver(toolName, ref) → bytes | refused
App     destination.reading(typeFor(toolName)).reveal(Surrogate.of(ref))
```

Nessy never sees an axis, a label or a ceiling. The app declares the door; **Nessy never conjures
one** — no resolver configured means surrogates do not resolve, loudly. Silence is not permission at
the framework level either.

**Automatic *attempt* is not automatic *permission*.** The ceiling still adjudicates; only trying is
automated. This is what guvnor's `DeskAgent` already does.

`SurrogateDestination.reading(SurrogateType<T>)` admits several declared types, so one door can
cover every tool result — and the manifest then says *everything this agent sees passes through one
named destination with one ceiling*, which is stronger than scattered per-tool reveals. Apps wanting
different ceilings per result type declare more destinations; invisible to Nessy.

**New behaviour required:** a resolver can fail *legitimately*, which a table read cannot. What the
model is told then:

- the turn fails — safe, useless, punishes a policy working correctly
- the result is omitted — **worst**; the model reasons confidently across a hole it cannot detect
- **the model is told** — *"a result exists for that call and you are not cleared for it"*

The third is right and needs a `ToolOutcome` arm distinct from `Denied`. `Denied` is *the call never
ran*; this is *it ran and you cannot have the answer*. Different fact, different recovery. This is
the only place Nessy must know refusal exists.

## 9. À la carte

Only **two** of `nessy-engine`'s fourteen packages touch a `DataSource`:

```
DataSource-coupled:  harness, store
persistence-free:    schema, extraction, inference, tool, token, observability,
                     trace, history, embedding, agent, backlog, effect
```

The menu already exists at module level (18 modules; embedding, memory, planning, mcp, approval,
narration, lease all optional). **The problem is that the kitchen is one room**: to get schema
generation you take the durable runtime.

| course | needs | standalone value |
|---|---|---|
| schemas | nothing | Java type → JSON Schema, kept in sync with the type |
| providers | SPI | one API, many vendors |
| extraction | above | prose → typed record, sealed outcome |
| context assembly | api | ambient/RAG with collision checks, windows, summaries |
| **inline executor** | above | bounded, blocking, no deferral |
| durable agents | + DataSource | backlog, coalescing, resumption |
| approvals | + durable | deferral, human in the loop |

The spine that keeps this a framework rather than a toolbox is the **shared vocabulary** — `Block`,
`Turn`, `Tool`, `Failure`, `Usage`, `Ambient`. Building the hard pattern first was not waste: the
nouns were forged against the hardest case, so they hold for the easy ones. A toolbox assembled the
other way round would have eight incompatible result types.

Note: `VictoolsInputSchemaGenerator` was moved from `nessy-api` to `nessy-engine` on 2026-09-22,
correctly, because it is not public API surface. À la carte says there is a third answer neither
option considered — its own home.

**The pitch is the context, not the call.** The loop is sixty lines. What people hand-roll badly is
retrieval sections that silently collide, history windowing, summarisation, schemas that drift from
the type, untyped tool args, exceptions instead of sealed outcomes, no bounds, no observability.
Nobody sets out to build a framework for those; they write the loop in minutes and acquire the other
eight one incident at a time.

`ContextConfig` *is* the assembly of what the model is shown. The framework that owns context
assembly is the framework that owns the inbound channel.

### 9.1 History is a course

A chatbot is the canonical inline case and is multi-turn by definition. The seam is small — read the
tail, append the turn — with three homes:

| impl | needs | for |
|---|---|---|
| in-memory | nothing | tests, single process, demos |
| caller-supplied | their table | the common case; they already have a messages table |
| JDBC | a `DataSource` | an opt-in course, not an ingredient |

`ContextConfig` already separates `summaries`, `maxTail` and `ambient`, so the entrance call (RAG
in, one exchange, structured out) uses `ambient` alone and needs no history at all.

**Vocabulary caution:** `Conversation` was permanently rejected. Whatever scopes inline history must
not become that concept under another spelling. Note it does something narrower than `AgentId`: it
names **where the history lives**, not an actor that might coalesce your observation into someone
else's turn — which is why it does not reintroduce what killed `ask(AgentId, O)`.

## 10. Why `ask(AgentId, O)` is unimplementable

Recorded so it is not re-proposed. `observe` is fire-and-forget because observations are
**coalesced**: N observations may fold into one turn, or into none. "The outcome of my observation"
is therefore not a well-posed question, and no correlation token fixes it — the ambiguity is
semantic.

It is only ill-posed because the agent outlives the exchange. One agent per exchange has nothing to
coalesce. The consumer's per-request agent is not a workaround; it is the only shape in which the
question can be asked — and the inline executor makes the agent unnecessary.

## 11. Near-term slice

Useful under either door, and already approved in conversation:

1. **Turn bounds** (`TerminationPolicy` in the old plans) on `HarnessConfig`, beside `inference(…)`
   and `effects(…)`. A predicate over `Turn`. Note the existing config surface has four near-misses
   and no loop bound: `maxTokens` (one answer), `timeout` (one inference call), `maxTail` (context
   window), `maxInFlight` (agent-type concurrency, and a runaway agent *consumes* it and starves its
   peers). A careful reader would conclude they were covered.
2. **`Failure` moves `nessy-spi` → `nessy-api`.** `nessy-spi` depends on `nessy-api`, not the
   reverse, so `AgentEvent.TurnFailed` cannot carry `Failure` today. It describes an outcome
   applications branch on, so it belongs up. Verify the `@JsonTypeInfo(Id.NAME)` wire format is
   undisturbed by the package move rather than assuming it.
3. **Turn outcomes carry their `Failure`**, plus a distinct arm or well-known reason for
   context-length overflow and `finish_reason=length` truncation, mapped in the adapters. Two
   independent reports in one day: a consumer mislabelled a max-tokens truncation as a provider
   outage, and the same blindness cost an hour of diagnosis in guvnor.
4. **An exhaustion outcome.** A turn stopped at its budget is not answered, failed or refused —
   nothing went wrong, we stopped it. Same arm serves `Extraction`. One decision, both doors.

Explicitly **not** in this slice: the hard cancel. `terminate` not stopping work in flight is a
stated invariant — *"the calls already have rows and are owed outcomes"* — so cancelling means
deciding how an owed call discharges. Bounds make it far less urgent.

## 12. Needs naming, needs sign-off

| thing | note |
|---|---|
| ~~`decide`/`evolve`~~ | **settled**: `AgentState.execute(AgentCommand) → Decision` and `AgentState.apply(AgentEvent) → AgentState`. `Decision` already exists and already carries events + effects; it sheds `next`, `opening` and `<O>`. Two warnings: do **not** name the return type `Effect` — that is Akka's word for this exact position and collides head-on with `AgentEffect`; and `apply` in classic OO aggregates usually *mutates* and is also called to raise events, so the javadoc must say this one returns a new state and runs only during reconstitution |
| "turn executor" | the assistant's phrase, superseded by §3; harness + the two functions is the shape |
| the inline door | `Extractor` sibling? widened `Extractor`? `extract` says *read this document for fields*; this is *do this bounded work* |
| the inline history scope | must dodge `Conversation` |
| the exhaustion outcome arm | |
| the not-cleared `ToolOutcome` arm | distinct from `Denied` |
| `Tool<I, O>` | breaking change to the main door |
| the tool-result renderer | symmetric to `ObservationRenderer` |
| the payload store seam | |

## 13. Open questions

1. **Does inline emit `AgentEvent`, or a narrower type?** Several arms fit; `Terminated`,
   `ApprovalSought` and `TurnEnded(TurnId)` do not. Reusing the vocabulary is on-thesis; it means an
   event type with arms that cannot occur in one of its homes.
2. **Does inline record?** Proposed: it emits events and recording is a listener the app supplies —
   no store, no schema, and the app picks the sink. Giving inline a store "for audit" is how the
   cliff grows back.
3. **`ToolResult` generic, or `Success` holding `Object`?**
4. **Where does the inline executor live** — beside `DefaultExtractor` in `nessy-engine`, or a
   module that can be depended on without it? This decides whether the cliff is removed or moved.
5. **Should `Tool`'s default shape change?** Evidence collected 2026-09-24: **no production `Tool`
   implementation in the repo defers.** Every deferral is an `Approver`, engine plumbing, or a test.
   Deferral lives on `Approver`, not on `Tool`. A non-deferring tool type would widen into `Tool` for
   free, so the narrow type is the *more* shareable one. Deliberately **not** decided as a side
   effect of building inline.

## 14. What does not change

- `Harness` keeps its semantics. No lie is added to the durable door.
- The agent document stays mutable disposition.
- Approvals, deferral and `ReplyTokens` stay where they are.
- The rejection of event-sourcing *machinery* stands.
