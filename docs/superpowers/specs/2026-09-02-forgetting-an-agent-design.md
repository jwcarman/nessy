# Forgetting an agent

**Status:** designed 2026-09-02, not built. Blocks `AgentApprover` in
`2026-09-02-approval-policy-design.md` §5, whose trust model requires a fresh agent
per decision and therefore produces one dead agent per decision.

## 1. What is wrong

**An agent instance can be created but never destroyed.** `Harness` offers `type()`,
`observe()` and `subscribe()`. There is no `forget`, no `dispose`, no `delete`.
Passivation is not deletion — `Sleep` unloads the actor and leaves every row where it
was.

That was survivable while an agent id named something long-lived: a house, a ticket,
a customer. Two things have made it urgent.

**It already leaks, in shipped code.** `nessy-examples/chat-web`'s browser client
does `location.hash.slice(1) || localStorage.getItem("agentId") || crypto.randomUUID()`
(`app.js:16`), so every browser that has ever opened that page holds a permanent agent
— a permanent state row and a permanent transcript, one per browser, forever. Nobody
noticed because it leaks slowly.

**And the judging agent makes it fast.** `AgentApprover`'s trust model requires a
FRESH agent id per decision — a long-lived judge accumulates every request it has
reviewed, so a hostile requester's text sits in its context while it judges somebody
else's call. That is a persistent injection surface built out of a feature. The
correct design therefore produces one dead agent per approval, forever.

A component whose documented usage is "leaks a row per call" should not ship.

## 2. What one forgotten agent leaves behind

| Store | Owned by | Reachable today |
|---|---|---|
| Pekko `durable_state` row, persistence id `type\|id` | the application's own DDL and plugin choice | **not** through the behavior — the Java durable-state DSL has `persist`, `none`, `stop`, `unhandled`, `stash`, `unstashAll`, `reply`, `noReply`, and no `delete` |
| `nessy_transcript` rows | `Memory` | **no** — `Memory` has `recall` and `remember`, nothing else |
| `nessy_backlog`, `nessy_claim` | the engine | yes, internally |
| `nessy_note`, `nessy_plan_task` | notebook / plan stores | only if granted; each has its own store |

So forgetting is not one method. It is a new verb on `Memory`, a deletion the
behavior DSL will not give us, and a new message to the actor.

## 3. The shape

### 3.1 Deletion goes through Pekko's own store, not SQL

`DurableStateStoreRegistry.get(system).getDurableStateStoreFor(...)` returns a
`DurableStateUpdateStore`, and **that interface has `deleteObject(persistenceId)`**
(and an overload taking a revision). Measured on
`org.apache.pekko.persistence.jdbc.state.javadsl.JdbcDurableStateStore`.

This matters more than it looks. The durable-state table is **not Nessy's** — the
application picks the plugin (`pekko.persistence.state.plugin` is deliberately unset
by the starter, because there is no right default) and ships the DDL. Deleting by SQL
would mean knowing a table name Nessy does not own, from configuration Nessy does not
read, in a schema an application chose. Going through the store API means forgetting
works on whatever plugin the application picked, including ones that are not JDBC at
all.

### 3.2 `Memory` grows a third verb

```java
public interface Memory {
  Context recall(AgentId agentId);
  void remember(AgentId agentId, HistoryMessage message);
  void forget(AgentId agentId);   // new
}
```

Every implementation must honour it: `TranscriptMemory` deletes the agent's rows,
`PipelineMemory` forwards to whatever it wraps. **A default method would be the wrong
kindness** — a memory that silently declines to forget turns a privacy operation into
a no-op, and the caller cannot tell. It is abstract, and implementations are made to
answer.

### 3.3 Forget when idle, decided by the actor

An agent knows whether it is busy — that is `AgentState.busy()` — and nothing outside
it does without asking. So `forget` is a message, not an external deletion:

```
Harness.forget(agentId)  ->  NessyMessage.Forget  ->  the actor decides
```

- **Idle**: delete memory, delete backlog rows and claims, delete the durable-state
  object, then stop. The order matters — state last, because it is the record that the
  agent existed at all, and a crash between steps should leave less behind rather than
  an agent whose state is gone but whose transcript is not.
- **Busy**: do not forget, and do not refuse either. The intent is recorded in the
  agent's own state, and the agent forgets itself when the turn ends — the same way
  `Sleep` already waits for a quiet moment.

**Why not the two alternatives.** Refusing while busy pushes a retry loop onto every
caller for a condition they cannot observe. Forgetting regardless can strand a turn
mid-model-call, and the answer then completes into a dead incarnation — which is
exactly the passivation defect already fixed once (52d4a387) and not worth
reintroducing under a new name.

**The cost of forget-when-idle, stated plainly:** `forget` is a request, not a
receipt. A caller cannot know from the return that the agent is gone. For the judging
agent that is fine — it is one-shot and idle almost immediately. For an application
that needs certainty (a deletion request under GDPR, say), a request is not enough,
and §6 keeps that out of scope deliberately rather than pretending otherwise.

### 3.4 What it does NOT touch

Stores the agent merely used — notebook, plan, intent — are **not** swept. They are
separate stores with their own lifecycles, an application may share them across
agents, and guessing is worse than not acting. An application that grants a notebook
and wants it forgotten too forgets the notebook itself. This is named here so the
omission is a decision rather than an oversight.

## 4. Why not the alternatives

**A reaper** sweeping ephemeral agent types older than some age. The project has the
idiom (`ReminderSweep`), and it needs no new public method. But it is a workaround for
a missing verb: "I am done with this instance" is a thing an application knows and a
sweeper has to guess, and a guess wrong in one direction deletes a live agent while
wrong in the other keeps garbage forever. A reaper is a reasonable BACKSTOP for agents
nobody remembered to forget; it is not the mechanism.

**A no-op `Memory`,** so a judge persists nothing. Ruled out by measurement:
`Instructions.callModel` builds its request from `deps.memory().recall(agentId)` and
nothing else, so memory is not an archive an agent could go without — it is *how the
observation reaches the model*. A judge with no memory is a judge that sees nothing.

**A bounded pool of reused ids.** Growth stops, and so does memorylessness: a reused
id inherits the previous decision's context, which is the exact injection surface the
fresh id existed to close.

## 5. Testing

- **Forgetting an idle agent leaves nothing**: observe, let the turn finish, forget,
  then assert the transcript rows, the backlog rows, the claims and the durable-state
  object are all gone — `getObject` returning empty is the assertion that matters,
  because it is the one that proves the store call happened rather than the row being
  deleted some other way.
- **Forgetting a busy agent waits**: a scripted model that blocks mid-turn, a forget
  during it, and an assertion that the turn still COMPLETES and the agent is gone
  afterwards. A forget that corrupts a turn in flight is the failure this design is
  shaped to avoid, so it gets the sharpest test.
- **A forgotten agent starts clean**: observe the same id again and assert the model's
  context is empty, which is what memorylessness means in practice.
- **Every `Memory` implementation forgets**: part of the memory contract test, so a
  new implementation cannot quietly skip it.
- **Forgetting an agent that never existed is silent**, like cancelling an alarm that
  was never armed.

## 6. Out of scope

- **A receipt.** `forget` does not report when the agent is actually gone. A caller
  needing certainty needs a different mechanism, and inventing one before something
  requires it would be guessing at its semantics.
- **Cascading to granted stores** (§3.4).
- **A reaper** as a backstop for un-forgotten agents. Worth having; not this.
- **Bulk forget** — "every agent of this type older than N". A loop over `forget` is
  the honest first version, and if it proves too slow, that is a measurement and a
  follow-up.
