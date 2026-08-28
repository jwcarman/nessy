# The actor composition

**Status:** designed in conversation with James, 2026-08-28. Written down before it is built.
One thing is deliberately OPEN — see §8, routing a late answer back to the right actor.

**Amends** `2026-08-27-actor-runtime-design.md` on two points it states outright:

- *"No `TurnActor`."* That spec rules one out — one turn at a time, no concurrency to exploit,
  no state the agent does not already hold. **Reversed here**, for a reason it did not consider:
  a turn actor lets the agent's job shrink to the backlog and lifecycle, gives a turn its own
  failure boundary, and makes it the *only* thing that touches `Memory`.
- The backlog moves back **into** the agent's durable state, reversing the ruling in
  `2026-08-28-ingest-and-turns-design.md` §2.1. Justified now because coalescing bounds it, and
  because it makes taking an observation **atomic** (§3).

**Companion to** `2026-08-28-principles-and-findings.md`, whose numbered principles are cited
rather than restated.

---

## 1. The shape

```
AgentActor            DURABLE     one per agent id
  backlog, Coalescer, ObservationRenderer, lifecycle
  │
  └─ TurnActor        ephemeral   one per turn
       Memory, the model loop
       │
       └─ ToolInvocationActor   ephemeral   one per tool call
            orchestrates approve -> execute, reports ONE outcome
            │
            ├─ ApprovalActor    ephemeral   asks the Approver, owns the deadline timer
            └─ RunnerActor      ephemeral   executes the tool, writes the result to Claims
```

**Only `AgentActor` persists.** Everything below is ephemeral and derives its position from
durable facts a collaborator already owns (§6). That is the property that makes four ephemeral
layers safe rather than reckless.

Each layer earns its boundary by having a genuinely different **lifetime**: forever / one turn /
one call / one decision. Nothing else justifies a layer.

## 2. The agent

```java
record AgentState<O>(List<BacklogItem<O>> backlog, BacklogItem<O> inFlight, Phase phase)

record BacklogItem<O>(String id, O observation, Instant receivedAt)
```

Its collaborators are a `Coalescer<O>`, an `ObservationRenderer<O>`, and nothing else. It does not
know about `Memory`, tools, approvals or models.

**`id` is load-bearing.** The `Remembrance` key for the observation derives from it, which is what
makes a re-take after a crash idempotent instead of duplicating a user turn. That is not
hypothetical: a live soak on 2026-08-28 recorded ONE observation and then ran for six rounds
against an unchanging context, because a coalescing key was reused as an entry id and every
derived key collided. Ids are minted per arrival, never reused.

**`receivedAt` is the only clock a pure coalescer gets.** Staleness ("drop anything older than five
minutes") is a real policy and a pure function must not read a clock.

## 3. Ingest, and why the take is atomic

```
tell(O)   →  append to the END of the backlog  →  coalesce  →  persist
```

An observation is never refused. Measured before this design: 26 of 31 rounds refused while parked
on one approval — the steady state of any agent that both runs continuously and asks a human
anything.

Starting a turn moves an item from the backlog to `inFlight` **in a single write**:

```
persist(backlog minus item, inFlight = item)      ← ONE CAS; the item is never nowhere
spawn TurnActor(render(item.observation), item.id)
turn: remember(...) → tell Started(item.id)
persist(inFlight = null, phase = running)
```

The invariant is small enough to check by eye: **an item is either in the backlog or in flight,
never neither.** A crash after the first write leaves it in `inFlight`; recovery re-renders,
respawns, and the key derived from `item.id` makes the repeated `remember` free.

This is why the backlog belongs in state. With it in a separate store, that same guarantee needed
a dedicated `takenEntryId` field and a bespoke recovery path. Here it falls out of where the data
lives.

**The agent renders, the turn receives blocks.** `render(item.observation)` happens at the agent,
so the turn actor and everything below it sees `List<ContentBlock>` — a closed framework type. The
user's `O` never reaches the turn machinery, which means no `Codec<O>` down there and non-generic
message protocols (`EntityTypeKey.create` needs class literals). The generic boundary ends at the
agent.

Rendering at turn-start rather than at ingest is also what lets a renderer fix reach observations
that are already queued.

## 4. Coalescing

```java
public interface Coalescer<O> {
  List<BacklogItem<O>> coalesce(List<BacklogItem<O>> backlog);
}
```

Called on **every ingest**, after the arrival is appended. The word is exact: a backlog is a thing
you **groom** — items merged, superseded, deprioritised, dropped — which is precisely the set of
operations this expresses. `ObservationStream` was rejected because `pekko-stream` is on the
compile classpath and a stream does not supersede its own elements; `Observations` because it
collides with Micrometer's `Observation`.

Coalescing on write is the ONLY place observations become one. That is why a turn takes exactly one
item (§5): draining the whole backlog into a single turn would silently override the user's policy,
merging things a vocabulary explicitly declined to merge.

**The framework must reject a returned list containing duplicate ids.** Merging two items and
keeping one of their ids is the natural thing to write, and it is exactly the bug described in §2 —
reachable in one line of user code, silent, and green under test. A `BacklogItem.of(observation,
now)` factory mints; the javadoc says a merged item is a NEW item; and the seam validates.

**Open:** when N items merge, does the survivor inherit the OLDEST `receivedAt` or take `now`?
Inheriting the oldest keeps its queue position honest; taking `now` makes a busy topic look
eternally fresh to a staleness policy. Leaning: inherit the oldest.

## 5. The turn

One observation per turn. The turn actor owns `Memory` and nothing else does — which matters
because on 2026-08-28 three fix rounds were spent on `ToolWorker` and `ToolCallActor` each having
their own record-then-notify ordering, and diverging: one caught claim failures and recorded an
error, the other threw and stalled; one caught executor rejection, the other died. One owner, one
ordering rule, one place to get it right.

Its loop:

1. `remember` the rendered observation, then tell the agent `Started(itemId)`.
2. Call the model.
3. If the model asked for tools, spawn one `ToolInvocationActor` per call.
4. As outcomes arrive, decide retry (§7); when all calls have settled, reconstitute results from
   claims, write the exchanges, delete the turn's claims, and go back to 2.
5. When the model answers without tools, the turn ends.

**Every call must produce an exchange — success or failure.** `ToolResult.error(...)` for a
failure, never nothing. `RemembranceFold` withholds an assistant message from every recall until
each of its `tool_use` ids has a matching exchange, so a call left unrecorded withholds the whole
assistant turn forever and the model reissues the tool in a loop. That was measured: a thrown tool
produced a 30-second reissue loop before it was fixed.

## 6. Recovery: derived, not replayed

The turn, invocation, approval and runner actors are all ephemeral. After a crash nothing replays a
state machine — the turn respawns and **asks**:

| question | who answers |
|---|---|
| is there a decision for this call? | the `Approver` |
| is there a result for this call? | its **claim** (§7) |
| is there an exchange for this call? | `Memory` |

Approved, claim present, no exchange → write the exchange. Approved, no claim → spawn a runner.
Still pending → spawn an approval actor, **with the deadline recomputed from the persisted ask
time**, never from now — otherwise an unanswered approval renews its own lease on every restart.

No per-call phase is persisted anywhere. Every phase boundary is marked by a durable fact some
collaborator already owns.

**Supervision is `stop`, never `restart`** — a restarted tool call is a tool that may run twice.
And with four layers of death-watch, one rule is stated once and applied at every level: **a child
reports its outcome before it stops, and a parent that sees a child stop WITHOUT an outcome treats
that as a failure of that phase.** Get that wrong anywhere and the symptom is a silent stall.

## 7. Tool invocation

A `ToolInvocationActor` owns one call's whole workflow and reports **one** outcome. The turn stays
simple: spawn N, await N, continue the loop.

```java
sealed interface ToolOutcome {
  record Succeeded(String claimId) implements ToolOutcome {}
  record Denied(String by, String reason) implements ToolOutcome {}
  record Failed(String detail) implements ToolOutcome {}   // the ONLY one with a retry affordance
}
```

**A denial is a decision, not a failure.** Retrying it means re-asking a human who already said no.
The type makes that unrepresentable rather than documented: there is no retry arm for `Denied` to
reach for. **An expired approval is a `Denied`** whose reason says it lapsed — it looks like a
failure but must behave like a decision, or an unattended agent re-asks forever.

`Failed` may be retried, bounded by the `maxAttempts` declared on the **grant**. Whoever holds the
grant decides; the call's own machine stays dumb.

### 7.1 Claim-checked in both directions

Arguments travel **down** as a claim id, results travel **up** as a claim id. The runner writes the
result to `Claims` and returns a reference; it never puts contents in a message.

Three reasons, in order of weight:

1. **A message carrying a result is serialized across the wire under remoting.** A claim id is
   thirty bytes. This is what makes tool results safe when the entity moves — and correctness must
   not depend on the single-node deployment (principle 1.6).
2. **The notification becomes droppable.** The durability point is the claim write, not the
   delivery, so losing a message costs latency rather than data (principle 1.10). Live actor: told.
   Respawned actor: asks. Neither is a special case.
3. **The result claim is a durable dispatch record.** "Ran, then died before the exchange was
   written" becomes distinguishable from "never ran" — the thing the old inbox-outbox spec called
   *"the row we have never had"*. It falls out of this design rather than needing its own
   mechanism.

**Failure detail stays inline**, not claim-checked: it is short, and you want it in a log line and
a span attribute rather than behind a reference nobody dereferences while debugging. That is a
stated choice, so nobody "fixes" the inconsistency in either direction.

### 7.2 What this does NOT solve

A crash **inside** the tool call, before the runner writes the claim, remains unknowable. The
window shrinks from "anywhere between starting the tool and recording the exchange" to "inside the
execution itself", which is the irreducible part — the part Temporal also declines to solve.
`maxAttempts` is our version of that contract: it does not make a tool safe, it lets the tool's
owner say whether it is.

## 8. OPEN — routing a late answer to the right actor

**Deliberately unresolved. Do not implement around it.**

An approval answered three days later, or a deferred tool result returning hours later, has to
reach a specific ephemeral actor four levels down, from a web request holding only
`(agentId, callId)`.

What is already settled:

- The fact must be **durable before it is acknowledged** — a human clicks deny, we return 200, and
  the box loses power a millisecond later. The `Approver` owns decision durability; a deferred
  result is written to a claim by the inbound door.
- The live and dead paths must **agree**: a live actor is told, a respawned one asks, and neither
  is special-cased.

The candidates:

- **Forward down the tree** — every layer learns about approvals to pass them along, coupling the
  turn and invocation actors to something they otherwise ignore.
- **Receptionist registration** under `(agentId, callId)` — the waiting actor registers, the door
  looks it up and tells it directly, nothing between them knows. Closest to the existing
  "answers route by address" ruling.
- **Approver/claim-mediated only** — the door writes durably and the actor discovers it, which is
  needed for the restart path regardless.

Realistically the last two together. What is NOT settled: how the Receptionist key is scoped, what
happens to a late answer for an already-settled call (the at-most-once rule says drop with a WARN —
but then its claim has no owner to be swept with), and whether a deferred result's claim needs an
owner that outlives its turn.

## 9. Other open items

1. **§4's merge timestamp** — oldest versus now.
2. **Attempt counts across restarts.** If the invocation actor owned retry, an ephemeral actor
   restarting at attempt zero could exceed `maxAttempts` indefinitely across process bounces. Either
   attempts become a durable fact like decisions and exchanges, or retry stays with the turn.
3. **Blocking I/O on the dispatcher.** The agent's `ingest` and the turn's `Memory` calls are
   blocking JDBC on a Pekko dispatcher. Invisible with one agent; classic starvation with many,
   since the model and tool workers share it. Unresolved: worker actor, `pipeToSelf` plus stash, or
   accept it knowingly.
4. **`ToolResult` is text-only** and should carry `List<ContentBlock>` — MCP's `CallToolResult`
   content is an array and Anthropic's `tool_result` accepts images. Ruled, not scheduled. It makes
   the type recursive and forces OpenAI-compatible providers to flatten non-text blocks, and
   dropping an image silently would be the wrong answer.
