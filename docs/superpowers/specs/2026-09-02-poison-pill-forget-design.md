# Forgetting by poison pill

**Supersedes the execution mechanics of `2026-09-02-forgetting-an-agent-design.md`.**
That spec's promise is unchanged — an agent instance can be told to end, and
what it leaves behind is nothing. How it gets there is what changes.

## Why the first attempt was wrong

`Harness.forget(agentId)` sent a message straight to the entity. The message
bypassed the backlog, which is the only queue the agent's work is ordered
against, so the forget was ordered against nothing.

The failure that exposed it is `ForgetTest`, red on CI roughly one run in five:

```
Expecting empty but was: [AnswerMessage[content=[TextBlock[text=done]]]]
Failed to stop [ClusterOfOne] within [10 seconds]
```

Two symptoms, one cause. `Instructions.performAll` submits **one decision's
instruction list** as a single task on the blocking executor, which is
`Executors.newVirtualThreadPerTaskExecutor()` — every submission gets its own
thread. Order holds *within* a list and nowhere else. Meanwhile `AgentState`
records what has been **decided**, never what has been **done**: `finished()`
is applied the instant a turn's decision is returned, while that decision's
`[Remember.Answer, TurnEnded, Release, TakeWork]` may not have started.

So a `Forget` arriving in that window found `busy() == false`, took the idle
path, and its delete raced the write it was supposed to follow. The engine's
own javadoc already claims the ordering that is missing here — "IN ORDER",
"the order is load bearing" — it just stops at the edge of one decision.

Deleting the actor's durable state made it worse. `deleteState` reached around
the actor into `DurableStateStoreRegistry` and called `deleteObject(...).join()`
on a worker thread, while the actor was alive and could still persist. That is
the undead entity, and it is a better fit for CI's "failed to stop" than a lost
write is.

## The shape

**A forget is a row in a table, not a message to an actor.** It rides the
machinery that already orders work.

`Harness.forget(agentId)` writes a poison pill and nudges — exactly what
`offer` does. `onBacklogUpdated` is unchanged: a busy agent ignores the nudge
and finds the pill later, an idle one looks now, a passivated one is woken by
it like any other work.

The agent discovers the pill by **taking** it. Taking is already the far side
of the dispatch/acknowledge gap: `TakeWork` is the last instruction in
`endTurn`'s list, so a reply to a take cannot arrive until that list has
finished. The race is not avoided, it stops existing.

### Pills live in their own table

Not as a backlog row. A backlog row is the application's observation type `O`,
merged by an application-supplied `BacklogCoalescer<O>`; a pill is neither and
must never be coalesced, rendered into a claim, or merged away. A separate
table keeps backlog rows homogeneous and keeps the coalescer honest.

Keyed `(agent_type, agent_id)`, the identity discipline of every other table —
never a composed string. Presence is the whole payload.

### take() becomes tri-state

```
TakeResult = Work(itemId, claim) | Empty | Poisoned
```

The pill is checked FIRST, in the same transaction as the take. That is what
gives "do not drain" for free: queued work is never reached, and the forget
deletes those rows anyway. Draining would mean running real tool calls — the
watchman's `prune_images` — on behalf of an agent whose record of having done
so is about to be erased. Side effects survive; the account of them does not.
That combination is worse than either.

### The phase gains AwaitingWork

`Idle` is doing two jobs today — *nothing is happening* and *I have asked for
work and am waiting* — and cannot tell them apart. That is why two takes can be
in flight at once, tolerated today by a `busy()` guard and by `take` being
stranded-first. Splitting them makes the duplicate stop existing, and gives the
poisoned reply an unambiguous place to land.

| in phase | input | → phase | instructions |
|---|---|---|---|
| `Idle` | `BacklogUpdated` | `AwaitingWork` | `TakeWork` |
| `Idle` | `Recovered` | `AwaitingWork` | `TakeWork` |
| `AwaitingWork` | `BacklogUpdated` | — | none |
| `AwaitingWork` | `WorkTaken` | `CallingModel` | `TurnStarted, Remember.Input, CallModel` |
| `AwaitingWork` | `NoWork` | `Idle` | `Sleep` |
| `AwaitingWork` | `Poisoned` | `Idle` | `Forget, Sleep` |
| `CallingModel`/`WorkingTools` | any take reply | — | ignored |
| any | end of turn | `AwaitingWork` | `…remember…, TurnEnded, Release, TakeWork` |

**`busy()` must be redefined, and this is the sharp edge.** It is
`!(phase instanceof Idle)`, and `takeWork` computes which turn to sweep from
it:

```java
TurnId finished = state.busy() ? null : state.turnId();
```

Add `AwaitingWork` without touching that and `finished` becomes null where it
used to be the completed turn, the finished backlog row is never deleted, and
the agent re-takes the same observation forever — a livelock that looks like an
agent working, just repeating itself. `busy()` therefore means *a turn is
running* (`CallingModel | WorkingTools`), and its uses are replaced with
explicit phase matching, because the compiler cannot catch a predicate whose
meaning shifted.

### No Forgetting phase

Considered and rejected. Its only jobs would be surviving a crash mid-wipe and
suppressing a redundant wipe. The pill already does the first — it is deleted
last, so a crash anywhere leaves it, and the next incarnation takes it and
re-runs. The second is a rare duplicate of an idempotent operation. Neither is
worth a new arm on a persisted sealed type, whose wire names are a compatibility
surface.

### Dying, in order

1. every participant's rows, all attempted
2. the actor's own durable state — last, because the actor is ending
3. **the pill — the commit point**
4. `Sleep` → the shard passivates → `Stop` → `thenStop()`

The pill is deleted last so the whole sequence is resumable: a crash before
step 3 leaves the pill, and the next incarnation converges. Deleting it first
would lose the forget silently.

Step 4 goes through the shard rather than self-stopping. The entity is
registered `.withStopMessage(NessyMessage.Stop)` and `AgentActor` already
answers it with `Effect().none().thenStop()`. Self-stopping bypasses the shard,
which finds out by watching the actor die — the way buffered messages get
stranded. This engine has had one passivation bug already.

Step 2 replaces `deleteState`. **Measured:** the Java DSL's `EffectFactories`
has no `delete()` (`javac`: *cannot find symbol: method delete()*), but the
Scala factory does, and `EffectImpl extends javadsl.EffectBuilder`, so it is a
plain cast — no reflection, no suppression. A probe against
`pekko.persistence.testkit.state` confirmed the actor terminated AND a fresh
actor on the same `PersistenceId` recovered `emptyState()`. It is a real
delete, not a tombstone.

## What this does not fix

**A parked turn does not take.** An agent parked on an approval with the
watchman's three-day term will not see its pill for three days, and
`Harness.forget` returns immediately regardless. Whether a park counts as
working or as waiting is UNDECIDED and deliberately not settled here.

**Participants are still a hardcoded list.** `Instructions.forget` names
memory, backlog and claims; `nessy_summary`, the notebook, plan memory, the
intent store and the starter's `nessy_pending_approvals` are all keyed by agent
and none of them are told. Every new store is a silent leak. The registration
design — an interface implemented by the live instances that already know where
their data lives, collected as beans in Spring and passed explicitly otherwise
— is deferred to its own spec. Ruled out along the way: discovery by method
signature (a typo becomes a silent no-op, and "we deleted it" is not a thing to
be wrong about) and `ServiceLoader` (no-arg construction means a disposer that
cannot know which database it deletes from).

## Testing

The original race is reproducible **deterministically**, not one run in five,
through the `EngineConfig.blocking(Executor)` seam: gate a submitted batch
before `Remember.Answer` runs, forget, release, and assert what survived. The
same assertion becomes the property test for the new design — a forget arriving
mid-turn does not delete until that turn's writes have landed.

Two pairs of facts live in different files and must be pinned together, because
no test fails when one drifts:

- "ignore a duplicate `WorkTaken` when busy" is only safe because `take` is
  stranded-first. If `take` ever claimed a fresh row instead, the guard would
  silently leak claims.
- the pill must be deleted as part of forgetting, or it poisons the next
  incarnation of a reusable id — a booby trap with no visible cause.

To verify before relying on it: whether `delete()` on already-deleted state is
silent or noisy, since the redundant-wipe window depends on it being harmless.
