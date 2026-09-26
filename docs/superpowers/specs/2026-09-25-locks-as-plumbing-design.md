# Locks as plumbing: one SPI, two implementations, and a direct door that locks for milliseconds

**Status: PROPOSED — awaiting sign-off on §14; the first five landed items of §13a are built and
committed, and `DirectHarness<I, O>` is in flight.** Every fact about the working tree was measured
on branch `fold-swap`, on 2026-09-25 for the first five revisions and re-verified on 2026-09-26 for
the sixth and seventh; every signature under "the design" that is not marked as landed is a
proposal.
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

The sixth revision records that the first four steps of §13a have landed (each with its SHA, in
§13a), and rewrites the handlers step around a ruling James made while reviewing the deadline step: "it really feels like we have a
need for 'a thing that can execute the effects emitted by the fold' and that would be a simple
method call ... In the queued case, we have to manage the effects store properly based on what
happens. In the direct case, we return the answer (or fail loudly if anything is deferred)." He is
right, and the thing already exists — `EffectHandlers.perform`. §4f finds that
`DefaultDirectHarness` is a hand-rolled reimplementation of it, and that this one fact is the root
cause of five things the record had been treating as separate findings; §4g is the handlers step
that follows. Two §14 questions stop being questions and become consequences. Three rulings made after
the fifth revision are folded in: the guard helper's name (§7), locks and leases as separate tables
(§8a), and lease observability with its three rejected alternatives (§8d).

The seventh revision is mostly §13a. It states the real order of what remains, with two steps that
were not in any earlier revision: a package restructure of the engine ("right now, engine is kind
of a mess") placed before the handlers step, and a module split — `nessy-engine`,
`nessy-engine-direct`, `nessy-engine-queued` — placed last, with the reason it kept being deferred
and the reason it is now tractable (§13b). Four rulings made since the sixth revision are folded in
and leave §14: `JdbcRowLocks` takes a `PlatformTransactionManager` and chooses its own propagation
(§8a); it lives in `nessy-engine`, so the engine does not depend on `nessy-lease` at all and that
module stays optional (§8a, §12) — which reverses this record's earlier proposal to make it
non-optional; `nessy-lease` keeps its name, module and package ("We have both constructs, leases
and locks"), so the rename question is retired (§12); and the inference `undispatchable()` blob
becomes `Failure.Unknown` (§4d), with the evidence for why recorded so the two comments that
currently describe the bug can be rewritten when it is gone.

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
`sinceLastTurnStarted`, execute one command, append, commit, release. Then perform the resulting
effects — the inference, the tool call, the approval — with no lock and no transaction held, and
come back for the next step with the outcome as the next command. Every step re-reads the agent
under its own lock; no state is carried across a release. "Perform" means `EffectHandlers.perform`
(§4g): the door does not have its own way of calling a model or a tool.

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

### 4b. The direct door enforced none of them (as measured before `8575a90a`, which fixed it)

Everything in this subsection describes the tree as it was when the finding was made. The deadline
step of §13a (`8575a90a`) landed it: the inert setters store, every effect is waited for with its own deadline, and the
line numbers below are those of the file before that commit. It is kept because the finding's root
cause — §4f — is only visible against what it fixed.

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

**Landed in `8575a90a`, in this shape:** `perform` is `within(termsFor(effect), () -> switch
(effect) { ... })` with the three arms — `infer`, `approve`, `callTool` — inside the one wait
(`DefaultDirectHarness.java:309–326`), the `Future` is cancelled on expiry, and an
`ExecutionException` from the work is delivered as `terms.failed(cause)` so a throwing provider no
longer escapes the turn (§4d). The handlers step (§4g; §13a step 3) keeps `within` exactly as committed and changes only
what is inside it: the switch over three hand-written arms becomes one call to
`EffectHandlers.perform`, so the wrapper has one call site and the door's private `termsFor` — a
seven-line copy of `EffectHandlers.termsFor` at `:329–335` — goes with the arms. An expiry is
`terms.failed(...)`: for an inference `InferenceFailed(Failure.Unknown(...))`
(`InferenceHandler.java:109–115`, "Nobody found out whether the call happened"), for a tool call
`ToolFailed(callId, "the call failed: ...")` (`ToolCallHandler.java:141–143`), for a blocking
approver `ToolFailed(callId, "the call could not be authorised: ...")`
(`ApprovalHandler.java:222–225`). `failed` rather than `undispatchable`, because an in-process
expiry is exactly what `failed` is documented for — "the work was attempted, threw, and will not be
attempted again ... nobody found out whether the work happened" — where `undispatchable` is for
work nobody performed (§4d uses that one). No outcome is invented by the door, no exception type
is minted, and no fold vocabulary is added: the `EffectOutcome` becomes the `AgentCommand` through
`EffectOutcomes.command` (`effect/EffectOutcomes.java`, lifted out of `DefaultQueuedHarness` in
`90a0fde8` so both doors use one conversion). A `Deferred` approval or tool is answered at once;
where and how is §4g. The Inference config's two inert setters store their values
(`DefaultDirectHarnessConfig.java:266–278`; `retryPolicy` says in its javadoc that it is "stored
... but not honoured"), the direct factory builds an `EffectTermsSource` from them
(`DefaultDirectHarnessFactory.java:184–192`), and the queued door's five-minute default is the
direct door's too (`DEFAULT_INFERENCE_TIMEOUT`, line 235).

**The direct door had no clock; it has one now.** `DirectHarnessFactoryConfig.clock(Clock)`
(line 110) defaults to `Clock.systemUTC()` and reaches the harness; the two `Instant.now()` calls
became `clock.instant()`. The three deadline tests in `DefaultDirectHarnessTest` step it and finish
in under half a second. The `Clock` is a public config method and was a §14 question; it landed with step
4 and the question is closed.

**One executor, on the factory.** The deadline step's first cut gave each harness its own virtual-thread
executor and had the factory keep a list of every harness it ever made so it could close them. Both
were removed before the commit: a thread-per-task executor over virtual threads holds nothing while
idle, so a harness has nothing to own and nothing to release, and the registry was an unbounded
collection existing to free resources that were not held. What landed is one
`Executors.newThreadPerTaskExecutor` on `DefaultDirectHarnessFactory` (line 98), shared by every
harness it makes, and the factory is `AutoCloseable`. `DirectHarnessFactory`'s javadoc had said
"Nothing here is closeable"; it was corrected rather than left false ("An implementation may be
closeable, which is a narrower difference from `QueuedHarnessFactory` than it once was").

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

1. **The inference blob's category and wording are wrong for a hung inference, on both doors —
   ruled, and it changes.** `Permanent` is "refused on its merits; retrying spends a budget to
   receive the same answer" (`Failure.java:49–58`), and "could not be dispatched" is false for a
   call that was dispatched and never came back — which is what a queued row that was claimed,
   overran, and reached its deadline is, and what a dead direct process leaves. `Failure.Unknown`'s
   javadoc names this case exactly (§4a). The tool blob already gets it right ("whether it ran is
   not known", with a comment explaining why it must not claim more). James approved the change
   ("I'm not worried about current live databases. They don't exist. Let's make sure we do it
   RIGHT."): `undispatchable()` for an inference becomes `InferenceFailed(Failure.Unknown("the
   inference did not complete before its deadline; whether it ran is not known"))`, the tool blob's
   wording. The fold accepts any `Failure`, so nothing else moves.

   **Why it is false, as evidence rather than assertion**, because the queued dispatcher's own
   code says so in four places. `EffectDispatcher.performInTrace` (`:301`) routes a claimed row to
   `expired` whenever `clock.instant()` is not before its deadline, with no check on
   `attempts_made`; the claim (`JdbcEffectStore.MARK_RUNNING`, `:93`) does `attempts_made =
   attempts_made + 1` *before* the work is performed; the dispatcher's own log line (`:408`) prints
   "effect {} passed its deadline after {} attempt(s)" — a number it knows can be greater than
   zero; and its class javadoc (`:53–54`) documents that "a crash mid-call leaves a row whose
   deadline has passed rather than one nothing will ever pick up". Put together: an inference that
   was claimed, dispatched to the provider, and possibly completed there is, on its next claim,
   told to the agent as "could not be dispatched" — `Permanent`, no less, which tells a retrying
   agent that asking again would receive the same answer. Two comments in the tree already say the
   stored blob is false on some path — `EffectDispatcher.giveUp` (`:496–497`: "the stored blob —
   which says the effect could not be dispatched — would be false") and `EffectTerms.failed`'s
   javadoc (`:65–67`: "Falling back to the stored blob would ... tell the agent the effect could
   not be dispatched, which is false") — and both are written against the wrong blob. Once it is
   honest, both comments describe a bug that no longer exists and are rewritten in the same commit;
   `failed` stays distinct from `undispatchable` for the reason its javadoc gives *next* (an
   attempt ran and there is an exception to carry), not for the reason it gives first.
   `EffectTermsSourceTest.undispatchableIsAPermanentFailureUntoldFromTheUnknownCase` (`:201`) pins
   the wrong behaviour by name and flips, name and assertion both. This is its own step (§13a),
   small and before the handlers step, so that the direct door's recovery lands against the honest
   blob and never delivers the false one.
2. **The direct door's own `approve` answers a `Deferred` approval with a `Denied`** ("approval was
   deferred, and nothing here can wait for it", `DefaultDirectHarness.java:444–448`), where the
   fold's doctrine and both `AskingTerms` blobs say nobody said no. The fifth revision proposed
   fixing the arm; the sixth finds that the arm should not exist (§4f, item 5). It is not a
   decision any more: once the door performs effects through `EffectHandlers`, there is one
   `Deferred` arm in the door and it delivers a failure, not a denial, by construction (§4g).

**The race with a slow-but-alive original** resolves without coordination, and `expectedLast` does
not save us here — it is the fold that does. When a recovered original comes back for its
completion step it takes the lock, reconstitutes, and finds the agent `Idle` (or in somebody else's
turn): `Idle.execute(CompleteInference)` is `Decision.ignore()` (line 140) by *phase*, its result
is discarded, and its caller reads `Failed` off the stream. `expectedLast` stands behind that only
as the guard against a step that carried stale state across a release; under the lock it should
never fire, and if it does it is a bug in the step, not a race. With §4c in place this race is
rarer still, because the in-process deadline and the lazy deadline are the same number: an
original alive enough to come back has already given up at the same moment recovery would.

### 4e. What the store exposes, and the one fold change (landed: `62fe03a2`)

Both halves of this subsection are in the tree: `AgentEventStore.writtenAt` with its JDBC and
in-memory implementations, and `Outstanding(action, phase, since)` populated by `AgentState`. The
design text is kept as the reasoning; nothing reads either yet, which is the recovery step's job
(§13a step 6).

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
lookups at most per recovery, all by primary key `(agent_id, seq)`. Nothing is missing (landed in `62fe03a2`). The
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

### 4f. The root cause: the direct door is a hand-rolled `EffectHandlers`

James, reviewing the deadline step (`8575a90a`): "it really feels like we have a need for 'a thing that can execute the
effects emitted by the fold' and that would be a simple method call. Am I right? It just depends on
what we want to do with the answer. In the queued case, we have to manage the effects store
properly based on what happens. In the direct case, we return the answer (or fail loudly if
anything is deferred)."

He is right, and the thing exists. `effect/EffectHandlers.java` has exactly two public methods:

```java
public EffectTerms termsFor(AgentEffect effect);                              // line 58
public Awaited<EffectOutcome> perform(AgentId agentId, AgentEffect effect);    // line 72
```

Nothing in `perform`'s signature is queued-specific: no row, no store, no trace context, no
dispatcher. It takes an agent and an effect and gives back an `Awaited<EffectOutcome>`. And
`Awaited` *is* the difference between the doors. `EffectHandler.handle`'s javadoc (lines 61–64) is
the load-bearing sentence: "`Awaited.Deferred` means the work is genuinely elsewhere — a person has
been asked, a queue has the job — and an answer will arrive later against a `ReplyToken`. It is not
'try again later': the effect has been performed, in the only sense that matters, and repeating it
would ask twice." So:

- **Queued**: `Ready` → deliver the outcome; `Deferred` → leave the row for a `ReplyToken` to
  answer later. The `EffectStore` / `EffectDispatcher` machinery is what "manage the effects store
  properly based on what happens" means, and it is the queued door's business and stays there.
- **Direct**: `Ready` → carry on with the turn; `Deferred` → nobody is coming back, so fail.

The direct door already says the `Deferred` half by hand — twice. `callTool` answers a deferred
tool with `ToolOutcome.Failed("the tool deferred, and nothing here can wait for it")`
(`DefaultDirectHarness.java:471–475`) and `approve` answers a deferred approver with
`ApprovalOutcome.Denied("approval was deferred, and nothing here can wait for it")` (lines
444–448). Two sentences that say the same thing, one a failure and one a denial.

**The finding.** `DefaultDirectHarness` is a reimplementation of `EffectHandlers.perform`: its
private `infer` (lines 519–543), `approve` (389–450) and `callTool` (452–483, with `completed` at
485–497) are the three handlers' `handle` methods rewritten, its private `termsFor` (329–335) is
`EffectHandlers.termsFor` rewritten, and its `argumentsOf`/`resolveCall` (638–661) and `turnOf`
(510–517) are `EventStreamToolCalls.find` rewritten against an in-memory `history` list instead of
the store. That one fact is the root cause of five things this record had been treating as
separate findings:

1. **The direct door wraps nothing in `Observed*`.** `grep -rn Observed
   nessy-engine/src/main/java/org/jwcarman/nessy/engine/direct/` is empty. The queued factory hands
   its handlers wrapped collaborators — `ObservedInferenceProvider.wrap(provider, observations)`,
   an `ObservedInferenceContextAssembler` over `ObservedTurnHistories`, `ObservedSummarizer` and
   `ObservedAmbientSource` (`DefaultQueuedHarnessFactory.java:216–230, 328`), and
   `DefaultQueuedHarnessConfig.tool` wraps every tool and approver as it is bound
   (`ObservedTool.wrap`, `ObservedApprover.wrap`, lines 197, 210). The direct door's copies of the
   handlers are handed the bare provider, and `DefaultDirectHarnessConfig.tool` (line 115) binds
   tools bare. So chat-web, which is on the direct door (`ChatConfiguration.java:147`,
   `DirectHarness<String>`), has no GenAI telemetry at all: no inference span, no tool span, no
   token usage. This is against James's standing ruling that the engine wraps everything it is
   handed (2026-09-17: "always wrap, never re-wrap; registry required, NOOP means nothing to
   report"). It was never a separate observability bug to fix; it is what a second copy of the
   handlers, built without the collaborators the real ones are handed, looks like.
2. **`ToolConfig.timeout` was advisory and `InferenceConfig.timeout` inert** (§4b). The real
   handlers have carried `termsFor` since they existed; the copies had no terms, so there was
   nothing to enforce them with. `8575a90a` fixed the symptom by giving the copies terms.
3. **`EffectTermsSource` had to be lifted out of the handlers at all** (§13a, `90a0fde8`).
   Its own class javadoc says why: "A door that only ever asks — never performs — has no way to
   build the handlers, but it can build this." The direct door could not construct a handler to
   ask one because it had chosen not to hold handlers. Once it holds them, `EffectHandlers.termsFor`
   answers the question and the door needs no separate resolver. (`EffectTermsSource` itself
   stays: it is where the handlers get their terms, and it is what recovery re-resolves through
   `handlers.termsFor` in §4d.)
4. **A second, unwrapped `ContextAssembler`.** The direct harness builds its own in its constructor
   (`DefaultDirectHarness.java:194–201`) under a comment that reads: "The queued door's assembler,
   unchanged. Ambient, the tail window and summaries are one job however the turn was started, and
   a second implementation of it would drift." It has drifted — same class, one wrapped by the
   factory that built it and one not, because the harness builds its own instead of being handed
   one. The comment is now false in the way it warned against.
5. **The deferred-approval answer diverging from the fold's doctrine** (§4d finding 2; was an open
   question). A denial for a deferral is what a second implementation of one decision produces.
   The real `ApprovalHandler` returns `Awaited.Deferred` and says nothing to the agent; what the
   door does with a `Deferred` is the door's one decision, made once.

None of the five is fixed by fixing it. All five are fixed by deleting the copy.

### 4g. The handlers step, reshaped: the direct door calls `EffectHandlers`

The direct factory builds the same three handlers the queued factory builds — `ToolCallHandler`,
`ApprovalHandler`, `InferenceHandler`, wrapped into one `EffectHandlers` — and hands them to the
harness. `perform` becomes:

```java
// shape, not signature
private AgentCommand perform(AgentId agent, AgentEffect effect) {
  return within(handlers.termsFor(effect), () ->
      switch (handlers.perform(agent, effect)) {
        case Awaited.Ready(EffectOutcome outcome) -> EffectOutcomes.command(outcome);
        case Awaited.Deferred<EffectOutcome> _ ->
            EffectOutcomes.command(handlers.termsFor(effect).failed(
                new IllegalStateException("deferred, and nothing here can wait for it")));
      });
}
```

The `within` wrapper is unchanged from `8575a90a` and now has one call site. `Ready` is
`EffectOutcomes.command`, already extracted. `Deferred` is the door's one decision, in one place:
the work is elsewhere and nothing on this door can receive its answer, so the call is discharged
with the terms' own `failed` blob carrying the door's reason — for a tool `ToolFailed(callId, "the
call failed: deferred, and nothing here can wait for it")`, for an approval `ToolFailed(callId,
"the call could not be authorised: deferred, and nothing here can wait for it")`
(`EffectTermsSource.java`, `CallTerms.failed` and `AskingTerms.failed`). `failed` rather than
`undispatchable()`, reversing the fifth revision's pick for this arm: `undispatchable()`'s wording
("so it was not run"; "did not complete before its deadline") describes work nobody performed or a
lapsed deadline, and a deferral is neither — the effect *was* performed, an answer may even arrive
somewhere, and nobody on this door will find out. That is `failed`'s documented case, and it is the
same path `within` already takes for an expiry. The fold takes `ToolFailed` on an
`AWAITING_APPROVAL` call by design (§4d table), so an approval that defers is no longer a `Denied`
that puts words in an approver's mouth. "Fail loudly" here means the *call* fails and the model is
told, which is what both hand-written arms already do; the fold has no arm that ends a turn from
`AwaitingActions` without discharging its calls, so failing the whole turn would be a fold change
for no gain — the caller still gets an answer, and the transcript says why the tool did not run.

**What is deleted from `DefaultDirectHarness`.** `infer`, `approve`, `callTool`, `completed`,
`commentary`, `requested`, `narratorFor`, `turnOf`, `argumentsOf`, `resolveCall` and the private
`termsFor` — roughly 190 of the file's 721 lines — plus the fields and constructor parameters they
alone used: `provider`, `systemPrompt`, `options`, `tools`, `toolset`, `assembler`, `transcript`,
and the `summaries`/`maxTail`/`ambient` parameters that only fed the assembler. `history` stays for
`outcome(history, reading)` and nothing else. What the harness keeps is the loop: reconstitute,
execute, append, narrate, perform, and read the answer back. The `Thinking` narration stays in the
loop, keyed off an emitted `Infer` effect the way `DefaultQueuedHarness.java:281–287` does it,
because it is the harness's to say and neither handler says it.

**What this changes in behaviour, so the tests are rewritten with eyes open.** Three arms of the
copy differ from the handlers, and each moves to the handlers' answer: an unbound tool at
approval was `CompleteApproval(Denied("no such tool"))` and becomes `ToolFailed("there is no tool
named ...")`; an approver that throws was `Denied("the approver failed: ...")` and becomes, via
`within`'s `ExecutionException` arm, `ToolFailed("the call could not be authorised: ...")`; a
deferred approval was `ToolDenied` and becomes `ToolFailed`. `a_deferred_approval_is_denied`
(`DefaultDirectHarnessTest.java:765`, asserting `ToolDenied` in the stream) is renamed and asserts
`ToolFailed`; `a_denied_call_does_not_run` (line 720) is unaffected, since a real denial is still
`ToolDenied`. One narration difference: the real handlers announce `CallDeferred` /
`ApprovalDeferred` before returning `Deferred`, so a watcher on the direct door now sees "deferred"
followed at once by "failed", which is the truth.

**Consequences, stated rather than buried.**

- **Observability arrives by construction, identical on both doors.** The direct factory wraps
  what it hands the handlers exactly as the queued factory does, and the direct config wraps tools
  and approvers as it binds them. But `DirectHarnessFactoryConfig` has no `ObservationRegistry`
  today — it carries locks, events, payloads, provider, schemas, mapper, clock, listeners,
  features and harnesses (lines 49–58), and nothing else — where `QueuedHarnessFactoryConfig` has
  `observations(ObservationRegistry)` defaulting to `NOOP` (line 95) and
  `DirectHarnessAutoConfiguration` never sees the registry bean the queued one takes
  (`QueuedHarnessAutoConfiguration.java:73, 91`). So this design FORCES a new public config method,
  `DirectHarnessFactoryConfig.observations(ObservationRegistry)`, with the same default, and the
  starter passes its registry through. It is a public method on a public config, so it is asked
  (§14 Q3) — but note that it is forced by the design rather than chosen: James's ruling is that a
  registry is required and NOOP means nothing to report, and the direct door cannot obey that
  ruling without a way to be given one. Internally, `DefaultDirectHarnessConfig`'s constructor
  gains the registry the way `DefaultQueuedHarnessConfig`'s already has it (line 100).
- **`ReplyTokens`, ephemeral.** `ToolCallHandler` and `ApprovalHandler` both require a
  `ReplyTokens` (constructors at `ToolCallHandler.java:68–76`, `ApprovalHandler.java:64–71`) and
  mint a token per call (`replyTokens.mint(agentType, agentId, requestSeq, callId)`), where the
  direct door hand-rolls `new ReplyToken(call.callId().value())` (lines 407, 468). James approved
  an ephemeral one ("Emphemeral would work on the reply token"), and `ReplyTokens.ephemeral()`
  exists (`engine/tool/ReplyTokens.java:157`): a fresh AES-256 key per process, documented as "for
  tests and for a single-process demo, and useless in production: every restart invalidates every
  outstanding token." That warning is about a door that can be answered later. On this door
  nothing can come back — a `Deferred` is failed on the spot — so a token that does not survive a
  restart loses nothing, and there is no key to configure, rotate or leak. Ephemeral is the right
  answer here, not a compromise; the direct factory constructs one and no config surface is added.
- **The `within` wrapper has one call site instead of three arms.** The committed code
  (`DefaultDirectHarness.java:315–326`) wraps a three-arm switch; after the handlers step it wraps one method
  call and the door's private `termsFor` switch (329–335) is deleted in favour of
  `handlers.termsFor`. `within` itself does not change.
- **What else the handlers need, checked against the direct factory's constructor arguments.**
  `ToolCallHandler(agentType, tools, calls, replyTokens, narrator, terms, clock, payloads)`:
  `calls` is `new EventStreamToolCalls(events, payloads)` — both in hand; `narrator` is the
  `Listeners` the factory already builds (it `implements Narrator`); `terms`, `clock`, `payloads`
  in hand; `replyTokens` is the ephemeral one above. `ApprovalHandler(agentType, tools, calls,
  replyTokens, narrator, terms, clock)`: the same set. `InferenceHandler(agentType, inference,
  options, terms, payloads, narrator)`: `inference` is `new DefaultInferenceService(assembler,
  ObservedInferenceProvider.wrap(provider, observations), systemPrompt, tools.offers(), narrator)`
  and the assembler's inputs (`events`, `payloads`, `summaries`, `maxTail`, `ambient`) are what the
  harness constructor takes today and the factory will take back. Two things are unavailable —
  the registry and the reply tokens, both above — and one is unrepresentable, next.
- **The constrained answer has nowhere to travel.** `ask(agent, input, TypeRef<T>)` builds an
  `OutputSchema` per call and the copy of `infer` puts it on the five-argument `InferenceRequest`
  (`DefaultDirectHarness.java:521–527`; `InferenceRequest.java:33–38`). The real path has no slot
  for it: `AgentEffect.Infer` is empty, `InferenceInvocation` is `(agentType, agentId, options)`,
  `InferenceOptions` is `(modelName, maxTokens)`, and `DefaultInferenceService.infer` uses the
  four-argument constructor. `OutputSchema` is referenced in `nessy-engine` main by
  `DefaultDirectHarness` alone. So `handlers.perform(agent, new Infer())` cannot carry a per-call
  shape, and putting one on `Infer` would change the payload of every queued effect row for a
  door that never uses it. This is exactly what James's adjacent decision resolves: with
  `DirectHarness<I, O>` and the output type bound at creation, the schema is a property of the
  harness, and `DefaultInferenceService` takes it at construction as an internal field — no new
  concept, no per-call slot. The handlers step therefore depends on that change landing first (§13a), and
  the record says so rather than inventing a per-call slot to avoid the dependency.

**What this retires rather than answers.** Two items leave §14 because they stop being decisions:
the deferred-approval divergence (§4d finding 2) — there is one `Deferred` arm and it is a failure
by construction; and the direct door's observability gap — the door observes what the handlers
observe, because they are the same handlers. Neither needed a yes; both needed the copy gone.

---

## 5. Provider transport timeouts: the hygiene half, and a bug on its own (landed: `e5bde878`)

Built as §5b describes: a `timeout(Duration)` setter on each of the four provider configs, applied
in `build()`/`resolveClient()` per the mapping, with a test per module reading the value back; and
the three starter auto-configurations set `TransportTimeouts.PROVIDER_TRANSPORT`, six minutes —
"one minute above the five-minute `InferenceConfig.timeout` default" — which settles the margin
that §14 once asked about. The §5a measurements below are what the change was made against.

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
`nessy-spi`, so it lives in `nessy-api`. **Ruled after the fifth revision: it is `Identifiers`, in
`nessy-api`.** It does not exist in the tree yet (no `Identifiers.java` under `nessy-api`); it
lands in the lock step (§13a step 4). Whether it restricts to ASCII as well as length was not part
of the ruling and is left to the implementation to match the memory notes' 256/ASCII guard where that guard exists.
`JdbcLeases`' hand-rolled `if (kind.isBlank())` goes.

**Four parameters is wide.** `org.jwcarman.nessy.engine.observability.Identity` is already exactly
`(AgentType, AgentId)` — `DefaultQueuedHarness` builds `new Identity(agentType, agentId)` for its
traces, and seven other files construct one. Promoting it would make the verbs three parameters;
it lives in the engine, which the SPI cannot see, so promoting means moving it to `nessy-api`, and
that is a vocabulary decision. §14 Q5 flags it without assuming it.

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

**The transaction manager it uses — ruled.** `JdbcRowLocks` takes a `PlatformTransactionManager`,
with a `DataSource`-only constructor that mints a `JdbcTransactionManager` for a non-Spring caller
who has nothing to hand in. Boot auto-configures a manager in every application that has a
`DataSource`, so the starter passes the container's; measurement 1 above is why the minted one is
correct for the plain case (it joins whatever is open on the same `DataSource`) and why it is not
enough for JTA or a second `DataSource`.

*Why the manager and not a `TransactionTemplate`.* A template carries propagation, isolation and
timeout, and the lock's correctness depends on the propagation being `PROPAGATION_REQUIRED`: the
row lock and the work it guards — the append, the payload `put`, the outbox insert — must be in
*one* transaction, or the lock is released before the work commits and guards nothing. A caller
handing us a template built with `REQUIRES_NEW` would silently put the lock in a different
transaction from the work; nothing would fail, and nothing would be excluded. So `JdbcRowLocks`
takes the manager and builds its own template with the propagation it needs, and no caller can
misconfigure the one setting that matters.

*The sharp edge, to be named in the class javadoc.* `REQUIRED` joins an outer transaction when one
is open. An application that wraps a `tell` or an `ask` in its own long `@Transactional` method
therefore holds the agent lock until *its* commit, which can be far longer than the step, and
breaks §3b's invariant — nothing holds the lock across anything slow — from outside the engine.
That is the application's transaction and the application's choice; the engine cannot forbid it
without `REQUIRES_NEW`, which is the propagation ruled out above for a worse reason. The javadoc
says so, so that the first person to see a queue of waiters behind a slow request handler knows
where to look.

The class javadoc's "Why a lock rather than a lease" paragraph moves here from `JdbcAgents`.

**Where it lives — ruled: `nessy-engine`, not `nessy-lease`.** `JdbcRowLocks` is a row-locking
transaction over the engine's own `DataSource`, both doors construct one, and the engine is where
the JDBC stores already are. Putting it in `nessy-lease` would have made that module a compile
dependency of the engine — the sixth revision proposed exactly that, by making the module
non-optional in the autoconfigure pom — for the sake of one class. With `JdbcRowLocks` in the
engine, `nessy-engine` does not depend on `nessy-lease` at all: the `nessy-lease` dependency at
`nessy-engine/pom.xml:105` is already unused (no file under `nessy-engine/src` imports
`org.jwcarman.nessy.lease`; measured) and is removed, the module's only consumers are the two
summarisers and chat-web's episode summariser bean, and it stays `<optional>true</optional>` in
`nessy-spring-boot-autoconfigure` (`pom.xml:171`) as it is today. That reverses the sixth
revision's "`nessy-lease` stops being optional". **Open: which package** — `engine.store`, beside
`JdbcAgentEventStore` and the in-memory stores the restructure (§13a) moves there, or an
`engine.lock` of its own. §14 Q1.

**Its table — ruled: separate from the lease's.** James: "they aren't really the same thing." So
`JdbcRowLocks` gets `nessy_lock (kind, agent_type, agent_id)` of its own, and the unified-table
option — one table with nullable `holder`/`expires_at`, and its variant that checked expiry inside
the row lock — is out (§10). `nessy_lock` needs NEITHER `holder` NOR `expires_at`, and not merely
because they would be null: a Postgres row lock *is* the holder, and it dies with its transaction,
including when the connection drops, so there is no stale holder to fence against and nothing for
an expiry to time. The lease needs both columns precisely because it is the mechanism that cannot
know whether its holder is alive. Each table's columns say what its rows are, and the two
mechanisms are not made to look alike when they are not.

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
`InMemoryLocks(int)` constructor. It moves out of `engine.direct` into `engine.store` with the
other two in-memory stores, in the package restructure (§13a) rather than in the lock step: it
serves either door, as James ruled the in-memory stores do, and it belongs beside its JDBC sibling
`JdbcRowLocks` (§8a) rather than in `nessy-lease`. Note that an in-memory `withLock` gives the direct door's steps
exclusion but no transaction; against the in-memory stores that is what "in memory" has always
meant here — `InMemoryAgentEventStore` is a list — and the phase check works the same way.

### 8d. Lease observability: know that a TTL was reached, without guessing why

Ruled after the fifth revision. The lease is the one mechanism here that can let two run at once
— a holder that is merely slow is indistinguishable, at take time, from one that died — and its
javadoc says so. What we want is to *know* a TTL was actually reached, without the log guessing
why, because at take time nothing can distinguish a dead holder from a slow one. Two signals, both
factual:

- **A `RELEASE` that affects zero rows is a `WARN`.** The releaser is the only observer that can
  *prove* duplicate work happened: if its own row is gone, somebody took the lease over while it
  was still working, and the work ran twice. Today the row count is discarded —
  `jdbc.sql(RELEASE).params(kind, key, holder).update()` at `JdbcLeases.java:107` returns it to
  nobody.
- **A `takeovers` column**, incremented only on the `ON CONFLICT DO UPDATE` path and returned by
  `TAKE`, so a take can report factually that it took over an expired lease, and how many times
  this row has been. It resets naturally: release `DELETE`s the row, so a row exists only between
  take and release and the count never outlives the contention it counts.

Three alternatives were rejected, with reasons, so they are not re-proposed:

- **`xmax <> 0` in `RETURNING`** to tell an insert from an update. Measured working on Postgres 16,
  17 and 18. Rejected because it infers control flow from an MVCC bookkeeping field that records
  tuple locks — undocumented for this use, and unreadable without a paragraph of background beside
  a log line.
- **`RETURNING OLD.holder`** to name the previous holder. Postgres 18 only, measured working;
  rejected because it would impose a version floor for the sake of a log line.
- **Returning the previous holder's UUID at all.** `holder` is a per-take `UUID.randomUUID()` nonce
  (`JdbcLeases.java:93`), so it names nothing anyone can look up; a log line carrying it would
  look like evidence and be noise.

**Two findings from the same reading, worth a line each.** `.filter(holder::equals)` in
`JdbcLeases.tryWithLock` (line 99) can never fire: `TAKE` says `SET holder = EXCLUDED.holder`, so
a row `RETURNING holder` always carries the caller's own holder, and `.isPresent()` alone is the
whole test. And the testcontainers image is `postgres:18-alpine` in every JDBC-backed test in the
reactor (`EngineFixture.java:71`, `JdbcLeasesTest.java:45`, `DurableDirectHarnessTest.java:58` and
eleven more) while the examples' compose files are `postgres:17`
(`chat-cli/compose.yaml:24`, `chat-web/docker-compose.yml:23`, `watchman/docker-compose.yml:24`),
so the suite tests *above* its floor: a version-specific construct — `RETURNING OLD` being the
example that nearly happened — would pass every test and fail in deployment.

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
`Transactions` SPI, or a `PlatformTransactionManager` on either config (the manager is a
constructor argument of `JdbcRowLocks`, §8a, and the config takes a `Locks`).

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

Rejected or overtaken by the sixth revision:

- **The direct door performing effects itself**, with `within` wrapping three hand-written arms
  (the shape `8575a90a` landed). Overtaken by §4f–§4g: the arms are a copy of `EffectHandlers.perform`
  and the copy is deleted. The `within` wrapper survives with one call site.
- **`terms.undispatchable()` for a deferred approval** (the fifth revision's fix for §4d finding 2).
  A deferral is performed work whose answer nobody here will see, which is `failed`'s case; and
  the arm is no longer approval-specific, so it is one arm for both kinds (§4g).
- **A per-call output schema on `AgentEffect.Infer` or `InferenceInvocation`** as the way to keep
  `ask(agent, input, TypeRef<T>)` working through the handlers. It would put a direct-only field on
  every queued effect row. The schema becomes per-harness under `DirectHarness<I, O>` instead (§4g).
- **One table for locks and leases**, with nullable `holder`/`expires_at`, and its variant that
  performed the expiry check under the row lock. James: "they aren't really the same thing" (§8a).
- **A per-harness virtual-thread executor and a factory registry of every harness ever made**
  (the deadline step's first cut). Removed before commit; one executor on the factory (§4c).
- **`xmax <> 0`, `RETURNING OLD.holder`, and returning the previous holder's UUID** as lease
  takeover signals (§8d).

Rejected or overtaken by the seventh revision:

- **`JdbcRowLocks` in `nessy-lease`, and `nessy-lease` non-optional in the starter.** It lives in
  the engine, the engine's unused dependency on the lease module goes, and the module stays
  optional (§8a).
- **`nessy-lease` → `nessy-lock`**, module and package. Retired by ruling: both constructs exist
  and each module holds one of them (§12).
- **A `TransactionTemplate` handed to `JdbcRowLocks`.** It would let a caller choose the
  propagation, and the lock's correctness depends on it being `REQUIRED`; the manager is taken and
  the propagation is the lock's own (§8a).
- **`Permanent("could not be dispatched")` for an inference that reached its deadline.** Changed
  to `Unknown` by ruling, with the dispatcher's own code as the evidence it was false (§4d).
- **The lock step moving `InMemoryLocks` into the lease module.** It moves to `engine.store` in
  the package restructure instead, with the other two in-memory stores (§8c, §13a).

---

## 11. Scope: the queued door's JDBC coupling is wider than the lock

James's ruling — "I don't want the queued door to do direct JDBC at all" — is about more than
`JdbcAgents.lock`.

**Measured.** `DefaultQueuedHarness` holds a `JdbcAgents` (line 80) and a `Backlogs<I>` whose one
method returns a concrete `JdbcBacklog<I>` (lines 94–95). `DefaultQueuedHarnessFactory` builds
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
`JdbcBacklog` and `JdbcEffectStore` have no interface today and would need one; `Backlogs<I>`
would return it. The queued auto-configuration would build the JDBC set from its `DataSource`, as
`DirectHarnessAutoConfiguration` already does for events and payloads. Every queued test that
constructs the factory from a `DataSource` changes shape.

**Recommendation: a follow-on.** This record's change — a lock SPI, the transaction it absorbs,
the direct door's steps and the queued door's three sites — is bounded and can land green on its
own. Folding four store SPIs in would triple the diff. §14 Q7.

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

## 12. Naming: "lease" and "lock" — ruled, no rename

`nessy-lease` keeps its name, its module and its `org.jwcarman.nessy.lease` package. James: "We
have both constructs, leases and locks." The sixth revision had the module holding three
implementations of `Locks` and proposed renaming it `nessy-lock`; with `JdbcRowLocks` in the engine
(§8a) and `InMemoryLocks` in `engine.store` (§8c), the module holds exactly one thing, a lease, and
its name is right as it stands. The only new name is the table: `nessy_lock (kind, agent_type,
agent_id)`, in the engine's `nessy-schema.sql` beside `nessy_agent`, because that is where its
one user is. `nessy_lease` stays `nessy_lease`, reshaped per §8b. The eight `pom.xml` files that
name the module are untouched except `nessy-engine/pom.xml`, which drops the dependency it never
used. The rename question is retired.

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
appended, nothing spent, nothing changed. `Identity`, if §14 Q5 says yes.

**`nessy-lease`** (§12, name unchanged). `JdbcLeases.java` — both constructors take
`Map<LockKind, Duration>`; `TAKE`/`RELEASE` take kind and agent from the call; the §8d signals.
`JdbcLeasesTest.java` — the `leases(kind, ttl)` helper at 67, `String key` at 64 and every
`tryWithLock(key, ...)` (76, 94, 103, 106, 118 and on), the "enrichment" test at 174. Its
`nessy-schema.sql` — `nessy_lease` reshaped to `(kind, agent_type, agent_id, holder, expires_at)`.
Nothing arrives from the engine and nothing leaves for it.

**`nessy-engine`.** (Line numbers for `direct/DefaultDirectHarness.java` are those of the tree at
`8575a90a`, 721 lines; the file is mid-change under `DirectHarness<I, O>` as this revision is
written. Package paths below are today's; after the restructure of §13a, `direct/` reads
`harness/direct/`, `harness/` reads `harness/queued/`, and the three `InMemory*` classes read
`store/`.)
- `store/JdbcRowLocks.java` (or `lock/`, §14 Q1) — new, with `nessy_lock` added to the engine's
  `nessy-schema.sql`. Takes a `PlatformTransactionManager`; the `DataSource`-only constructor
  mints one (§8a). `pom.xml:105` — the unused `nessy-lease` dependency goes.
- `direct/DefaultDirectHarness.java` — the largest change in the record, in two parts. *The
  handlers (§4g):* the constructor takes an `EffectHandlers` instead of `provider`, `systemPrompt`,
  `options`, `tools`, `summaries`, `maxTail` and `ambient` (`schemas` and `mapper` stay for the
  constrained answer until `DirectHarness<I, O>` moves the schema to construction); `perform`
  (309–326) becomes `within(handlers.termsFor(effect), () -> switch (handlers.perform(...)))` with
  the `Deferred` arm of §4g; `termsFor` (329–335), `approve` (389–450), `callTool` and `completed`
  (452–497), `turnOf` (510–517), `infer` (519–543), `commentary` and `requested` (551–565),
  `narratorFor` (573–575), `argumentsOf` and `resolveCall` (638–661) are deleted; the `Thinking`
  tell moves into the loop, keyed off an emitted `Infer`. *The lock (§3):* `under` (224–230) and
  the whole-turn `tryWithLock` go; `runTurn` (232–287) becomes the per-step loop, with the phase
  check and the `put` (269) moved under the first lock and the two contradicting comments (225–228,
  274–275) rewritten; `terminate` (289–303) becomes one step; the `Locks` field stays, now a
  `JdbcRowLocks` in the durable wiring. Lazy recovery (§4d) is the first locked step's preamble.
  `within` (357–375) is untouched.
- `direct/DefaultDirectHarnessConfig.java` — DONE in `8575a90a`: `Inference.timeout` and
  `retryPolicy` (266–278) store, the default is five minutes (235), `retryPolicy`'s javadoc says
  it is not honoured. The handlers step adds: the constructor takes the `ObservationRegistry` and `tool(...)`
  (115) wraps with `ObservedTool`/`ObservedApprover` as `DefaultQueuedHarnessConfig.tool` does
  (197, 210); the `OutputSchema` for a harness whose `O` is bound (§4g) is read from here.
- `direct/DirectHarnessFactoryConfig.java` — DONE: `clock(Clock)` (110). The handlers step adds
  `observations(ObservationRegistry)` defaulting to `NOOP` (§14 Q3).
- `direct/DefaultDirectHarnessFactory.java` — DONE: one virtual-thread executor (98), `close()`,
  `EffectTermsSource` per harness (184–192). The handlers step: builds `EventStreamToolCalls`, the wrapped
  `ContextAssembler` and `DefaultInferenceService`, the three handlers and one `EffectHandlers`
  per harness, exactly as `DefaultQueuedHarnessFactory.create` and its three `create*Handler`
  helpers do (216–239, 284–333), with `ReplyTokens.ephemeral()` built once per factory. `inMemory`
  (140) keeps `new InMemoryLocks()` from `engine.store` after the restructure.
- `effect/EffectTermsSource.java`, `effect/EffectOutcomes.java` — DONE in `90a0fde8`: the
  resolvers moved out of the handlers into `EffectTermsSource`, the `EffectOutcome`-to-`AgentCommand`
  conversion into `EffectOutcomes.command`, and the three handlers take the source and delegate
  `termsFor`. `InferenceHandler.undispatchable()`'s category was left as `Permanent` with a comment
  saying why (`EffectTermsSource.java:179–194`); ruled since, it becomes `Unknown` in its own step
  (§4d finding 1), which also rewrites the comment there, the one in `EffectDispatcher.giveUp`
  (`:496–497`) and the one in `EffectTerms.failed`'s javadoc (`:65–67`), and flips
  `EffectTermsSourceTest.undispatchableIsAPermanentFailureUntoldFromTheUnknownCase` (`:201`).
- `agent/Outstanding.java`, `core/AgentState.java` — DONE in `62fe03a2`: `since`, populated by
  `opening` and `running`, with the two `AgentStateTest` assertions.
- `core/AgentEventStore.java`, `store/JdbcAgentEventStore.java`, `direct/InMemoryAgentEventStore.java`
  — DONE in `62fe03a2`: `Instant writtenAt(AgentId, Seq)`, the JDBC `SELECT written_at` by primary
  key, the in-memory store's `Clock` stamp, each with its test.
- `direct/DirectHarnessFactoryConfig.java` — `locks(Locks)` (62–66) stays; its javadoc names
  `InMemoryLocks` by the old package and follows it to `engine.store`.
- `direct/InMemoryLocks.java`, `direct/InMemoryLocksTest.java` — to `store/` in the restructure,
  destriped in the lock step.
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

**`nessy-memory`.** `HeadSummarizer.java:216` and `EpisodeSummarizer.java:181` — a `LockKind` and
the `agentType` each already holds reach the call; `Config.locks(Locks)` keeps its shape.
`HeadSummarizerTest.java:127`, `HeadSummarizerFoldTest.java:89`, `EpisodeSummarizerTest.java:124`
— the constructor. `HeadSummarizerTranscriptTest.java:105–111` — the anonymous `Locks`. Both poms
name the module (`:55`).

**`nessy-spring-boot/autoconfigure`.** `DirectHarnessAutoConfiguration.java` — takes the
`ObservationRegistry` bean the queued auto-configuration already takes
(`QueuedHarnessAutoConfiguration.java:73`) and passes it as `.observations(observations)` (§14
Q3; the handlers step). Line 96 — `locks.getIfAvailable(InMemoryLocks::new)` becomes a
`JdbcRowLocks` over the `DataSource` the factory is already conditional on and the container's
`PlatformTransactionManager` (§8a, ruled); an application's own `Locks` bean still wins. The
autoconfiguration record's "Locks default to `JdbcLeases`" paragraph is overtaken: the default is
a row lock, and no TTL is involved. The two empty `spring/boot/lease/` directories (main and test,
created 2026-09-25 07:16) were presumably for that default. `QueuedHarnessAutoConfiguration.java`
takes the same manager for the same reason. `pom.xml:169–171` — `nessy-lease` STAYS optional:
`JdbcRowLocks` is in the engine (§8a), so neither door needs the lease module, and only an
application that summarises adds it. (The sixth revision said the opposite; it is reversed.) After
the module split (§13b) this pom declares `nessy-engine-direct` and `nessy-engine-queued` optional
too.

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

### 13a. Execution order

Written so that every step leaves the reactor compiling and green, and no test asserts a behaviour
that no longer exists without its replacement landing in the same commit. Each step is one
`clean verify` and one commit; `spotless:apply license:format` before every push.

**Execution state (2026-09-26).** Five things have landed, each with a green full-reactor `clean
verify`; one is in flight; eight remain. The order below is the real one, and each entry says why
it sits where it does. Earlier revisions numbered the remaining work 5–8; those numbers are gone,
because two steps were inserted and one was split, and a reader holding an old number would land
on the wrong step.

**Landed.**

- **DONE `e5bde878` — Provider transport timeouts** (§5). `nessy-inference` only, plus the three
  starter auto-configurations and `TransportTimeouts` (six minutes). Fixed the hung-provider bug on
  its own. First because it stands entirely alone.
- **DONE `90a0fde8` — `EffectTermsSource` and `EffectOutcomes` lifted out of the handlers** (§13,
  `effect/`). Pure refactor: the source built by `DefaultQueuedHarnessFactory` and handed to the
  three handlers, `EffectOutcomes.command` lifted out of `DefaultQueuedHarness`,
  `EffectTermsSourceTest` added. `InferenceHandler.undispatchable()`'s category was deliberately
  NOT changed (the question was not yet answered) and the comment at
  `EffectTermsSource.java:179–184` says so.
- **DONE `62fe03a2` — `Outstanding.since` and `AgentEventStore.writtenAt`.** Additive, each with
  its test (`AgentStateTest`; `JdbcAgentEventStoreTest` against Postgres;
  `InMemoryAgentEventStoreTest` for the stamp). Unread until the recovery step.
- **DONE `8575a90a` — The direct door enforces its deadlines in-process** (§4b–§4c). `Clock` on
  the factory config, the inert setters store, `within` around `perform`, a throwing provider lands
  as `terms.failed`. Landed with ONE factory-level virtual-thread executor rather than one per
  harness; an earlier cut's registry of every harness the factory ever made was rejected and
  removed; `DirectHarnessFactory`'s "Nothing here is closeable" javadoc was corrected rather than
  left false. Three deadline tests with a stepped `Clock`, plus
  `inference_retry_policy_is_stored_but_not_honoured`. Two things the fifth revision listed for
  this step did NOT land in it and are carried to the handlers step by design: the `Deferred` arm
  still delivers a `Denied` (the arm is deleted rather than fixed, §4f item 5), and the
  `Thinking`/narration shape is untouched. Still under the whole-turn `tryWithLock`.
- **DONE `5be127c2` — Observation → input, and the queued door's `O` → `I`.** Adjacent to this
  record's plan, not part of it, and noted because the record's own vocabulary changed under it:
  "observation" was the engine's old word for what a caller hands an agent, and the type parameter
  on `QueuedHarness`, `InputRenderer`, `Backlog`, `BacklogItem`, `BacklogPolicy`, `Pull` and
  `JdbcBacklog` was still `O` — on `QueuedHarness` naming the *input*, which a reader arriving from
  the direct door, whose `ask` returns an output, would read exactly backwards.
  `Block.ObservationContent` is `Block.InputContent`, `inference.turn.Observation` is
  `inference.turn.Input`, and the accessors that carried the word (`BacklogItem.input`,
  `StartTurn.input`, `TurnStarted.input`, `Turn.input`) followed. Nothing stored or on the wire
  changed shape. The Micrometer sense of "observation" — every `Observed*.wrap`, the registry, the
  `nessy.observe` span name — is untouched and is the sense this record uses in §4f and §14.

**In flight.**

- **`DirectHarness<I, O>` — a single `ask`, the output type bound at creation.** James's decision;
  the three `ask` overloads on `DirectHarness` collapse to one, `DirectHarnessFactory` binds `O`
  when it creates a harness, and `Repl`, `ReplLoop`, `FakeHarness`, chat-web's configuration and
  controller, and the three direct-door tests follow (twelve files in the working tree as this
  revision is written). The sixth revision established that this is a **prerequisite** for the
  handlers step, not an adjacent change: `AgentEffect.Infer` is empty and `InferenceInvocation` is
  `(agentType, agentId, options)`, so a per-call `OutputSchema` cannot reach a handler, and putting
  one on `Infer` would change the payload of every queued effect row for a door that never uses it
  (§4g). With `O` bound at creation the schema is a property of the harness, and
  `DefaultInferenceService` takes it at construction as an internal field. A reader of the handlers
  step should expect the door's signature to have changed under it.

**Remaining, in order.**

1. **NEXT — the inference `undispatchable()` blob becomes `Failure.Unknown`** (§4d finding 1,
   ruled). `EffectTermsSource`'s inference terms return
   `InferenceFailed(Unknown("the inference did not complete before its deadline; whether it ran is
   not known"))`; the deliberate-`Permanent` comment at `EffectTermsSource.java:179–184` goes; the
   two comments that describe the false blob — `EffectDispatcher.giveUp` (`:496–497`) and
   `EffectTerms.failed`'s javadoc (`:65–67`) — are rewritten, because after this commit they
   describe a bug that no longer exists; and
   `EffectTermsSourceTest.undispatchableIsAPermanentFailureUntoldFromTheUnknownCase` (`:201`) is
   renamed and asserts `Unknown`. One small commit. It sits here, before anything that delivers the
   blob from the direct door, so recovery (step 6) lands against the honest one and the false
   `Permanent` is never appended to a direct-door stream. Changes what the queued door stores on
   every inference row from now on; James: the live databases that would care do not exist.
2. **The package restructure.** James: "right now, engine is kind of a mess. It has all the queued
   stuff in the 'engine.harness' package. Perhaps we need engine.harness and engine.harness.queued
   and engine.harness.direct." Measured, and the contents are what he said:

   | today | files | after |
   |---|---|---|
   | `engine.harness` | `DefaultQueuedHarness`, `DefaultQueuedHarnessConfig`, `DefaultQueuedHarnessFactory`, `QueuedHarnessFactoryConfig` — four, ALL queued | `engine.harness.queued` |
   | `engine.direct` | `DefaultDirectHarness`, `DefaultDirectHarnessConfig`, `DefaultDirectHarnessFactory`, `DirectHarnessFactoryConfig` — the four direct files | `engine.harness.direct` |
   | `engine.direct` | `InMemoryAgentEventStore`, `InMemoryLocks`, `InMemoryPayloads` — three, none direct-specific | `engine.store` |

   The ride-along is the three `InMemory*` classes. They implement `AgentEventStore`, `PayloadStore`
   and `Locks` respectively (`InMemoryAgentEventStore.java:40`, `InMemoryPayloads.java:34`,
   `InMemoryLocks.java:42`), nothing about them is the direct door's, and James has already ruled
   that the in-memory stores serve either door; so they go beside their JDBC siblings in
   `engine.store`, and `engine.harness.direct` is left holding just the door. The test tree moves
   the same way (`harness/` holds seven queued tests, `direct/` holds three direct tests plus
   `InMemoryAgentEventStoreTest` and `InMemoryLocksTest`, which follow their classes to `store/`).

   **`engine.harness` itself starts EMPTY**, and the record says so rather than filling it.
   `InputRenderer` and `HarnessConfig` — the two things a reader would expect there — are in
   `nessy-api` (`nessy-api/.../InputRenderer.java`, `HarnessConfig.java`), and everything the two
   doors share engine-side already has a well-named home: `core`, `store`, `effect`, `tool`,
   `history`, `inference`, `narration`, `observability`, `trace`, `schema`. It is an honest
   namespace with nothing in it yet, not a home for orphans; the first class to land there should
   be one that both doors need and none of those packages fits.

   **Blast radius: five modules outside the engine import these packages** (measured by
   `grep -rl 'org\.jwcarman\.nessy\.engine\.(harness|direct)\.'`): `nessy-console` (`Repl`),
   `nessy-examples/chat-cli` (`Chat`), `nessy-memory/summarizing` and `nessy-memory/episodic`
   (tests only, constructing `QueuedHarnessFactoryConfig`), and `nessy-spring-boot/autoconfigure`
   (both auto-configurations and `NessyAutoConfigurationTest`). chat-web and watchman import only
   `engine.store.TurnHistories` and do not move. It is a breaking change for anyone naming
   `DefaultQueuedHarnessFactory` or `DefaultDirectHarnessFactory` by package, which James accepts
   ("Nobody except me is using this"). No behaviour changes and every test passes unchanged; that
   is the commit's whole assertion.

   **Why it sits before the handlers step.** That step deletes roughly 190 lines from
   `DefaultDirectHarness` (§4g). Doing the move first makes that a deletion in a settled location
   rather than a move and a rewrite at once, so the review diff of the handlers step is the
   deletion and nothing else. It also sits after `DirectHarness<I, O>` for the same reason in the
   other direction: that change is mid-flight in these exact files, and a package move under it
   would make one of the two diffs unreadable.

   **One question the measurement raises, not decided here** (§14 Q2): two queued-only classes
   live in `effect/` — `EffectDispatcher` and `AgentEffectCallback` (used by `DefaultQueuedHarness`
   and by `tool/DefaultReplies`, nothing else) — and `DefaultReplies` in `tool/` is bound to
   `EffectStore` and that callback. James's sentence names `engine.harness`; it does not say
   whether the outbox machinery follows the queued door into `engine.harness.queued` now, or
   stays in `effect/` and `tool/` until the module split (§13b) forces the question. The
   restructure is cheaper if it answers this at the same time; the record asks rather than
   assumes.
3. **The direct door calls `EffectHandlers`** (§4f–§4g; the sixth revision's 7a). The direct
   factory builds `EventStreamToolCalls`, the wrapped `ContextAssembler`, the wrapped provider
   inside a `DefaultInferenceService` that also takes the harness's `OutputSchema`, the three
   handlers and one `EffectHandlers` per harness, with `ReplyTokens.ephemeral()` once per factory;
   `DirectHarnessFactoryConfig.observations(ObservationRegistry)` arrives (§14 Q3) and the starter
   passes its registry; `DefaultDirectHarnessConfig` wraps tools and approvers as it binds them.
   `perform` becomes the §4g shape; `infer`, `approve`, `callTool` and their private helpers are
   deleted. Still under the whole-turn `tryWithLock`, so every existing test except the three
   named in §4g passes unchanged, which is the proof that the handlers do what the copies did.
   `a_deferred_approval_is_denied` becomes `a_deferred_approval_is_failed` and asserts
   `ToolFailed`; the unbound-tool and throwing-approver arms get a test each asserting the
   handlers' wording. One new test: the direct door produces the same spans as the queued door for
   one inference and one tool call against a `SimpleMeterRegistry`-backed `ObservationRegistry` —
   the first GenAI telemetry the direct door has ever had. **Prerequisites:** `DirectHarness<I, O>`
   (above) and the restructure (step 2). After this step `effect/` is shared by both doors, which
   is the fact §13b turns on.
4. **The lock SPI** (§7–§9; the sixth revision's step 5): `LockKind`, the widened verbs,
   `Identifiers` in `nessy-api`, `JdbcRowLocks` in the engine (§8a; package per §14 Q1) with its
   `nessy_lock` table (no `holder`, no `expires_at`) and its `PlatformTransactionManager`
   constructor, `JdbcLeases` reshaped with the two lease-observability signals of §8d (the
   `RELEASE` row count checked and warned on; `takeovers` incremented on the conflict path and
   returned) and the dead `.filter(holder::equals)` removed, `InMemoryLocks` destriped where it
   now lives, and the engine's unused `nessy-lease` dependency dropped. The memory modules, the
   starter and chat-web re-import in the same commit so the reactor compiles; the two summarisers
   change their call and nothing else. `JdbcLeasesTest` and `InMemoryLocksTest` are rewritten
   here; the stripe tests go; a test that a release after takeover warns, and one that a takeover
   is reported, are added. No module is renamed (§12). Sits after the handlers step only because
   the handlers step is the bigger risk and wants the door in a settled shape before its exclusion
   changes; the SPI itself depends on nothing above it.
5. **The queued door onto the SPI** (§6, §11a; the sixth revision's step 6): `JdbcRowLocks`
   replaces `agents.lock` and the minted `TransactionTemplate`; `JdbcAgents.lock` becomes
   `ensure`; `tell` gains `terminated()`. The queued tests should pass unchanged — that is the
   assertion that the shape is the same. Depends on step 4.
6. **The direct door locks per step, with lazy recovery** (§3, §4d; the sixth revision's 7b).
   `under` and the whole-turn lock go; the phase decides `Busy`; `put` moves under the first lock;
   recovery is that step's preamble. `a_busy_scope_is_refused` is rewritten in this commit (it
   asserts the lock-stub refusal that no longer exists); `only_one_of_many_callers_runs` must pass
   unchanged. Tests added here, one per row of the §4a table plus the three paths that matter
   most:
   - **the stale answer**: a turn whose inference outlives its deadline is recovered by a second
     caller, and when the first turn's `CompleteInference` arrives it is ignored by phase, the
     stream shows `InferenceFailed` then the second turn, and the first caller reads `Failed`;
   - **the expired approval**: an `AWAITING_APPROVAL` call past `ApproverConfig.timeout` is
     discharged `ToolFailed` with the `AskingTerms` blob, the `Infer` the fold then emits is
     discharged `InferenceFailed(Unknown)` without a model call (the provider stub counts zero), and
     the agent is `Idle` before the recovering caller's own `TurnStarted`; and its twin, a call
     *inside* its deadline is left alone and the caller told `Busy`;
   - **cross-door exclusion** (§3): against Postgres, a direct turn in `Inferring` and a queued
     `tell` over the same agent — the `tell` waits for the step and coalesces into the backlog
     rather than starting a second turn, and a direct `ask` during a queued turn reads the phase
     and is told `Busy`. This is the one test that needs both factories over one `DataSource`.

   Depends on steps 3 and 4: it locks per step around one `perform` call rather than three arms,
   and it takes the row lock through the SPI.
7. **Docs and the two earlier records** (§13, "Docs"). Describing what is, once the behaviour is
   settled and before the module split moves the artifact names the docs cite.
8. **LAST — the module split** (§13b). `nessy-engine`, `nessy-engine-direct`,
   `nessy-engine-queued`. A pom exercise, because step 2 already put every class in the package it
   will keep; last, because it is only correct after step 3, for the reason §13b gives.

Dependencies among what remains, in one line: 1 stands alone; 2 waits for `DirectHarness<I, O>`;
3 needs 2 and `DirectHarness<I, O>`; 4 stands alone but is scheduled after 3; 5 and 6 need 4, and 6
needs 3; 7 follows 6; 8 needs 2 and 3.

### 13b. The module split, and why it is last

James: "at some point, I thought we were going to split these two harness types out into their own
modules and we'd have engine core, engine direct, and engine queued." The shape:

| module | holds |
|---|---|
| `nessy-engine` | the core: the fold (`core`, `agent`), the stores (`store`, including the three in-memory ones and `JdbcRowLocks`), `tool`, `history`, `inference`, **the effect handlers** (`EffectHandler`, `EffectHandlers`, `EffectTerms`, `EffectTermsSource`, `EffectOutcomes`, the three handlers), `observability`, `trace`, `narration`, `schema`, `embedding` |
| `nessy-engine-direct` | `engine.harness.direct` — the four classes of the door, and nothing else |
| `nessy-engine-queued` | `engine.harness.queued` — the four harness classes — plus the outbox: `EffectDispatcher`, `AgentEffectCallback`, `EffectStore`, `JdbcEffectStore`, `Attempt`, `JdbcBacklog`, `JdbcAgents`, the `backlog` package, and `DefaultReplies` (which is bound to `EffectStore` and the callback, `DefaultReplies.java:58`) |

**The key point, and the reason it is last.** The handlers step (§13a step 3) moves `effect/` from
queued-only to shared. The first revision measured `effect/` as the queued door's — every class in
it was constructed by `DefaultQueuedHarnessFactory` and by nothing else — and that was true. Once
the direct door performs through `EffectHandlers.perform`, the handlers and their terms belong to
neither door: they are the thing that executes what the fold emits, and both doors call it.
Splitting modules before that step would put `effect/` in the queued module and immediately have
to pull it back out, and every earlier attempt to schedule the split ran into exactly this — the
line between "core" and "queued" ran through the middle of `effect/`, so the split was deferred
until somebody moved the line. §4f is where the line moved. That is why the split kept being
deferred, and why it is now tractable: after step 3 there is a package-level answer to "which
module does this class belong to" for every class in the engine, and the only remaining seam is
the outbox — `EffectDispatcher` and `AgentEffectCallback` in `effect/`, `DefaultReplies` in `tool/`
— which is §14 Q2 and is answered by the restructure or by this step, whichever gets there first.

**Why the restructure is the prerequisite, not wasted work on the way.** A package need not be
renamed when it changes module. If the packages are right before the split — `engine.harness.direct`
holds the direct door and only the direct door, `engine.harness.queued` the queued one, `engine.store`
what both use — then the split is a pom exercise: three `<module>` entries, two new `pom.xml`
files, a dependency each way on `nessy-engine`, and the autoconfigure and BOM poms. If the packages
are wrong before the split, it is a pom exercise plus a rename across the same five modules the
restructure touches (§13a step 2) done at the same time, and the two diffs would be one diff.

**The payoff, which closes a thread from the very start of this work: `@ConditionalOnClass`
becomes meaningful.** Both `@ConditionalOnClass` annotations were deleted in `a52f66e0` — "a
condition that cannot be false is not a guard" — because `nessy-engine` is a non-optional compile
dependency of `nessy-spring-boot-autoconfigure`, so a condition naming either factory could never
evaluate false; a comment in each auto-configuration says so, so that nobody adds one back
(`DirectHarnessAutoConfiguration.java:65–68`). With three modules the autoconfigure pom declares
`nessy-engine-direct` and `nessy-engine-queued` `<optional>true</optional>` — as it already
declares `nessy-lease` and the embedding modules — the two annotations return and this time they
guard something, and there are two starters: add the jar for the door you want and get that door.
The default becomes *right* instead of being "both, and exclude one by name", which is what `Repl`
has to do today (`Repl.java:150–157`, from `93594541`: "Nothing about the classpath can tell those
two doors apart — both factories live in the engine — so an application that wants one says
which"). Honestly stated: name-exclusion already WORKS, and `93594541` split the auto-configurations
precisely so that it would. What the module split buys is ergonomics and a correct default — a
console that never sees a `DataSource` question because the queued door is not on its classpath —
not a capability that is missing today.

**The direct module is small, and that is acceptable rather than a thing to apologise for.** It
ends up at roughly four classes against a queued module holding the outbox, the dispatcher, the
backlog and the reply desk. The two doors are genuinely asymmetric — one is a method call on the
caller's thread with in-memory stores as a legitimate configuration, the other is durable
machinery with a watchdog and a table per concern — and the module sizes reflect that asymmetry
rather than a failure to divide the engine evenly. A reader who expects two modules of similar
weight has misread what a door is.

**What is NOT decided by this section.** The outbox's package (§14 Q2); whether `DefaultReplies`
and the reply-token machinery in `tool/` are queued-only in full or only in part; and the two
starters' artifact names. None of it blocks steps 1–7.

---

## 14. Open questions for James

Closed since the sixth revision, and no longer asked: the inference `undispatchable()` blob
becomes `Unknown` (§4d finding 1; now §13a step 1); `JdbcRowLocks` takes a
`PlatformTransactionManager` and chooses `REQUIRED` itself, with a `DataSource`-only constructor
for a non-Spring caller (§8a); `JdbcRowLocks` lives in `nessy-engine`, the engine's unused
`nessy-lease` dependency goes, and the module stays optional (§8a, §12); and `nessy-lease` keeps
its name, module and package — the rename is retired, not re-asked (§12). Closed earlier and still
closed: the guard helper is `Identifiers` in `nessy-api` (§7); `Clock`, `writtenAt`,
`Outstanding.since` and the stored-but-not-honoured `retryPolicy` all landed; `nessy_lock` is its
own table (§8a); the transport timeouts and the six-minute margin (§5). Retired rather than
answered by §4f–§4g: the deferred-approval divergence and the direct door's observability gap.

Two are new in this revision (Q1, Q2), one was forced by the sixth (Q3), and four have stood since
the fifth without an answer and are carried rather than dropped (Q4–Q7). None blocks §13a step 1.

1. **Which package for `JdbcRowLocks`** (§8a). `engine.store`, beside `JdbcAgentEventStore` and
   the three in-memory stores the restructure moves there — one package for "everything that
   talks to the engine's database", with `InMemoryLocks` and `JdbcRowLocks` side by side as the
   other store pairs are — or `engine.lock` of its own, on the ground that a lock is not a store.
   The record leans `engine.store` for the pairing and asks.
2. **Where the outbox goes in the restructure** (§13a step 2, §13b). `EffectDispatcher` and
   `AgentEffectCallback` are queued-only and live in `effect/`; `DefaultReplies` is queued-only
   and lives in `tool/`. Your sentence named `engine.harness`; it did not say whether these follow
   the queued door into `engine.harness.queued` in the same commit, or wait for the module split
   to force it. Moving them now makes the split a pure pom exercise; leaving them makes the
   restructure smaller. Which?
3. **`DirectHarnessFactoryConfig.observations(ObservationRegistry)`** — forced rather than chosen.
   The direct door observes nothing today (§4f item 1); calling the real handlers makes it observe
   everything, but only if the factory can be handed a registry, and its public config has no
   method for one. Same shape and default as `QueuedHarnessFactoryConfig.observations` (`NOOP`,
   "nothing to report"), and the starter passes its registry bean through as the queued
   auto-configuration already does. Yes?
4. **The direct door as designed in §3–§4, including §4g**: per-step `withLock`, phase decides
   `Busy`, effects performed through `EffectHandlers` with one `Deferred` arm that fails the call,
   `put` under the first lock, no outer lease. Every piece of it was shaped by a ruling and the
   steps are ordered on that basis, but the whole has not had an explicit yes. Yes?
5. **`Identity`.** Four parameters on both verbs, or promote `(AgentType, AgentId)` from
   `engine.observability` into `nessy-api` and take three? A vocabulary decision, so it is asked.
6. **`withLock` on a lease** polls at a fixed interval in the SPI's default (§7), for the sake of
   one total interface; nothing in the tree calls it. Acceptable?
7. **Scope** (§11). Lock and transaction now; the other four stores as a follow-on. Or widen?
