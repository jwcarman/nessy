# Turn policy: a bound on the loop, decided inside the fold

**Status: APPROVED, NOT BUILT.** The shape was settled in conversation with James on 2026-09-27
and this record writes it down. Nothing in it is on `main`. It adds three public types --
`TurnStats`, `TurnPolicy`, `TurnDecision` -- one arm on `ToolChoice` (`Answer`, §6b), one field on
`AgentEffect.Infer`, an instant on `AgentEvent.TurnStarted` and the commands that need one (§5b),
one arm on `AgentEvent` (`InferenceAttempted`, §3c) with the `FailedAttempt` record that feeds it,
and one column on `nessy_agent_effect` (§3c); anything else it turned out to need is listed in §10
as a question rather than quietly added.

Revised the same day, after three rulings: the answer-now request is intent the adapters honour,
not an empty toolset (§6b); inference retry was found unreachable and fixed in `d36de45c9`, which
changes what §3c can promise; and `FailTurn` is confirmed as the name of the third arm (§8).

Revised a second time, the same day, after two more: the tokens a retried call burned on its
earlier attempts are no longer invisible to the fold -- each failed attempt becomes an event of
its own, accumulated durably on the effect row until the call settles (§3c), which makes this the
first command to yield several events (§3d); and the tally keeps productive and wasted spend as
two counters rather than one sum, beside a count of the attempts that failed (§3a).

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
asked, how many calls it has asked for, what it has cost, and how much of that cost bought
nothing. At the one point where the fold would otherwise ask the model again, it hands that tally
to a policy and obeys the answer: carry on, ask the model to answer from what it already has, or
fail the turn with a reason. The decision is made inside the lock, in the same place on both
doors, and comes out as events and effects like every other decision the fold makes.

## 3. `TurnStats` -- a running tally in the agent state

### 3a. What it holds

Only recorded facts:

| field | what it is |
|---|---|
| `startedAt` | the instant the turn opened |
| `modelCalls` | how many times this turn called the model, every attempt included |
| `toolCalls` | how many calls the model has asked for in this turn |
| `failedAttempts` | how many attempts at the model produced nothing |
| `usage` | what this turn spent, summed -- everything, including the calls that bought nothing |
| `failedUsage` | the part of `usage` that bought nothing, summed |

It is accumulated by the fold and is part of the state a replay rebuilds: `AgentState.Inferring`
and `AgentState.AwaitingActions` each carry one, and it moves with every event they accept. A turn
that begins has an empty tally; `Idle` and `Terminal` carry none, because there is no turn.

**Total spend and wasted spend are two counters, and one contains the other.** They answer
different questions, and a policy wants both. Total spend is a budget signal. The share of it that
bought nothing is a *thrashing* signal, and thrashing is the case actually worth failing a turn
over: a turn spending steadily is working, a turn spending on failures is stuck. With only the
total, the two are indistinguishable, and the only policy anyone could write would bound total
spend -- which ends the working turn and the stuck one alike.

**Each counter is named for the question it answers, not for a share of a partition.** `usage` is
everything the turn spent and `modelCalls` is every call it made, because those are what a reader
means by the words; `failedUsage` and `failedAttempts` are the subsets that bought nothing.
Subtract to get the productive figures. An earlier draft made the four numbers partition by
narrowing `usage` and `modelCalls` to the productive calls -- tidier arithmetic, and wrong where it
counts, since the totals are what anyone reconciling against a vendor's bill or a rate limit
reaches for first. `failedUsage` composes with `failedAttempts` rather than
duplicating it: the count says how often the turn stumbled, the usage says what each stumble cost,
and a cheap failing call and an expensive one are different problems that a policy reading only
one of the two could not tell apart. `failedAttempts` is there so a policy can see stumbling
without reading usage at all, which matters on a provider that reports no counts (§3c).

**The derived figures are methods on `TurnStats`, not arithmetic at each call site.** The
counters are deliberately not a partition, so the useful numbers come from subtracting -- and a
subtraction of nullable counts is exactly where the same question gets answered two different ways
by two different readers. `productiveCalls()` (`modelCalls - failedAttempts`) and
`productiveUsage()` (`usage - failedUsage`) belong here, beside `elapsed(Instant now)` from §3b, so
that whatever §10 (4) settles about nulls is settled once. A policy asking whether a turn is
thrashing, a dashboard showing what a turn bought, and a test asserting on either must not be able
to disagree.

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
`InferenceRefused`, `InferenceFailed` and `ActionsRequested` -- and this spec adds a fifth,
`InferenceAttempted` (below), so the fold is handed every number it needs on the events it
applies. The counting rules:

- `modelCalls` goes up by one on every inference event: `InferenceAnswered`, `InferenceRefused`,
  `ActionsRequested`, `InferenceFailed` and `InferenceAttempted`. It answers "how many times did
  this turn call the model", and a call that failed was still a call -- someone was asked, a
  request crossed the wire, a rate limit was consumed. A draft of this spec had it count only the
  calls that came back with something, so that the counts would partition. **That is the wrong
  trade.** `modelCalls` is the number a reader reaches for when reasoning about load or rate
  limits, and one that said 2 while the engine made 5 requests would be wrong in the way that
  matters most. `ActionsRequested`'s own javadoc already makes the related point: a turn calling
  three tools pays for four inferences.

  **These counters are not a partition and are not meant to be.** `failedAttempts` is a subset of
  `modelCalls`, and `modelCalls - failedAttempts` is how many calls got the turn somewhere. Each
  number answers its own question honestly, which is worth more than arithmetic that adds up.
- `toolCalls` goes up by `actions().size()` on `ActionsRequested`. Calls the model asked for, not
  calls that ran: a denied call was still a round of the loop, and at the moment the policy is
  consulted (§5a) every call asked for has been settled, so the two readings agree there anyway.
- `failedAttempts` goes up by one on `InferenceAttempted` and on `InferenceFailed`: the attempts
  that produced nothing. Since `InferenceFailed` closes the turn, at any point the policy is
  consulted the count is the `InferenceAttempted` events alone; the closing arm is counted so the
  tally exposed to a reader after the turn (§10 (3)) is complete.
- `usage` accumulates `usage` from **every** inference event, the failures included. It answers
  "what did this turn cost", and the honest answer includes calls that were billed and produced
  nothing -- a vendor's invoice certainly includes them. A reader comparing this against a bill
  must not come up short, and `usage` is the one number they will reach for first.
- `failedUsage` accumulates `usage` from `InferenceAttempted` and `InferenceFailed` -- the same
  set `failedAttempts` counts, so the two always describe the same calls. It is a **part of**
  `usage`, not a sibling of it: `usage - failedUsage` is what the spending bought.
- How nullable counts sum, for either counter, is §10 (4).

**Retried attempts, and what the fold sees of them.** Until `d36de45c9` no inference was ever
retried on either door, whatever a policy said: retrying was reachable only from the queued
dispatcher's `catch`, and no adapter throws -- each one catches its vendor's exception and returns
`InferenceResult.Fault`, which `InferenceHandler` turns into a *returned*
`EffectOutcome.InferenceFailed`. So a failure the provider classified `Failure.Transient` ended the
turn, and the classification decided nothing. Since that fix, `EffectDispatcher.worthAnotherGo`
sends a returned `InferenceFailed` whose `Failure` is `Transient` through the same `settle` a
throw always took; a returned `Permanent`, `Rejected` or `Unknown` stays terminal. (A throw still
reaches `settle` as it always did, classified `Unknown` by
`EffectTermsSource.InferenceTerms.failed`; it is the *returned* `Unknown` that is terminal.)
`settle` then does one of two things. On `RetryDecision.RetryAfter` it reschedules the row and
delivers nothing -- `DispatcherFailureTest` pins that the turn *"has not been told anything
yet"*. On `RetryDecision.GiveUp` it delivers the handler's own outcome for the last attempt,
carrying the provider's real `Failure` and that attempt's `Usage`, rather than the blob stored
beside the row.

**That is the starting position, and it is a blind spot.** Verified again in source: the
`RetryAfter` arm of `settle` calls `effects.reschedule(attempt.effectId(), attempt.attemptsMade(),
next)` -- through `Outbox.reschedule`, which passes straight to `Effects.reschedule` -- and never
`callback.deliverOutcome`. So the fold is told about one attempt per model call: the one that
produced an outcome, or the last one when the attempts ran out. Tokens a vendor billed for the
earlier attempts reach nothing: not the story, not the tally, not the caller. An earlier draft of
this record accepted that on the grounds that the story records outcomes, not attempts. That was
wrong for exactly the case the tally exists to catch: a turn that is thrashing is spending
precisely where nobody is looking, and a budget that cannot see it is bounding the wrong thing.

**The design closes it, by making each failed attempt a fact of its own.** Five pieces:

- **A new `AgentEvent` arm, `InferenceAttempted(Seq seq, TurnId turn, Failure failure, Usage
  usage)`.** One event per failed attempt, not a list inside one event: an attempt is a fact, and a
  fact hidden inside another event's collection is one no projection over the story will find.
  Told apart from `InferenceFailed` by finality -- that one ends the turn, this one is the turn
  carrying on -- so `Inferring.accept` stays `Inferring` on it, with the seq moved and the tally
  grown. It is in the story for whoever is counting what the turn spent and for nobody else:
  neither door's `narrate` announces it (a call being tried again is the engine keeping its own
  promise, not anything the turn did), `Transcript` does not show it to the model (from where the
  model sits, the call it receives is the first one), and `DefaultDirectHarness.asOutcome` reads
  past it because it closes nothing. The `Failure` it carries is what the attempt was classified
  as: `Failure.Transient` when the adapter returned one, because that is the only returned arm
  `worthAnotherGo` admits; `Failure.Unknown` when the attempt threw, because
  `EffectTermsSource.InferenceTerms.failed` classifies every throw so, with `Usage.unreported()`
  beside it. Both go through the same `settle` and the same policy, so both can be retried and
  both are recorded.
- **A new record, `FailedAttempt(Failure failure, Usage usage)`, in `nessy-backend-spi`.** What one
  attempt learned, carried from the dispatcher to the fold. It is *not* what the event holds; the
  event flattens the two fields beside its `Seq` and `TurnId`. It lives in the backend SPI because
  the effect store is what accumulates it and the engine is what reads it back, and the type has to
  be visible to both.
- **The effect row accumulates them durably.** `Effects.reschedule` gains the failed attempt as a
  parameter, and a column on `nessy_agent_effect` holds the accumulated list, appended to in the
  same fenced `UPDATE` (`status = RUNNING AND attempts_made = ?`) that puts the row back. On the
  row rather than in the dispatcher's memory because a retry spans crashes: the row is what
  survives, and a process that dies between attempt two and attempt three must not come back
  having forgotten attempt one. The column is `failed_attempts BYTEA`, nullable, encoded through
  the same pinned mapper as the row's two existing blobs; a null reads as an empty list, which is
  what every row written before the column existed truthfully has. `Attempt` -- the claimed row as
  handed to the performer -- carries the list read off it, so the dispatcher has it at delivery
  time without a second query.

  **How an existing database gets the column.** It does not get it from `nessy-schema.sql`. That
  file is `CREATE TABLE IF NOT EXISTS` throughout, `Schemas`' own javadoc says it is *"a bootstrap,
  not a migration"* -- *"change a column and this silently does nothing, and the mismatch surfaces
  at query time rather than at startup"* -- and there is no `ALTER TABLE` anywhere in the tree. So
  a database that already has the table keeps the old shape, and the new build's claim and
  reschedule statements would name a column that is not there and fail on the first effect. The
  column arrives by an operator running `ALTER TABLE nessy_agent_effect ADD COLUMN failed_attempts
  BYTEA` before the new build starts; a fresh database gets it from the file like any other column.
  Whether the file should learn to say that itself is §10 (7).
- **`AgentEffectCallback.deliverOutcome` carries the accumulated list**, rather than the four
  inference arms of `EffectOutcome` each gaining one. The four arms of both grammars --
  `EffectOutcome.Inference*` and `AgentEvent.Inference*` -- stay exactly as they are, and that was
  the whole reason this shape was chosen over a list on each: what settled the call is one thing,
  what it cost to get there is another, and the second is the delivery's to carry. Every delivery
  off a row carries what the row accumulated -- the ordinary `Ready` arm, `giveUp` on either of the
  two paths that reach it, and `expired`; `undispatchable` too, since the row is readable even
  when its effect is not. From the callback the list rides the command: `EffectOutcomes.command`
  takes it beside the turn and `CompleteInference` carries it into the fold, which is a field on
  an engine-internal command and not a new concept. The direct door performs effects on its own
  threads and never retries an inference (below), so it passes an empty list and nothing about it
  changes.
- **The fold emits N+1 events from one command.** `Inferring.completed`, handed a
  `CompleteInference` with N accumulated attempts, emits one `InferenceAttempted` per attempt in
  the order they happened and then the closing event -- whichever of the four it would have
  emitted before. On `GiveUp` after three tries that is two `InferenceAttempted` and one
  `InferenceFailed`, the last attempt's `Failure` and `Usage` on the closing event where
  `d36de45c9` put them. This is the first command in the fold to yield more than one event, and
  §3d says why that is safe.

**What is recorded for a tool retry: nothing.** A tool attempt that throws goes through the same
`settle` and can be rescheduled by its own policy, but its discharge is `ToolFailed(callId,
message)` -- no `Failure`, no `Usage` -- so there is nothing a `FailedAttempt` could hold. The
dispatcher records an attempt only when the discharge is an `InferenceFailed`; a rescheduled tool
row accumulates nothing, and the tally is about inferences anyway. How `reschedule` spells "nothing
to add" is a mechanical internal.

Two facts bound how often any of this fires. **Retrying is off by default and stays off**: every
retry default in the tree is `RetryPolicy.Never` -- `DefaultQueuedHarnessConfig`'s
`DEFAULT_INFERENCE_RETRY_POLICY` and `DEFAULT_TOOL_RETRY_POLICY`, `DefaultDirectHarnessConfig`'s
`DEFAULT_RETRY_POLICY` -- so `d36de45c9` made the knob work and did not turn it on, and a model
call has a second attempt only where an application asked for one. And **it is queued-door only**:
`EffectDispatcher` is used by `DefaultQueuedHarness` alone; the direct door performs effects on its
own threads, and `DefaultDirectHarnessConfig` documents its inference retry policy as *"Stored ...
but not honoured"*. On the direct door a model call is exactly one attempt, no `InferenceAttempted`
is ever written, and `failedAttempts` can only ever reach one -- on the `InferenceFailed` that ends
the turn. The fold is shared, so the multi-event path is tested once and holds on both doors.

`Usage.unreported()` -- an event written before usage was recorded, a provider that did not count,
or an attempt that threw before reaching one -- adds nothing to either counter, which is the honest
reading: null is "nobody counted", not zero. It still counts one in `failedAttempts`, which is why
that field exists beside the usage: a provider that reports no counts is exactly where a policy
watching only tokens would see a thrashing turn as a free one.

### 3d. Several events from one command -- a first

Every decision `AgentState` makes today yields exactly one event. Verified: four sites do `Seq at
= seq.next()` once -- `Idle.execute(StartTurn)`, `Inferring.completed`, `AwaitingActions.approved`
and `AwaitingActions.ran` -- and the fifth, `Idle.execute(Terminate)`, calls `seq.next()` inline
for its one `Terminated`; every one of them passes a single-element list to `Decision.of`. The
N+1 emission in §3c is the first command to yield several, so the two facts that make it safe are
stated here rather than assumed.

**The append already takes a list, and the list commits as one.** `AgentEvents.append(type, agent,
events, expectedLast)` is typed on `List<AgentEvent>`. `JdbcAgentEvents` writes one row per
event, but inside the transaction the fold's lock opened -- `JdbcRowLocks.withLock` runs its body
under `transactions.execute`, and both doors call `append` from inside it -- so the N+1 rows land
together or not at all, the same guarantee the effect rows written beside them already rely on.
`InMemoryAgentEvents.append` is `synchronized` over the whole list. No backend has to learn
anything; the list was always the contract.

**The fence does not depend on how many events are appended.** On both doors `expectedLast` is
`state.seq()` -- the seq the state was read at -- verified at every `append` call in
`DefaultQueuedHarness.apply` and `DefaultDirectHarness`. A competing writer that decided from the
same state mints the same first seq and violates the primary key on its first row, whatever either
of them appended after it. So the conflict detection is exactly what it was for one event. (An
earlier line of reasoning had the fence become attempt-count-dependent; it does not, and this
paragraph is here so nobody repeats it.)

**Turn identity is undisturbed.** `Seq.opensTurn()` mints the `TurnId` from the seq of the
`TurnStarted` that opened the turn, and only that seq. Extra events mid-turn shift the seqs of
everything after them and touch nothing about which turn they are in; `InferenceAttempted`
carries the `TurnId` like every other turn-scoped event and the fold's own-turn check on
`CompleteInference` is unchanged.

What *is* new is the idiom: the fold must allocate several consecutive seqs in one decision --
`seq.next()`, then the next after that, N+1 times -- where every site today takes one and is done.
That is a new habit in `AgentState`, not a new mechanism, and it stays inside `Inferring.completed`.

One promise to check rather than assume: `DefaultDirectHarness.timedEffectsOf` relies on *"exactly
one event behind any effects this engine emits"*. It holds, because it is the direct door's helper
over the direct door's decisions, and the direct door never carries attempts (§3c): on that door
`completed` still yields one event. The queued door, where a `RequestedActions` closing a retried
call yields N+1 events beside its `Approve` effects, has no such helper and makes no such promise.

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
- **`AnswerNow`** -- the next inference is sent with `ToolChoice.Answer` (§6b), so the model is
  asked to answer from what it already has rather than call again. The caller still gets a real
  `Outcome.Answered`. This is the arm that makes the feature valuable rather than merely safe: a
  turn that has done nine tool calls' worth of work and is cut off with a failure has wasted all
  nine, where one more inference told to answer turns them into an answer.
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


### 4a. The default is two thresholds, and it is a guardrail rather than a tuning

`TurnPolicy.calls(int answerAt, int failAt)` is a provided implementation, and the default is
`calls(20, 25)`: `AnswerNow` at twenty model calls, `FailTurn` at twenty-five.

**Why a default at all.** For retries, `Never` is the safe default because doing nothing costs
nothing. Here the analogy inverts -- doing nothing means a turn that will not converge keeps
spending until somebody notices -- so "no bound" looks conservative and is not. This is also the
only cheap moment to set one: after 0.1.0 a new bound is a breaking change for every agent already
running.

**Why twenty.** It is the ecosystem's number rather than ours. LangChain's `AgentExecutor` has
defaulted `max_iterations` to 15 for years, which is evidence of where loops stop converging even
though no derivation is published alongside it; twenty gives headroom over that without abandoning
it. A number chosen for being safely enormous -- a hundred, say -- would only ever catch a
catastrophe, and the case that actually costs people money is the turn stuck at twenty.

**Why the first response does not destroy work.** At twenty you cannot tell a stuck loop from a
long honest turn -- research and multi-file work genuinely run that long -- and that is exactly when
a policy must not throw away what the turn has done. `AnswerNow` returns a real answer, slightly
early, to a turn that was fine, and stops one that was not. An agent that never reaches twenty
cannot tell the policy exists, which is the property a default needs.

**Why there is a second threshold.** `ToolChoice.Answer` is emulated per adapter and unmeasured
(§10 (5)). If a vendor ignores it and the model keeps asking for tools, `AnswerNow` alone loops
forever; `FailTurn` at twenty-five ends it. This is also what makes the default shippable before
that measurement lands: on an adapter where `Answer` does not work the behaviour degrades to
"fails at twenty-five" rather than breaking, and improves as each adapter is measured.

**Model calls, not tool calls, and not time or tokens.** Model calls are what cost money. A
wall-clock default would misfire on the queued door, where elapsed includes queueing and process
downtime (§7), so a turn that was merely waiting would be failed for it. A token or currency
default would be arbitrary across models and applications. Counts mean the same thing everywhere,
so counts are what a default may assume; time and spend stay opt-in through the same function.

**`calls` rejects `failAt <= answerAt`.** Two ints of the same type in one factory invite
`calls(25, 20)`, which compiles and reads as a policy while behaving as neither threshold.

**Both thresholds read as "at or past", never as equality.** `modelCalls` does not advance once per
consultation -- a tool completing moves the turn on without calling the model -- so a policy asking
whether the count *is* twenty can be consulted at nineteen, then at twenty-one, and never fire at
all. Comparing with `>=` removes that whole class of silent miss.

**One provided implementation, and no combinators.** `calls` exists because it is the default and a
default has to be nameable. Anything else -- a second unit, two thresholds in different units, two
policies at once -- is the function, written directly by whoever wants it. Helpers can follow if
anyone finds themselves writing the same lambda twice; inventing them first would be building a
vocabulary for uses nobody has had yet.

Anything wanting thresholds in different units -- calls for one, spend for the other -- writes the
function directly, which is what the function form is for.

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
  completion, and it takes the instant beside the turn (and, after §3c, the accumulated attempts
  beside both). That `at` is the `now` the policy is handed.
- `CompleteInference` gains it too, for uniformity, though no decision is made on it today. It is
  the one completion that also carries a list of `FailedAttempt` (§3c), empty on the direct door.
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
emits"*, and that stays true here: a discharge is a tool or approval completion, which carries no
attempts, so the N+1 case of §3d never reaches this decision -- and the effect is a durable row
written in the same transaction, carrying the flag. On replay the agent comes back `Inferring`, which is correct whether or not the
inference in flight was told to answer: the row knows, and the fold does not need to.

**`AnswerNow` is not sticky.** If the model, told to answer, nonetheless comes back asking for
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

**It is recorded by a new arm, `AgentEvent.TurnFailed(Seq seq, TurnId turn, String reason)`**, and
that arm carries **no usage**. Ruled 2026-09-27.

Today the only event that closes a turn with a reason is
`InferenceFailed(seq, turn, Failure, usage)`, and reusing it is not merely inelegant -- it has a
`Usage` slot, and the only value available to fill it is `Usage.unreported()`, which says *a call
happened and nobody counted it*. No call happened. **An arm with no usage field cannot tell that
lie**, and what the turn did spend is already on the events that spent it. `Failure`'s arms are
statements about whether *a request* would fail again, and there was no request, so that field has
nothing to say either -- which is why the arm carries a plain reason instead.

The name completes one vocabulary end to end:
`TurnDecision.FailTurn(reason)` -> `AgentEvent.TurnFailed(seq, turn, reason)` ->
`Narration.TurnFailed(reason)` -> `Outcome.Failed(reason)`. The event grammar has lacked a
`TurnFailed` only because until now a turn could fail in one way, by an inference failing; this
policy is the second way, which is what the arm is for.

Adding an arm is *"a public API change, not an internal one"* (`AgentEvent` javadoc), and what it
costs is exactly what `InferenceAttempted` (§3c) cost: a line in the subtype list, an arm in both
doors' `narrate` switches, one in `Transcript`, and one in `DefaultDirectHarness.asOutcome`.
`JdbcAgentEvents` needs nothing.

### 6b. `AnswerNow` -- the only part that touches the wire

`AgentEffect.Infer` currently carries only a `TurnId` (verified: `record Infer(TurnId turn)`).
Telling the model to answer means the effect has to say so, and an effect is a durable row: the
intent has to survive from the fold's decision to whenever that row is performed, which on the
queued door may be another process on another day.

**One field, and its absent reading is "ask as usual".** The row is decoded by
`JdbcEffects.effectOf` through a `Codec<AgentEffect>` built from the pinned mapper, so a row
written by the build before this one decodes with the field missing. The house already has the
pattern for that: `InferenceAnswered`'s compact constructor turns a null `usage` into
`Usage.unreported()`, *"an entry written before this event recorded one"*. `Infer` does the same
-- the field is boxed on the wire and normalised in the constructor, so the default is written in
the code where a reader can see it rather than left to a deserializer's setting for primitives. A
row that says nothing is a row that asks as usual, because that is what every row said before.

The field is named so that the default is the quiet one: `answerOnly`, true when the model is to
answer rather than call. A test decodes a pre-change row (`{"type":"infer","turn":7}`) and asserts
it asks as usual; that is the whole of the compatibility contract, and it is the same contract
`Infer`'s `requireTurn` already documents from the other direction.

**What the request says: `ToolChoice.Answer`, a new arm.** The request carries the intent, and
each provider adapter decides how to honour it on its vendor. `ToolChoice` rides on
`Toolset.choice` (verified: `record Toolset(List<ToolOffer> offers, ToolChoice choice)`), and its
javadoc already names this exact case -- *"A turn that has gone round the loop enough times needs
to be told to answer rather than call again"* -- with nothing behind it. `Answer` is what goes
behind it: the offers stay what they were, and the choice says answer.

An earlier draft of this record ruled otherwise -- an empty offer, `Toolset.none()`, sent by the
engine -- and the ruling that replaced it is better for one reason: **the cached prefix is why
this belongs in the adapter.** `ToolChoice.None`'s javadoc records that the tools *"are the cached
prefix on vendors that cache, so taking them out for a turn throws the cache away."* If the engine
forces an answer by sending an empty toolset, it pays that cost on every vendor, including the
ones that need not. If the request only says "answer now", an adapter whose vendor can express
that keeps the tools in place, and one whose vendor cannot drops them. Each adapter pays only the
cost its own vendor imposes -- and the engine cannot make that trade, because it does not know
which vendor it is talking to.

**This amends `ToolChoice`'s stated contract, and the amendment is deliberate.** That type
promises *"translation rather than emulation"*: every existing arm -- `Auto`, `None`, `Any`,
`Named` -- is something every vendor literally spells, and the javadoc lists the four spellings.
`Answer` is the first arm adapters *emulate*. There is no field on any wire that says "answer
now"; each adapter composes it from what its vendor has, and two adapters may compose it
differently. The javadoc says so in as many words, or someone will later "fix" an emulating
adapter back into a literal translation and break it.

**Each of the four adapters must honour `Answer`.** What each one does is that adapter's business,
subject to actually producing prose, and each has to be measured (§10 (6)). Two constraints are
already in the tree. Bedrock's Converse cannot express `None` at all (`BedrockRequests` refuses it
with *"offer no tools instead"*), so that adapter will likely drop the offers. And Anthropic is
measured: a `None` with the tools still in the request *"ends the turn with no content at all"*,
so that adapter cannot honour `Answer` by translating it to `ToolChoiceNone` either, whatever it
does instead. OpenAI's `none` and Gemini's `NONE` are candidates that would keep the prefix; that
they produce prose is not measured, and is the obligation.

`Toolset.requireCoherent` gains an arm: `Answer` is satisfiable whatever is on offer, including
nothing, the same as `Auto` and `None`. That is a mechanical internal, not a new concept.

**The two names do not stutter.** `TurnDecision.AnswerNow` is the decision the fold makes;
`ToolChoice.Answer` is what the request says. One is a policy's answer, the other a vendor-facing
intent, and calling both `AnswerNow` would suggest the fold reaches into the request, which it
does not (§8).

The flag travels `Infer` -> `InferenceHandler.handle` -> `InferenceInvocation` ->
`DefaultInferenceService.infer`, which today puts its one `toolset` on every request and, for an
`answerOnly` invocation, puts a `Toolset` with the same offers and `Answer` as its choice.
`DefaultInferenceService`'s comment that the offer is *"the same ... on every call of this agent
type"* stays true -- it is the choice, not the offer, that varies -- which is the reason
`Toolset`'s own javadoc gives for building the offers once: *"varying what is on offer
mid-conversation leaves calls in the story for tools the model can no longer see, which reads to
it as having imagined them."* Whether an adapter then chooses to drop the offers for its vendor is
that adapter's cost to weigh, and the story it is sending still has the calls in it either way.

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
`FailTurn(reason)` is confirmed, and was chosen over two alternatives:

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

**`failedAttempts` and `failedUsage`, as a pair.** The count is named for the set it counts, and
the usage for the same set, so the two read as one fact seen two ways. `attempts` was rejected
for the count: it reads as every try, and every try is `modelCalls + failedAttempts`, which a
policy that wants it can add. `retries` was rejected because it is wrong twice over -- the first
failed try is not a retry, and the count is of failures, not of repeats. `wastedUsage` was weighed
for the usage and rejected because it judges: an attempt that failed may have been the provider's
weather rather than waste, and the fact recorded is only that it produced nothing.

**`InferenceAttempted`, not `InferenceRetried`.** The event records the attempt that failed, and
it is written whether or not another follows -- on `GiveUp`, the last attempt is the
`InferenceFailed` and the earlier ones are these. "Retried" would name the decision that came
after the fact rather than the fact. It sits beside `InferenceAnswered`, `InferenceRefused` and
`InferenceFailed` as a fourth past participle about one call, and the odd one out on purpose: the
other three settle the call and this one does not.

**`FailedAttempt`, not `Attempt`.** `Attempt` is already taken -- `backend.effect.Attempt` is a
claimed effect row -- and the two are not the same thing: one is a row about to be performed, the
other is what performing it once came to. A carrier of a `Failure` and a `Usage` that could be
confused with the row it is stored on would be read wrongly in the one place both appear, which
is the dispatcher.

**`ToolChoice.Answer`, not `ToolChoice.AnswerNow`.** The decision and the request are two things
at two layers, and they are named so as not to stutter. `TurnDecision.AnswerNow` is what a policy
says and the fold obeys; `ToolChoice.Answer` is what a request tells an adapter, beside `Auto`,
`None`, `Any` and `Named`, all of which are one word about the model's freedom to call. `Answer`
reads in that row; `AnswerNow` would read as the decision having leaked into the wire vocabulary.

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
  attractive and the three built-ins map directly onto three of the facts in `TurnStats`
  (`modelCalls`/`toolCalls`, `usage`, `startedAt`). None of them sees what `failedAttempts` and
  `failedUsage` see -- AutoGen's token count is one number -- which is the case §3a argues is the
  one most worth stopping on. The stem is not borrowed, for the reason above.
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
  toolset, and a policy that knows tool names is not a function of `TurnStats` any more. The
  policy has no view of the toolset at all; it says "answer", and what that does to the offer is
  the adapter's (§6b).
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

2. ~~The default policy.~~ **Settled** (§4a): `TurnPolicy.calls(20, 25)` -- answer now at twenty
   model calls, fail the turn at twenty-five.

3. **Whether `TurnStats` is exposed beyond the policy** -- on narration, so a watcher can show
   "3 calls, 41k tokens, 12s" while a turn runs, or to tools through their context. James's stated
   motivation included "anything else folks might want to use". Narration today carries *"no
   timestamp"* by design and is announced, not stored; a tally on it would be a new kind of
   announcement.

4. **How nullable `Usage` counts sum.** *Into what* is closed by §3a: two counters, `usage` and
   `failedUsage`, each summing a different set of calls. *How* is open, and the split made it
   sharper rather than softer, because every part of the question now applies twice.
   `Usage.totalTokens()` sets one precedent -- null + null is null, null + x is x, *"which is the
   most that can honestly be said"* -- and per-field summing on that rule is the obvious reading.
   Three things are not obvious. First, a sum that silently absorbs an uncounted call
   under-reports, and a policy bounding spend cannot tell "41k tokens" from "41k tokens plus two
   calls nobody counted"; whether each counter should also carry how many of its calls went
   uncounted is a real question, and it bites harder on `failedUsage`, where a thrown attempt is
   *always* uncounted (§3c) and so a turn thrashing on throws reads as free. Second, `Usage`
   insists that a counted value *"must name the model it was counted on"*, and that invariant
   applies to both counters: a turn's inferences may be billed as different dated builds of one
   alias, and the attempts under one call may be too, so a cumulative `Usage` has one `model`
   field and no honest value to put in it. Each counter may need to hold the five counts rather
   than a `Usage`. Third, `totalTokens()` on whatever a cumulative counter turns out to be is
   ambiguous the moment there are two of them: a reader has to be able to tell whether the number
   is productive spend or everything, and a method that does not say so in its name will be read
   both ways. What the fold sums across is now settled -- across calls for `usage`, across
   attempts and the closing failure for `failedUsage` -- and the dated-build concern is weaker
   within one call than across a turn, since a retry lands on the same alias minutes apart; but
   it is no longer avoided.

5. ~~The event that records `FailTurn`.~~ **Settled** (§6a): a new
   `AgentEvent.TurnFailed(Seq, TurnId, String reason)` carrying no usage, because deciding not to
   infer costs nothing and `InferenceFailed`'s usage field could only have lied about it.

6. **What each adapter does for `ToolChoice.Answer`, measured** (§6b). This used to be one
   global unknown -- whether every vendor accepts a story containing tool calls when no tools are
   offered -- and with the intent in the request it becomes four per-adapter obligations, each of
   which still has to be measured before the arm can be relied on. For each of Anthropic, OpenAI,
   Gemini and Bedrock: which composition of the vendor's own fields honours `Answer`, whether it
   keeps the offers (and so the cached prefix) or drops them, and -- the only thing the engine
   requires -- that the model then produces prose rather than a call, an empty reply or a
   rejected request. If an adapter cannot produce prose on some path, `AnswerNow` on that vendor
   is an `InferenceFailed` and the caller gets `Outcome.Failed` where an answer was promised; that
   is a defect in the adapter, not a fact about the engine.

7. **Whether `nessy-schema.sql` learns to add a column** (§3c). Today an existing database gets
   `failed_attempts` only from an operator's `ALTER TABLE`, run before the build that needs it
   starts, and nothing in the tree says so except this record. `Schemas` calls that trade
   *"accepted before 1.0"* and names versioned scripts as the point it stops being acceptable. The
   choices are: a line of `ALTER TABLE nessy_agent_effect ADD COLUMN IF NOT EXISTS ...` in the
   file, which PostgreSQL spells so and which keeps the file idempotent (whether H2 accepts the
   same spelling is the portability question `Schemas` already says nothing enforces); a
   versioned migration tool, which is the answer `Schemas` itself points at; or leaving it
   operator-run and documented in `docs/concepts/storage.md`. The file has faced this before and
   declined -- its own note on `nessy_lock` says *"this file has never destroyed anything"* and
   leaves the dead table in place -- so whatever is chosen here is the first time it adds rather
   than leaves alone, and is the precedent.
