# One actor per agent

**Status:** designed 2026-09-01, not built. Supersedes the actor hierarchy in
`2026-08-28-actor-composition-design.md` §5 and §7.

## 1. What is wrong

The engine runs five actor types to work one turn:

```
AgentActor (durable, sharded)
  └── TurnActor (durable, CHILD)
        └── ToolCallActor
              ├── ApprovalActor      holds a timer
              └── ExecutionActor     holds a timer
```

Two defects in one day came out of that shape, and neither was a coding mistake:

- **A turn is a child, so it dies with its agent.** The agent asks to be unloaded the
  instant a turn ends; an observation arriving before the shard answers starts a new
  turn, and the `Stop` then kills it mid-model-call. The answer completes into a dead
  incarnation and nobody ends the turn. Patched by having the dying agent post a `Wake`
  to its own entity id (52d4a387).
- **A deadline lives in an actor's timer**, so it dies with that actor. An approval
  parked for three days depended on a process staying up. Patched by writing deadlines
  down as reminders (d5aa25f4, 8f2650e5) — but nothing sweeps them yet.

Both patches are scaffolding around a structure that does not want to hold what it is
holding. Neither is testable without a cluster, a race and a fifteen-second timeout.

## 2. The shape

**One sharded, durable actor per agent, and a pure decision function it delegates to.**

```
AgentActor (durable, sharded)   ← a thin shell: message → Input → decide → persist → run
NarrationActor (sharded)        ← unchanged; separate concern, own lifecycle
```

`TurnActor`, `ToolCallActor`, `ApprovalActor` and `ExecutionActor` are deleted. Model
calls, tool runs and approver questions become futures on the blocking executor. Their
answers come back as messages **to the agent's logical address**.

```java
Decision decide(AgentState state, Input input);

record Decision(AgentState next, List<Instruction> then) {}
```

The actor does four things and nothing else: translate a message into an `Input`, call
`decide`, persist `next`, execute `then`.

## 3. The rule that makes it safe

> **Work handed to the blocking executor has its answer addressed to a LOGICAL address,
> never to `self`.**

The executor outlives actors; `getSelf()` does not. This is the whole of the first
defect: `pipeToSelf` hands a slow call a reference to a mortal object. An `EntityRef` is
resolved by the shard at delivery — if the agent was unloaded, the shard creates one and
delivers there, so **the answer arriving is itself the knock that revives the agent.**

Inside one actor tree direct refs were safe, because a child cannot outlive its parent.
After this change there is no tree, and the rule covers every case.

## 4. The state

```java
record AgentState(String turnId, Phase phase, String observation) {}  // see 4a

sealed interface Phase {
  record Idle() implements Phase {}
  record CallingModel() implements Phase {}
  record WorkingTools(Map<String, CallState> calls) implements Phase {}
}

sealed interface CallState {
  record Approving() implements CallState {}              // the approver was asked
  record Running() implements CallState {}                // approved, the tool is running
  record Parked(Instant expiresAt) implements CallState {} // waiting on the world
  record Completed() implements CallState {}              // its result is in claims
}
```

`Idle` is an ARM, not the absence of a turn, so going to sleep is a transition that can
be tested rather than a stale-snapshot check bolted onto a nudge.

**These records exist to answer exactly one question: what should happen if this process
dies right now?** Anything not needed to answer it does not belong in the document — which
is why `Parked` carries no instant. The deadline is a reminder row; storing it here too
would be a second copy that can drift from the first.

| state | on recovery | why |
|---|---|---|
| `Approving` | ask again | asking is idempotent |
| `Running` | run again | nobody else will answer; see at-least-once below |
| `Parked` | WAIT | someone holds a reply token and an alarm is armed; re-asking mints a second token and restarts a term |
| `Completed` | nothing | the result is checked in |

Today's `resumeTools` re-runs ANY call without a stored result, parked ones included — so a
restart silently re-asks a person and invalidates the token already in their inbox. Recording
which kind of waiting a call is doing fixes that by construction.

**Tool execution is at-least-once, and that is a contract, not an accident.** A `Running`
call whose process died may have finished its work; nothing recorded that it had. No marker
fixes it — a "started" marker only moves the ambiguity. The engine's mitigation is to hand
the tool a stable key it can use for itself.

Claim keys are DERIVED, never stored: `claim/{agentId}/{turnId}` plus `asked` or
`result-{callId}`. Everything needed to compute one is already in the state, so there is no
second copy to go stale.

## 4a. The backlog is a store, not state

**The backlog moves out of the actor document entirely, into a purpose-built table**
beside the notebook and the plan. It is not the claim check: a general-purpose store
is for content with no upper bound, and a backlog is a short ordered list the engine
owns.

```java
interface BacklogStore<O> {
  void offer(AgentId agentId, O observation);
  Optional<String> take(AgentId agentId, String lastCompleted);  // → claim id
}
```

Two methods. The store owns the codec, the `ObservationRenderer` and the
`BacklogCoalescer`, which is why **`<O>` stops here**: `AgentState`, `AgentActor`,
`NessyMessage` and `Decision` lose their type parameter and `StateTypes` deletes.

The coalescer keeps working on real observations, unrendered — the watchman's tick
comparison reads the string it was given rather than parsing it back out of a
`UserMessage`. Rendering happens once, at `take`, so an observation coalesced away is
never rendered at all.

### offer: commit, then signal

```
1. commit the row      ← the observation is durable, coalesced against what waits
2. tell the agent      ← BacklogUpdated, a bare signal
```

**Store first, always.** Reversed, the agent could take before the row commits, find
nothing, and go back to sleep with work sitting in the table.

`BacklogUpdated` carries nothing — not a count, not a delta, not an id — and it must
stay that way. Its whole value is that it is droppable:

- **A busy agent ignores it.** No stash, no persist, no state change, because the
  transition to `Idle` always ends with a take. Missing the signal costs nothing when
  a signal-free path reaches the same place.
- **Duplicates are free**, since a take that finds an empty backlog is a no-op. So
  `offer` never reasons about whether a signal is warranted — it always sends one.
- **It cannot be lost while idle**, because it goes to the logical address: an
  unloaded agent is started by the shard and the signal is delivered (§3).

The name is `BacklogUpdated` and not `WorkArrived` because coalescing means an arrival
is not always an addition — the watchman's tick *replaces* the ticks already waiting.
"Arrived" would be false on exactly the path the coalescer exists for.

**This is what finally makes `Wake` explicable.** It had two unrelated jobs jammed
together — "check for work" and "rescue a turn stranded by passivation" — which is why
it never explained itself to anyone reading it. There is now one reason to send a
signal, and the rescue belongs to the alarm.

### take: finish the one I name, give me the next

```
take(agentId, lastCompleted):
  BEGIN
    delete the row for lastCompleted                  -- the lazy sweep
    if a row is still marked taken → return its claim  -- already rendered, already held
    select the head                                    -- the coalescer's order IS the row order
    render it, hold it                                 -- content into claims
    mark the row taken, with the claim id
  COMMIT → claim id
```

**One transaction, so the invariant is enforced by the database rather than by a CAS
on a document.** Crash anywhere and the row is either untaken (retry, clean) or taken
with a claim already written (the next take returns it unchanged). Never neither.

**The sweep names an id; it never infers from phase.** An earlier draft had the sweep
clear "whatever was taken" once the agent was `Idle`, which is ambiguous and loses
work: an agent that is idle with a taken row either finished that turn *or* died
between the take committing and the agent recording it. Those histories are
indistinguishable from both sides. Naming the completed claim removes the guess — the
unrecorded case is the one where nobody names the row, and it comes back with the
claim it already has.

Sweeping lazily means the happy path writes nothing extra: no `complete()`, no ack
message, no second write at the end of every turn. A turn that died halfway leaves
debris that the next take clears, rather than debris needing a reaper of its own.

The cost is that a finished row and its claim linger until the next take, indefinitely
for a quiet agent. The row is small; the claim expires on its own, which is the reaper
doing its ordinary job rather than a leak being waved at.

### What the agent holds

```java
record AgentState(String turnId, Phase phase, String observation) {}
```

`observation` is the claim id from the last `take` and is **not cleared when the turn
ends** — the finished id is exactly what the next `take` needs to name. One field
serves both the working turn and the sweep.

Recovery needs no question of the store: the claim id is in the document, so a
`CallingModel` or `WorkingTools` agent redeems it and carries on (§7a). An `Idle` agent
takes, and the take sorts out for itself whether there is unrecorded work.

`TakeWork` is an instruction; `WorkTaken(claimId)` and `NoWork` are the inputs it
produces. The store call runs on the blocking executor like every other, and its answer
is addressed logically (§3).

## 5. Inputs and instructions

**`Input` — what happened.** `BacklogUpdated`, `WorkTaken`, `NoWork`, `Recovered`,
`ModelAnswered`, `ModelFailed`, `ApprovalGiven`, `ToolParked`, `ToolCompleted`,
`DeadlinePassed`, `SleepNow`.

**`Instruction` — what to do.** `TakeWork`, `CallModel`, `RunTool`, `AskApprover`,
`Remember`, `Hold`, `Release`, `Narrate`, `SetAlarm`, `CancelAlarm`, `Sleep`.

`Hold` and `Release` are the claim check's own verbs — content in, and everything for a
finished turn out. There is no read instruction: reads happen in the SHELL before an input
is fed, which is what keeps `decide` pure.

### Inputs name facts, never provenance

The same event has two names today depending on who delivered it — `Ran` vs `RelayResult`,
`Answered` vs `RelayApproval` — because a child actor replying and a reply token arriving
took different paths. The agent has no reason to care. `ToolCompleted` is identical whether
a future finished in two milliseconds or a webhook answered three days later.

`DeadlinePassed` stays distinct from `ToolCompleted` for the same reason in reverse: the
sweep knows time ran out, and does not get to decide what that means. Whether a timeout is a
denial, an error or a retry is policy, and policy belongs where it is testable.

### Messages carry tickets, never cargo

**Whatever produces content checks it in BEFORE telling the actor, and the message carries
only the id.** That is every slow call without exception — a tool answering, and the model
answering:

```java
ToolCompleted(String callId)                          // that is the whole message

sealed interface ModelAnswered extends Input {
  record Answered(StopReason stopReason, Usage usage) {}       // held under "answer"
  record Asked(List<CallSummary> calls, Usage usage) {}        // held under "asked"
  record Refused(String category, String explanation, Usage usage) {}
}

record CallSummary(String callId, String toolName) {}          // bounded: an id and a name
```

`Asked` carries what the logic needs to DECIDE — which calls exist and what they are called —
and nothing it does not. The arguments, the provider blocks, the answer text: all held, all
redeemed by the shell when it dispatches a tool or writes the exchange.

Streaming is unaffected: text deltas already go straight to narration and never through the
actor.

The line is bounded versus unbounded. Inline: ids, `StopReason`, `Usage`, `ApprovalResult`
with its short reason, `TurnResult` with its explanation — things a person wrote to be read.
Checked in: the asking message, `ToolResult` content, an `AnswerMessage`, a rendered
observation — whatever a tool or a model decided to produce.

Three things follow, and the first is the reason to do it:

- **The ordering hazard disappears.** "The state says `Completed`" now implies "the content
  is there", because the content was durable before the actor was told. §7's inversion stops
  having teeth.
- Messages stop being unbounded, so a megabyte of `docker logs` never enters a mailbox or
  crosses a shard to be put straight back into a claim.
- The serializer gap shrinks to ids and small statuses.

One consequence: `ToolCallCompleted` narration carries the result, so the SHELL narrates at
completion time rather than the logic emitting an instruction for it.

## 6. Style, and why it is part of the design

The logic is organised by phase — `whenIdle`, `whenCallingModel`, `whenWorkingTools` —
so each phase's rules sit together. Behaviour does NOT go on the `Phase` records: they
are persisted, their wire names are a compatibility surface, and decisions need the whole
state anyway.

Four rules, which are what erodes first:

1. **Instructions are data, never lambdas.** One `Runnable` and the tests stop being
   assertions.
2. **The decision function touches nothing.** No clock, no random, no I/O — ids and
   instants arrive IN the input. That is what makes every test an equality check.
3. **No streams in decisions.** A switch and an if read at a glance.
4. **One sentence per method.** A handler needing a paragraph of javadoc is two handlers.

The payoff:

```java
assertThat(decide(workingOn("c1", "c2"), completed("c1")))
    .isEqualTo(new Decision(stillWorkingOn("c2"), List.of(claimFor("c1"))));
```

No test kit, no cluster, no awaitility. Every race chased on 2026-09-01 becomes a table.

## 7. Ordering: persist, then instruct

This inverts today's *write the fact before the state that references it*. Two things make
that safe, and the second is the stronger:

1. **Recovery already tolerates a missing claim** — a `WorkingTools` state whose asking
   message is gone starts the model call over, which is always safe because an exchange is
   written whole and a transcript never holds half of one.
2. **Content is checked in before the actor is told** (§5), so a state that says `Completed`
   cannot reference a result that is not there.

The standing rule for any new instruction: **a state that references something missing must
be recoverable, not stuck.**

## 7a. Recovery is not a mode

There is no "should we re-drive?" decision anywhere. Pekko reads the document before any
command; the actor then sends itself `Recovered` on every activation, and the logic answers
per phase — `Idle` does nothing, `CallingModel` calls the model again, `WorkingTools`
re-runs what died and leaves parked calls alone.

The rare path is therefore the common path, exercised on every activation rather than only
after a crash. That matters: `resumeTools` re-asks parked approvals today, and it went
unnoticed precisely because nothing ordinary ran it.

`{turnId}/{callId}` is stable across re-drives for the same reason — recovery resumes the
same turn id, and the claimed asking message pins the same call ids. That is what makes it
usable as an idempotency key. It is stable while the claim survives, not forever: if
recovery falls back to calling the model afresh, new call ids are minted.

## 8. What this deletes

- `TurnActor` (580 lines), `ToolCallActor` (327), `ApprovalActor` (192),
  `ExecutionActor` (149).
- Every in-memory timer, replaced by `Parked(expiresAt)` plus a reminder.
- The relay chain — `RelayApproval`, `RelayResult`, `RelayDeadline`, `Answered`, `Ran`.
- `TurnState`, its persistence id, and `describes(turnId)` for spotting a predecessor.
- The `Turns` seam and `TurnActor.Dependencies` (13 components).
- `AgentActor.onStop`'s rescue `Wake` and its `shuttingDown()` guard, because a model
  answer addressed logically is its own knock.
- `nudge`, which today sends a Wake and asks to passivate in the same breath — one of
  which is always pointless.
- `StateTypes`, and the `<O>` parameter on `AgentState`, `AgentActor` and `NessyMessage`
  — the type stops at `BacklogStore` (§4a).

## 9. What it costs

- **One document per agent.** Every call completing rewrites the agent's state — now a
  turn id, a phase and two ids, since the backlog moved to its own table (§4a). Keeping
  the two apart was the stated reason for the split; they are apart again, just not in
  actors.
- **A backlog row and its claim linger** until the next take (§4a).
- **`AgentActor` grows** before the deletions land.
- **Serializers.** Answers arriving at a logical address must serialize to cross nodes,
  and no engine message has one today — a pre-existing gap this makes visible.
- **Blocking store calls stay inline** unless separately moved. The design permits moving
  them later without touching the logic, which is the point, but it does not do it.

## 10. Open

- `AgentLogic` as the home for `decide` — proposed, not ruled on.
- `ToolCallRequest` replacing `ToolContext` and `ApprovalContext`: one record carrying
  `agentType`, `agentId`, `callId`, `callKey`, `toolName`, `arguments`, `replyToken`, with
  `ApprovalRequest` composing it and adding `description`, `askedAt`, `facts`. Both contexts
  delete; `Approver` becomes a single-argument function again. A wide API break —
  fourteen modules reference the two contexts.
- `ToolCallRequest.callKey()` as `{turnId}/{callId}`: the model's call id is unique within
  one response only, so it cannot be an idempotency key on its own.
- Whether an alarm is still needed once answers arrive logically. It covers what the
  logical address cannot: `kill -9`, where nothing is in flight to deliver.
- The idle linger before sleeping, mirroring `NarrationActor` — same pattern, and it
  narrows the passivation window that started all of this.
