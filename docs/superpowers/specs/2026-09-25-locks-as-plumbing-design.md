# Locks as plumbing: one SPI, two implementations, and a direct door that locks for milliseconds

**Status: PROPOSED — awaiting sign-off.** Nothing here is built. Every fact about the working tree
was measured on branch `fold-swap` on 2026-09-25; every signature under "the design" is a proposal.
This version supersedes, in place, earlier drafts of the same date. The first unified the two
doors' exclusion behind one SPI keyed by an opaque string. James's pushback ("Why do we fucking
need JdbcLeases?"; "We don't need it to wrap the entire run turn do we? This is really annoying")
produced the finding in §2. Four rulings then shaped the rest: "I don't want the queued door to do
direct JDBC at all"; "we really have the idea of locking around an agent type/agent id/kind combo";
"The transaction manager can be hidden too"; and, for the direct door, "Can't direct use a
transactional lock to read the agent state, perform the command, save the events. Then dispatch
the effects outside of that transaction. Anyone coming in to try to muck with the same agent state
will get it in the wrong state phase and be declined." §10 lists what earlier drafts had that this
one does not, and which questions those rulings closed.

This fourth revision replaces §4 and adds §5. James rejected the third draft's recovery ("the
asymmetry of us just completely ignoring awaiting actions state situations doesn't sit right with
me. There has to be a better way here") and settled it: "let's use these timeout values as our
detection mechanism." Between drafts, checking the tree found that the timeout values in question
already exist as public API — and that the direct door enforces none of them. His mechanism for
that: "we'd need to use the timeouts for the effect handler and use a virtual thread with a
CompletableFuture on which we wait (with the right timeout) for it to return." §4 is recovery by
deadline; §5 is the provider-transport work that is a prerequisite for it and a bug fix on its
own.

The fifth revision replaces the deadline-storage design inside §4c–§4e after James ruled on where
deadlines live: nowhere in the fold. "We have that get terms stuff on the queued side" —
`EffectTerms` is already the abstraction, both doors use it, and recovery re-resolves it. Three
rejected shapes and one retracted interface are recorded in §10 with their reasons. §13 gains an
execution order, written so the plan stands without him.

Date: 2026-09-25. Continues `2026-09-25-one-core-two-doors-design.md`, whose §5 table records that
the two doors exclude differently; this record makes them exclude the same way. It overturns one
non-goal of `2026-09-25-spring-boot-autoconfiguration-design.md` §9 ("Changing `InMemoryLocks`
itself") and makes that record's "Locks default to `JdbcLeases`" ruling moot.

---

## 1. What is wrong today

Three places exclude over an agent, and they do not agree on what a lock is or where it comes
from.

**The direct door holds a lease across a model call.** `DefaultDirectHarness.under`
(`nessy-engine/src/main/java/org/jwcarman/nessy/engine/direct/DefaultDirectHarness.java:187`):

```java
return locks.tryWithLock(agent.value().toString(), turn).orElse(new Outcome.Busy<>());
```

`turn` is the whole of `runTurn`, which calls the model, so the lock is held for as long as an
inference takes — tens of seconds, and in chat-web's case up to the five minutes its approval desk
waits on a person, which is why chat-web's lease is ten minutes. `terminate` (line 252) takes the
same key. What answers depends on wiring: `DirectHarnessAutoConfiguration.java:94` falls back to
`locks.getIfAvailable(InMemoryLocks::new)` and `DefaultDirectHarnessFactory.inMemory` (line 120)
uses `InMemoryLocks` outright. `InMemoryLocks` is striped — sixty-four `ReentrantLock`s indexed by
`Math.floorMod(key.hashCode(), 64)` — so two unrelated agents can share a stripe and the second
caller is told `Busy` for an agent that was never busy. Commit `352c73c4` named this ("a lie to
somebody whose own conversation was never busy") and answered it by giving chat-web a `JdbcLeases`
bean. `InMemoryLocksTest.java:176` asserts the hazard rather than its absence.

**The queued door does its own JDBC, and its own transactions.** `DefaultQueuedHarness` holds a
`JdbcAgents` — a concrete class in `engine/store`, not an interface — as a field (line 80) and
calls `agents.lock(agentType, agentId)` at lines 159, 194 and 223. `JdbcAgents.lock` is an
`INSERT ... ON CONFLICT DO NOTHING` followed by `SELECT terminated_at IS NOT NULL ... FOR UPDATE`,
and it returns that boolean, so the lock call is also the "has this agent been told to end"
query; only `tell` uses the answer. Each call is the first statement inside a
`transactions.execute(...)` (lines 157, 192, 221) on a `TransactionTemplate` the factory mints for
itself: `new TransactionTemplate(new JdbcTransactionManager(dataSource))`
(`DefaultQueuedHarnessFactory.java:153`). None of this goes through the `Locks` SPI, and the
coupling is wider than the lock — §11 measures it.

**Two summarisers hold a lease across a model call.** `HeadSummarizer.java:216` and
`EpisodeSummarizer.java:181`, identically:

```java
locks.tryWithLock(agentId.value().toString(), () -> summarize(agentId)).orElse("lease-refused");
```

Both key on the bare agent UUID, and `JdbcLeases` binds its kind at construction, so telling a
head summary from an episode summary of the same agent means constructing two instances. chat-web
today constructs two `JdbcLeases` — kind `"agent"` for the direct door (`ChatConfiguration.java:86`)
and kind `"episode"` for the episode summariser (line 131), both ten minutes. No example wires
`HeadSummarizer`.

**And the two doors do not exclude each other.** One agent reachable through both — the previous
design record's §5 says this is "correct rather than a seam that failed to close" — can have a
direct turn and a queued turn started over it at once, because one holds a lease row and the
other a `nessy_agent` row and neither knows the other exists.

---

## 2. The finding: the lock is held across inference for a guard the fold already has

The direct door's lock exists to stop two callers running a turn over one agent at once. Two
things already do that job, and neither needs a lock held for the length of a model call.

**The append is guarded.** `AgentEventStore.append(agent, events, expectedLast)`
(`engine/core/AgentEventStore.java:53`) is "Appends, if nothing else has": `InMemoryAgentEventStore`
is `synchronized` and throws `Conflict` on a stale `expectedLast` (line 42);
`JdbcAgentEventStore.append` (line 97) throws `Conflict` when the `(agent_id, seq)` primary key is
taken, which the schema comment on `nessy_agent_event` calls "the concurrency control". Two
writers that decided from the same state mint the same seq, and the second fails. The harness
contradicts itself about this: line 182 says "expectedLast still guards the append ... this stops
two callers, that stops two writers", and line 231 says "expectedLast is vacuous here — nothing
else writes this agent". The second is true only because the lock is there.

**The fold is guarded by phase.** `AgentState` accepts `StartTurn` and `Terminate` only from
`Idle` (line 45: "Only Idle accepts a command from outside"); `Inferring.execute(StartTurn)` (line
166) and `AwaitingActions`' (line 263) return `Decision.ignore()`, and `Terminal` throws (line
371). A caller that reads the agent in a busy phase is declined by the fold itself. Today that
arm is unreachable from the direct door, because the whole-turn lock means no second caller ever
reads a busy phase — which matters in §3b.

**The ordering is what makes short steps cheap.** `runTurn`'s loop (lines 228–240) appends a
decision's events before it performs its effects:

```java
Decision decision = state.execute(pending.poll());
events.append(agent, decision.events(), state.seq());        // durable first
...
for (AgentEffect effect : decision.effects()) {
  pending.add(perform(agent, content, effect, history, shape));  // then the world
}
```

The first command is `StartTurn`, which `Idle.execute` (line 129) turns into one `TurnStarted`
and one `Infer`. So the stream already records where an agent is *before* anything slow happens:
between one append and the next, the agent's phase is on disk and the model call is in flight
with nothing to protect but the caller's own thread. That is a turn as a fold — durable state
between short steps — and it is exactly the shape a short lock fits.

---

## 3. The direct door: short locked steps, effects performed outside

`runTurn` becomes N short locked transactions with the model called between them. Each step, under
`locks.withLock(KIND, agentType, agent, ...)` (§7), is: reconstitute the agent from
`sinceLastTurnStarted`, execute one command, append, commit, release. Then `perform` the resulting
effects — the inference, the tool call, the approval — with no lock and no transaction held, and
come back for the next step with the outcome as the next command. Every step re-reads the agent
under its own lock; no state is carried across a release.

The first step, for `StartTurn`, is the phase check: an agent that reconstitutes `Terminal`
answers `Outcome.Refused("terminated")` as it does today at line 220, and one that reconstitutes
anything but `Idle` answers `Outcome.Busy` — a turn is in flight. Only then is the input put away
(`content.put(renderer.apply(input))`, today at line 226 *before* any check) and `TurnStarted`
appended. `renderer.apply` can stay outside the lock; the `put` moves inside it, so a declined
caller writes nothing and the earlier draft's orphaned-payload cost is fixed rather than accepted.
`terminate` is the same one step with `Terminate`.

The direct door thereby uses the same mechanism as the queued door — `JdbcRowLocks` through the
SPI, one short transaction per step — and the lease leaves it. That has two consequences the
earlier drafts listed as absent or unfixed:

- **Cross-door exclusion falls out for free.** Both doors take the same row lock on the same
  `(kind, agent_type, agent_id)`, and both check the same phase under it. An agent reachable
  through both doors is genuinely excluded, and the previous record's "the two doors still do not
  exclude each other" paragraph is overtaken.
- **`nessy-lease`'s only consumers are the two summarisers.** chat-web's `"agent"` lease bean
  (`ChatConfiguration.java:85`) goes away entirely.

### 3a. `withLock` only, because the phase does the work

James: "Even if someone gets through the try lock, they could still fail by finding it in an
invalid state phase ... So what is the likelihood that the try lock actually hits and rejects
someone?" The arithmetic: a hold is now one read, one append and one commit — a few milliseconds —
and a turn lasts five to thirty seconds. A lock refusal can only happen if a second caller
attempts acquisition inside that window, on the order of 3 ms in 10,000, so roughly 0.03% of
collisions. The phase check catches the rest.

So the direct door uses `withLock` and never `tryWithLock`. It waits — a wait bounded by §3b to
milliseconds — and then the phase decides. The door has no `Attempt.Ignored` arm to handle,
`Outcome.Busy` has one meaning ("a turn is in flight, read under the lock"), and there is no
opening-step-tries / later-step-waits split to explain. `tryWithLock` stays in the SPI for the
summarisers, which genuinely want to give up (§6).

**The inversion, stated plainly.** Today the lock is held for the whole turn, so it intercepts
essentially every collision and the fold's `Decision.ignore()` arm is never reached from this door
— which is exactly why the bug behind it has been invisible: a caller that *did* reach it would
have its empty append pass, its loop end, and `outcome()` (line 561) hand back the *previous*
turn's `InferenceAnswered` as though it were this one's, or throw "a turn that ended without
ending" on a first turn. Shorten the hold to milliseconds and that path goes from unreachable to
the normal case for every second caller. The phase check is not a nicety beside the lock; under
this design it does essentially all of the work, and the lock's job is only to make the read and
the append one atomic step.

### 3b. The load-bearing invariant: nothing holds the agent lock across anything slow

Every hold is a short database transaction. Inference, tool calls and approvals all happen with
the lock released. This is what makes waiting safe: the only thing that can be holding the lock is
somebody else's short step, so a wait is milliseconds and never an inference.

**What would break it**, as a standing constraint on future work: if anything slow is ever moved
inside a locked step, every waiting caller piles up behind an LLM call, and the direct door's
`withLock` becomes the very thing this record removes. The approval path is the one to watch. An
approval can park on a person for hours; under this design that parking happens outside the lock
with the agent sitting in `AwaitingActions` on disk, so every other caller reads that phase under
its own millisecond hold and gets a clean `Busy`.

**No deadlock**: the order is always agent lock, then that agent's rows, and the lock is per agent,
so two agents never cross. **No permanent block**: a row lock dies with its transaction's
connection, so a crashed holder's lock is released by Postgres at once — the property a lease
cannot give, and the reason waiting is safe here and would have been reckless against a lease.

### 3c. No outer lock

The natural instinct is to keep the whole-turn `tryWithLock` around the outside with the
transactional steps nested inside. It is not kept. An outer lock would be held across inference,
so it could not be transactional, so it would have to be a lease — bringing back the TTL and the
whole lease apparatus the direct door just shed. All the correctness is in the inner phase check.
And an outer lease makes recovery *worse*: a dead holder blocks the agent for the full TTL
(chat-web's is ten minutes), where the event stream self-heals the moment a failure event is
appended (§4).

---

## 4. Deadlines, and recovery by deadline

With the lock gone from around the model call, a turn that dies mid-flight leaves the agent in a
busy phase on disk, and every later caller reads it and is told `Busy`. James asked how that is
recovered. The whole section hangs off one sentence:

**Recovery asks whether the thing being waited on has passed its own deadline — never whether the
phase is old.**

The third draft asked the other question ("has this phase been stuck too long?"), needed a new
threshold to answer it, and could not answer it at all for `AwaitingActions`, because an approval
may legitimately park for hours; so it excluded that phase. James rejected the exclusion. Asking
about the deadline instead dissolves the objection rather than working around it: the thing that
made timing out `AwaitingActions` dangerous was a *second* mechanism racing the approval's own
expiry, two clocks deciding one question. When the deadline recovery enforces *is* the approval's
own, there is one clock. No exclusion, no special case.

### 4a. The three deadlines already exist

Every busy phase is waiting on exactly one kind of thing, and each kind already has a configured
deadline — public API, defaulted, and symmetric across the two doors:

| phase | waiting on | its own deadline | default | recovery delivers |
|---|---|---|---|---|
| `Inferring` | an inference | `InferenceConfig.timeout` (`nessy-api/.../InferenceConfig.java:45`) | 5 min on the queued door (`DefaultQueuedHarnessConfig.java:374`) | `CompleteInference(InferenceOutcome.Failed(…))` → `InferenceFailed` → `Idle`; the `Failure` is the terms' (§4c, §4d) |
| `AwaitingActions` · `AWAITING_APPROVAL` | a person | `ApproverConfig.timeout` | 10 min (`DefaultDirectHarnessConfig.java:214`, `DefaultQueuedHarnessConfig.java:77`) | `CompleteToolCall(callId, ToolOutcome.Failed)` → `ToolFailed` (§4c) |
| `AwaitingActions` · `RUNNING` | a tool | `ToolConfig.timeout` | 30 s (`DefaultDirectHarnessConfig.java:168`, `DefaultQueuedHarnessConfig.java:66`) | `CompleteToolCall(callId, ToolOutcome.Failed)` → `ToolFailed` |

`Outstanding` (`engine/agent/Outstanding.java`) carries `Phase.AWAITING_APPROVAL` or
`Phase.RUNNING` per action and the tool's name, so for any outstanding action the door already
knows which deadline applies and can look the binding up to read it. That the table is composed
entirely of settings that already exist is the strongest thing about the design: there is no new
number, no new property, and nothing to tune that an application has not already been able to
tune. The third draft's `nessy.agent.abandoned-after` and its "abandonment threshold" are
retracted (§10); a `nessy.inference.timeout` property, proposed between drafts, was retracted
before it was written, because `InferenceConfig.timeout` is that setting.

The fold needs no new vocabulary for any row. `Inferring.accept(InferenceFailed)` returns `Idle`
(`AgentState.java:153`); `AwaitingActions.accept(ToolFailed)` discharges the call (line 233) and
the fold emits the next inference (line 317). `Failure.Unknown` is the category for a deadline,
and its javadoc says so in as many words: "A read timeout after the request was accepted is the
fast version of this, and a deferral whose deadline lapsed is the slow one — both leave exactly the
same question open" (`inference/Failure.java:83–85`).

### 4b. The direct door enforces none of them today

The table is only true if the deadlines are. On the queued door they are: `EffectStore.insert`
writes `at.plus(terms.timeout())` as the row's `deadline` (`store/EffectStore.java:66–68`), the
dispatcher claims a row at its deadline "to be given up on" (`EffectDispatcher.java:298–302`), and
`expired` delivers the failure stored beside the effect at emit (line 381). A crash mid-call
"leaves a row whose deadline has passed rather than one nothing will ever pick up" (line 54). That
is deadline-based recovery, already built, for one door.

On the direct door all three are advisory or discarded — a finding in its own right, wider than
the lock work:

- **`InferenceConfig.timeout` is an inert setter.** `DefaultDirectHarnessConfig.Inference.timeout`
  (lines 256–261) accepts the value, returns `this`, and stores nothing: "Nothing to time out
  against: the call is the caller's own thread, and a caller that wants to stop waiting interrupts
  it." `retryPolicy` directly below (263–266) is inert the same way. An application that configures
  either gets silence — no effect, no error. This is the same bug class as the `written_at` column
  nothing reads: a surface that accepts and forgets. Nothing else on that config is inert; every
  other setter stores what it is given.
- **`ToolConfig.timeout` is advisory.** `callTool` passes `Instant.now().plus(binding.timeout())`
  *to the tool* as `ToolCallRequest.deadline()` (`DefaultDirectHarness.java:365`) and trusts it to
  respect that. The `try` around the call turns a thrown `RuntimeException` into
  `CompleteToolCall(Failed)` (line 375), so a tool that *fails* is handled; a tool that *hangs*
  hangs the turn. `ToolConfig`'s own javadoc describes exactly this: "Reaches the tool as
  `ToolCallRequest#deadline()`". `retryPolicy` is stored on the binding and never read by this door.
- **`ApproverConfig.timeout` is advisory in the same way.** `ToolBinding.question` puts
  `askedAt.plus(approvalTimeout)` on the `ApprovalRequest` as `deadline` (`ToolBinding.java:136`);
  `approve` blocks on `binding.approve(question)` (`DefaultDirectHarness.java:322`) with nothing
  bounding it. An approver that answers `Deferred` is denied at once ("approval was deferred, and
  nothing here can wait for it"); an approver that blocks on a person blocks the turn.
- **There is no enforcement machinery in the door at all**: no `CompletableFuture`, no executor,
  no virtual thread, no timed `get` (`grep` of `DefaultDirectHarness.java`; the only `.get(` calls
  are payload reads at 547 and 587). Every effect runs to completion on the caller's thread.

So of the three deadlines the recovery design rests on, none is real on the direct door.
Recovery cannot enforce a deadline the door never made true.

### 4c. The mechanism: the same deadline, enforced two ways

The two doors enforce the *same configured deadlines* through substrate-appropriate means. The
queued door enforces durably — the deadline is a due time on a row, swept by the watchdog, because
the work is on a row somebody else may pick up. The direct door enforces in-process — the work
happens on this thread in this process, and there is no row to come back to — with James's
mechanism: perform the effect on a virtual thread and wait for it with the deadline.

**Where the numbers come from: `EffectTerms`, on both doors.** The queued door already has the
abstraction — `effect/EffectTerms.java`, "What one kind of effect is worth, for one agent type",
with `timeout()`, `retryPolicy()`, `undispatchable()` and `failed(RuntimeException)` — and a
resolver per effect kind: `ToolCallHandler.termsFor` (line 101) reads the binding's `timeout()`
and `retryPolicy()` with the config defaults as fallback for an unbound tool;
`ApprovalHandler.termsFor` (line 94) reads `approvalTimeout()` and `approvalRetryPolicy()` the same
way; `InferenceHandler.termsFor` (line 87) is the handler itself, one set of terms per agent type;
`EffectHandlers.termsFor(AgentEffect)` (line 58) switches over the sealed effect. The direct door
asks the same question of the same code, so the phase-to-timeout mapping is not in the door — it
is in `termsFor`, which already exists and is already per tool per agent type.

```java
// shape, not signature: one helper, used for every effect the door performs
private AgentCommand within(EffectTerms terms, Supplier<AgentCommand> work,
                            Function<EffectOutcome, AgentCommand> asCommand) {
  Future<AgentCommand> f = virtualThreads.submit(work::get);
  try {
    return f.get(terms.timeout().toMillis(), MILLISECONDS);
  } catch (TimeoutException e) {
    f.cancel(true);                        // interrupts the thread; see the caveat below
    return asCommand.apply(terms.failed(new IllegalStateException("no answer within " + terms.timeout())));
  }
}
```

`perform` becomes `within(handlers.termsFor(effect), ...)` for all three arms, and an expiry is
`terms.failed(...)`: for an inference `InferenceFailed(Failure.Unknown(...))`
(`InferenceHandler.java:109–115`, "Nobody found out whether the call happened"), for a tool call
`ToolFailed(callId, "the call failed: ...")` (`ToolCallHandler.java:141–143`), for a blocking
approver `ToolFailed(callId, "the call could not be authorised: ...")`
(`ApprovalHandler.java:222–225`). `failed` rather than `undispatchable`, because an in-process
expiry is exactly what `failed` is documented for — "the work was attempted, threw, and will not be
attempted again ... nobody found out whether the work happened" — where `undispatchable` is for
work nobody performed (§4d uses that one). No outcome is invented by the door, no exception type
is minted, and no fold vocabulary is added: the `EffectOutcome` becomes the `AgentCommand` the way
the queued door already converts it (`DefaultQueuedHarness.java:308–324`). A `Deferred` approval
is answered at once as today. The Inference config's two inert setters store their values, the
direct factory builds the same three handlers' terms from them, and the queued door's five-minute
default becomes the direct door's too.

**The direct door has no clock.** It calls `Instant.now()` at lines 304 and 365, and neither
`DirectHarnessFactoryConfig` nor `DefaultDirectHarnessFactory` carries a `Clock`, where every queued
handler takes one. Deadline tests that wait real seconds are the alternative; a `Clock` on the
direct factory is the right one, and since it is a method on a public config it is §14 Q5.

**The trade, stated as a cost.** The discarding comment at `DefaultDirectHarnessConfig.java:258`
is not wrong about what it describes: today the call is the caller's own thread. Enforcing a
deadline means it no longer is — a thread handoff on every turn and every tool call, and the
caller waiting on a future rather than on the socket. That is bought in exchange for enforceable
deadlines, and therefore for recovery being possible at all. James has accepted this; it is
recorded as a decision with its price rather than as a free win.

**Caveat, not glossed.** The timed wait gives a correct *deadline*: the moment it passes, the door
delivers `Failed`, the agent is `Idle`, the caller has an answer. It does not stop the work. Two
layers of that: `CompletableFuture.cancel(true)` does not interrupt at all — the JDK javadoc says
"`mayInterruptIfRunning` — this value has no effect in this implementation because interrupts are
not used to control processing" — which is why the shape above submits to an `ExecutorService`
and cancels a `Future`, which does interrupt. And an interrupted virtual thread blocked in OkHttp
or netty does not necessarily abandon its socket read promptly; the transport's own timeout is
what releases the connection. So there are two jobs: the engine's deadline is what recovery
derives from (correctness), and the provider transport timeouts are what actually release
resources (hygiene). Both are wanted and they are not the same thing. It also means §5 is not
optional garnish: without it, an abandoned call leaks a connection for as long as the transport
allows — and for Gemini as shipped, that is forever.

### 4d. The two layers of recovery

**In-process compensation** is the common case, and §4c is most of it. Today `infer` (line 417)
hands `provider.infer(...)` to a switch without a `try`: an `InferenceResult.Fault` folds to
`Failed` (line 434), but a provider that *throws* — a connection reset, a 500 the adapter did not
translate — escapes `runTurn` with the agent left `Inferring`. `infer` is verified not to catch.
Inside `within`, a throw from the work surfaces as an `ExecutionException` from `get` and is
delivered as the same `Failed` outcome, through the same locked step. The agent returns to `Idle`,
the caller gets `Outcome.Failed` rather than an exception, and the next caller is not told `Busy`.

**Lazy recovery at the door** is for the dead process — the one case no in-process layer can reach.
The next caller is already under the lock reading the phase; let it also read *when the thing
being waited on was started*, compare that with the deadline that applies, and if the deadline has
passed, append the row's recovery outcome from §4a in the same step and carry on with its own
turn. No reaper, no background thread, no scan: recovery happens when somebody cares and costs
nothing when nothing is wrong. The step, for each thing the reconstituted state is waiting on:

```
effect  = the AgentEffect the phase implies        // Infer; Approve(requestSeq, callId, name);
                                                    // CallTool(requestSeq, callId, name)
terms   = handlers.termsFor(effect)                 // the same resolver §4c performs with
started = events.writtenAt(agent, sinceSeq)         // §4e: the seq that entered this phase
overdue = started.plus(terms.timeout()).isBefore(clock.instant())
if overdue: state.execute(asCommand(terms.undispatchable()))  -> append, apply, and go on
```

Every input already exists. `AwaitingActions` holds `requestSeq` and, per call, an
`Outstanding(action, phase)` whose `action` is the `ActionRequest.ToolCall(id, name)` the effect
needs (`AgentState.java:207, 321–326`); `Inferring` needs nothing but itself. The seq that entered
the phase is `state.seq()` for `Inferring` — it is only ever constructed from the event that
entered it (`Inferring(started.seq(), ...)` at line 118, `new Inferring(at, turn)` at line 253) and
accepts no event without leaving — and for an outstanding call it is the one fold change in this
record, §4e.

**Recovery performs nothing.** The abandoned turn has no caller to answer, so recovery never calls
a model or a tool on the new caller's clock: it discharges each overdue effect with
`terms.undispatchable()` — "what to tell the agent when the effect can never be performed at all
... or a deadline that passed before anyone picked the work up" (`EffectTerms.java:54–55`), which
is literally this case — and repeats until the state is `Idle`. That matters for `AwaitingActions`:
discharging the last outstanding call does not go to `Idle`, it goes to `Inferring` with an
`Infer` effect (`AwaitingActions.discharge`, line 250–254; `nextInference`, line 329), and that
inference is overdue by construction the moment it is emitted, because nobody is going to perform
it. Recovery discharges it with the inference's `undispatchable()` in the same locked step and the
agent is `Idle` before the new caller's own `StartTurn` is executed. All of it is fold and append,
no I/O, one step; the model sees the `ToolFailed` and `InferenceFailed` facts in the transcript on
the next turn, which is where an abandoned turn's story belongs.

**What `undispatchable()` yields, reconciled against what the fold accepts.** Both doors deliver the
same outcome for the same reason from the same place; the check is whether each lands on an arm
the fold takes.

| effect | `undispatchable()` yields | fold arm | accepted? |
|---|---|---|---|
| `Infer` | `InferenceFailed(Failure.Permanent("the inference could not be dispatched"))` (`InferenceHandler.java:118–121`) | `Inferring.completed(Failed)` → `InferenceFailed` → `Idle` (line 187) | yes |
| `Approve` | `ToolFailed(callId, "the call could not be authorised, so it was not run")` (`ApprovalHandler.java:216–219`) | `AwaitingActions.ran`: `Failed` accepted on an `AWAITING_APPROVAL` call by design — "an approval that expires discharges its call as failed rather than denied, because nobody said no" (`AgentState.java:301–307`) → `ToolFailed` → discharge | yes |
| `CallTool` | `ToolFailed(callId, "the call did not complete before its deadline; whether it ran is not known")` (`ToolCallHandler.java:135–138`) | `ran`: `Failed` on a `RUNNING` call → `ToolFailed` → discharge | yes |

So the expired-approval arm is `ToolFailed`, not `CompleteApproval(Denied)`, and it was never ours
to choose: the fold ruled it, the queued door already delivers it, and the direct door delivers the
identical blob. Two findings from the reconciliation, neither papered over:

1. **The inference blob's category and wording are wrong for a hung inference, on both doors.**
   `Permanent` is "refused on its merits; retrying spends a budget to receive the same answer"
   (`Failure.java:49–58`), and "could not be dispatched" is false for a call that was dispatched
   and never came back — which is what a queued row that was claimed, overran, and reached its
   deadline is, and what a dead direct process leaves. `Failure.Unknown`'s javadoc names this case
   exactly (§4a). The tool blob already gets it right ("whether it ran is not known", with a
   comment explaining why it must not claim more). The recommendation is to change
   `InferenceHandler.undispatchable()` to `Unknown` with wording of the tool blob's shape; the fold
   accepts any `Failure`, so nothing else moves. §14 Q7. It is not a blocker: the agent is `Idle`
   either way.
2. **The direct door's own `approve` answers a `Deferred` approval with a `Denied`** ("approval was
   deferred, and nothing here can wait for it"), where the fold's doctrine and both `AskingTerms`
   blobs say nobody said no. Once the door has terms in hand, that arm should be
   `terms.undispatchable()` too. Small, and in the same file this record already rewrites.

**The race with a slow-but-alive original** resolves without coordination, and `expectedLast` does
not save us here — it is the fold that does. When a recovered original comes back for its
completion step it takes the lock, reconstitutes, and finds the agent `Idle` (or in somebody else's
turn): `Idle.execute(CompleteInference)` is `Decision.ignore()` (line 140) by *phase*, its result
is discarded, and its caller reads `Failed` off the stream. `expectedLast` stands behind that only
as the guard against a step that carried stale state across a release; under the lock it should
never fire, and if it does it is a bug in the step, not a race. With §4c in place this race is
rarer still, because the in-process deadline and the lazy deadline are the same number: an
original alive enough to come back has already given up at the same moment recovery would.

### 4e. What the store exposes, and the one fold change

**The store read: `Instant writtenAt(AgentId agent, Seq seq)` on `AgentEventStore`.**
`nessy_agent_event.written_at` is `TIMESTAMPTZ NOT NULL DEFAULT now()`, so every clock start is
already on disk with no new column. But nothing reads it: `AgentEvent` carries no time (its
records are `(seq, turn, ...)`), `AgentEventStore` has exactly three methods — `append`,
`readFrom`, `sinceLastTurnStarted` (`core/AgentEventStore.java:52–68`) — and
`JdbcAgentEventStore`'s two queries select `payload` only (lines 53, 63). One method, one column,
no new type. **Ruled out**: a timestamp on the event records. It would make replay depend on wall
time and change the stored payload of every event — and the fold must never see a clock (§10).

*Is the signature sufficient?* Yes, checked against every row of the §4a table. Each clock start
is the write time of one event in the last turn, and each is addressable by a seq the reconstituted
state already holds or will hold: `Inferring` → `state.seq()`; `AWAITING_APPROVAL` → the seq of
`ActionsRequested`, which is `requestSeq`; `RUNNING` → the seq of that call's `ToolApproved`. Three
lookups at most per recovery, all by primary key `(agent_id, seq)`. Nothing is missing (§14 Q6). The
in-memory store has no column and stamps with the harness's `Clock` at `append`.

**The fold change: `Outstanding` gains the seq at which it entered its current phase.** Today
`Outstanding(action, phase)`; it becomes `Outstanding(action, phase, since)`, with `since` set to
`requestSeq` by `awaitingApproval` (from `opening`, line 219–224) and to the `ToolApproved`'s seq
by `running(at, callId)` (line 238–246), which already has `at` in hand. The same move as
`requestSeq`, for the same reason; it costs no migration because state is never stored, and it
adds no event arm, no command arm, no phase and no method that takes a clock. Strictly only
`RUNNING` needs it — `AWAITING_APPROVAL`'s seq is `requestSeq` already — but one field with one
meaning beats a special case.

**Residual caveat, one sentence.** Re-resolving terms at recovery means a timeout changed in
configuration applies to in-flight work on the direct door, where the queued door froze it onto
the row at insert ("what SQL touches is frozen onto the row, what only Java touches is read live",
`EffectTerms.java:31`); the direct door has no row, so reading live is the defensible behaviour
for it, and the two doors differ here for a reason the javadoc already states.

**Clocks.** `written_at` is `DEFAULT now()` — the database's clock. `overdue` should be judged
against that same clock (read `now()` alongside, or compare in SQL) rather than against the JVM's,
so skew between hosts cannot recover a turn early or late; for the in-memory store the harness's
`Clock` is both.

---

## 5. Provider transport timeouts: the hygiene half, and a bug on its own

This is not part of the lock change. It is a prerequisite for the `Inferring` deadline being
hygienic rather than merely correct (§4c), and it fixes a real bug independently: a hung provider
hangs a turn for as long as its transport allows, and for two of the four providers that is a
long time or forever. It should land first, on its own, and be green before the lock work starts.

### 5a. Measured

There is no inference timeout anywhere in `nessy-inference`: `grep -ri timeout` over its sources
finds Bedrock's `ModelTimeoutException` import and match (`BedrockInferenceProvider.java:53, 416`)
and two javadoc mentions (`Failure.java:83`, `ToolOffer.java:26`), nothing else. Each provider
config builds its SDK client with whatever that SDK defaults to. Those defaults, read from the
source jars the root pom pins (`anthropic.version` 2.62.0, `openai.version` 4.50.0,
`google-genai.version` 1.66.0, `awssdk.version` 2.54.17):

- **openai-java 4.50.0** and **anthropic-java 2.62.0** are identical (`core/Timeout.kt` in each):
  connect defaults to 1 minute, request to 10 minutes, and read and write default to `request()`.
  So a hung call costs ten minutes. Both clients are OkHttp (`OpenAIOkHttpClient`,
  `AnthropicOkHttpClient`).
- **google-genai 1.66.0**: `HttpOptions.timeout()` is `Optional<Integer>`, "Timeout for the request
  in milliseconds", unset by default. And the SDK's `ApiClient` *removes* OkHttp's own defaults on
  the client it builds — "Remove timeouts by default (OkHttp has a default of 10 seconds)", then
  `connectTimeout(0)`, `readTimeout(0)`, `writeTimeout(0)` (`ApiClient.java:283–286`) — and applies
  `timeout`, when present, as `callTimeout` (line 288) plus an `X-Server-Timeout` header (line
  650). An unconfigured Gemini client therefore has *no* transport bound at all: a hung call hangs
  forever. Connect and read are reachable only through `ClientOptions.customHttpClient(OkHttpClient)`
  (`types/ClientOptions.java:139`).
- **Bedrock**: `BedrockProviderConfig.resolveClient` (lines 90–101) builds
  `BedrockRuntimeAsyncClient.builder()` with region and credentials only — no
  `overrideConfiguration`, no `httpClientBuilder` — so AWS SDK v2's netty defaults apply:
  `DEFAULT_CONNECTION_TIMEOUT` 2 s, `DEFAULT_SOCKET_READ_TIMEOUT` 30 s
  (`http-client-spi/.../SdkHttpConfigurationOption.java:140–142`), and the SDK retries — three
  attempts in standard mode, four in legacy (`SdkDefaultRetrySetting.java:37, 51`) — which per
  James hangs on the order of two minutes and then throws `SdkClientException`. `apiCallTimeout`
  is unset. James's point, and it matters: configuring `apiCallTimeout` alone is *not* enough,
  because the socket read timeout fires first and the retries sit underneath the API-level bound.
  `NettyNioAsyncHttpClient.Builder` exposes `readTimeout`, `writeTimeout`, `connectionTimeout`
  and `connectionAcquisitionTimeout` (`NettyNioAsyncHttpClient.java:318–342`);
  `ClientOverrideConfiguration.Builder` exposes `apiCallTimeout` and `apiCallAttemptTimeout`
  (`ClientOverrideConfiguration.java:641, 667`). Only Bedrock is netty; the other three are OkHttp
  underneath.

**Why Bedrock has not bitten, and what it would look like when it does.** Our Bedrock provider
uses `converseStream` (`BedrockInferenceProvider.java:143`; `BedrockClient.java:38, 59`), so the
30-second read timeout applies *between stream events*, not to the whole call. Long generations
are fine. The exposure is time to first token, which a large prompt or a reasoning model can
exceed — and when it does, the symptom is a two-minute stall followed by an `SdkClientException`,
not a truncated answer. Nobody should "fix" this by concluding that streaming is broken.

### 5b. The design

- **A `timeout(Duration)` setter on each of** `OpenAiProviderConfig`, `AnthropicProviderConfig`,
  `GeminiProviderConfig`, `BedrockProviderConfig`. It is the mechanism, not an option. The
  mapping:
  - OpenAI / Anthropic → `Timeout.builder().request(d)` on the client builder.
  - Gemini → `HttpOptions.timeout((int) d.toMillis())`, which the SDK applies as OkHttp's
    `callTimeout`.
  - Bedrock → `overrideConfiguration(o -> o.apiCallTimeout(d))` **and**
    `httpClientBuilder(NettyNioAsyncHttpClient.builder().readTimeout(d))`, both, for the reason
    above.
- **No Spring-facing property.** There is nothing to add: the agent's willingness to wait is
  `InferenceConfig.timeout`, which is already the engine's, and the transport timeout is a property
  of whoever built the client. In this tree every provider construction in main code is one of the
  starter's auto-configurations — `OpenAiAutoConfiguration` (two beans, the second for xAI),
  `AnthropicAutoConfiguration`, `GeminiAutoConfiguration`; Bedrock has none, by its own javadoc
  ("the starter wires no bean for it") — and no example constructs a provider by hand (chat-cli is
  Spring Boot and takes the starter's bean; mcp and policy construct none). So the starter sets the
  transport timeout on the three it builds, and an application that declares its own provider bean
  sets it on that — James's standing rule that provider-specific tuning is done by declaring the
  bean. Per-vendor properties (`openai.timeout` and kin) are ruled out: only one provider is ever
  active (the open provider-selection bug, where several keys set means Anthropic always wins,
  makes that sharper, not looser), so three of four would be dead config in every application, and
  the vendor namespaces exist for vendor-named concepts like `openai.api-key`.
- **Connect and read are not exposed.** Gemini *can* set them, through a custom `OkHttpClient` —
  recorded so nobody records a false capability gap — but doing so means owning an `OkHttpClient`'s
  construction and lifecycle where the SDK owns it today, and surfacing three knobs across four
  providers is a wider surface than the problem. Where an SDK has a connect timeout, the setter
  leaves it at a fixed sensible value; it becomes configurable when somebody needs it.
- **What value the starter passes.** The transport bound should be at least the engine's
  deadline, or the transport gives up before the engine does and the failure arrives as the
  provider's exception rather than as the engine's deadline — correct either way, but the log
  says two different things. The starter knows both; it should set the transport a margin above
  `InferenceConfig.timeout`'s default. The exact margin is a detail, not a question.

### 5c. Ruled out: a config overhaul of the four providers

James ruled out reworking the four provider configs beyond the setter. The reasons, verified: all
four already give explicit settings precedence over the environment — OpenAI and Anthropic layer
explicit `apiKey`/`baseUrl`/`organization` on top inside `buildFromEnv()`, Gemini guards with
`useEnv && key == null`, Bedrock checks `region != null` first — so the environment handling is
one rule in three spellings, not four behaviours; and `fromEnv()` is the non-Spring path, while
Spring users already get relaxed binding through properties. The one real asymmetry, worth noting
and not fixing now: OpenAI and Anthropic delegate to the vendor SDK's own `fromEnv()` and so
inherit base URLs, profile files and workload identity, where Gemini and Bedrock hand-roll
`System.getenv` for a key and a region and therefore support strictly less of their vendor's
environment story.

### 5d. Retracted: the "engine cannot know the bound" limitation, and the SPI method

Because the engine enforces the deadline itself (§4c), it knows the bound whatever provider bean
it was handed and whether or not a preconfigured `client(...)` was passed. So the "known
limitation" that recovery could fire while an inference was still legitimately running under an
application's own transport settings is retracted, and with it the open question of adding an
`Optional<Duration> timeout()` to the `InferenceProvider` SPI so the engine could ask rather than
assume. Neither is needed; a provider built from a preconfigured client may genuinely not know
its own timeout, and it no longer has to.

---

## 6. The shape: two mechanisms, three sites

| call site | mechanism | what decides |
|---|---|---|
| direct door (`ask`, `terminate`) | `Locks.withLock` over `JdbcRowLocks`, one short transaction per step | the phase, read under the lock, gives `Busy` |
| queued door (`tell`, `terminate`, `deliverOutcome`) | `Locks.withLock` over `JdbcRowLocks`, unchanged in shape | `tell` always accepts, so it waits |
| the two summarisers | `Locks.tryWithLock` over `JdbcLeases` | opportunistic: giving up is right |

**The queued door** keeps what it has, reached through the SPI. `QueuedHarness`'s javadoc: "It
always accepts ... Nothing comes back, because there is nothing a caller could do with it — by the
time the turn runs, whoever spoke has gone." A refused lock in `tell` would drop an observation
and break that contract, so it waits, and waiting is cheap because its three sites are pure
database round trips with the model call already in the outbox.

**Why the lock absorbs the transaction.** There are exactly three transaction boundaries in the
engine today (`grep -rn "transactions\." nessy-engine/src/main`: `DefaultQueuedHarness.java:157,
192, 221`, nothing else), and they are the three lock sites, with the lock the first statement
inside each; the direct door's new steps have the same shape. Nothing needs a transaction without
a lock or a lock without a transaction, so `withLock` means "hold this agent while doing this,
transactionally": `JdbcRowLocks` opens the transaction, takes the row, runs the work and commits.
No `Transactions` SPI, nothing on either config for a transaction manager, and no cross-SPI rule
of the form "the row lock only works inside a transaction over the same `DataSource`". The
factory's minted `JdbcTransactionManager` dissolves rather than being fixed.

**The summarisers keep leases, and cannot borrow the door's trick.** James: "Don't we still need
to prevent multiple summarizers from running at the same time?" Yes. The direct door's pattern
works because a turn is a fold: it breaks into short steps with durable state between them, so
exclusion need only cover a database round trip. Summarisation has no fold — the model call *is*
the work, with no intermediate state to return to — so there is nothing to break into locked
steps, and long, non-transactional, give-up-on-contention is exactly right. Both stores guard their
own writes (`JdbcSummaries.REPLACE` updates only when `through_turn` advances,
`JdbcEpisodes.SUMMARIZE` only where `summary IS NULL`; each says "which the lease prevents, but a
row must hold on its own"), so what the lease buys is the duplicate *model call*, not write
safety. `LockKind` (§7) is what stops a head summary and an episode summary of the same agent
blocking each other.

---

## 7. The SPI: `LockKind`, an agent, two verbs

```java
package org.jwcarman.nessy.spi.lock;

/** The namespace a lock lives in: which activity is being excluded, so a head summary and an
 *  episode summary of the same agent are two locks and not one. */
public record LockKind(String value) { ... }   // non-null, non-blank, at most 64 characters

public interface Locks {

  /** Runs work if the agent can be taken now; Ignored if somebody else holds it. Never waits. */
  <T> Attempt<T> tryWithLock(LockKind kind, AgentType type, AgentId agent, Supplier<T> work);

  /** Runs work once the agent is held, waiting for it if it must. The default asks again until
   *  it gets it; an implementation that can wait natively overrides. */
  default <T> T withLock(LockKind kind, AgentType type, AgentId agent, Supplier<T> work) { ... }

  default Attempt<Void> tryWithLock(LockKind kind, AgentType type, AgentId agent, Runnable work)
  default void withLock(LockKind kind, AgentType type, AgentId agent, Runnable work)

  sealed interface Attempt<T> { ... }          // unchanged: Ran, Ignored, orElse
}
```

**The key is an agent, by ruling.** The first draft weighed an opaque key against
`(kind, agent type, agent id)` at length; James has now said twice that the lock is "around an
agent type/agent id/kind combo", and that settles it. No `LockKey`, no key-spelling convention, no
wrapper to curry one. His plumbing principle — storage "not bound to any agent harness or agent
type or anything" — still governs the other stores, which is §11. `nessy-spi` already depends on
`nessy-api` (the `Narrator` interface imports `AgentType` and `AgentId`).

**One interface.** A lease is a kind of lock, and James did not want two interfaces. No capability
split, no `WaitingLocks`, no implementation throwing `UnsupportedOperationException` from half its
contract. The default `withLock` polls `tryWithLock` at a short fixed interval, which lets a lease
satisfy the signature honestly: slow, unfair (a late arrival can win), a round trip per poll, and
nothing in the tree calls it on a lease. `JdbcRowLocks` overrides it with `FOR UPDATE`, which
blocks in the database and queues waiters in arrival order; `InMemoryLocks` with `lock()`.

**Both doors use the same kind.** Cross-door exclusion (§3) depends on it: one `LockKind` for "a
step over this agent's state", whichever door takes it. The summarisers use their own.

**What "released" means.** `JdbcRowLocks` releases when the transaction it opened commits, after
the work returns and before `withLock` returns; a lease releases when the work returns. Either way
the caller sees the lock held for at least the work.

**The guards, honestly stated.** On this branch `AgentType`, `CallId` and `ToolName` check null
and blank only; the 256/ASCII guard in the memory notes lives in the `pekko-out` worktree. `LockKind`
is a column (`VARCHAR(64)`), so it carries the bound 64 and the schema and the guard must agree.
James asked that `AgentType` share the guard ("a helper method somewhere as the guard");
`nessy_agent.agent_type` is also `VARCHAR(64)`. The helper must be reachable from `nessy-api` and
`nessy-spi`, so it lives in `nessy-api`; its name, and whether it also restricts to ASCII, are
§14 Q2. `JdbcLeases`' hand-rolled `if (kind.isBlank())` goes.

**Four parameters is wide.** `org.jwcarman.nessy.engine.observability.Identity` is already exactly
`(AgentType, AgentId)` — `DefaultQueuedHarness` builds `new Identity(agentType, agentId)` for its
traces, and seven other files construct one. Promoting it would make the verbs three parameters;
it lives in the engine, which the SPI cannot see, so promoting means moving it to `nessy-api`, and
that is a vocabulary decision. §14 Q4 flags it without assuming it.

---

## 8. The two implementations

### 8a. `JdbcRowLocks` — `FOR UPDATE`, exact, and the transaction is its own

```sql
INSERT INTO nessy_lock (kind, agent_type, agent_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING;
SELECT 1 FROM nessy_lock WHERE kind = ? AND agent_type = ? AND agent_id = ? FOR UPDATE;         -- withLock
SELECT 1 FROM nessy_lock WHERE kind = ? AND agent_type = ? AND agent_id = ? FOR UPDATE NOWAIT;  -- tryWithLock
```

`withLock` is: begin, ensure the row, `FOR UPDATE`, run the work, commit — or roll back and
rethrow if the work throws. `tryWithLock` is the same with `NOWAIT`; a refused `NOWAIT` rolls back
and returns `Ignored`. The row must always exist — `JdbcAgents`' javadoc explains why locking the
work itself fails: "two arrivals to an empty backlog with nothing to contend for" — so the insert
comes first and the row is never deleted; the table grows one row per agent per kind, as
`nessy_agent` does.

**Measured against Postgres 16 with spring-jdbc 7.0.9** (the version the root pom pins at line
210), using a separately constructed `JdbcClient` handed only the `DataSource` and knowing nothing
about the caller — exactly where an SPI implementation sits:

1. *Joined.* A write through that client inside a transaction opened elsewhere on the same
   `DataSource` rolled back with that transaction (`n=0` after rollback). A `JdbcClient` obtains
   its connection through Spring's `DataSourceUtils`, which hands back the one bound to the
   thread's transaction. This is what makes absorbing the transaction safe: everything a locked
   step touches over the same `DataSource` — the append, the outbox insert, the payload `put`,
   the backlog — is inside the transaction the lock opened, which is the same fact the queued door
   leans on today with its own template.
2. *Excluded.* While that transaction held `FOR UPDATE`, a second connection's `FOR UPDATE NOWAIT`
   on the row failed with SQLSTATE `55P03` (`lock_not_available`). That code is the discriminator
   `tryWithLock` keys on to tell "somebody else holds it" from a real error.
3. *Not excluded without a transaction.* A `FOR UPDATE` issued with no transaction open excluded
   nothing: autocommit closed it at statement end. Left to the caller, the boundary is a hazard a
   caller can forget. Absorbed into the implementation, it is avoided by construction: the row
   lock always has a transaction because it made one.

**The transaction manager it uses — an addition James has not ruled on.** Built over a
`DataSource` alone, `JdbcRowLocks` would mint its own `JdbcTransactionManager`, which is the same
thing this record faults `DefaultQueuedHarnessFactory` for. Measurement 1 says it works — it joins
an application's already-open transaction on the same `DataSource` — but it breaks for JTA or a
second `DataSource`. The recommendation is a second constructor,
`JdbcRowLocks(DataSource, PlatformTransactionManager)`, with the one-argument form minting a
`JdbcTransactionManager` for the case where there is nothing to hand in. §14 Q9.

The class javadoc's "Why a lock rather than a lease" paragraph moves here from `JdbcAgents`.

**Its table.** `nessy_lease`'s `holder` and `expires_at` are `NOT NULL`, and a row that exists only
to be locked has neither, so `JdbcRowLocks` gets `nessy_lock (kind, agent_type, agent_id)` of its
own and each table's columns say what its rows are. §14 Q10 only because it is one more table.

### 8b. `JdbcLeases` — a row with a TTL, approximate, transaction-free

As it is, with `kind` a parameter of the verbs rather than a field, the TTL per §9, and the key
columns following the ruling: `nessy_lease` becomes `(kind, agent_type, agent_id, holder,
expires_at)` with `(kind, agent_type, agent_id)` as the primary key, replacing today's
`key VARCHAR(255)`. Spelling the pair into a string would be the stringification James ruled out.

### 8c. `InMemoryLocks` — one lock per agent, no stripes, and it moves

A map of `(kind, type, id)` to a `ReentrantLock`, pruned with `ConcurrentHashMap`'s atomic
`compute`/`computeIfPresent` when the last holder leaves; the old javadoc's objection to a map was
right about naive pruning and is answered by the per-key atomic operations. A refused
`tryWithLock` then means a real holder. `InMemoryLocksTest.java:176` ("at most one stripe is
taken") is deleted rather than fixed, with the stripe-count test at 183–187 and the
`InMemoryLocks(int)` constructor. It moves out of `engine.direct` into the lock module beside the
other two implementations (§12). Note that an in-memory `withLock` gives the direct door's steps
exclusion but no transaction; against the in-memory stores that is what "in memory" has always
meant here — `InMemoryAgentEventStore` is a list — and the phase check works the same way.

---

## 9. The time-to-live

James rejected a global default: "forcing folks to think about their TTL is smarter than having a
global default." With the kind on the call, `JdbcLeases` takes its durations by kind:

```java
public JdbcLeases(JdbcClient jdbc, Map<LockKind, Duration> ttls)
public JdbcLeases(DataSource dataSource, Map<LockKind, Duration> ttls)
```

Every entry is validated at construction (positive), and a call with a kind not in the map throws
`IllegalArgumentException` naming the kind. No default, no silent fallback, and the set of kinds an
application leases is readable in one place. chat-web's two constructions become one with one
entry. `JdbcRowLocks` has no TTL and takes none.

---

## 10. Decided along the way, and deleted from earlier drafts

Closed by ruling, recorded here so they are not re-asked: the SPI key is the agent, not a string;
one interface with both verbs; the transaction is the row lock's own; the direct door locks per
step and never across inference; the direct door uses `withLock` only. Closed by this record's
own reasoning: no outer lease around the turn (§3c); the orphaned payload is fixed by moving the
`put` under the lock (§3), so the earlier "accept and name it, no reaper" paragraph and the
`Outcome.Busy` javadoc loosening that went with it are withdrawn.

Deleted from earlier drafts: the opaque `String` key and the `LockKey` type that briefly replaced
it; the `AgentLocks` wrapper (nothing to curry); the capability split and `WaitingLocks`; the
terminated-check riding on the lock call (§11a); the direct door as a non-user of locks, and the
"catch `Conflict` → `Busy`" mechanism that went with it (under the lock the read and the append
are one step, so the phase, not a conflict, is what declines a caller); the "must refuse to run
outside a transaction" rule, avoided by construction (§8a); the one-table-with-nullable-columns
lock table; and the "not fixed, on purpose" cross-door paragraph. Never designed and not to be: a
`Transactions` SPI, or a `PlatformTransactionManager` on either config.

Retracted by the fourth revision (§4–§5), with the reason, so none is re-proposed:

- **Recovery by phase age**, and with it the exclusion of `AwaitingActions` from recovery. James
  rejected the asymmetry; recovery asks about the deadline of the thing waited on, and the
  approval's own deadline is what makes `AwaitingActions` recoverable without racing anything.
- **`nessy.agent.abandoned-after`**, the "abandonment threshold", and its five-minutes-and-six
  defaults. There is no abandonment setting: each thing waited on has a deadline that is already
  configured, and recovery enforces those.
- **`nessy.inference.timeout`**, proposed between the third and fourth revisions as a new
  Spring-facing property. Never written: `InferenceConfig.timeout` is already that setting, on the
  public API, defaulted to five minutes on the queued door, and the only problem with it is that
  the direct door discards it (§4b).
- **The "known limitation" that the engine cannot know the real bound** when an application
  declares its own provider bean or passes a preconfigured `client(...)`, and the open question of
  an `Optional<Duration> timeout()` on the `InferenceProvider` SPI so the engine could ask. Both
  retracted because the engine enforces the deadline itself (§4c) and so knows it regardless of
  transport; the SPI method would have been a new public concept for a problem that no longer
  exists (§5d).
- **Per-vendor timeout properties** (`openai.timeout` and kin) and **configurable connect/read
  timeouts** — ruled out in §5b, with a full config overhaul of the four providers ruled out in
  §5c.
- **`CompleteApproval(Denied)` as the expired-approval outcome.** The fold already rules that an
  expired approval is `ToolFailed`, "because nobody said no" (§4d); the queued door already
  delivers that, and the direct door's recovery does the same.

Rejected by the fifth revision — where deadlines live. Four shapes were weighed; James killed
three. The reasoning is the valuable part, so it is kept:

- **(A) One deadline per turn, on `TurnStarted`.** Rejected: it redefines `InferenceConfig.timeout`
  from per-answer to per-turn, and demotes the approval and tool timeouts from being enforced in
  their own right to being bounded by a turn budget they were never about.
- **(B) A deadline field on every event that starts a wait** (`TurnStarted`, `ToolSucceeded`,
  `ToolFailed`, `ToolDenied`, `ActionsRequested`, `ToolApproved`). Rejected for two verifiable
  reasons. First, the fold has no clock and no config — `Decision execute(AgentCommand)` is the
  whole signature — so it cannot populate a deadline; the deadlines would have to arrive on the
  *commands*, and the harness cannot know which branch the fold will take. Whether a
  `CompleteToolCall` discharges the *last* outstanding call and therefore starts an inference is
  known only to the fold, which holds the outstanding map (`AwaitingActions.discharge`:
  `next.isEmpty() ? new Inferring(at, turn) : ...`, `AgentState.java:250–254`), so commands would
  carry speculative deadlines for branches never taken. Second, it would make the direct door
  inconsistent with the queued door, which never puts deadlines in events at all: its deadline is
  on the effect row, set by the handler, outside the fold.
- **(C) A new `InferenceRequested` event** between the last discharge and `Inferring`. Rejected:
  `AwaitingActions` forbids being empty ("an agent awaiting nothing is not awaiting", line 213) and
  `discharge` goes straight to `Inferring`, so there is nowhere for a "discharged, not yet
  inferring" state to live without a new phase in the sealed fold — which touches every switch
  over it.
- **(D) `EffectTerms` on both doors, re-resolved at recovery** — accepted, §4c–§4e.
- **`AgentState.isExpired(Instant)` and `AgentCommand.Expire(Instant)`**, proposed and recommended
  between revisions. Retracted: they were the better *interface*, but the fold cannot populate the
  data behind them without losing its purity (a clock or config in `execute`) or taking the
  speculative command fields of (B). The data lives beside the fold — on a row for the queued door,
  in terms plus `written_at` for the direct one — and the fold stays a function of events.

---

## 11. Scope: the queued door's JDBC coupling is wider than the lock

James's ruling — "I don't want the queued door to do direct JDBC at all" — is about more than
`JdbcAgents.lock`.

**Measured.** `DefaultQueuedHarness` holds a `JdbcAgents` (line 80) and a `Backlogs<O>` whose one
method returns a concrete `JdbcBacklog<O>` (lines 94–95). `DefaultQueuedHarnessFactory` builds
`JdbcClient.create(dataSource)` and from it `JdbcAgentEventStore`, `JdbcPayloadStore`,
`JdbcEffectStore` (lines 149–152), `JdbcAgents` and `JdbcBacklog` (lines 233, 237).
`QueuedHarnessFactoryConfig` takes a `DataSource` (`requiredDataSource()`, line 134) where
`DirectHarnessFactoryConfig` takes the SPI. `QueuedHarnessAutoConfiguration.java:86` hands the
starter's `DataSource` straight through.

**What this record already takes off that list.** The lock, and the transaction with it. The
minted `TransactionTemplate` (`DefaultQueuedHarnessFactory.java:103, 153`;
`DefaultQueuedHarness.java:85, 107`) is gone once `withLock` owns the boundary, so the follow-on
does not have to decide how a store-shaped config obtains a transaction manager — a question it
would otherwise have faced, since `JdbcClient` and `JdbcTemplate` have no transaction API of their
own (checked with `javap` against spring-jdbc 7.0.9) and `PlatformTransactionManager` is a
separate thing.

**Blast radius of the rest.** `QueuedHarnessFactoryConfig` becomes store-shaped and symmetric with
the direct one: event store, payload store, effect store, agent store, backlog store, `Locks`.
`JdbcBacklog` and `JdbcEffectStore` have no interface today and would need one; `Backlogs<O>`
would return it. The queued auto-configuration would build the JDBC set from its `DataSource`, as
`DirectHarnessAutoConfiguration` already does for events and payloads. Every queued test that
constructs the factory from a `DataSource` changes shape.

**Recommendation: a follow-on.** This record's change — a lock SPI, the transaction it absorbs,
the direct door's steps and the queued door's three sites — is bounded and can land green on its
own. Folding four store SPIs in would triple the diff. §14 Q11.

### 11a. `JdbcAgents` splits three ways

- **"Ensure this agent's row exists"** — the `INSERT ... ON CONFLICT DO NOTHING` on `nessy_agent`.
  `nessy_agent_effect` has a foreign key to it, so the row must exist before an effect is written.
  `JdbcAgents.ensure(type, id)` keeps it; the queued door calls it first inside the locked work.
- **"Has this agent been told to end"** — a query asked *inside* the locked work rather than
  returned by the lock. `JdbcBacklog.terminated()` (line 174, over `TERMINATED` at line 94) already
  asks it; `tell` uses it and nothing else needs it. `tell` becomes: `withLock`, ensure, if
  terminated refuse, else coalesce and drive. `terminate` and `deliverOutcome` never used the
  boolean.
- **The `FOR UPDATE` and the transaction around it** — `JdbcRowLocks` (§8a). `JdbcAgents.lock`
  goes.

---

## 12. Naming: "lease" and "lock"

James earlier said yes to renaming `nessy_lease` → `nessy_lock`, with the `nessy-lease` module and
`org.jwcarman.nessy.lease` package following (eight `pom.xml` files name the module: root,
`nessy-bom`, `nessy-coverage`, `nessy-lease`, `nessy-engine`, `nessy-spring-boot/autoconfigure`,
`nessy-memory/summarizing`, `nessy-memory/episodic`). Between drafts the design briefly had the
module holding nothing but a lease; it is flagged here because his yes should be re-confirmed
against the design as it now stands.

As it now stands the module holds three implementations of `Locks` — an in-process lock, a row
lock and a lease — so the *module and package* become `nessy-lock` / `org.jwcarman.nessy.lock`,
which is the yes he gave. The recommendation is that the *class* `JdbcLeases` and the *table*
`nessy_lease` keep their names, because each is a lease and says so, and `JdbcRowLocks` sits beside
them over `nessy_lock`. `nessy-engine` depends on the module today (`pom.xml:105`) without
importing from it; after this record both factories construct a `JdbcRowLocks`, so the dependency
stays and its direction — engine on lock module, never the reverse — holds.

---

## 13. Migration and breakage

James accepts breakage ("Nobody except me is using this") but wants the extent. Every file below
was found by searching for `Locks`, `InMemoryLocks`, `JdbcLeases`, `tryWithLock`, `agents.lock`,
`JdbcAgents`, `TransactionTemplate`, `nessy_lease`, `nessy-lease` and `Conflict`, excluding
`target/` and worktrees.

**`nessy-spi`.** `spi/lock/Locks.java` — both verbs take `LockKind, AgentType, AgentId`;
`withLock` added with its polling default; the javadoc's "a harness asking to run a turn" paragraph
is rewritten for a door that waits, and the "released" contract per §7. `spi/lock/LockKind.java`
— new.

**`nessy-api`.** The guard helper (§7) — new, name open. `AgentType.java:31` gains the bound.
`DirectHarness.java:33–36` ("this gets it from a lock ... What kind of lock decides whether that
holds across machines") and `:88–92` ("Takes the same lock a turn does, so ending an agent
mid-turn does nothing") are rewritten: one turn at a time per agent, decided by the agent's phase
under a lock held for a step. `Outcome.java:45–55` (`Busy`) stays true as written — nothing is
appended, nothing spent, nothing changed. `Identity`, if §14 Q4 says yes.

**`nessy-lease` → `nessy-lock`** (§12). `JdbcLeases.java` — both constructors take
`Map<LockKind, Duration>`; `TAKE`/`RELEASE` take kind and agent from the call. `JdbcRowLocks.java`
— new. `InMemoryLocks` and `InMemoryLocksTest` arrive from the engine, destriped.
`JdbcLeasesTest.java` — the `leases(kind, ttl)` helper at 67, `String key` at 64 and every
`tryWithLock(key, ...)` (76, 94, 103, 106, 118 and on), the "enrichment" test at 174.
`nessy-schema.sql` — `nessy_lease` reshaped to `(kind, agent_type, agent_id, holder, expires_at)`;
`nessy_lock (kind, agent_type, agent_id)` added.

**`nessy-engine`.**
- `direct/DefaultDirectHarness.java` — the largest change in the record. `under` (180–188) and
  the whole-turn lock go; `runTurn` (190–243) becomes the per-step loop of §3, with the phase
  check and the `put` (226) moved under the first lock and the two contradicting comments (182,
  231) rewritten; `terminate` (246–259) becomes one step; `perform` (264–276) wraps each arm in
  the `within` helper of §4c with `handlers.termsFor(effect)`, on an executor of virtual threads
  the door owns and closes; `approve`'s `Deferred` arm (§4d finding 2) delivers
  `terms.undispatchable()`; the two `Instant.now()` calls (304, 365) read the door's `Clock`; the
  `Locks` field and constructor parameter (89, 122, 137) stay, now a `JdbcRowLocks` in the durable
  wiring. Lazy recovery (§4d) is the first locked step's preamble.
- `direct/DefaultDirectHarnessConfig.java` — `Inference.timeout` (256–261) and `retryPolicy`
  (263–266) store what they are given instead of discarding it, with the "caller's own thread"
  comment deleted; `Inference` gains the readers the factory needs, and the default timeout is
  the queued door's five minutes (`DefaultQueuedHarnessConfig.java:374`) so the two doors say the
  same thing. Whether the direct door then *honours* `retryPolicy` is §14 Q8.
- `direct/DirectHarnessFactoryConfig.java`, `direct/DefaultDirectHarnessFactory.java` — a `Clock`
  (§14 Q5), defaulting to `Clock.systemUTC()`; the factory builds the direct door's terms.
- `effect/ToolCallHandler.java`, `effect/ApprovalHandler.java`, `effect/InferenceHandler.java`,
  `effect/EffectHandlers.java` — **the resolvers move out of the handlers.** `termsFor` needs only
  `Tools` and the two defaults (`ToolCallHandler.java:101–106`, `ApprovalHandler.java:94–101`), or
  the inference timeout and policy (`InferenceHandler.java:87`), but each handler's constructor
  takes the queued door's collaborators — `ToolCalls`, `ReplyTokens`, `PayloadStore`,
  `InferenceService` (constructors at `ToolCallHandler.java:71`, `ApprovalHandler.java:67`,
  `InferenceHandler.java:68`) — so the direct door cannot construct a handler just to ask it.
  The `CallTerms` and `AskingTerms` records and the inference terms become a terms source of
  their own (a class the two factories both build from `Tools` plus defaults; the handlers take
  it and delegate `termsFor` to it). Mechanical internals in a package nothing outside the engine
  sees — no new public concept — but it must land before the direct door can ask for terms, and
  it is where `InferenceHandler.undispatchable()`'s category (§14 Q7) would change.
- `agent/Outstanding.java` — `since` (§4e); `core/AgentState.java` — `opening` (219–224) and
  `running` (238–246) populate it. `AgentStateTest` gains the two assertions.
- `core/AgentEventStore.java`, `store/JdbcAgentEventStore.java`, `direct/InMemoryAgentEventStore.java`
  — `Instant writtenAt(AgentId, Seq)` (§4e): one `SELECT written_at` by primary key; the in-memory
  store gains a `Clock` and stamps at `append`.
- `direct/DefaultDirectHarnessFactory.java` — `inMemory` (120) keeps `new InMemoryLocks()` from
  its new package; nothing else changes shape.
- `direct/DirectHarnessFactoryConfig.java` — `locks(Locks)` (62–66) stays; its javadoc names
  `InMemoryLocks` by the old package.
- `direct/InMemoryLocks.java`, `direct/InMemoryLocksTest.java` — move out.
- `harness/DefaultQueuedHarness.java` — the `JdbcAgents` field and parameter (80, 102) become a
  `Locks` plus whatever keeps `ensure`; the `TransactionTemplate` field and parameter (85, 107)
  go, and the "Transactions are explicit" javadoc (65–68) says the lock owns them; the three
  `transactions.execute` / `agents.lock` pairs (157–159, 192–194, 221–223) become
  `locks.withLock(KIND, agentType, agentId, ...)`; `tell` gains `terminated()` (§11a).
- `harness/DefaultQueuedHarnessFactory.java` — the `TransactionTemplate` field (103) and its
  construction (153) go with the imports at 69 and 71; line 233 constructs `JdbcRowLocks` over the
  `DataSource` instead of `JdbcAgents` over the client, or takes a `Locks` from the config if §11
  lands with it.
- `store/JdbcAgents.java` — `lock` becomes `ensure`; its lock javadoc moves to `JdbcRowLocks`.
- `nessy-schema.sql` — the `nessy_agent` comment block ("the lock everything else about it is taken
  under") describes a lock that no longer lives there; the words change, the columns do not.
- Tests: `DefaultDirectHarnessTest` — `a_busy_scope_is_refused` (353–380) is rewritten, since it
  proves busyness with a `Locks` stub that refuses everything and the door no longer takes a
  refusal; the honest replacement starts a real turn first. `only_one_of_many_callers_runs`
  (383–414) is the regression test for §3a and should pass unchanged: its seven latecomers arrive
  mid-turn, wait milliseconds for the lock, read `Inferring`, and get `Busy`. The `harness(...)`
  helper (124–130) and its six `new InMemoryLocks()` sites re-import. `DurableDirectHarnessTest.java:94`
  wires a `JdbcRowLocks` if it is to prove the durable path honestly. `JdbcAgentEventStoreTest.java:134`
  is unaffected. No test constructs a `TransactionTemplate` (the only other users are
  `JdbcEpisodes` and `JdbcPlanStore`, which own theirs and are untouched). `JdbcBacklogTest`'s four
  direct `nessy_agent` inserts are unaffected. New tests are listed per step in §13a.

**`nessy-inference`** (§5, its own change, landing first). `OpenAiProviderConfig`,
`AnthropicProviderConfig`, `GeminiProviderConfig`, `BedrockProviderConfig` — a `timeout(Duration)`
setter each, applied in `build()`/`resolveClient()` per the §5b mapping; the `client(...)` path is
untouched, since a supplied client is the caller's. Each module's provider test gets one case that
a client built through the config carries the timeout (readable back from the SDK's options for
OpenAI and Anthropic; from `HttpOptions` for Gemini; from the override configuration for Bedrock).

**`nessy-spring-boot/autoconfigure`, inference** (§5b). `OpenAiAutoConfiguration` (both beans),
`AnthropicAutoConfiguration`, `GeminiAutoConfiguration` — each customizer sets the transport
timeout a margin above the engine's default. No property, no `NessyProperties` change.

### 13a. Execution order

Written so that every step leaves the reactor compiling and green, and no test asserts a behaviour
that no longer exists without its replacement landing in the same commit. Each step is one
`clean verify` and one commit; `spotless:apply license:format` before every push.

1. **Provider transport timeouts** (§5). `nessy-inference` only, plus the three starter
   auto-configurations. Independent of everything below; fixes the hung-provider bug on its own.
2. **Terms source lifted out of the handlers** (§13, `effect/`). Pure refactor: the queued door's
   behaviour and tests are unchanged, `DefaultQueuedHarnessFactory` builds the source and hands it
   to the three handlers, and the `EffectOutcome`-to-`AgentCommand` conversion (`DefaultQueuedHarness.java:305–325`, private today) moves beside it so both doors use one. `InferenceHandler.undispatchable()`'s category changes here if Q7 says
   yes, with `DeadlineTest`'s assertion on the stored failure updated in the same commit.
3. **`Outstanding.since`** and **`writtenAt`**. Additive: a fold field nothing reads yet, a store
   method nothing calls yet, each with its own test (`AgentStateTest`; `JdbcAgentEventStoreTest`
   against Postgres, and the in-memory store's stamp).
4. **The direct door enforces deadlines in-process** (§4b–§4c): `Clock` on the factory config, the
   inert setters store, `within` around every `perform` arm, the `Deferred` arm delivers
   `undispatchable()`. Still under today's whole-turn `tryWithLock`. Tests: a provider that never
   answers is `Outcome.Failed` at `InferenceConfig.timeout` and the agent is `Idle` after; a tool
   that never returns is `ToolFailed` at its `ToolConfig.timeout` and the turn goes on; a blocking
   approver that never answers is `ToolFailed` at `ApproverConfig.timeout`. Each with a stepped
   `Clock`, none waiting real seconds.
5. **The lock SPI and its three implementations** (§7–§9, §12): `LockKind`, the widened verbs, the
   guard helper, `JdbcRowLocks` and its table, `JdbcLeases` reshaped, `InMemoryLocks` destriped and
   moved, the module renamed. The engine and memory modules, the starter and chat-web re-import in the same commit so the
   reactor compiles; the two summarisers change their call and nothing else. `JdbcLeasesTest` and
   `InMemoryLocksTest` are rewritten here; the stripe tests go.
6. **The queued door onto the SPI** (§6, §11a): `JdbcRowLocks` replaces `agents.lock` and the
   minted `TransactionTemplate`; `JdbcAgents.lock` becomes `ensure`; `tell` gains `terminated()`.
   The queued tests should pass unchanged — that is the assertion that the shape is the same.
7. **The direct door as per-step locked transactions with lazy recovery** (§3, §4d). `under` and
   the whole-turn lock go; the phase decides `Busy`; `put` moves under the first lock; recovery is
   that step's preamble. `a_busy_scope_is_refused` is rewritten in this commit (it asserts the
   lock-stub refusal that no longer exists); `only_one_of_many_callers_runs` must pass unchanged.
   Tests added here, one per row of the §4a table plus the two paths that matter most:
   - **the stale answer**: a turn whose inference outlives its deadline is recovered by a second
     caller, and when the first turn's `CompleteInference` arrives it is ignored by phase, the
     stream shows `InferenceFailed` then the second turn, and the first caller reads `Failed`;
   - **the expired approval**: an `AWAITING_APPROVAL` call past `ApproverConfig.timeout` is
     discharged `ToolFailed` with the `AskingTerms` blob, the `Infer` the fold then emits is
     discharged `InferenceFailed` without a model call (the provider stub counts zero), and the
     agent is `Idle` before the recovering caller's own `TurnStarted`; and its twin, a call
     *inside* its deadline is left alone and the caller told `Busy`;
   - **cross-door exclusion** (§3): against Postgres, a direct turn in `Inferring` and a queued
     `tell` over the same agent — the `tell` waits for the step and coalesces into the backlog
     rather than starting a second turn, and a direct `ask` during a queued turn reads the phase
     and is told `Busy`. This is the one test that needs both factories over one `DataSource`.
8. **Docs and the two earlier records** (§13, "Docs"). Last, describing what is.

Steps 1–3 are independent of one another and of the rest; 4 depends on 2 and 3; 5 stands alone;
6 and 7 depend on 5, and 7 on 4. Nothing in 1–4 touches a lock, so the deadline work can be
reviewed and merged before the lock work is begun.

**`nessy-memory`.** `HeadSummarizer.java:216` and `EpisodeSummarizer.java:181` — a `LockKind` and
the `agentType` each already holds reach the call; `Config.locks(Locks)` keeps its shape.
`HeadSummarizerTest.java:127`, `HeadSummarizerFoldTest.java:89`, `EpisodeSummarizerTest.java:124`
— the constructor. `HeadSummarizerTranscriptTest.java:105–111` — the anonymous `Locks`. Both poms
name the module (`:55`).

**`nessy-spring-boot/autoconfigure`.** `DirectHarnessAutoConfiguration.java:94` —
`locks.getIfAvailable(InMemoryLocks::new)` becomes a `JdbcRowLocks` over the `DataSource` the
factory is already conditional on (and the container's `PlatformTransactionManager` if §14 Q9
says yes); an application's own `Locks` bean still wins. The autoconfiguration record's "Locks
default to `JdbcLeases`" paragraph is overtaken: the default is a row lock, and no TTL is involved.
The two empty `spring/boot/lease/` directories (main and test, created 2026-09-25 07:16) were
presumably for that default. `QueuedHarnessAutoConfiguration.java` unchanged unless §11 lands.
`pom.xml:169` — `nessy-lease` stops being optional, since both doors now need the row lock.

**`nessy-examples/chat-web`.** `ChatConfiguration.java:70–86` — the `agentLocks` bean and its
javadoc go entirely; `:131` — the episode summariser's construction takes the map form.
`ChatController.java:106` keeps its `Busy` arm.

**Docs** (state what is; no history). `docs/concepts/leases.md`, `docs/concepts/storage.md:15`,
`docs/guides/spring-boot.md:43, 78, 144–154`, `docs/index.md:138`, `README.md:177`,
`ROADMAP.md:95–96` — describe the construction-time kind and the old module name.
`docs/concepts/memory.md:163` shows `.leases(new JdbcLeases(dataSource))`, a method and a
one-argument constructor that do not exist today; it is already wrong and is rewritten with the
rest. `docs/superpowers/specs/2026-09-25-one-core-two-doors-design.md:263–275` — the "two doors
exclude differently" table and its "cannot hold a row lock: its turn spans an inference"
paragraph are overtaken; both doors now hold a row lock for a step and neither across an
inference. The autoconfiguration record's §9 non-goal and "Locks default to `JdbcLeases`" are
overtaken and should say so.

**Live databases.** `CREATE TABLE IF NOT EXISTS` never alters, so a database carrying `nessy_lease`
in its old shape keeps it and the new statements fail against it. `nessy_lock` is new. The compose
Postgres for development is disposable by standing rule; a lease row is transient, so nothing in
`nessy_lease` is worth carrying across.

---

## 14. Open questions for James

1. **The direct door as designed in §3–§4**: per-step `withLock`, phase decides `Busy`, effects
   outside, `put` under the first lock, no outer lease. Yes?
2. **The guard helper.** In `nessy-api`, shared by `AgentType` and `LockKind`, bound 64. What is it
   called, and ASCII or length only?
3. **`withLock` on a lease** polls at a fixed interval in the SPI's default (§7), for the sake of
   one total interface; nothing in the tree calls it. Acceptable?
4. **`Identity`.** Four parameters on both verbs, or promote `(AgentType, AgentId)` from
   `engine.observability` into `nessy-api` and take three?
5. **A `Clock` on `DirectHarnessFactoryConfig`** (§4c). The direct door has none and calls
   `Instant.now()`; deadlines that can be tested need one, and the queued handlers already take
   one. A method on a public config, defaulting to `Clock.systemUTC()` — yes? (The `within`
   helper itself needs no decision: it delivers `terms.failed(...)`, an outcome that already
   exists, and mints no exception type. The thread handoff per turn and tool call is recorded as
   accepted; say so if it is not.)
6. **`Instant writtenAt(AgentId, Seq)` on `AgentEventStore`** (§4e). Checked sufficient for every
   clock start the recovery table needs, with `Outstanding.since` as the one fold change. Yes to
   both?
7. **`InferenceHandler.undispatchable()`'s category and wording** (§4d finding 1). Today
   `Failure.Permanent("the inference could not be dispatched")`, which is false for a claimed call
   that overran on the queued door and for a dead direct process, and which `Failure.Unknown`'s
   javadoc names as its own case. Change it to `Unknown` with the tool blob's honesty ("did not
   complete before its deadline; whether it ran is not known")? Recommended; it changes what the
   queued door stores on every inference row from then on, so it is asked rather than done.
8. **`retryPolicy` on the direct door.** Now that the door holds terms, honouring it is a loop
   around `within` spending the same budget the queued door spends from emit. Honour it now, or
   store it and say in the javadoc that the direct door does not retry yet? Recommended: the
   latter, so the lock change does not grow a retry loop, but no longer silently.
9. **`JdbcRowLocks(DataSource, PlatformTransactionManager)`** as an optional second constructor
   (§8a), so it never mints a manager where the application has one. Yes?
10. **`JdbcRowLocks`' table.** Its own `nessy_lock (kind, agent_type, agent_id)` (§8a). Yes?
11. **Scope** (§11). Lock and transaction now; the other four stores as a follow-on. Or widen?
12. **The rename** (§12). Module and package to `lock`; `JdbcLeases` and `nessy_lease` keep their
    names. Asked again because the design under your earlier yes has moved.
13. **Sequencing** (§5). The provider transport timeouts land first, as their own change, green on
    their own, before the lock work — yes? And is the starter's margin above the engine's default
    a number you want to set, or a detail?
