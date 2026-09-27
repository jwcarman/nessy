# Turn policy: a bound on the loop, decided inside the fold

**Status: APPROVED, NOT BUILT.** The shape was settled in conversation with James on 2026-09-27
and this record writes it down. Nothing in it is on `main`. It adds three public types --
`TurnStats`, `TurnPolicy`, `TurnDecision` -- one field on `AgentEffect.Infer`, and an instant on
`AgentEvent.TurnStarted` and the commands that need one (§5b); anything else it turned out to need
is listed in §10 as a question rather than quietly added.

Date: 2026-09-27. Sits on the pure core of
`2026-09-24-inline-inference-and-the-turn-executor-design.md` and the two doors of
`2026-09-25-one-core-two-doors-design.md`; it changes neither door's contract. Every type named
below was checked against `src/main/java` at the time of writing.

---

## 1. The problem

There is no turn-level bound of any kind. Verified by search: no `maxIterations`, no `maxSteps`,
no `turnTimeout`, nothing of that family anywhere in the tree.

What exists is per-**effect** terms. `EffectTerms` (`nessy-engine`, `engine.effect`) gives each
effect a `timeout()`, a `retryPolicy()`, and the two fallback outcomes (`undispatchable()`,
`failed(cause)`). So one inference is bounded, one tool call is bounded, one approval is bounded.
A turn that goes infer -> tool -> infer -> tool is bounded by nothing: every effect inside it is
within its own budget and the turn as a whole runs until the model stops asking for work. That is
unbounded in wall-clock time and unbounded in spend, and the caller who asked has no lever.

The codebase already knows it wants this. `ToolChoice`'s javadoc names the case in so many words
-- *"A turn that has gone round the loop enough times needs to be told to answer rather than call
again"* -- and `DirectHarness.ask` promises that *"a turn running out of budget"* is an outcome to
branch on, not a fault. Neither has anything behind it yet. This is what goes behind them.

## 2. The shape in one paragraph

The fold keeps a running tally of the turn -- when it opened, how many times the model has been
asked, how many calls it has asked for, what it has cost. At the one point where the fold would
otherwise ask the model again, it hands that tally to a policy and obeys the answer: carry on, ask
the model to answer from what it already has, or fail the turn with a reason. The decision is made
inside the lock, in the same place on both doors, and comes out as events and effects like every
other decision the fold makes.

## 3. `TurnStats` -- a running tally in the agent state

### 3a. What it holds

Only recorded facts:

| field | what it is |
|---|---|
| `startedAt` | the instant the turn opened |
| `modelCalls` | how many inferences of this turn have come back |
| `toolCalls` | how many calls the model has asked for in this turn |
| `usage` | what this turn's inferences have cost, summed |

It is accumulated by the fold and is part of the state a replay rebuilds: `AgentState.Inferring`
and `AgentState.AwaitingActions` each carry one, and it moves with every event they accept. A turn
that begins has an empty tally; `Idle` and `Terminal` carry none, because there is no turn.

### 3b. Elapsed is derived, never stored

This is the load-bearing decision. The fold is replayed -- `AgentState`'s own javadoc: *"apply
runs over the whole turn on every command, so non-determinism means the state you rebuild is not
the state you had"* -- so a stored "elapsed" would be a number that was true once. A turn rebuilt
tomorrow would evaluate its budget against tomorrow's clock and the fold would stop being
deterministic. `startedAt` is a fact; elapsed is `Duration.between(startedAt, now)`, computed at
decision time from a `now` that arrives on the command (§5b). `TurnStats` has no `elapsed` field
and no clock.

### 3c. What the fold already sees

`Usage` (`nessy-inference-spi`) is already carried by four `AgentEvent` arms -- `InferenceAnswered`,
`InferenceRefused`, `InferenceFailed` and `ActionsRequested` -- so the fold is handed every number
it needs on the events it already applies. The counting rules:

- `modelCalls` goes up by one on each of those four events. Every one of them *is* an inference
  that came back; `ActionsRequested`'s javadoc already makes the point that a turn calling three
  tools pays for four inferences.
- `toolCalls` goes up by `actions().size()` on `ActionsRequested`. Calls the model asked for, not
  calls that ran: a denied call was still a round of the loop, and at the moment the policy is
  consulted (§5a) every call asked for has been settled, so the two readings agree there anyway.
- `usage` accumulates the four events' `usage`. How nullable counts sum is §10 (4).

**What is not counted, and why.** An attempt that threw and was retried by the queued door's
dispatcher never reaches the fold -- `EffectDispatcher.settle` consults the row's `RetryPolicy`
and reschedules the row, and only the attempt that finally produced an outcome (or the give-up)
becomes a command. Its tokens, if a vendor billed them, are not in `usage`. The stats are a tally
of the turn's *story*, which is the only thing a replayable fold can tally; what the dispatcher
spent between rows is the dispatcher's to observe. The direct door does not retry an inference at
all (`DefaultDirectHarnessConfig`: *"Stored ... but not honoured"*), so there the two numbers are
one.

`Usage.unreported()` -- an event written before usage was recorded, or a provider that did not
count -- adds nothing, which is the honest reading: null is "nobody counted", not zero.

## 4. `TurnPolicy` and `TurnDecision` -- the two halves

```java
interface TurnPolicy {
  TurnDecision decide(TurnStats stats, Instant now);
}

sealed interface TurnDecision {
  record Continue()              implements TurnDecision {}
  record AnswerNow()             implements TurnDecision {}
  record FailTurn(String reason) implements TurnDecision {}
}
```

- **`Continue`** -- carry on; the next inference is issued as it would have been.
- **`AnswerNow`** -- the next inference offers **no tools**, so the model must answer from what it
  already has. The caller still gets a real `Outcome.Answered`. This is the arm that makes the
  feature valuable rather than merely safe: a turn that has done nine tool calls' worth of work and
  is cut off with a failure has wasted all nine, where one more inference with the tools taken
  away turns them into an answer.
- **`FailTurn(reason)`** -- the turn ends now, with that reason.

`now` is passed rather than read, for the same reason `RetryPolicy.decide` takes its
`RandomGenerator` as an argument: a policy holding a clock could not be a pure function of its
arguments, and *"every branch ... is pinned by an assertion rather than a tolerance"* only holds
when it is one. Where `now` comes from is §5b.

**This deliberately mirrors the house pattern.** `RetryPolicy.decide(attemptsMade, random)`
returns `RetryDecision`, a sealed type with arms `RetryAfter(Duration backoff)` and `GiveUp()`.
`BacklogPolicy.coalesce(backlog, incoming)` sits beside them. A reader who has met one has met all
three: a policy is asked about recorded facts and answers with a decision, never a number, so
*"no caller has to know what a budget is"* (`RetryDecision`). A policy counting calls and a policy
watching the clock answer the same question, and the fold cannot tell them apart -- which is the
point.

Built-in policies are not enumerated here. Whatever ships must be expressible as a function of
`TurnStats` and `now` and nothing else; a policy that wants to read the story, the toolset or a
feature flag is asking for something the fold cannot give it (§5).

## 5. Where the decision is made: inside the fold

### 5a. The point

Exactly where the fold would otherwise issue the next `Infer` effect. Today that is
`AgentState.AwaitingActions.nextInference(int)`: when a discharge leaves nothing outstanding, the
fold emits `new AgentEffect.Infer(turn)`. Three discharge paths reach it -- a tool succeeded
(`ran`), a tool failed (`ran`), or an approver said no (`approved`, the `Denied` arm) -- and the
policy is consulted on whichever of them is the last of its batch.

Three things follow for free, and they are why the decision lives here rather than in a door:

1. **It is inside the lock.** The fold runs under `Locks.withLock` on both doors; the decision is
   made against the same state the events are appended to, with nobody else able to move it.
2. **It is the same place on both doors.** `DefaultDirectHarness.executeStep` and
   `DefaultQueuedHarness.deliverOutcome` both end in `state.execute(command)`. Neither door
   learns anything about turn policy; neither can drift from the other.
3. **The decision comes out as events and effects**, through `Decision.of(events, effects)`, so
   everything downstream -- narration, the outcome read, the outbox -- handles it with the
   machinery it already has.

The first inference of a turn is not a decision point. `Idle.execute(StartTurn)` opens the turn
and issues `Infer` with an empty tally; a policy that wanted to refuse a turn before it began
would be admission control, which is a different feature and not this one.

### 5b. Determinism: the command carries the instant

The rule in `AgentState`'s javadoc stands unchanged: *"Nothing here does I/O, reads a clock, or is
random."* The policy runs when the command arrives and its **result** is recorded as events
(§5c); replay reapplies recorded events and never re-decides. The fold therefore never reads a
clock -- it reads an instant off the command, the same way it reads a `TurnId` off one.

**Commands do not currently carry an instant.** Verified against `AgentCommand`: `StartTurn`
carries a `PayloadRef`; `CompleteInference`, `CompleteApproval` and `CompleteToolCall` carry a
`TurnId` plus their outcome; `Terminate` carries nothing. The precedent is the `TurnId` itself --
stamped by the harness onto every completion command so the fold can check *"the turn is its own
and ignore it otherwise"* -- and the instant follows the same pattern:

- `StartTurn` gains `Instant at`, stamped by the harness from its own `Clock` (both doors hold
  one: `DefaultDirectHarness.clock`, `DefaultQueuedHarness.clock`). `Idle.execute(StartTurn)`
  writes it into `TurnStarted` as `startedAt`, which is where the tally's first fact comes from.
- `CompleteToolCall` and `CompleteApproval` gain `Instant at`, stamped at the same moment the
  `TurnId` is -- `EffectOutcomes.command(turn, outcome)` is the one place both doors build a
  completion, and it takes the instant beside the turn. That `at` is the `now` the policy is
  handed.
- `CompleteInference` gains it too, for uniformity, though no decision is made on it today.
- `Terminate` carries none: it ends an agent rather than answering anything.

One clock, on both sides of the subtraction. `startedAt` and `now` are both the harness's
`Clock`, so elapsed never mixes the engine's time with the database's. That matters: the
one place the engine already compares instants across that boundary -- `DefaultDirectHarness`
reading `AgentEvents.writtenAt` (the database's `DEFAULT now()`) against `clock.instant()` for
deadline recovery -- is the kind of arithmetic the turn-identity work found a clock-skew trap in.

**`TurnStarted` gains a field, and one javadoc has to change.** `AgentEvents.writtenAt` today says
*"This is the one thing an AgentEvent does not carry: putting a timestamp on the record itself
would make replay depend on wall-clock time."* That reasoning is about the fold reading a clock,
and it still holds; a recorded instant is a fact like a `Seq`, and replaying it depends on nothing
but the row. The javadoc is rewritten to say what is actually true after this lands: the events
carry the instant the *engine* stamped, the column carries the instant the *store* wrote, and the
two answer different questions (a policy's elapsed; a deadline's recovery). They are not two
copies of one fact, because they are read from two clocks that nobody promised agree.

**A turn opened before the field existed** decodes with no `startedAt`, and the fold does not
consult the policy for that turn at all -- the decision is `Continue`, exactly as it was under the
build that opened it. Handing a policy a tally with an invented start would be worse than handing
it none. This lasts one turn per agent across one deploy and then never happens again.

### 5c. What comes out

| decision | events | effects |
|---|---|---|
| `Continue` | the discharge event, as today | `Infer(turn)`, as today |
| `AnswerNow` | the discharge event, as today | `Infer(turn, answerOnly = true)` |
| `FailTurn(reason)` | the discharge event, then the event that closes the turn with `reason` (§6a) | none |

`AnswerNow` needs no event of its own. The discharge event is the one event behind the effect --
`DefaultDirectHarness.timedEffectsOf` relies on *"exactly one event behind any effects this engine
emits"*, and that stays true -- and the effect is a durable row written in the same transaction,
carrying the flag. On replay the agent comes back `Inferring`, which is correct whether or not the
inference in flight was told to answer: the row knows, and the fold does not need to.

**`AnswerNow` is not sticky.** If the model, offered nothing, nonetheless comes back asking for
work, the fold treats it as it treats any request for actions, and the policy is consulted again
at the next discharge with a larger tally. The stats only ever grow within a turn, so any policy
with a ceiling on any of them reaches `FailTurn` eventually; a policy that answers `AnswerNow`
forever to a model that asks forever has written itself a loop, and that is the policy's, not the
engine's.

## 6. What each decision costs

### 6a. `FailTurn` -- no new outcome vocabulary

Both doors already have a word for a turn that ended without an answer and already carry a reason
with it:

- the direct door: `Outcome.Failed(String reason)` -- *"The turn ended without an answer"*
  (`Outcome.java`);
- the queued door: `Narration.TurnFailed(String reason)` -- *"The turn ended without an answer,
  and might have gone otherwise ... for one door this is the only place it is said"*
  (`Narration.java`).

Both are produced from the turn's closing event: `DefaultDirectHarness.asOutcome` reads the
terminal event of `turn` off the stream, and both doors' `narrate` switches announce `TurnFailed`
then `TurnEnded`. `FailTurn(reason)` lands on those two arms and adds nothing beside them.

**Which event records it is an open question (§10 (5)).** Today the only event that closes a turn
with a reason is `InferenceFailed(seq, turn, Failure, usage)`, and writing one for a turn the policy
ended would record an inference that never happened -- `Failure`'s arms are statements about
whether *a request* would fail again, and there was no request. The stream is a record of facts,
and `Decision.Ignore`'s javadoc is the house rule: recording something that did not happen *"is
worse than no record"*. A new arm is the honest answer, and adding one is *"a public API change,
not an internal one"* (`AgentEvent` javadoc) -- which is exactly why it is asked rather than
assumed here.

### 6b. `AnswerNow` -- the only part that touches the wire

`AgentEffect.Infer` currently carries only a `TurnId` (verified: `record Infer(TurnId turn)`).
Withholding tools means the effect has to say so, and an effect is a durable row.

**One field, and its absent reading is "tools offered".** The row is decoded by
`JdbcEffects.effectOf` through a `Codec<AgentEffect>` built from the pinned mapper, so a row
written by the build before this one decodes with the field missing. The house already has the
pattern for that: `InferenceAnswered`'s compact constructor turns a null `usage` into
`Usage.unreported()`, *"an entry written before this event recorded one"*. `Infer` does the same
-- the field is boxed on the wire and normalised in the constructor, so the default is written in
the code where a reader can see it rather than left to a deserializer's setting for primitives. A
row that says nothing is a row that wants tools, because that is what every row said before.

The field is named so that the default is the quiet one: `answerOnly`, true when tools are
withheld. A test decodes a pre-change row (`{"type":"infer","turn":7}`) and asserts it asks with
tools; that is the whole of the compatibility contract, and it is the same contract `Infer`'s
`requireTurn` already documents from the other direction.

**What "no tools" means on the wire, and why it is not `ToolChoice.None`.** `Toolset.none()`
already exists -- *"Nothing on offer, which is how a model was asked before tools existed at
all"* -- and `Toolset.any()` exists so that an adapter sends no `tools` array at all rather than an
empty one, which several OpenAI-compatible servers reject. `ToolChoice.None` was considered and is
ruled out by measurements already in the tree: its own javadoc records that *"measured against
Anthropic on 2026-09-20, a ban with tools still in the request ends the turn with no content at
all"*, and that Bedrock's Converse cannot express it. The same javadoc says what does work:
*"An application that does not want a tool called should not offer the tool: that works on every
vendor, sends no schemas, and cannot be dropped in translation."* So `AnswerNow` is an empty offer.

The flag travels `Infer` -> `InferenceHandler.handle` -> `InferenceInvocation` ->
`DefaultInferenceService.infer`, which today puts its one `toolset` on every request and, for an
`answerOnly` invocation, puts `Toolset.none()` instead. `DefaultInferenceService`'s comment that
the offer is *"the same ... on every call of this agent type"* is amended to say: except the one
call that has been told to answer.

Two costs, stated rather than discovered:

- **The cache prefix.** `ToolChoice.None`'s javadoc notes the tools *"are the cached prefix on
  vendors that cache, so taking them out for a turn throws the cache away."* An `AnswerNow`
  inference pays full input price on such a vendor. Once per turn, on the last call, and only when
  a policy asked for it.
- **A story with calls in it, sent with no tools defined.** The tail this inference is shown
  contains the tool calls and results that got the turn here, and the model is now told it has no
  tools. `DefaultInferenceService` already warns that this *"reads to it as having imagined them"*;
  whether every vendor *accepts* such a request is not established anywhere in the tree and must
  be measured per adapter before this ships (§10 (6)).

The effect's terms are unchanged: `EffectTermsSource.termsFor(Infer)` is uniform for the agent
type, so an `answerOnly` inference has the same timeout, retry policy and fallback outcomes as any
other.

## 7. Per-door nuance: what elapsed means

Elapsed is `now - startedAt`, both from the harness's clock, on both doors. What that duration
*contains* differs, and it is worth writing down so nobody tunes a budget against the wrong
picture.

**On the queued door** it includes everything between the `TurnStarted` row and the discharge
that is being folded: time the effect sat in the outbox waiting to be claimed, time it waited for a
`maxInFlight` permit behind other agents' work, time a person took to answer an approval, and time
the process was down. `EffectTerms.timeout()` already takes the same view of a single effect --
*"Queueing included: time waiting to be picked up is time the agent spent waiting"* -- and this
extends it to the turn. That is arguably the right number for "has this been going on too long":
a caller waiting on a queued agent is waiting for all of that. But it means a turn can blow a
30-second budget without the model ever having been slow, because the machine it was on was
rebooted at the wrong moment. A wall-clock policy on this door is a statement about the *turn*,
not about the *model*, and should be sized like one.

**On the direct door** the process is alive for the whole turn by construction -- the caller is
blocked in `ask` -- so downtime is out of the picture. What remains in the duration is the model,
the tools, any approver, and waiting for a permit on the harness-wide `inFlight` semaphore, which
`DefaultDirectHarness`'s javadoc notes *"can make an effect queue for a permit behind work
belonging to a different agent's turn entirely"*. Smaller than the queued door's number, and
still not purely the model's time.

Counts and usage mean the same thing on both doors. Only elapsed has a door-shaped meaning.

## 8. Naming

Argued, and the argument is part of the record.

**`TurnStats`** is kept because the object has two audiences: the policy, and anyone who just
wants the numbers (§10 (3)). Rejected:

- `TurnProgress` -- over-claims. Nothing here can know whether the ninth tool call is closer to
  an answer than the first; the tally measures spend, not distance.
- `TurnMetrics` -- collides with Micrometer's vocabulary, which this engine already speaks
  (`engine.observability`), and a reader would look for a registry.
- `TurnBudget` -- names the limit, not the spend. The budget is the policy's; this is what has
  been spent against it.

**`TurnDecision` arms.** `Continue` and `AnswerNow` name what the engine does next.
`FailTurn(reason)` was chosen over two alternatives:

- `GiveUp(reason)` -- the obvious echo of `RetryDecision.GiveUp()`, and rejected for the same
  reason it was tempting. It would have shared a name with an arm that carries nothing, and every
  reader would have had to translate "giving up" into what the caller actually receives.
  `FailTurn` matches what it lands on: the direct caller gets `Outcome.Failed`, the queued watcher
  gets `Narration.TurnFailed`, and `FailTurn` / `TurnFailed` read as one vocabulary. It also makes
  all three arms imperatives the engine obeys -- continue, answer now, fail the turn -- rather than
  two imperatives and one intransitive verb.
- `Fail(reason)` -- the enclosing type already says "turn", so the shorter word is defensible in
  isolation. It was rejected because "fail" on its own collides with effect failure
  (`EffectTerms.failed`, `RetryDecision`), tool failure (`ToolFailed`, `ToolOutcome.Failed`) and
  inference failure (`InferenceFailed`), all of which are everywhere in this codebase, and the arm
  has to survive being read in a `switch` far from its declaration.

**The `Termination*` stem is deliberately avoided**, even though AutoGen uses it for exactly this
concept. `TerminationOutcome` in this codebase already means ending the *agent* --
`DirectHarness.terminate` returns one -- and a turn and an agent are two different lifetimes. Two
lifetimes must not share a root, or the day somebody reads `TerminationPolicy` beside
`TerminationOutcome` they will assume one configures the other.

**`Advisor` is deliberately avoided.** In Spring AI, `CallAdvisor` / `StreamAdvisor` is an
interceptor chain around a chat call. Nessy ships a Spring Boot starter, so a Spring developer
reading `TurnAdvisor` would expect a hook that sees and rewrites the request. This is not that:
it sees a tally and answers with one of three words.

### 8a. Industry precedent

The general term is *stopping criteria*, or *early stopping*. Two frameworks are worth naming:

- **AutoGen** has composable `TerminationCondition` primitives -- `MaxMessageTermination`,
  `TokenUsageTermination`, `TimeoutTermination` -- combined with `|` and `&`. The composability is
  attractive and the three built-ins map directly onto the three facts in `TurnStats`
  (`modelCalls`/`toolCalls`, `usage`, `startedAt`). The stem is not borrowed, for the reason above.
- **LangChain**'s `AgentExecutor` has `max_iterations` plus `early_stopping_method`, whose values
  are `"force"` -- stop and return a canned "stopped due to iteration limit" -- and `"generate"`
  -- make one final model call with no further tool use and return whatever it says. That is
  exactly `FailTurn` and `AnswerNow`, already validated by another framework's users. The
  difference here is that the *policy* picks between them per turn rather than the configuration
  picking once.

## 9. Explicitly out of scope

- **A "nudge" response** -- injecting guidance ("you have two calls left; wrap up") while leaving
  the tools available. Cut as YAGNI: it needs a prompt-mutation seam the engine does not have.
  `InferenceContextAssembler` assembles from the story, ambient and summaries, and nothing lets
  the fold put a sentence in front of the model for one call. Building that seam for this would be
  building it backwards.
- **Withholding only some tools.** Cut: it would couple the policy to a particular agent's
  toolset, and a policy that knows tool names is not a function of `TurnStats` any more. All or
  nothing, and "nothing" is the arm that has precedent.
- **Caller-initiated cancellation of a turn in flight.** `DirectHarness.terminate` returns
  `TerminationOutcome.Busy` while a turn is running -- *"A turn is in flight, and nothing was
  written"* -- and that rule is unchanged by this spec. `QueuedHarness.terminate` is `void` and
  always accepts, writing the ending down (`Agents.seal`) and honouring it when the agent next
  falls idle; also unchanged. A turn policy ends turns from the inside, on facts the fold
  recorded; it is not a lever a caller pulls from outside, and giving them one is a different
  design with its own consequences for effects already written.

## 10. Open questions

Listed, not answered.

1. **Where `TurnPolicy` is configured.** Both doors fold the same way and the decision is made in
   the shared fold, which argues for the shared `HarnessConfig` -- one setting, applied to either
   door, the way `tool` and `ambient` are. Against it: `HarnessConfig`'s javadoc defines itself as
   *"what it takes to EQUIP an agent"* -- tools, ambient, summaries -- and says outright that it is
   *"a capability, not a universal"*. A bound on the turn is not equipment. The alternative is one
   method on each of `DirectHarnessConfig` and `QueuedHarnessConfig`, the way `inference(...)`
   and `listener(...)` are declared twice today.

2. **The default policy.** Unbounded (`Continue` always) leaves the gap this spec exists to close
   open for everyone who does not read about it; any ceiling ends turns that ran fine yesterday.
   `QueuedHarnessConfig`'s own principle -- *"Everything else has a default"*, and a wrong default
   is one *"working perfectly and doing the wrong job, with nothing in the logs to say so"* -- cuts
   both ways here.

3. **Whether `TurnStats` is exposed beyond the policy** -- on narration, so a watcher can show
   "3 calls, 41k tokens, 12s" while a turn runs, or to tools through their context. James's stated
   motivation included "anything else folks might want to use". Narration today carries *"no
   timestamp"* by design and is announced, not stored; a tally on it would be a new kind of
   announcement.

4. **How nullable `Usage` counts sum, and into what.** `Usage.totalTokens()` sets one precedent
   -- null + null is null, null + x is x, *"which is the most that can honestly be said"* -- and
   per-field summing on that rule is the obvious reading. Two things are not obvious. First, a
   sum that silently absorbs an uncounted call under-reports, and a policy bounding spend cannot
   tell "41k tokens" from "41k tokens plus two calls nobody counted"; whether the tally should
   also carry how many calls went uncounted is a real question. Second, `Usage` insists that a
   counted value *"must name the model it was counted on"*, and a turn's inferences may be billed
   as different dated builds of one alias; a cumulative `Usage` has one `model` field and no honest
   value to put in it. The tally may need to hold the five counts rather than a `Usage`.

5. **The event that records `FailTurn`** (§6a). Reusing `InferenceFailed` with a `Failure` and
   `Usage.unreported()` works with zero downstream change and records an inference that never
   happened; a new `AgentEvent` arm is honest and is a public backend SPI change that
   `JdbcAgentEvents`, both doors' `narrate` switches and `DefaultDirectHarness.asOutcome` all have
   to learn.

6. **Whether every vendor accepts a story containing tool calls when no tools are offered**
   (§6b). Not measured anywhere in the tree. If a vendor rejects the request, `AnswerNow` on that
   vendor is an `InferenceFailed` and the caller gets `Outcome.Failed` where an answer was
   promised -- and the fallback, `ToolChoice.None`, is already measured not to produce an answer on
   Anthropic. This has to be measured per adapter before the arm can be relied on, and the answer
   may be that one adapter needs to do something the others do not.
