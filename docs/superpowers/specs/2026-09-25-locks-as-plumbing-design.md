# Locks as plumbing: one SPI, two implementations, and a direct door that locks for milliseconds

**Status: LANDED.** The SPI, both implementations, the queued door on the SPI, the handlers step
and the direct door's per-step locking with lazy recovery are all committed on branch `fold-swap`
(each with its SHA in §13a). The lock SPI has since been split in two — `Locks` for exclusion a
transaction holds, `Leases` for exclusion a row with an expiry approximates (`bdd0906a`, §7) — and
this record describes the tree after that split. Re-verified against the tree on 2026-09-26 after
`bdd0906a`.

**How to read the measurements.** Line numbers in this record are those of the tree at the revision
each section says it measured (`235d812e` for §1–§2, `8575a90a` for §4b–§4g), kept as citations of
evidence rather than as pointers into today's files; no line in `DefaultDirectHarness` is cited as
current. Type and package names in the reasoning sections are the ones the tree had when the
reasoning was done; the current names are:

| then | now |
|---|---|
| `engine.direct` | `engine.harness.direct` |
| `engine.harness` | `engine.harness.queued` |
| `engine.store.Jdbc*`, `engine.core.AgentEventStore` | `engine.jdbc.Jdbc*`; `engine.store` holds only backend-neutral types (`Outbox`, `Attempt`, `StorageCodec`, the history interfaces) |
| `engine.direct.InMemory*` | `engine.inmemory.InMemory*` (with `ListBacklog`) |
| `AgentEventStore`, `PayloadStore` | `AgentEvents` (`backend.event`), `Payloads` (`backend.payload`), both in `nessy-backend-spi` |
| `JdbcAgentEventStore`, `JdbcPayloadStore`, `JdbcEffectStore` | `JdbcAgentEvents`, `JdbcPayloads`, `JdbcEffects` |
| `EffectStore` (the per-agent-type wrapper) | `Outbox` |
| `spi.lock.Locks` | `backend.lock.Locks` and `backend.lock.LockKind`, in `nessy-backend-spi` |
| `Locks.tryWithLock`, `Locks.Attempt`, the summarisers' `LOCK_KIND` | `backend.lease.Leases.tryWithLease`, `backend.lease.Attempt`, `backend.lease.LeaseKind`, the summarisers' `LEASE_KIND` (`bdd0906a`); `Locks` has one verb, `withLock` |
| `nessy-spi` (locks, payloads, narration, schemas) | `nessy-spi` holds ONLY `spi.store.Schemas`; narration is in `nessy-api` |

**Before trusting a green build.** `nessy.excludedGroups` defaults to `live,container` (root
`pom.xml`), and the seven tests tagged `@Tag("container")` include every test in `engine.jdbc`
(`JdbcRowLocksTest`, `JdbcAgentEventsTest`, `JdbcPayloadsTest`, `JdbcBacklogTest`) and
`DurableDirectHarnessTest`. A plain `./mvnw clean verify` skips all of them. Confirmed:
`./mvnw -q -pl :nessy-engine -am clean verify -Dnessy.excludedGroups=live` exits 0 with the database
tests actually running, and a plain `clean verify` runs none of them. This mattered: under the
default, `DurableDirectHarnessTest.termination_is_durable` had been failing invisibly — it asserted
a thrown `IllegalStateException` where the direct door deliberately answers
`Outcome.Refused("terminated")` — and is now fixed. **The gate for any change touching JDBC is
`-Dnessy.excludedGroups=live`.** The tag is applied unevenly: `EngineFixture` (every queued-door
and effect test), `JdbcLeasesTest`, the memory modules' `Calls` fixtures and `HeadSummarizerTest`
all start a Postgres container and carry no tag, so they run in a plain build while the five above
do not. Which tests a plain build skips is a fact about the tag, not about whether a test needs
Docker.

**What this record owns, and what the adjacent one owns.** This record is authoritative for the
lock and lease SPIs (§7), `JdbcRowLocks` and `JdbcLeases` (§8–§9), the direct door's per-step locking and
deadline-based recovery (§3–§4), the provider transport timeouts (§5), and the execution order of
everything that remains (§13a). `2026-09-26-backends-design.md` is authoritative for the backend
interfaces, `Agents`/`Coalescing`/`Backlog`, the `Effects` extraction, the factory cutover, the
module split with its DDL split, the TCK, and the retirement of `nessy-spi`; where this record once
sketched those (§11, §13b) it now points there.

**Provenance, kept for the rulings.** The first draft unified the two doors' exclusion behind one
SPI keyed by an opaque string. James's pushback ("Why do we fucking need JdbcLeases?"; "We don't
need it to wrap the entire run turn do we?") produced the finding in §2. Four rulings shaped the
rest: "I don't want the queued door to do direct JDBC at all"; "we really have the idea of locking
around an agent type/agent id/kind combo"; "The transaction manager can be hidden too"; and, for the
direct door, "Can't direct use a transactional lock to read the agent state, perform the command,
save the events. Then dispatch the effects outside of that transaction. Anyone coming in to try to
muck with the same agent state will get it in the wrong state phase and be declined." Recovery was
settled by "let's use these timeout values as our detection mechanism" and "we'd need to use the
timeouts for the effect handler and use a virtual thread with a CompletableFuture on which we wait";
where deadlines live by "We have that get terms stuff on the queued side"; and the handlers step by
"it really feels like we have a need for 'a thing that can execute the effects emitted by the fold'
and that would be a simple method call". §10 records what each ruling closed.

Date: 2026-09-25. Continues `2026-09-25-one-core-two-doors-design.md`, whose §5 table records that
the two doors exclude differently; this record makes them exclude the same way. It overturns one
non-goal of `2026-09-25-spring-boot-autoconfiguration-design.md` §9 ("Changing `InMemoryLocks`
itself") and makes that record's "Locks default to `JdbcLeases`" ruling moot.

---

## 1. What was wrong, as measured at `235d812e`

Everything in this section describes the tree before the lock work and is kept because §2's
finding is only visible against it. **The two code snippets below quote an API that no longer
exists**: `tryWithLock` was removed from `Locks` in `bdd0906a`, and no interface in the tree has a
verb of that name; the lease's verb is `Leases.tryWithLease` (§7). Where each item stands now: the
direct door's whole-turn `tryWithLock` and its `under(...)` are gone, replaced by per-step
`withLock` (§3, `221e447c`); the queued door's own JDBC lock and minted `TransactionTemplate` are
gone (`391d536d`); the two summarisers take a lease under their own `LeaseKind` (`0e25584f`, then
`bdd0906a`); `InMemoryLocks` is destriped (`0e25584f`); and both doors take `Locks.TURN`, so they
exclude each other. chat-web declares two beans, a `JdbcRowLocks` as its `Locks` and a
`JdbcLeases` as its `Leases` (`bdd0906a`, §8a); the `Locks` bean stops being consulted when the
factory takes a backend (backends record §6).

Three places excluded over an agent, and they did not agree on what a lock is or where it comes
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
bean. The `InMemoryLocksTest` of that revision asserted the hazard rather than its absence ("at
most one stripe is taken"); the test has since been rewritten (§8c) and the line is not cited.

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
`EpisodeSummarizer.java:181` at that revision, identically:

```java
// as it was at 235d812e; the verb no longer exists on any interface
locks.tryWithLock(agentId.value().toString(), () -> summarize(agentId)).orElse("lease-refused");
```

Both keyed on the bare agent UUID, and `JdbcLeases` bound its kind at construction, so telling a
head summary from an episode summary of the same agent meant constructing two instances. chat-web
at that revision constructed two `JdbcLeases` — kind `"agent"` for the direct door
(`ChatConfiguration.java:86`) and kind `"episode"` for the episode summariser (line 131), both ten
minutes. No example wires `HeadSummarizer`.

**And the two doors do not exclude each other.** One agent reachable through both — the previous
design record's §5 says this is "correct rather than a seam that failed to close" — can have a
direct turn and a queued turn started over it at once, because one holds a lease row and the
other a `nessy_agent` row and neither knows the other exists.

---

## 2. The finding: the lock is held across inference for a guard the fold already has

The direct door's lock exists to stop two callers running a turn over one agent at once. Two
things already do that job, and neither needs a lock held for the length of a model call.

**The append is guarded.** `AgentEvents.append(agent, events, expectedLast)` (then
`engine/core/AgentEventStore.java:53`, now `backend.event.AgentEvents` in `nessy-backend-spi`) is
"Appends, if nothing else has": `InMemoryAgentEvents` is `synchronized` and throws `Conflict` on a
stale `expectedLast`; `JdbcAgentEvents.append` throws `Conflict` when the `(agent_id, seq)` primary key is
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

## 3. The direct door: short locked steps, effects performed outside (landed: `221e447c`)

This is what `DefaultDirectHarness` does. The whole-turn lock and `under(...)` are gone. `ask` renders the input outside any
lock, then takes ONE locked step — `beginTurn`, under `locks.withLock(Locks.TURN, agentType,
agent, ...)` — that reconstitutes the agent from `sinceLastTurnStarted`, checks the phase, runs
lazy recovery (§4d), and only once the agent is genuinely `Idle` writes the payload and appends
`TurnStarted`. Then each effect the fold emitted is performed with NO lock held, through
`EffectHandlers.perform` inside `within` (§4c, §4g), and the command its outcome becomes is
applied in its own fresh locked step: reconstitute, execute, append, commit, release. Every step
re-reads the agent under its own lock; no state is carried across a release. `terminate` is the
same one step with `Terminate`.

The phase check is the guard: an agent that reconstitutes `Terminal` answers
`Outcome.Refused("terminated")`, and one that reconstitutes anything but `Idle` — after recovery
has had its say — answers `Outcome.Busy`, a turn is in flight. Because the `put` is inside the
lock and after the check, a declined caller writes nothing, and the earlier draft's
orphaned-payload cost is fixed rather than accepted.

**One subtlety worth recording.** With short locks two turns can be open over one agent at once
— a recovered original coming back for its completion step, a new caller already past `beginTurn`
— and the old `outcome`, which took the latest terminal event in the stream, would hand a caller
somebody else's answer. Under the whole-turn lock that could never happen, which is why the old
`outcome` could afford to be lazy about it. The first fix, a `TurnId` filter on the final read,
did not hold: the fold stamped a completion's event with whatever turn it had reconstituted, so
the event being filtered already carried the reader's own turn and the filter matched the very
thing it was meant to exclude. What landed (`221e447c`) puts the turn into the grammar instead:
`CompleteInference`, `CompleteApproval` and `CompleteToolCall` name the turn they answer, the fold
ignores a completion whose turn is not its own (`AgentState`'s `when !done.turn().equals(turn)`
arms), and `AgentEffect`'s three arms carry the turn that emitted them, which is how it survives
the queued door's round trip through the outbox without a column of its own.

The direct door thereby uses the same mechanism as the queued door — `JdbcRowLocks` through the
SPI, one short transaction per step — and the lease leaves it. That has two consequences the
earlier drafts listed as absent or unfixed:

- **Cross-door exclusion falls out for free.** Both doors take the same row lock on the same
  `(kind, agent_type, agent_id)`, and both check the same phase under it. An agent reachable
  through both doors is genuinely excluded, and the previous record's "the two doors still do not
  exclude each other" paragraph is overtaken.
- **`nessy-lease`'s only consumers become the two summarisers.** They are: `HeadSummarizer` and
  `EpisodeSummarizer` take a `Leases`, and nothing else in `src/main` anywhere does. chat-web's
  one ten-minute `JdbcLeases` bean, which had served both as the direct door's `Locks.TURN` and
  the episode summariser's kind, is now two beans — `agentLocks`, a `JdbcRowLocks`, and
  `agentLeases`, a `JdbcLeases` with the summariser's kind alone (`bdd0906a`; §8a says why that
  split was a bug fix and not tidying).

### 3a. `withLock` only, because the phase does the work

James: "Even if someone gets through the try lock, they could still fail by finding it in an
invalid state phase ... So what is the likelihood that the try lock actually hits and rejects
someone?" The arithmetic: a hold is now one read, one append and one commit — a few milliseconds —
and a turn lasts five to thirty seconds. A lock refusal can only happen if a second caller
attempts acquisition inside that window, on the order of 3 ms in 10,000, so roughly 0.03% of
collisions. The phase check catches the rest.

So the direct door uses `withLock`, which is now the only verb a lock has. It waits — a wait
bounded by §3b to milliseconds — and then the phase decides. The door has no `Attempt.Ignored` arm
to handle, `Outcome.Busy` has one meaning ("a turn is in flight, read under the lock"), and there
is no opening-step-tries / later-step-waits split to explain. Giving up on contention is what the
summarisers want, and that is a lease's verb, `Leases.tryWithLease`, on a different interface (§6,
§7); for a while `Locks` carried a `tryWithLock` alongside `withLock` for their sake, and once they
became lease callers it had no production caller left and was removed (`bdd0906a`).

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

The natural instinct is to keep a whole-turn try-lock around the outside with the
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
longer escapes the turn (§4d). The handlers step (§4g; landed `534602bb`) keeps `within` exactly as committed and changes only
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

**In-process compensation** is the common case, and §4c is most of it (landed: `8575a90a`,
then `534602bb`). Before `8575a90a` the door's own `infer` handed `provider.infer(...)` to a switch
without a `try`, so a provider that *threw* — a connection reset, a 500 the adapter did not
translate — escaped `runTurn` with the agent left `Inferring`. Inside `within`, a throw from the work surfaces as an `ExecutionException` from `get` and is
delivered as the same `Failed` outcome, through the same locked step. The agent returns to `Idle`,
the caller gets `Outcome.Failed` rather than an exception, and the next caller is not told `Busy`.

**Lazy recovery at the door** (landed with §3, `221e447c`) is for the
dead process — the one case no in-process layer can reach. The next caller is already under the
lock in `beginTurn` reading the phase; it also reads *when the thing being waited on was started*
(`AgentEvents.writtenAt`), compares that with the deadline that applies
(`EffectHandlers.termsFor(effect).timeout()`), and if the deadline has passed, appends the row's
recovery outcome from §4a in the same step and carries on with its own turn. No reaper, no
background thread, no scan: recovery happens when somebody cares and costs nothing when nothing is
wrong. The step, for each thing the reconstituted state is waiting on:

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
it. Recovery discharges it with the inference's `undispatchable()` in the same locked step — and
without a deadline check, which could never find it overdue, since its seq was written
microseconds earlier; the first cut did check, returned `Busy` having already mutated the stream,
and left the agent `Inferring` with no performer until the timeout elapsed (`221e447c`). The
deadline check still governs the phase recovery finds first, which is what tells a live turn
inside its deadline from an abandoned one. The agent is `Idle` before the new caller's own
`StartTurn` is executed. All of it is fold and append,
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

Both halves of this subsection are in the tree: `AgentEvents.writtenAt` (now on the interface in
`nessy-backend-spi`) with its JDBC and in-memory implementations, and `Outstanding(action, phase,
since)` populated by `AgentState`. The design text is kept as the reasoning; the recovery in §4d is
the first reader of both.

**The store read: `Instant writtenAt(AgentId agent, Seq seq)` on `AgentEvents`.**
`nessy_agent_event.written_at` is `TIMESTAMPTZ NOT NULL DEFAULT now()`, so every clock start was
already on disk with no new column. But nothing read it: `AgentEvent` carries no time (its
records are `(seq, turn, ...)`), the store interface had exactly three methods — `append`,
`readFrom`, `sinceLastTurnStarted` — and the JDBC store's two queries selected `payload` only. One
method, one column, no new type. **Ruled out**: a timestamp on the event records. It would make replay depend on wall
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

### 4g. The handlers step, reshaped: the direct door calls `EffectHandlers` (landed: `534602bb`)

Landed as designed: `DefaultDirectHarness` went from 723 lines to 453 in that commit, the direct
factory builds the three handlers and one `EffectHandlers` per harness with
`ReplyTokens.ephemeral()` once per factory, `DirectHarnessFactoryConfig.observations` exists and
the starter passes its registry, `DefaultDirectHarnessConfig` wraps tools and approvers as it binds
them, and `DirectHarnessObservabilityTest` is the first GenAI telemetry the direct door has had.
The prose below is the design as it was argued, kept for the reasoning.

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
  and approvers as it binds them. That forced one public config method,
  `DirectHarnessFactoryConfig.observations(ObservationRegistry)`, with the queued config's `NOOP`
  default, which landed with the step; `DirectHarnessAutoConfiguration` passes the registry bean
  through as the queued one always has. It was forced rather than chosen: James's ruling is that a
  registry is required and NOOP means nothing to report, and the direct door could not obey that
  ruling without a way to be given one.
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

| call site | kind | mechanism | what decides | status |
|---|---|---|---|---|
| direct door (`ask`, `terminate`) | `Locks.TURN` | `Locks.withLock` over `JdbcRowLocks`, one short transaction per step | the phase, read under the lock, gives `Busy` | `221e447c` |
| queued door (`tell`, `terminate`, `deliverOutcome`) | `Locks.TURN` | `Locks.withLock` over `JdbcRowLocks`, unchanged in shape | `tell` always accepts, so it waits | `391d536d` |
| the two summarisers | `HeadSummarizer.LEASE_KIND`, `EpisodeSummarizer.LEASE_KIND` | `Leases.tryWithLease` over `JdbcLeases` | opportunistic: giving up is right | `0e25584f`, then `bdd0906a` |

`Locks.TURN` (`"nessy.agent.turn"`) is one constant on the SPI, shared by both doors on purpose:
its javadoc says "a kind named after a door instead of the work would not do that". There are no
per-door kinds. The summarisers' kinds are `LeaseKind`s, not `LockKind`s: the two rows above that
wait and the one that gives up are on two interfaces, and §7 says why that is the whole point.

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
safety. `LeaseKind` (§7) is what stops a head summary and an episode summary of the same agent
blocking each other. And the converse holds: a lock cannot serve the summarisers, because
`JdbcRowLocks.withLock` IS a transaction (§8a), so exclusion around a model call would hold a
Postgres transaction open across an inference — the exact thing this record removed from the
direct door.

---

## 7. The SPI: two interfaces, one verb each, keyed by an agent (landed: `0e25584f`, split: `bdd0906a`)

As it is in the tree, in `nessy-backend-spi`:

```java
package org.jwcarman.nessy.backend.lock;

/** The namespace a lock lives in: which activity is being excluded. */
public record LockKind(String value) { ... }   // non-null, non-blank, at most 64 characters

/** "Only one of us should do this right now, and I would rather wait than be told no." */
public interface Locks {

  LockKind TURN = new LockKind("nessy.agent.turn");   // a turn is running, whichever door started it

  /** Runs work once the agent is held, waiting for it if it must. Runs the work or throws;
   *  never reports a refusal. Waiting is the implementation's, and there is no default. */
  <T> T withLock(LockKind kind, AgentType type, AgentId agent, Supplier<T> work);

  default void withLock(LockKind kind, AgentType type, AgentId agent, Runnable work)
}
```

```java
package org.jwcarman.nessy.backend.lease;

/** The namespace a lease lives in, so a head summary and an episode summary of the same agent
 *  are two leases and not one. */
public record LeaseKind(String value) { ... }  // the same guard as LockKind, 64 characters

/** "Somebody will do this eventually; it does not matter who, it matters that it is not two of
 *  us at once." */
public interface Leases {

  /** Runs work if the lease can be taken now; Ignored if somebody else is believed to hold it.
   *  Never waits, and there is deliberately no verb that does. */
  <T> Attempt<T> tryWithLease(LeaseKind kind, AgentType type, AgentId agent, Supplier<T> work);

  default Attempt<Void> tryWithLease(LeaseKind kind, AgentType type, AgentId agent, Runnable work)
}

public sealed interface Attempt<T> { ... }     // Ran, Ignored, orElse — a lease concept, beside Leases
```

Implementations: `Locks` is `JdbcRowLocks` (`engine.jdbc`) and `InMemoryLocks` (`engine.inmemory`);
`Leases` is `JdbcLeases` (`nessy-lease`). One JDBC implementation per interface, which was the
point.

**Two interfaces, because they promise different things.** A row lock is exclusion held *by a
transaction*: released on commit, rollback or a dropped connection, and exact for as long as it is
held. A lease is a row with an expiry: it bounds how long a holder is *believed* to be working,
not how long it runs, so a holder that is merely slow cannot be told from one that died, and its
work can be taken over while still running. The first cut of this SPI (`0e25584f`) put both behind
one `Locks` with two verbs, and that forced the contract down to the intersection — which is why
the old `Locks` javadoc had to tell every caller to assume the weaker implementation. The split is
the two contracts stated on the interfaces that keep them, so the type says which guarantee a
caller is holding.

**It was a live defect, not a tidiness concern.** The direct door builds each step's transaction
out of `withLock` — `JdbcRowLocks.withLock` IS the transaction (§8a) — and
`DirectHarnessAutoConfiguration` takes an application's `Locks` bean as that turn boundary.
chat-web's `ChatConfiguration` was registering a `JdbcLeases` as that bean, so its turn lock had no
transaction and permitted takeover: the several appends `recoverToIdle` makes were not atomic, and
a slow step could be taken over by a second instance. The Opus review of the direct door had
predicted the hazard abstractly (its Finding 8); the example application turned out to be doing
it. `bdd0906a` fixed it in the same commit by splitting the bean into `agentLocks` (a
`JdbcRowLocks`) and `agentLeases` (a `JdbcLeases`), and with the interfaces apart that wiring can
no longer be expressed.

**Why `Leases` has no waiting verb.** Waiting on a lease means polling — nothing about a row with
an expiry can be blocked on the way a row lock can — and its only callers are opportunistic and
skip when refused. Conversely, a lock cannot serve those callers (§6): exclusion around a model
call would hold a Postgres transaction open across an inference.

**Why `Locks` lost `tryWithLock`.** Once the summarisers became lease callers it had zero
production callers — the only two had been `HeadSummarizer` and `EpisodeSummarizer`, and every
other call was in a test of `tryWithLock` itself. The "two verbs, for two different callers"
framing this section once carried was really one interface spanning two contracts. A lock either
runs the work or throws; refusal, and `Attempt` with it, went to the leases.

**Why the polling default was a symptom, not a cause.** The first cut's `withLock` had a default
body that polled `tryWithLock` every `POLL_INTERVAL` (20 ms). It existed only because a lease
cannot block, so a poll was the one body both implementations could satisfy — and being free to
inherit, it landed on `InMemoryLocks`, which owns a `ReentrantLock` and never needed it. Its own
javadoc admitted it was "slow, unfair (a late arrival can win)". The cost it had been imposing:
no queue, so a caller could lose repeatedly while the deadline its work is measured against kept
running — once per contended *step*, now that the direct door takes a lock per step; the Opus
review flagged that unfairness as an input to its deadline findings. The poll did not vanish by
being replaced; it was never needed once the false kinship went. Every implementation now blocks
the way its own substrate can — `FOR UPDATE` queues waiters in the database, `ReentrantLock.lock()`
queues them in the process — and `Locks.withLock` is abstract, with its javadoc saying there is
deliberately no default "that would not be a poll dressed up as a wait".

**The key is an agent, by ruling.** The first draft weighed an opaque key against
`(kind, agent type, agent id)` at length; James has now said twice that the lock is "around an
agent type/agent id/kind combo", and that settles it. No `LockKey`, no key-spelling convention, no
wrapper to curry one. His plumbing principle — storage "not bound to any agent harness or agent
type or anything" — still governs the other stores, which is the backends record's subject.
`nessy-backend-spi` depends on `nessy-api` (and on `nessy-inference-spi`, for the `Failure` an
`AgentEvent` carries); `nessy-api` depends on nothing of ours (`1a9ee75b`).

**Not a capability split.** The first cut's argument against two interfaces was against a
`WaitingLocks` sub-capability and against implementations throwing `UnsupportedOperationException`
from half a contract. The split that landed is neither: no implementation implements both, no
method is unsupported anywhere, and neither interface extends the other. What was rejected was
one family with optional verbs; what landed is two families with one verb each.

**Both doors use the same kind.** Cross-door exclusion (§3) depends on it: one `LockKind` for "a
step over this agent's state", whichever door takes it. The summarisers use their own.

**What "released" means.** `JdbcRowLocks` releases when the transaction it opened commits, after
the work returns and before `withLock` returns; a lease releases when the work returns. Either way
the caller sees the lock held for at least the work.

**The guards, as landed.** `Identifiers.require(value, what, maxLength)` in `nessy-api` checks
null, blank and length, and names what it is checking ("lock kind", "agent type") so the message
says which string is wrong. `LockKind`, `LeaseKind` and `AgentType` all bind 64, because `VARCHAR(64)` is the
column behind each (`nessy_lock.kind`, `nessy_lease.kind`, `nessy_lock.agent_type`, `nessy_agent.agent_type`). It does
not restrict to ASCII; the 256/ASCII guard the memory notes mention lives in the `pekko-out`
worktree, not on this branch. `JdbcLeases`' hand-rolled `if (kind.isBlank())` went with it.

**Four parameters is wide, and four is what landed.** `engine.observability.Identity` is exactly
`(AgentType, AgentId)`, and promoting it to `nessy-api` would make the verbs three parameters. It
was asked as a vocabulary question and not answered; the SPI landed with four, nothing has pushed
back, and it is not re-asked here. Reopening it is a vocabulary decision and needs a yes.

---

## 8. The two implementations

### 8a. `JdbcRowLocks` — `FOR UPDATE`, exact, and the transaction is its own (landed: `d678e8dd`)

In `engine.jdbc`, with `nessy_lock (kind, agent_type, agent_id)` in the engine's
`nessy-schema.sql`, `JdbcRowLocksTest` under `@Tag("container")`, and one finding the design did not
predict, recorded below under "The ensure runs in its own transaction".

```sql
INSERT INTO nessy_lock (kind, agent_type, agent_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING;  -- ENSURE
SELECT 1 FROM nessy_lock WHERE kind = ? AND agent_type = ? AND agent_id = ? FOR UPDATE;       -- LOCK
```

Two statements, and `withLock` is the class's only verb: ensure the row (in a transaction of its
own, below), then begin, `FOR UPDATE`, run the work, commit — or roll back and rethrow if the work
throws. There was a third statement, `FOR UPDATE NOWAIT` for `tryWithLock`, with a
`LOCK_NOT_AVAILABLE_SQLSTATE` (`55P03`) discriminator to tell "somebody else holds it" from a real
error; all three went with the verb in `bdd0906a`. The row must always exist — `JdbcAgents`' javadoc explains why locking the
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
   on the row failed with SQLSTATE `55P03` (`lock_not_available`). `NOWAIT` was the probe; the
   fact it measured — that a second connection is excluded until the first commits — is what
   `JdbcRowLocksTest.with_lock_waits_for_the_holder` now pins with a plain `FOR UPDATE` that waits.
3. *Not excluded without a transaction.* A `FOR UPDATE` issued with no transaction open excluded
   nothing: autocommit closed it at statement end. Left to the caller, the boundary is a hazard a
   caller can forget. Absorbed into the implementation, it is avoided by construction: the row
   lock always has a transaction because it made one.

**The transaction manager it uses — landed as ruled.** `JdbcRowLocks(DataSource,
PlatformTransactionManager)` is the primary constructor; `JdbcRowLocks(DataSource)` mints a
`JdbcTransactionManager` for a non-Spring caller who has nothing to hand in. Measurement 1 above is
why the minted one is correct for the plain case (it joins whatever is open on the same
`DataSource`) and why it is not enough for JTA or a second `DataSource`. **Not yet true: that the
starter passes the container's manager.** `JdbcDirectBackend` and `JdbcQueuedBackend` exist
(`3cc341c0`) and each takes a `PlatformTransactionManager` and builds its `JdbcRowLocks` from it;
but neither factory is wired to a backend yet, so today `DefaultQueuedHarnessFactory` still
constructs `new JdbcRowLocks(dataSource)` and `DirectHarnessAutoConfiguration` still falls back to
`locks.getIfAvailable(InMemoryLocks::new)`. Both are fixed by the factory cutover in the backends
record. Until then an application that wants a transactional turn lock on the direct door declares
a `Locks` bean, and it must be a `JdbcRowLocks` — chat-web's is (§7).

**The ensure runs in its own transaction — and its stated reason is gone, which is an open
question (§14).** The design above had the ensure as the first statement inside the lock's
transaction. Measured on Postgres 18 while building it: `INSERT ... ON CONFLICT DO NOTHING` has no
`NOWAIT` form, and a second insert of a key that a rival transaction has inserted and not yet
committed *blocks* until the rival commits or rolls back — Postgres cannot decide whether there is
a conflict until then. Inside the lock transaction that would have made the then-existing
`tryWithLock` wait on a brand-new key exactly when a caller was relying on it never waiting: the
first contention on an agent's first lock of a kind. So the ensure was made to run first, alone,
under `PROPAGATION_REQUIRES_NEW`, committing before the lock-and-work transaction opens under
`REQUIRED`, and `a_brand_new_key_refuses_promptly_rather_than_waiting_on_the_ensure` pinned it.

That is still how the code is shaped (`ensureTransaction` under `REQUIRES_NEW`, `transactions`
under `REQUIRED`), but the justification no longer applies: `tryWithLock` and its test are gone
(`bdd0906a`), and `withLock`'s plain `FOR UPDATE` is allowed to wait anyway, so a wait on the
ensure would be a wait like any other. The class javadoc has been rewritten to describe the split
mechanically without that justification — "the ensure is idempotent bookkeeping with nothing of the
caller's in it ... committing it immediately, on its own, means the row is never left uncommitted
for the length of a turn". What the split still buys is real but smaller: a rival's insert on a
brand-new key waits for one one-statement commit rather than for the whole step, and the row is
committed even if the step rolls back. Whether that is worth a second transaction per `withLock`
call, or whether the ensure should fold back into the main transaction, is not decided here. What
stays decided: `REQUIRES_NEW` on the lock-and-work transaction would separate the lock from the
work it guards, which is the mistake this class exists to prevent, so whatever happens to the
ensure, the work stays under `REQUIRED`.

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

**Where it lives — `engine.jdbc`, in `nessy-engine`, not `nessy-lease`.** `JdbcRowLocks` is a
row-locking transaction over the engine's own `DataSource`, both doors construct one, and
`engine.jdbc` is where the JDBC stores are; the package question the seventh revision asked was
answered by the restructure (`a0fa98a2`) putting every JDBC class in one package. Putting it in
`nessy-lease` would have made that module a compile dependency of the engine for the sake of one
class. The module stays `<optional>true</optional>` in `nessy-spring-boot-autoconfigure`, and its
only consumers are the two summarisers and chat-web's `agentLeases` bean. `nessy-engine` no longer
depends on `nessy-lease` at all: the unused declaration this record had flagged was dropped in
`bdd0906a`. **One loose end that remains, measured:** `nessy-memory/summarizing` and
`nessy-memory/episodic` declare `nessy-lease` at main scope, but nothing under either module's
`src/main` imports `org.jwcarman.nessy.lease` — the summarisers take the SPI's `Leases`, and only
their tests construct a `JdbcLeases`. The scope should be `test`; it is a two-line pom change and
is listed in §14 rather than silently made.

**Its table — ruled: separate from the lease's.** James: "they aren't really the same thing." So
`JdbcRowLocks` gets `nessy_lock (kind, agent_type, agent_id)` of its own, and the unified-table
option — one table with nullable `holder`/`expires_at`, and its variant that checked expiry inside
the row lock — is out (§10). `nessy_lock` needs NEITHER `holder` NOR `expires_at`, and not merely
because they would be null: a Postgres row lock *is* the holder, and it dies with its transaction,
including when the connection drops, so there is no stale holder to fence against and nothing for
an expiry to time. The lease needs both columns precisely because it is the mechanism that cannot
know whether its holder is alive. Each table's columns say what its rows are, and the two
mechanisms are not made to look alike when they are not.

### 8b. `JdbcLeases` — a row with a TTL, approximate, transaction-free (landed: `0e25584f`; a `Leases` since `bdd0906a`)

As it is, implementing `Leases` and nothing else, with `kind` a `LeaseKind` parameter of the verb
rather than a field, the TTL per §9, and the key columns following the ruling: `nessy_lease` is
`(kind, agent_type, agent_id, holder, expires_at)` with `(kind, agent_type, agent_id)` as the
primary key, replacing the old `key VARCHAR(255)`, plus the `takeovers INT NOT NULL DEFAULT 0` of
§8d. Spelling the pair into a string would be the stringification James ruled out. `nessy-lease`
depends on `nessy-backend-spi` for the interface and on `nessy-spi` for `Schemas`. It has no
transaction, no `FOR UPDATE` and no way to wait, and since the split it no longer has to pretend
otherwise: `tryWithLease` is a `TAKE` (an upsert that succeeds only where the row is absent or
expired), the work, and a `RELEASE` scoped to this holder.

### 8c. `InMemoryLocks` — one lock per agent, no stripes, and it moved (landed: `a0fa98a2`, `0e25584f`)

A map of `(kind, type, id)` to a `ReentrantLock`, pruned with `ConcurrentHashMap`'s atomic
`compute`/`computeIfPresent` when the last holder leaves; the old javadoc's objection to a map was
right about naive pruning and is answered by the per-key atomic operations. A waiter claims the
entry before it blocks, so a releasing holder cannot evict an entry somebody is still waiting on.
Since `bdd0906a` it blocks on the entry's own `lock()` — the first cut had inherited the SPI's
polling default `withLock` (§7), which it never needed — and `InMemoryLocksTest` is rewritten
around one verb: the first caller runs, a held lock is waited for rather than refused, contenders
run one at a time, and different agents and different kinds never collide. The old test that
asserted the stripe hazard ("at most one stripe is taken") is gone with the stripes, along with
the stripe-count test and the `InMemoryLocks(int)` constructor. It moved out of `engine.direct` into `engine.inmemory` with the
other in-memory stores (`InMemoryAgentEvents`, `InMemoryPayloads`, `ListBacklog`) in the package
restructure: it serves either door, as James ruled the in-memory stores do, and it sits opposite
its JDBC sibling `JdbcRowLocks` in `engine.jdbc` rather than in `nessy-lease`. Note that an in-memory `withLock` gives the direct door's steps
exclusion but no transaction; against the in-memory stores that is what "in memory" has always
meant here — `InMemoryAgentEvents` is a list — and the phase check works the same way.

### 8d. Lease observability: know that a TTL was reached, without guessing why (landed: `0e25584f`)

Ruled after the fifth revision and built as described: the `RELEASE` row count is checked and a
zero is a `WARN` ("was taken over while this holder was still working; its work may have run
twice"), `takeovers` is a column incremented on the conflict path and returned by `TAKE`, and the
dead `.filter(holder::equals)` is gone. The lease is the one mechanism here that can let two run at once
— a holder that is merely slow is indistinguishable, at take time, from one that died — and its
javadoc says so. What we want is to *know* a TTL was actually reached, without the log guessing
why, because at take time nothing can distinguish a dead holder from a slow one. Two signals, both
factual:

- **A `RELEASE` that affects zero rows is a `WARN`.** The releaser is the only observer that can
  *prove* duplicate work happened: if its own row is gone, somebody took the lease over while it
  was still working, and the work ran twice. Before `0e25584f` the row count was discarded —
  `jdbc.sql(RELEASE).params(kind, key, holder).update()` returned it to nobody.
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
- **Returning the previous holder's UUID at all.** `holder` is a per-take `UUID.randomUUID()` nonce,
  so it names nothing anyone can look up; a log line carrying it would look like evidence and be
  noise.

**Two findings from the same reading, worth a line each.** The `.filter(holder::equals)` that the
first `JdbcLeases` had after `TAKE` could never fire: `TAKE` says `SET holder = EXCLUDED.holder`,
so a row `RETURNING holder` always carries the caller's own holder, and presence alone is the
whole test. It was removed in `0e25584f`, and `tryWithLease` today has no such branch — the take
is `.optional().orElse(null)` and a null row is the refusal. And the testcontainers image is `postgres:18-alpine` in every JDBC-backed test in the
reactor (`EngineFixture.java:71`, `JdbcLeasesTest.java:45`, `DurableDirectHarnessTest.java:58` and
eleven more) while the examples' compose files are `postgres:17`
(`chat-cli/compose.yaml:24`, `chat-web/docker-compose.yml:23`, `watchman/docker-compose.yml:24`),
so the suite tests *above* its floor: a version-specific construct — `RETURNING OLD` being the
example that nearly happened — would pass every test and fail in deployment.

---

## 9. The time-to-live (landed: `0e25584f`)

James rejected a global default: "forcing folks to think about their TTL is smarter than having a
global default." With the kind on the call, `JdbcLeases` takes its durations by kind:

```java
public JdbcLeases(JdbcClient jdbc, Map<LeaseKind, Duration> ttls)
public JdbcLeases(DataSource dataSource, Map<LeaseKind, Duration> ttls)
```

Every entry is validated at construction (positive), and a call with a kind not in the map throws
`IllegalArgumentException` naming the kind. No default, no silent fallback, and the set of kinds an
application leases is readable in one place. chat-web's `agentLeases` bean has one entry,
`EpisodeSummarizer.LEASE_KIND` at ten minutes; the `Locks.TURN` entry it once carried alongside
did not wait for the backend cutover — it went in `bdd0906a`, because a `LeaseKind` cannot name a
`LockKind` and the turn boundary is a `JdbcRowLocks` bean of its own (§7). `JdbcRowLocks` has no
TTL and takes none.

---

## 10. Decided along the way, and deleted from earlier drafts

Closed by ruling, recorded here so they are not re-asked: the SPI key is the agent, not a string;
the transaction is the row lock's own; the direct door locks per step and never across inference;
the direct door uses `withLock` only. One earlier ruling is reversed by what was found: "one
interface with both verbs" held from `0e25584f` until `bdd0906a`, when the one interface turned
out to be spanning two contracts and an example application was wiring the weaker one as the
direct door's transaction boundary (§7). It is two interfaces now, one verb each, and the
reasoning that keeps them apart is §7's. Closed by this record's
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
- **The lock step moving `InMemoryLocks` into the lease module.** It moved to `engine.inmemory` in
  the package restructure instead, with the other in-memory stores (§8c).

Overtaken since, by what landed:

- **The ensure as the first statement of the lock's transaction** (§8a as first designed). Measured
  to block the then-existing `tryWithLock` on an uncommitted key, so the ensure commits alone under
  `REQUIRES_NEW`. That reason is itself gone with the verb, and whether the split stays is open (§14).
- **`outcome` reading the latest terminal event in the stream**, and the `TurnId` filter that
  first replaced it. With short locks two turns can be open at once; the filter could not tell
  them apart because the fold had already stamped the event with the reader's turn, so the turn
  went into the completion commands and effects instead (`221e447c`, §3).
- **One `Locks` with `withLock` and `tryWithLock`, a polling default `withLock`, `POLL_INTERVAL`,
  a nested `Locks.Attempt`, `JdbcRowLocks.TRY_LOCK` with `FOR UPDATE NOWAIT` and its `55P03`
  discriminator, `JdbcLeases implements Locks`, and the summarisers' `LOCK_KIND`.** All replaced
  by the `Locks`/`Leases` split in `bdd0906a` (§7): `Locks.withLock` is abstract and alone;
  `Leases.tryWithLease`, `LeaseKind` and `Attempt` live in `backend.lease`; the summarisers hold a
  `Leases` and a `LEASE_KIND`; `nessy-engine` no longer depends on `nessy-lease`.
- **`AgentEventStore`'s TODO** ("this belongs in `nessy-spi` ... the move waits on `Failure` being
  lifted to `nessy-api`"). Deleted with the type: `AgentEvents`, `AgentEvent` and `ActionRequest`
  are in `nessy-backend-spi` (`07c275e3`), `Failure` stayed in `nessy-inference-spi` (`dde22239`),
  and the TODO had named the wrong blocker — the backends record §8a has the measurement.
- **The dead `engine.backlog.Backlog`/`Pull` pair** from the design the actor engine replaced
  (`260ea3fd`); the live `Pull` moved beside `BacklogManagement` in `e0eb76cd`.

---

## 11. Scope: the queued door's JDBC coupling is wider than the lock — now the backends record's

James's ruling — "I don't want the queued door to do direct JDBC at all" — is about more than the
lock, and this record's first measurement of the rest (a `JdbcAgents` field, a `Backlogs<I>`
returning the concrete `JdbcBacklog<I>`, a factory building every JDBC store from a bare
`DataSource`) recommended a follow-on rather than widening. That follow-on is
`2026-09-26-backends-design.md`, which owns the subject: the two backend interfaces, `Agents`,
the `Effects` extraction, the `Backlogs<I>` lift, and the factory cutover. This record's part of
it — the lock, and the transaction it absorbs — landed in `391d536d`: the minted
`TransactionTemplate` is gone from the queued factory and harness, and `JdbcClient` and
`JdbcTemplate` having no transaction API of their own (checked with `javap` against spring-jdbc
7.0.9) is why absorbing it into the lock was the right place.

### 11a. `JdbcAgents` split three ways (landed: `391d536d`)

- **"Ensure this agent's row exists"** — `JdbcAgents.ensure(type, id)`, the `INSERT ... ON
  CONFLICT DO NOTHING` on `nessy_agent`, called first inside every locked step of the queued door.
  `JdbcAgents` is now that one method.
- **"Has this agent been told to end"** — `BacklogManagement.terminated()`, asked inside the locked
  work rather than returned by the lock; `tell` uses it and nothing else needs it.
- **The `FOR UPDATE` and the transaction around it** — `JdbcRowLocks` under `Locks.TURN`.
  `JdbcAgents.lock` is gone.

What the split leaves behind is the backends record's finding: `terminated()` and `seal()` are
`nessy_agent` statements declared on the backlog interface, so `Agents` finishes the split there.
One stale comment survives in the engine's `nessy-schema.sql`: the `nessy_agent` block still says
"Taken with SELECT ... FOR UPDATE", which nothing does any more; it is rewritten with the DDL
split.

---

## 12. Naming: "lease" and "lock" — ruled, no rename

`nessy-lease` keeps its name, its module and its `org.jwcarman.nessy.lease` package. James: "We
have both constructs, leases and locks." With `JdbcRowLocks` in `engine.jdbc` and `InMemoryLocks`
in `engine.inmemory`, the module holds exactly one thing, a lease, and its name is right as it
stands. The only new name is the table: `nessy_lock (kind, agent_type, agent_id)`, in the engine's
`nessy-schema.sql`. `nessy_lease` is reshaped per §8b. The rename question is retired; whether the
module *moves* under the `nessy-backend/` family, name unchanged, is open in the backends record.

---

## 13. What changed, by module

James accepted breakage ("Nobody except me is using this"). The file-by-file migration list this
section once carried, with line numbers, is retired: everything on it landed under the SHAs in
§13a. What stands per module, as of `bdd0906a`:

- **`nessy-backend-spi`** (new in `07c275e3`): `backend.lock.Locks` with `TURN` and the one verb
  `withLock`, and `backend.lock.LockKind`; `backend.lease.Leases` with `tryWithLease`,
  `backend.lease.LeaseKind` and `backend.lease.Attempt` (`bdd0906a`); `backend.event.AgentEvents`,
  `AgentEvent`, `ActionRequest`; `backend.payload.Payloads`; `backend.agent.Agents`
  (`d2944a51`); `backend.effect.Effects`, `AgentEffect`, `EffectOutcome`, `Attempt` (the effect
  claim, a different type from the lease's; `fd5393ae`); `backend.backlog.Backlog`, `Backlogs`,
  `Pull`; `DirectBackend` and `QueuedBackend` (`3cc341c0`). Depends on `nessy-api` and
  `nessy-inference-spi`.
- **`nessy-api`**: `Identifiers`; `AgentType` bound to 64 through it; `DirectHarness<I, O>`
  (`2ec55eeb`); narration moved here from `nessy-spi`. Depends on nothing of ours (`1a9ee75b`).
- **`nessy-spi`**: `spi.store.Schemas` and nothing else. Its pom description says what it is
  waiting for: a `nessy-backend-jdbc` module to move into, after which it retires.
- **`nessy-lease`**: `JdbcLeases implements Leases`, `JdbcLeases(DataSource | JdbcClient,
  Map<LeaseKind, Duration>)`, `TAKE`/`RELEASE` keyed by `(kind, agent_type, agent_id)`, the §8d
  signals; `nessy_lease` reshaped. Optional in the starter.
- **`nessy-engine`**: `engine.jdbc.JdbcRowLocks` with `nessy_lock`, one verb, two statements;
  `engine.inmemory.InMemoryLocks` destriped and blocking on its own `ReentrantLock`; the queued
  door's three sites under `locks.withLock(Locks.TURN, ...)` with `agents.ensure` first;
  `JdbcAgents` implementing `Agents` in full (`d2944a51`); `JdbcEffects` implementing `Effects`
  (`fd5393ae`); `JdbcDirectBackend` and `JdbcQueuedBackend`, built and not yet wired to a factory
  (`3cc341c0`); `EffectTermsSource` and `EffectOutcomes`; `Outstanding.since` and `writtenAt`; the
  direct door on `EffectHandlers` with `within`, `Clock`, `observations`, one factory executor and
  `ReplyTokens.ephemeral()`; the inference `undispatchable()` blob `Failure.Unknown` (`76c4ad99`),
  with the two comments that described the false blob rewritten; and the direct door's `beginTurn`
  step, per-step `withLock`, lazy recovery and the turn on every completion command (`221e447c`,
  §3). No dependency on `nessy-lease` (`bdd0906a`).
- **`nessy-inference`** and the starter's inference auto-configurations: the transport timeouts
  (§5, `e5bde878`).
- **`nessy-memory`**: both summarisers take a `Leases` and call `tryWithLease` under their own
  public `LEASE_KIND` with the `agentType` they hold (`bdd0906a`). `summarizing` and `episodic`
  still declare `nessy-lease` at main scope though only their tests use it (§8a, §14).
- **`nessy-spring-boot-autoconfigure`**: `DirectHarnessAutoConfiguration` passes the registry and
  still wires `locks.getIfAvailable(InMemoryLocks::new)` over JDBC stores — the silent combination
  the backends record §1 measures — until the factory takes a backend.
  `QueuedHarnessAutoConfiguration` hands a bare `DataSource` through, as before.
- **`nessy-examples/chat-web`**: two beans since `bdd0906a` — `agentLocks`, a `JdbcRowLocks` over
  the container's `PlatformTransactionManager`, which the direct auto-configuration takes as the
  turn boundary; and `agentLeases`, a `JdbcLeases` with the episode summariser's kind at ten
  minutes, handed to `EpisodeSummarizer`. The `agentLocks` bean stops being consulted at the
  cutover.
- **Docs** (`docs/concepts/leases.md`, `storage.md`, `memory.md`, the Spring guide, README,
  ROADMAP) still describe the construction-time kind and are the "docs" step, last in §13a.
- **Live databases.** `CREATE TABLE IF NOT EXISTS` never alters; a database carrying `nessy_lease`
  in its old shape keeps it and the new statements fail against it. The compose Postgres for
  development is disposable by standing rule.

### 13a. Execution order

Written so that every step leaves the reactor compiling and green, and no test asserts a behaviour
that no longer exists without its replacement landing in the same commit. Each step is one
`clean verify` — with `-Dnessy.excludedGroups=live` for anything that touches JDBC, see the status
block — and one commit; `spotless:apply license:format` before every push. This subsection is the
one place the remaining order is stated; the backends record's §9 points here.

**Landed, in the order they landed.**

| SHA | step | notes |
|---|---|---|
| `e5bde878` | provider transport timeouts (§5) | first because it stood alone; `TransportTimeouts` six minutes |
| `90a0fde8` | `EffectTermsSource` + `EffectOutcomes` lifted out of the handlers | pure refactor; `EffectTermsSourceTest` added |
| `62fe03a2` | `Outstanding.since` + `AgentEvents.writtenAt` (§4e) | additive, unread until recovery |
| `8575a90a` | the direct door enforces its deadlines in-process (§4b–§4c) | `Clock` on the config, `within`, one factory executor, factory `AutoCloseable` |
| `5be127c2` | observation → input; the queued door's `O` → `I` | adjacent; vocabulary only, nothing stored changed |
| `2ec55eeb` | `DirectHarness<I, O>`, single `ask`, output bound at creation | the prerequisite for the handlers step (§4g) |
| `76c4ad99` | the inference `undispatchable()` blob → `Failure.Unknown` (§4d finding 1) | the dispatcher's and terms' comments rewritten with it |
| `4ea77f9e` | `*Store` → plural; the wrapper becomes `Outbox` | the backends record §3 |
| `a0fa98a2` | package restructure: `engine.harness.{direct,queued}`, `engine.jdbc`, `engine.inmemory`, `engine.store` backend-neutral | the outbox machinery stayed in `effect/` and `tool/` |
| `07c275e3` | `nessy-backend/nessy-backend-spi` created; narration to `nessy-api`; `Schemas` left in `nessy-spi` | `AgentEvent`, `ActionRequest`, `AgentEvents`, `Payloads`, `Locks` moved |
| `1a9ee75b` | `nessy-api` depends on nothing of ours; the SPIs depend on it | 245 files, all imports |
| `dde22239` | `Failure` stays in `nessy-inference-spi`, and says so | closes the backends record's §8a move 1 as not needed |
| `260ea3fd` | the dead `engine.backlog` `Backlog`/`Pull` deleted | described a design the actor engine replaced |
| `e0eb76cd` | a coalescing policy cannot take from the backlog | `BacklogPolicy.coalesce(Backlog<I>, ...)` over the api type; `BacklogManagement<I> extends Backlog<I>` adds `take`, `seal`, `terminated` |
| `f0c05523` | the OPA renderer becomes `InputDocumentRenderer` | adjacent |
| `534602bb` | the direct door performs effects through `EffectHandlers` (§4f–§4g) | 723 → 453 lines; `observations` on the direct config; `DirectHarnessObservabilityTest` |
| `0e25584f` | the lock SPI (§7–§9): `Identifiers`, `LockKind`, both verbs, `InMemoryLocks` destriped, `JdbcLeases` reshaped with §8d | the summarisers, chat-web and the memory tests re-imported in the same commit |
| `d678e8dd` | `JdbcRowLocks` (§8a) | with the `REQUIRES_NEW` ensure finding |
| `391d536d` | the queued door onto the SPI; both doors lock under `Locks.TURN` (§6, §11a) | the queued tests passed unchanged, which was the assertion |
| `221e447c` | the direct door as per-step locked transactions with lazy recovery (§3, §4d); the turn on every completion command and effect | the `TurnId` filter on `outcome` did not hold and the turn went into the grammar instead; a reopened inference is discharged without a deadline check; one clock shared by harness and store in the recovery tests |
| `d2944a51` | `Coalescing<I>` / `Backlog<I>` / `Agents` — the three backlog interfaces | backends record §4; `Agents` owns `nessy_agent` in full in `nessy-backend-spi`; `JdbcAgentsTest` split out of `JdbcBacklogTest` |
| `fd5393ae` | `Effects` extracted from `JdbcEffects`; `AgentEffect`, `EffectOutcome` and the effect `Attempt` to `backend.effect` | backends record §8b; `Outbox` holds the interface; no pom changed |
| `4a4a0f5c` | `Backlogs<I>` lifted out of `DefaultQueuedHarness`'s body | backends record §8c |
| `3cc341c0` | `DirectBackend` / `QueuedBackend` in `nessy-backend-spi`; `JdbcDirectBackend` / `JdbcQueuedBackend` over the existing stores, each taking the `PlatformTransactionManager` | backends record §4–§5; `Backlog`, `Backlogs`, `Pull` to the SPI; the backlog seam takes a `TypeRef`, not a `Codec`; neither factory is wired to a backend yet |
| `bdd0906a` | the `Locks` / `Leases` split (§7): `Locks` is `withLock` alone, `Leases.tryWithLease` with `LeaseKind` and `Attempt` in `backend.lease`; the polling default and `POLL_INTERVAL` gone; `JdbcRowLocks` loses `NOWAIT`; `InMemoryLocks` blocks on its own lock; the summarisers take a `Leases` | chat-web's `Locks` bean was a `JdbcLeases` — a live defect, split into `agentLocks` + `agentLeases`; `nessy-engine` drops `nessy-lease`; `JdbcDirectBackendTest` added |

**Standing notes on what landed.** `DurableDirectHarnessTest` still wires `InMemoryLocks` and
should wire `JdbcRowLocks` — or, after the cutover, a `JdbcDirectBackend` — to prove the durable
path honestly; it only runs under `-Dnessy.excludedGroups=live`. `JdbcDirectBackendTest`
(`bdd0906a`) is the first test that a lock taken through one backend instance excludes a second
instance over the same agent.

**Remaining, in order.** Each of these is the backends record's to describe; this is the order.

1. **THE CUTOVER**: factories take a backend; `QueuedHarnessFactoryConfig` loses `dataSource` and
   `storage`, `DirectHarnessFactoryConfig` loses `locks`/`events`/`payloads` (backends record §6).
   `DirectHarnessAutoConfiguration`'s `InMemoryLocks` fallback dies here, and chat-web's
   `agentLocks` bean stops being consulted (its `agentLeases` bean stays: the summariser is not a
   backend's concern).
2. **The in-memory backend, then the TCK** — extracted from the JDBC tests, not the in-memory
   ones (backends record §8e).
3. **`nessy-backend-jdbc` + `nessy-backend-inmemory` modules; `Schemas` moves; `nessy-spi`
   retires.** The memory modules' test-only `nessy-lease` dependency (§8a, §14) can go to test
   scope in the same pom pass if James has not moved it sooner.
4. **The DDL split, which IS the module split** (backends record §5).
5. **`nessy-engine-direct` / `nessy-engine-queued`, two starters, both doors optional** (backends
   record §5 and §9; the "why last" reasoning is §13b here).
6. **Docs, last** — `docs/concepts/*`, the Spring guide, README, ROADMAP, and the two earlier
   records this one overtakes (`2026-09-25-one-core-two-doors-design.md` §5's "exclude
   differently" table; the autoconfiguration record's §9 non-goal and "Locks default to
   `JdbcLeases`"), once the artifact names the docs cite have stopped moving.

### 13b. The module split, and why it is last

James: "at some point, I thought we were going to split these two harness types out into their own
modules and we'd have engine core, engine direct, and engine queued." The shape, the DDL split
that goes with it, the two starters and the backend modules that precede it are the backends
record's (§5, §9); this subsection keeps only the reason the split is last, because that reason is
this record's finding.

**The handlers step moved the line.** The first revision measured `effect/` as the queued door's —
every class in it was constructed by `DefaultQueuedHarnessFactory` and by nothing else — and that
was true. Since `534602bb` the direct door performs through `EffectHandlers.perform`, so the
handlers and their terms belong to neither door: they are the thing that executes what the fold
emits, and both doors call it. Every earlier attempt to schedule the split ran into exactly this —
the line between "core" and "queued" ran through the middle of `effect/` — and §4f is where the
line moved. After it there is a package-level answer to "which module does this class belong to"
for every class in the engine except the outbox seam: `EffectDispatcher` and `AgentEffectCallback`
in `effect/`, `DefaultReplies` in `tool/`, queued-only and not yet moved (the restructure left
them; the split will move them).

**The restructure was the prerequisite.** `a0fa98a2` put every class in the package it will keep,
so the split is a pom exercise rather than a pom exercise plus a rename across the five modules
that import the engine's packages.

**The payoff: `@ConditionalOnClass` becomes meaningful.** Both annotations were deleted in
`a52f66e0` ("a condition that cannot be false is not a guard") because `nessy-engine` is a
non-optional compile dependency of the autoconfigure module, and a comment in each
auto-configuration says so. With the door modules optional, the two annotations return and guard
something, and there are two starters: add the jar for the door you want. Honestly stated:
name-exclusion already works (`Repl` excludes the queued auto-configuration by name); the split
buys ergonomics and a correct default, not a missing capability. The direct module will be small
— four classes against a queued module holding the outbox, the dispatcher, the backlog and the
reply desk — and that reflects what a door is, not a failure to divide evenly.

---

## 14. Open questions for James

Closed by landing, and not re-asked: the inference blob is `Unknown` (`76c4ad99`); `JdbcRowLocks`
takes a `PlatformTransactionManager` and chooses `REQUIRED` for the work and `REQUIRES_NEW` for
the ensure (`d678e8dd`); it lives in `engine.jdbc` and `nessy-lease` stays optional and keeps its
name; `Identifiers` is in `nessy-api`; `Clock`, `writtenAt`, `Outstanding.since`, the
stored-but-not-honoured `retryPolicy`, `observations` on the direct config and the transport
timeouts all landed; `nessy_lock` is its own table; the outbox machinery stayed in `effect/` and
`tool/` for the restructure and moves with the split; the verbs take four parameters. Retired by
§4f–§4g: the deferred-approval divergence and the direct door's observability gap. The direct door
as designed in §3–§4 landed in `221e447c`, and the lock/lease split in `bdd0906a`, so the whole
has its yes in the only form that counts.

Genuinely open, and this record's own:

1. **Whether `JdbcRowLocks`' ensure keeps its own `REQUIRES_NEW` transaction** (§8a). It was put
   there so that `tryWithLock` would never wait on a rival's uncommitted insert of a brand-new
   key; `tryWithLock` is gone and `withLock` is allowed to wait, so the stated reason no longer
   holds. The class javadoc now describes the split mechanically. What it still buys: a rival's
   wait on a brand-new key is bounded to one short statement rather than a whole step, and the
   row survives a rolled-back step. What it costs: a second transaction on every `withLock`, and
   a `REQUIRES_NEW` that suspends any ambient transaction for the ensure. Keep the split for the
   bounded wait, or fold the ensure back into the lock's transaction as first designed? Not
   decided here.
2. **`nessy-memory/summarizing` and `nessy-memory/episodic` declare `nessy-lease` at main scope
   though only their tests construct a `JdbcLeases`** (§8a). The summarisers themselves take the
   SPI's `Leases`. Moving the dependency to `test` scope is a two-line change; flagged rather than
   made, since it changes what an application gets transitively when it depends on a memory
   module.

Open and owned by the backends record because that is where each is decided:

3. **The TCK's exact shape** — a module backends depend on in test scope, or a test-jar; and
   which JDBC tests seed it (backends record §8e, §10).
4. **Whether `nessy-lease` moves under the `nessy-backend/` family**, name unchanged (§12 here;
   backends record §10).
5. **The placeholder name `Coalescing`** for the api-side backlog interface, landed with a TODO
   recording that James dislikes it (`d2944a51`; backends record §10).

Nothing else in this record is waiting on an answer.
