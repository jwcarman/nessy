# The actor composition

**Status:** designed in conversation with James, 2026-08-28. Written down before it is built.
Routing a late answer back to a specific ephemeral actor (§8) was resolved on the same day.

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

### 7.3 Deleting claims: bulk by id, and no owner field

**Ruled 2026-08-28 by James.** A claim carries NO owner. The turn actor holds every `callId` it
minted, so it deletes its claims by handing that list to a bulk delete — one batch, not N calls.

An owner field would exist only so a sweeper could ask "whose claims are these?", and nothing needs
to ask: the happy path already knows the ids, and the crash path is covered by expiry (§8a). So the
field would buy nothing while adding a value that can go stale — a claim naming a turn that no
longer exists is a lie a sweeper would then act on. Two mechanisms cover the whole space:

| path | mechanism |
|---|---|
| turn completes, or fails, or is denied | bulk delete by id, one batch |
| turn dies before deleting, or a late answer outlives its turn | expiry reaper (§8a) |

The bulk door is a `Substrate` batch so the deletions land together; a partial delete is
indistinguishable from a leak, and the reaper would clean the remainder anyway — but the metric in
§8a would then be measuring our own bug rather than the condition it exists to detect.

### 7.2 What this does NOT solve

A crash **inside** the tool call, before the runner writes the claim, remains unknowable. The
window shrinks from "anywhere between starting the tool and recording the exchange" to "inside the
execution itself", which is the irreducible part — the part Temporal also declines to solve.
`maxAttempts` is our version of that contract: it does not make a tool safe, it lets the tool's
owner say whether it is.

## 8. Routing a late answer to the right actor

**RESOLVED 2026-08-28 with James.** An approval answered three days later, or a deferred tool result
returning hours later, has to reach a specific ephemeral actor from a caller holding only a token.

### 8.1 Not a persisted actor path

Pekko will serialise an `ActorRef` — `ActorRefResolver.toSerializationFormat` / `resolveActorRef` —
and persisting that string is the obvious first idea. **Reject it.** `resolveActorRef` on a dead
actor's path does not fail; it returns a ref that routes to **dead letters**. So a stale slip means
a human clicks deny, receives a 200, and nothing happens — silently. That is the exact scenario the
"persist the decision BEFORE the reply" ruling exists to prevent, reintroduced through the
addressing layer. A path is also node-encoded, so it breaks again whenever a sharded entity moves.

**A path is an address, not an identity.**

### 8.2 The Receptionist holds the identity

```java
ServiceKey.create(ApprovalActor.Command.class, "approval:" + agentId + ":" + callId)
```

The key is derived from facts that outlive any process. The **registration** is re-established by
whoever is alive and is never persisted, so it cannot go stale. `find` returns the live refs, or
empty — and empty is a fact the caller can act on rather than a message quietly vanishing.

The inbound door then has no special cases:

```
write the fact durably     (the Approver's decision, or the result claim)   ← the truth
respond 200                                                                  ← now honest
Receptionist.find(key) → tell if present                                     ← latency only
```

An empty `find` needs no retry and no queue: a respawned actor asks on startup (§6). Live actor is
told, dead actor asks, neither is special-cased.

**The notification may genuinely be dropped, and that is correct** — principle 1.10, notifications
are hints. Losing one costs the latency until the actor asks. It cannot lose a decision, because
the decision was durable before anyone was told anything. Do not "fix" this later by adding
delivery guarantees to the notification; the guarantee is in the write.

**Register only what can be answered from outside.** Receptionist registrations gossip across a
cluster, so registering every actor would churn. Only actors awaiting an EXTERNAL answer need to be
findable — approvals and deferred tool results, which are rare and long-lived. A tool call that
finishes in fifty milliseconds never registers.

`ServiceKey.create` takes a class literal, so a registered actor's protocol must be non-generic —
which this design already guarantees, since `O` never travels below the agent (§3).

### 8.3 The token handed to a tool

A tool that defers is handed a **string encoding the service key**, and presents it later.

This is what Continuum's computation id was for, and it is better in the way that matters: **derived,
not minted.** Continuum allocated an opaque id, stored it in a registry, and the registry had to be
consulted to learn what it meant — hence its own storage and its own lifecycle, and the possibility
of an orphan. A token over `(agentId, callId)` allocates nothing, cannot be orphaned, and survives a
restart precisely because it was never bound to a live actor.

It also leaves the actor spec's §7 ruling intact rather than reversing it: *"answers route by
address... nothing is minted and nothing is handed out."* Handing over a token that ENCODES an
address the call already had is giving out the envelope, not minting an identity.

**It must be unforgeable once it leaves the process.** A tool may be a remote MCP server, and a raw
`agent-x:call-y` is guessable — anyone able to reach the endpoint could deliver a fabricated result
into another agent's turn, or answer an approval. An HMAC over `(agentId, callId)` with a server
secret is enough and stays stateless, so nothing is stored and "nothing is minted" still holds.

### 8.4 What this retires

The result-claim (§7.1) is a durable, deduplicating, expiry-swept memoised outcome keyed by call.
That was the last job Continuum was kept for in the actor spec — *"a memoised outcome remains the
only answer to 'it ran and died'"* — so that reason no longer stands.

## 8a. Claim expiry — the backstop for everything §8 leaks

**Ruled 2026-08-28 by James.** Every claim carries an **expiry instant**, and a periodic job
deletes expired claims **and reports how many it deleted**.

`deleteTurn` at turn end stays the primary mechanism. Expiry is the backstop, and it covers every
path that mechanism misses, without any of them needing their own analysis:

- a turn abandoned by a stall never reaches its own deletion (open item in the actor spec)
- a deferred result arriving after its turn settled is never named by any live turn (§8, §7.3)
- a claim written just before a crash that no state ever names

**The count is the point, not decoration.** A silent reaper cannot be distinguished from a dead
one, and a rising count is the signal that something upstream stopped deleting its own claims. Emit
it as a metric, not only a log line.

Two things this must get right, and both are the hazard that TTL-versus-`approvalTerm` already
posed for transcript retention:

**Expiry must exceed the longest legitimate life.** A claim holds the arguments of a call that may
park on a human for `approvalTerm` — three days by default, and configurable. Expire it sooner and
we delete the arguments of a call a human is about to approve, so the tool cannot run: the failure
looks like a broken approval rather than a retention bug. So expiry is derived from the longest
park a turn can legitimately take, never a hardcoded constant.

**Err long.** A leaked claim costs disk. A claim deleted too early breaks a turn. The costs are not
symmetric and the policy should not pretend they are.

### 8b. Claim age is the stalled-turn signal we do not otherwise have

**Ruled 2026-08-28 by James.** The same periodic job reports the AGE DISTRIBUTION of live claims —
how many are older than 1d, 3d, 7d, 14d — alongside what it deleted.

The two numbers do different jobs. The delete count is a **lagging** signal: it says what already
expired. The age buckets are a **leading** one: they show a leak forming, and they distinguish
"many claims because we are busy" from "many claims because turns are not ending".

**The sharp use is stall detection.** A turn deletes its own claims when it ends, and the longest a
turn can legitimately live is one park — so a claim older than `approvalTerm` is, by construction,
evidence of a turn that never finished. That is an answer to the actor spec's open item #2,
*"silent stalls have no signal"*, obtained for free from a job we are already running. It is worth
saying plainly because every other stall we hit on 2026-08-28 was invisible until someone went
looking.

Shape:

- **Gauges, not a histogram.** Claims are a population sampled periodically, not a stream of events.
  One gauge per bucket, tagged, plus a single gauge for the **oldest live claim's age** — usually
  the most actionable number and one query to obtain.
- Emit alongside the reaper's delete count, from the same pass, so stock and flow always agree about
  the same moment.

**This needs a `Substrate` door that does not exist.** Today the document door is `read` / `write` /
`delete(kind, key, version)` / `keys(kind, limit)` — there is no way to find expired entries without
scanning every kind. Either the claim's expiry becomes a column the store can index and sweep, or
the SPI grows something like `deleteExpired(String kindPrefix, Instant now)` returning a count. That
is new SPI surface and wants James's yes before it lands.

## 10. Durability: exactly one durable actor

**Ruled 2026-08-28 by James.** `AgentActor` persists. Nothing below it does, and this is a design
rule rather than an accident of what has been written so far.

**Durability follows the truth, not the actor.** For each actor, ask what is lost if it dies:

| actor | state | already durable as |
|---|---|---|
| **AgentActor** | backlog, turn id, taken entry id | **nothing else — so it persists** |
| TurnActor | the conversation | `Memory` |
| ToolInvocationActor | which phase it is in | derived: is there a decision? a result claim? |
| ApprovalActor | waiting | the Approver's decision store |
| ToolExecutionActor | the result | the result claim (§7.1) |

Only the agent owns something no other store holds, and even it persists identifiers rather than
content (measured: 356 bytes at revision 15, holding three claims).

The argument for stopping at one is correctness, not cost. **Every durable actor is another source
of truth, and two sources of truth can disagree.** A persisted `ToolInvocationActor` that recovers
believing "approved" while the Approver's store says "denied" needs an arbiter, and any rule we
write for that is wrong under some interleaving. With one durable actor and derived children, the
question cannot be posed.

**Persistence does not buy at-most-once, and nobody should reach for it expecting that.** The
tempting second candidate is the executor: an actor that calls a non-idempotent tool and dies before
writing its claim. Persisting that actor changes nothing — the side effect happened OUTSIDE the
actor, so the window between "called" and "recorded" is identical either way (§7.2). If we ever need
at-most-once, the mechanism is an attempt marker written BEFORE the call. That is a claim write, not
a `DurableStateBehavior`.

### 10.1 AMENDED 2026-08-28 — "exactly one durable actor" was too strong

James: *"If the turn actor is not durable and it's somehow lost, we will process the observation
over again... thereby executing tools again."* He is right, and the argument above missed why.

**Claims do not deduplicate across a re-run, and cannot be made to.** Re-processing an observation
calls the model again, which mints fresh call ids, so nothing lines up with what was parked before.

I proposed keying claims by content — `(agent, observation, tool, arguments hash)` — to make them
memoise. **That was wrong on two counts** (James: *"No, you do not get to choose the claim id...
that should be assigned by the claim check service"*). The signature is
`String put(agentId, turnId, byte[])`: the id is RETURNED, never supplied. And that is the point of
the pattern rather than an accident of the API — a claim check is a ticket the service issues, and a
ticket you name yourself is not a claim check.

Cross-restart deduplication is a DIFFERENT mechanism — a content-addressed memo — and calling it a
claim would blur a door that is currently unambiguous. If we want it, it gets its own name.

Nor does the earlier "the side effect happens outside the actor" argument save it. That argument is
about the irreducible millisecond between calling a tool and recording its result. This is a
different and much larger window: an entire turn re-executed from the observation.

**Nothing is broken today** only because there is no separate turn actor — `AgentActor` IS the
orchestrator, it is durable, and its phase holds the tool-call records, so recovery re-issues just
the unsettled calls. The hazard arrives the moment §5's `TurnActor` is split out.

**The corrected rule.** Not "one durable actor" but:

> Every fact has exactly ONE durable home, and anything that can outlive a deploy must have one.

Two sources of truth for the SAME fact is the thing to avoid; that was the real content of §10. A
turn's progress is not a copy of something the agent holds — once `TurnActor` exists it is the only
home for it. And a turn can sit parked on an approval for `approvalTerm`, three days by default, so
a deploy during it is a certainty rather than a risk.

**Therefore:** when `TurnActor` is split out it is DURABLE, or the agent keeps holding the turn's
progress as it does now. Splitting it out non-durable is the version that silently re-runs
`prune_images`, and no amount of cleverness with claim ids substitutes for it.

## 11. The harness door: an ActorSystem in

**Ruled 2026-08-28 by James.** The engine is constructed from an `ActorSystem` and spins everything
else up itself — matching the harness-first spec's door discipline, one level down.

`ActorSystem` is the right seam because it is **the one type that is identical local and clustered**,
and every extension hangs off it. So the harness chooses how agents come up by asking the system what
it has, and the caller never states which world they are in:

| what the system offers | how agents come up |
|---|---|
| no cluster extension | spawned locally |
| cluster sharding | `ClusterSharding.get(system).init(Entity.of(...))` |

Nothing else in Pekko has that property, and it is why this seam is worth more than its convenience
in tests.

**A harness handed an existing system cannot be its guardian.** The guardian behavior is fixed when
the system is created, so top-level spawning goes through `SpawnProtocol` — the harness either asks
for `ActorSystem<SpawnProtocol.Command>` or spawns beneath a named parent it owns. This is the detail
that decides the signature; settle it before writing the door, not after.

Testing falls out rather than being designed for: `ActorTestKit` hands over a system, the harness
brings up the rest beneath it, and a whole agent — ingest, coalescing, turn, tool invocation,
approval — is exercised in-process with no Spring, no HTTP, and no database beyond whichever
`Substrate` the test chooses.

## 12. Turn events: agent-scoped subscription

**Ruled 2026-08-28 by James.** A caller subscribes to an AGENT and receives turn events across every
turn it runs, until it unsubscribes. Each subscription yields its own actor whose only job is
delivering to that one listener — an SSE response, a websocket, a test collector.

`TurnEvent`, `TurnObserver`, and `Subscription extends AutoCloseable` already exist in `nessy-api`
and do not change. What changes is **lifetime**: an observer was handed to one turn, and is now
registered against an agent.

### 12.1 The envelope, and why the events alone are not enough

`TurnEvent` carries no turn id — `TextDelta(text)`, `ToolCallCompleted(call, result)`,
`TurnEnded(failureReason)`. That was right when an observer belonged to one turn and "which turn?"
was answered by construction. Agent-scoped, it is not: two turns interleaving deltas produce an
unreadable stream.

So delivery is an envelope and `TurnEvent` stays exactly as it is:

```
(eventId: UUIDv7, turnId, TurnEvent)
```

**UUIDv7 specifically, because it sorts.** A resume cursor must answer "everything after this",
which a v4 makes unanswerable. The house convention hands us the ordering for free.

### 12.2 The rule that matters most: the agent never blocks on a subscriber

A browser on hotel wifi must not slow down a turn. **A subscriber that cannot keep up is dropped,
and told it was dropped.** Never buffered without bound, never awaited.

The per-listener actor exists FOR this: its mailbox absorbs jitter, and an overflow kills one actor
instead of stalling the agent every other subscriber shares. Do not soften this rule into "grow the
buffer" — an unbounded buffer converts a slow client into an agent-wide outage, which is the
distributed-systems failure this whole design is otherwise built to avoid.

Subscriber refs are `watch`ed, so a dead SSE connection deregisters itself. Without that, a
long-lived agent accumulates refs to dead listeners forever.

### 12.3 Lookback and resumption

Each agent keeps a bounded ring of recent envelopes. **It is a transient actor field, never the
persisted document** — §10 says the agent persists identifiers, and a replay buffer is content. A
restart therefore empties it. That is correct behavior, not a limitation to be fixed later.

On reconnect with a `Last-Event-ID`:

| the cursor | what we send |
|---|---|
| is in the ring | everything after it |
| is not in the ring, but sorts within it | everything sorting after it — v7 ordering makes this exact |
| predates the oldest entry (or the ring is empty after a restart) | the whole ring, **plus a gap signal** |

The middle row is why the ids sort: we answer correctly for a cursor we never held, rather than
dumping the ring blind and duplicating what the client already has.

**Silence on resume is the bug, not the lost events.** A client that resumes into a hole and is not
told renders a coherent, wrong picture. The gap is a stream-level frame emitted by the SSE layer,
NOT a `TurnEvent` variant — a gap is not something that happened during a turn, and putting it in
the sealed grammar would force every observer to handle a case that is purely about transport.

### 12.4 Non-goal: this is a view, never a source of truth

Turn events are **lossy by design** — the exact inverse of the discipline governing memory, claims,
and decisions everywhere else in this document. That asymmetry is why it is stated here: sooner or
later someone will want to persist from an observer, drive an approval off an event, or reconstruct
a transcript from the stream. All three are wrong, and all three look reasonable to someone who has
only read this section. The truth is in `Memory`, `Claims`, and the Approver's store; the event
stream is how a human watches it happen.

### 12.5 Finding the subscription from another node

`GET /agents/{id}/events` may land on any node. Resolution uses the same mechanism as everything
else late-arriving (§8): the Receptionist, keyed per agent. Nothing new is minted, and nothing about
subscription needs its own addressing scheme.

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

## 13. Routing to exactly one agent

**Ruled 2026-08-29 by James:** *"it doesn't matter how many harness actors we have as long as there
is deterministic routing to exactly one agent actor."*

The invariant is **one agent actor per (agent type, agent id)**. Harness count is not part of it.

| strategy | how the invariant holds | scope |
|---|---|---|
| **local children** | the harness actor is the parent, so it holds only if there is ONE harness per type | one JVM |
| **cluster sharding** | `entityRefFor(typeKey, id)` routes every caller to the same entity | whole cluster |

**Sharding is the real answer**, and it makes duplicate harnesses harmless: N harnesses for one type
all address the same entity. The entity key needs no new concept — one harness is one agent type, so
it is `EntityTypeKey.create(NessyMessage.class, agentType.name())`.

**Local routing needs a guard, and Pekko does not provide one.** Asked to spawn a duplicate actor
name, `SpawnProtocol` silently RENAMES rather than failing — measured 2026-08-29: `same`, then
`same-1`. So a second harness for a type would quietly parent a second `agent-<id>`, and the two
would write the same persistence id. The guard is a claim held in a per-`ActorSystem` extension,
because Pekko creates exactly one extension per system and synchronises it — a per-factory field
cannot see a second factory on the same system.

**The guard belongs to the local strategy, not to harnesses.** When the sharded strategy lands it
skips the claim; keeping it would forbid something that is safe under sharding.

**It covers one JVM only.** Nothing local can know about another process. Two JVMs routing locally to
the same agent type is unprotected, and only sharding fixes that.
