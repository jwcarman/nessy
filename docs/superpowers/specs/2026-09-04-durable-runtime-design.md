# The durable runtime

**Status:** designed 2026-09-04, not built.

Supersedes the hosting model in `2026-09-01-one-actor-per-agent-design.md` — the pure
`AgentLogic` it introduced survives untouched; the actor it lives inside does not.
Supersedes the storage model in `2026-08-24-jdbc-substrate-design.md` §transcript.
Amends the memory model of `2026-09-02-summarizing-memory-design.md`.

Origin: `~/Downloads/nessy-durable-agent-runtime.md`, and a conversation about why the
watchman corrupted its own transcript.

## 1. What is wrong

Three failures, all measured, all the same failure.

**A model call failed and the record could not say so.** Running the watchman locally,
summarization failed on reasoning-token volume, the context window overflowed, and the
model call failed. `Memory` stores `HistoryMessage` — a provider shape — and there is no
provider shape for "and then the call failed." So the turn wrote nothing, leaving the
transcript ending on a user message with no answer. The next observation appended a
second user message, and the transcript became something no provider will reliably
accept. That is not a bug in `Memory`. It is the only thing `Memory` could have done.

**A whole table exists to hold facts the transcript refuses.** `Claims.java:26` states
it plainly:

> They cannot live in the transcript either: an exchange is written whole, so for
> exactly the window a call is in flight the transcript is designed not to hold it.

`nessy_claim` is an event log — a per-turn, amnesiac one. It holds exactly what a fact
log would hold (the rendered observation, what each tool answered) and exists solely
because the permanent store will not accept a fact until that fact can be assembled into
a wire-valid shape. An approval parked three days keeps three days of facts in a scratch
table, deleted on the way out.

**And the trade is written into the interface.** `Memory.remember`:

> It is not durable until its tools finish, so a crash in that window loses it and the
> model is called again.

One cause underneath all three: **the call/result pairing invariant is a provider
constraint that was hoisted into permanent storage.** Anthropic requires pairs. OpenAI
requires pairs. *What happened* does not — a tool was called, and three days later a
person clicked a button, and in between there was a gap. That is a coherent story and an
incoherent transcript.

Everything else follows from that hoist. `ExchangeMessage` must exist and must validate
pairing, because a half-exchange is unstorable. `Context` needs a pair-atomic edit
algebra to carry the invariant through every transformation. `AgentState.observation` is
a pointer into the scratch store. And `Context` calls itself "wire-safe… a list of
messages that a provider will always accept" while enforcing exactly one of the several
rules that would make that true — the gap the watchman found.

Separately, and independently: Pekko. The engine is always-clustered to get one
mailbox per agent. That buys serialization, addressing and passivation, and charges for
them in a cluster, a race and a fifteen-second timeout on every test that touches the
lifecycle — plus a stopped turn stranding an entity (52d4a387), and a config bridge
pushing Spring's datasource into Slick so two connection pools can address one database.

## 2. The shape

```
                          FACTS  (durable, engine-owned, one table)
                             |
                 +-----------+-----------+
                 |                       |
             project                 project
             + ambient               + redact
                 |                       |
                 v                       v
               model                  watcher  <-- + deltas (never stored)
```

Both audiences get a projection. Neither gets the raw log.

Layer ownership:

| layer | owns |
|---|---|
| engine | appends facts; the transition transaction; effects; reapers. Projects nothing. |
| memory | assembles the **story** for the model: facts, truncated at a summary fact, ambient woven in |
| adapter | legalizes a story onto one vendor's wire |
| narration | projects facts for humans, redacting; interleaves deltas from the local node |

PostgreSQL is the only supported substrate. It provides durability, per-agent
serialization, the atomic transition, and an inspectable operational model. Nothing else
is required, and nothing else is introduced.

**Spring Boot is the only supported host.** Not a new dependency — the engine already
uses `spring-jdbc` (`Claims` is built on `JdbcClient`) — but now an explicit one, and it
carries weight: `@Transactional` for §4 rather than hand-rolled transaction management,
`spring.threads.virtual.enabled` for §11, `@Scheduled` for the reapers, and propagation
semantics for §5.2. Nessy is a testbed for agentic principles, and portability across
hosting frameworks is not one of the principles under test.

## 3. Facts, ambient, deltas

Two questions separate them. **Tense**: a fact about the past, or a statement about the
present? **Audience**: the model, or a human watching?

| | tense | audience | durable | why not |
|---|---|---|---|---|
| **fact** — observation received, model answered, model call failed, tool completed | past | both | yes | — |
| **ambient** — notes, plan, clock | present | model only | no | storing it freezes a stale snapshot |
| **delta** — text, reasoning fragments | in progress | watcher only | no | the fact that follows subsumes it |

The two non-durable kinds are non-durable for different reasons, which is why "durable
vs ephemeral" is the wrong top-level axis — it is a consequence, not the distinction.
Ambient is excluded because it would be a lie tomorrow. A delta is excluded because it is
redundant in one second.

**A delta never reaches a model.** It cannot: the completed answer covers the same
tokens, so sending both duplicates. Deltas have exactly one audience, ambient has exactly
the other, and facts are the intersection.

### 3.1 Vocabulary

`Fact` is the durable arm. A `Story` is made of facts. That makes the fold's rule read
as a sentence: *state is a deterministic fold of facts, and the story is what the model
is shown of them.*

`AgentEvent` keeps its current meaning — the live story for whoever is watching — and
gains structure: it is a projection of facts, interleaved with deltas. Its nine variants
split cleanly, seven facts to two deltas, so the SSE wire discriminators are unchanged.

| today's `AgentEvent` | becomes |
|---|---|
| `TurnStarted`, `ToolCallRequested`, `ApprovalRequested`, `ApprovalDecided`, `ToolCallCompleted`, `Answered`, `TurnEnded` | projections of facts |
| `TextDelta`, `ReasoningDelta` | deltas |

### 3.2 Redaction is a projection, not an exception

Today narration is sanitized at construction: no raw tool arguments are ever written into
an `AgentEvent`, because a browser will see it. The durable record must hold the real
arguments — the model needs them replayed.

Rather than two stores or two truths, the watcher's projection redacts: it replaces tool
arguments with the binding's `ActionRenderer` output. That is not a security exception
bolted onto the design; it is what that projection *does*, the same way the model's
projection weaves in ambient and legalizes for a vendor. The raw fact never leaves the
engine in either direction.

## 4. The transition

One event, one transaction:

```
BEGIN
  SELECT state, version FROM nessy_agent WHERE agent_id = ? FOR UPDATE
  fold(state, input) -> (next, effects)
  INSERT fact
  UPDATE state
  INSERT effects
  INSERT/DELETE reminders
COMMIT
```

Fact, folded state, emitted effects and reminder changes are consistent or none of them
happened. No external I/O occurs inside the lock, and the lock is held for the fold only
— microseconds, never a model call.

The fold is `AgentLogic.decide(AgentState, Input) -> Decision`. It is already pure, has
no clock, no store and no Pekko import, and it is unchanged by this design. That is the
property that makes this a swap rather than a rewrite.

A row lock rather than optimistic versioning: parallel tool calls fan in, so several
results routinely arrive at once and conflicts are the common case, not the exceptional
one. A version column is still maintained, for ordering, diagnostics and recovery.

## 5. Effects

An effect is a durable obligation to do work outside the transition — never proof that
the work happened. Committed with the fact that caused it, so an effect cannot exist
without its cause and cannot be lost after it.

**The committing thread claims its own effects.** It performs the same claim any worker
would (`SET status = EXECUTING WHERE id = ? AND status = PENDING`) and runs them on its
virtual thread. The reaper's floor sits above normal hand-off latency, so the local
thread wins that race essentially always and the reaper only picks up genuinely abandoned
work. The queue is the truth; running it locally is a latency optimization on which
nothing depends.

This is already the engine's shape — `Effect().persist(state).thenRun(...)` persists and
then runs the instructions on the same thread. The only change is that the instructions
become durable rows first, so a crash stops losing them.

### 5.1 Watchdogs

Before external work begins, the claim transaction also writes a watchdog reminder. It
protects the window in which a process dies after an external operation begins but before
its outcome is durably observed. Normal completion consumes the watchdog in the same
transaction that records the outcome.

Reapers run on every node as plain virtual-thread loops, claiming with
`FOR UPDATE SKIP LOCKED`. No leader election, no singleton scheduler.

A reaper does not decide what an expired watchdog *means*; it durably surfaces that an
expected outcome was not observed by its deadline. **An unknown outcome is not a
failure.** For an idempotent operation, retry under the same stable effect identity; for
a world-changing one, reconcile before deciding. The runtime must never blindly retry a
non-idempotent action whose outcome is unknown.

### 5.2 Completion is a transition

A worker that finishes a tool performs the transition itself: append the fact, fold, mark
the effect complete, write the next effects — one transaction. The effect row *is* the
durability, so a worker that dies holding one leaves it claimable and nothing is lost.

There is therefore **no durable mailbox**. This was considered and rejected as a table
that would duplicate what the effect row already guarantees.

## 6. Driving

The caller drives; the reaper recovers. One pattern, three entry points.

```
observe(agentId, obs)
  |- txn: INSERT backlog row                            -- commit
  '- then: lock agent -> fold(BacklogUpdated) -> commit  -> run effects on this thread
```

Commit before signalling, as today: reversed, the agent could take before the row lands
and go back to sleep with work in the table. A busy agent blocks the caller for a
transition's duration, not a model call's; the fold returns nothing when a turn is
running, and the row waits for that turn's closing take. That behaviour is unchanged.

Recovery needs no new table:

| stranded how | recovered by |
|---|---|
| effect claimed, node died | watchdog expiry over `nessy_effect` |
| observation committed, drive never ran | reaper over `nessy_backlog` for agents not idle-with-empty-backlog |
| external answer (approval, parked reply) | synchronous — the caller sees the failure and retries; the reply token stays valid |

The backlog reaper needs `last_touched_at` on the agent row so it can find genuinely
stalled agents — not idle, nothing pending, untouched for N minutes — rather than racing
live ones. A stall detector with no heartbeats and no leader election.

**`Instruction.Sleep` deletes.** There is no resident thing to unload: the thread returns
and the agent is a row. The passivation-hang class of defect goes with it.

**Recovery stays undistinguished.** `AgentLogic.onRecovered` is fed on every drive, as it
is fed on every activation today, so the rare path stays the common path.

## 7. Projections

### 7.1 To the model

Two jobs that `Context` conflates, separated:

- **Shaping** — elide old tool results, keep recent, drop, map. Provider-neutral, and the
  application's hook. It operates on the **story**, and gets simpler there: a story has no
  pairing invariant, so `drop` need not be pair-atomic.
- **Legalization** — pairing, role alternation, non-empty content, per-vendor quirks.
  Genuinely per-provider, and it moves wholly into the adapters. No shared type is left
  claiming to have done it for them.

The watchman failure lands here. Under a story the record is simply true —
`ObservationReceived`, `ModelCallFailed(context-too-large)`, `ObservationReceived` — and
the Anthropic adapter decides what two adjacent observations around a failed call should
look like on *its* wire. Nothing is corrupt, because nothing was required to be
wire-shaped, and nothing is fabricated to make it look valid.

A summary is a fact, and projection reads backwards until it hits one. Summarization
stops being a special assembly step. Reasoning content becomes facts the projector can
drop wholesale when assembling a *summarization* call, without touching the record —
which is the specific thing that choked.

### 7.2 To a watcher

Facts project to `AgentEvent`, one to one, redacting. Deltas interleave from whichever
node is running the model call, published on `NOTIFY`, coalesced on a short interval, and
never entering a table or the fact sequence.

Facts carry their sequence from the transition, which is what makes the number a real
cursor: `Last-Event-ID` becomes exact, and the unbuilt reconnect ring buffer is no longer
needed. Deltas are not resumable, which is honest — they were never durable.

**Deltas are not stored.** A delta has no transition, so it cannot take a number from the
sequence that numbering made meaningful; storing them would mean either polluting the
fact sequence with rows that do not survive, or a second numbering authority outside the
transaction. Add the volume — hundreds of rows per answer against a handful of facts,
dead in a second, needing their own retention sweep — and it is not close.

`NOTIFY` is a wakeup and a bus, never the durable transport. The table remains
authoritative. Durability and cross-node reach are separate properties.

### 7.3 Delivery

**`Narrator` is the seam, and it already exists** — `void narrate(AgentEvent)`, with
`silent()` and `to(Consumer)` factories. `Instructions` calls `narrator(agentId).narrate(...)`
and never learns what is behind it. Facts and deltas ride the same call, because
`AgentEvent.TextDelta` is an `AgentEvent`; nothing new is needed for the interleaved
stream. What dies with Pekko is `NarrationActor`, the sharded implementation — not the
interface.

**The clustered implementation is a Substrate `Journal<AgentEvent>` per agent**, in its
own module. `Journal`'s shape is the one Nessy's door already has:

| Nessy | Substrate |
|---|---|
| `Narrator.narrate(event)` | `journal.append(event, ttl)` |
| `Harness.subscribe(id, subscriber)` | `journal.subscribe(Subscriber)` |
| `Harness.subscribe(id, subscriber, lastEventId)` | `journal.subscribeAfter(afterId, Subscriber)` |
| replay-on-connect | `journal.subscribeLast(count, Subscriber)` |
| agent forgotten | `journal.complete(retention)` / `delete()` |

The `lastEventId` overload already exists on `Harness` and was written for a reconnect
story with no mechanism behind it. This is the mechanism.

The journal key is the stream identity, so it must be namespaced by agent type:
`"{agentType}/{agentId}"`. Two types sharing a backend would otherwise collide on a bare
agent id — the same hazard `nessy_reminder` avoids by keying on the pair while
`nessy_backlog` does not.

Backends are Substrate platform modules: in-memory for single-node and tests, Redis,
NATS, PostgreSQL or another per deployment. So the multi-node delta question of §11 is a
dependency choice, and coalescing over `LISTEN`/`NOTIFY` is one backend rather than the
mechanism.

Tests use `Narrator.to(list::add)` — no Substrate, no journal, no TTL — and
`Narrator.silent()` remains the default.

**SSE is an application concern, not Nessy's.** Nessy's door is `AgentSubscriber`, not
`SseEmitter`, so the runtime stops at the subscription. An application bridges it to a
browser however it likes — `org.jwcarman.odyssey`, which is exactly this `Journal` behind
an `SseEmitter` facade, is a good option and deliberately not a dependency of the engine.

**Two ids, two jobs, and they must not be confused.** `AgentEvent.id()` is minted by Nessy
at emit and its own javadoc concedes it identifies a delivery rather than a fact.
`JournalEntry.id` is minted by the backend and is what `subscribeAfter` accepts. The SSE
`id:` field must carry the JOURNAL id, or a reconnect silently resumes nothing.

**Nessy touches only `Journal`.** Substrate's other two primitives are deliberately
unused: `Atom` is a leased value, but the agent lock must be the row lock inside the
transition transaction (§4); `Mailbox` is a durable future, and §5.2 established that the
effect row already provides what a mailbox would.

**Why this may be depended upon when a store may not.** Nessy removed the `Substrate` SPI
on 2026-09-01 for purpose-built JDBC tables, and that ruling stands. The rule that
separates the two cases:

> Abstract the thing whose contract is weak; never the thing that must be in your
> transaction.

Facts, state, effects and reminders are transactional and stay purpose-built JDBC.
Delivery is lossy, non-transactional and correctness-irrelevant (invariant 11), which is
the clean case for a dependency.

**The boundary that must hold: the journal is a delivery buffer, never the record.** It is
TTL'd; `nessy_fact` is not. A reconnect inside the retention window is served by the
journal, and anything older is replayed from the fact table. Letting the TTL grow until
the stream becomes a second history would reintroduce exactly the
narration-parallel-to-record split this design removes (§3.1).

**Jackson.** Substrate is on Jackson 3 (`tools.jackson.databind`) and Nessy is on
Jackson 2. Nessy migrates to Jackson 3 (ruled 2026-09-04); Boot 4 ships it, so the move is
due regardless of this design. Scheduled in phase 1, ahead of anything that serializes an
`AgentEvent`.

## 8. Provider attestation

An opaque provider artifact — a thinking signature, a reasoning-item id, an encrypted
continuity token — is not part of a fact. It is one vendor's receipt for something that
happened, valid only at that vendor. `ProviderBlock` survives and gains a provider
identity, and projection has one rule:

> Replay an attestation only when projecting to the provider that minted it; otherwise
> drop it, **together with the content it attests**.

The second clause is load-bearing and unverified (see §12): a reasoning block replayed
without its signature is believed to be rejected, so the block goes as a unit rather than
being stripped.

This makes switching providers mid-conversation safe by construction. Today it is not:
`ExchangeMessage` states that nothing in its content may be quietly dropped, so an
Anthropic signature would be replayed at OpenAI. Under the tag rule that is
unrepresentable, and it is testable in one assertion.

## 9. The PostgreSQL model

```
nessy_fact       agent_type, agent_id, seq, turn_id, fact_type, payload, observability, created_at
nessy_agent      agent_type, agent_id, version, state, last_touched_at, updated_at
nessy_effect     effect_id, agent_id, turn_id, caused_by_seq, effect_type, payload,
                 observability, status, attempt_count, next_attempt_at, created_at, updated_at
nessy_reminder   reminder_id, agent_id, turn_id, effect_id (nullable), fire_at,
                 reminder_type, payload, status, claimed_at, claimed_by, created_at
nessy_backlog    (unchanged)
nessy_poison     (unchanged)
```

Unique ordered sequence per agent; stable unique effect identity. `nessy_transcript` and
`nessy_claim` are dropped — the fact log replaces both.

State, effects and facts must live in one database, since the transition writes all three
in one transaction. That is the both-stores-or-neither rule already ruled on, and it
narrows the `Memory` SPI by direction:

- **write side** — the fact log. Engine-owned, in-transaction, not an extension point.
- **read side** — `Memory` becomes projection and retrieval: assemble the story, truncate
  at a summary, and eventually resolve against a graph or vectors.

That keeps the capstone seam and puts it somewhere better. Graph-based retrieval is a
read-side concern; today it sits in the write path only because `remember` and `recall`
share an interface.

## 10. What leaves the public API

Deleted: `Context`, `ContextMessage`, `HistoryMessage`, `ExchangeMessage`,
`AnswerMessage`, `UserMessage`. The hierarchy exists to satisfy the pairing constraint
this design removes.

Added as a dependency, not an SPI: `org.jwcarman.substrate` `Journal` for clustered
narration delivery (§7.3), in its own module, with a platform module chosen per
deployment. `Narrator`, `AgentSubscriber` and `AgentSubscription` are unchanged.

Survives: `Block` and its subtypes — the content payloads a fact carries. `AmbientMessage`
in some form, since ambient is still woven in at projection time and applications build
them. `ProviderBlock`, with a provider tag (§8).

`ModelRequest` carries the shaped story rather than a `Context`.

Deleted with Pekko: `PekkoHarnessFactory`, `ShardedHarness`, `AgentActor`, `NessyMessage`,
`NarrationActor`, `ReplyTokens`' ask plumbing, `PekkoConfigBridge`,
`watchman.conf`, the Pekko journal and durable-state tables, and Pekko dependencies in
seven poms.

Deleted with the move to a Boot-only host: `nessy-model/discovery` and the five
`ModelProviderBootstrap` implementations. They exist so a non-Spring application can find
providers by `ServiceLoader`, and a Boot-only host has no non-Spring applications.
Providers are Boot beans, or hand-assembled in tests. This reverses the restoration of
2026-08-25 (14f01928), whose ruling — *the starter takes a bean, never discovery;
discovery is for non-Spring apps* — survives with its second clause emptied of subjects.

`nessy-console` and `nessy-examples/chat-cli` are plain-Java today (`spring-jdbc`, no
Boot) and become Boot applications. `Repl.run(customizer)` sits on top of Boot rather
than beside it.

Untouched: `AgentLogic`, `AgentState`, `Input`, `Instruction`, `Decision`, `Phase`,
`CallState`. None of them ever imported Pekko. Also untouched: `Narrator`, which is
already the delivery seam (§7.3), and `ModelReplies.drain`, which already accumulates a
model run into one assembled `ModelResult` while passing every event to a narration
watcher — so no adapter and no part of the engine accumulates anything.

## 11. Phasing

The order is a judgment call, not a constraint. An earlier draft argued it was forced,
on the grounds that `pekko-persistence-jdbc` runs on Slick with its own pool while
`EngineConfig` takes Spring's `DataSource`, so §4's transaction is unachievable while
Pekko owns persistence. True, and moot: Pekko is being removed entirely, and Slick with
it. The real reason to swap hosting first is that phase 2 is large and wants a stable
substrate beneath it.

1. **Hosting swap.** Pekko out, Boot in. Row-lock serialization, virtual threads,
   `nessy_agent`, `nessy_effect`, watchdogs, reapers, the driving model of §6. Discovery
   deleted; console and chat-cli become Boot applications. Storage model otherwise
   untouched; transcript and claims still stand. Unblocks §4.
2. **The story.** `nessy_fact` replaces transcript and claims. Message hierarchy deleted,
   `Fact`/`Story` introduced, memory becomes projection, four adapters legalize their own
   wire, provider attestation tagged.
3. **Narration derived.** Facts project to `AgentEvent` and go through the existing
   `Narrator` seam; a `JournalNarrator` in its own module appends to a Substrate
   `Journal<AgentEvent>` per agent (§7.3), deltas included. `NarrationActor` and the
   duplicate `Narrate` instructions delete; reconnect becomes `subscribeAfter`. In-memory
   by default; a Substrate platform module is a deployment choice.

**Phase 2 has no incremental path, and that is the largest risk in this design.**
`nessy-api`, `nessy-spi`, the engine, four model adapters and four memory modules all
move in one landing, because `Context` cannot be half-deleted: the moment `ModelRequest`
stops carrying it, every adapter must already legalize its own wire. There is no green
intermediate state. Mitigation is a worktree and a clean full-reactor `verify` as the
only gate — not a smaller first step, because no smaller first step exists.

Between 1 and 3, multi-node live *deltas* are unavailable — cross-node addressing was
free with sharding and is not free without it. Facts and effects are unaffected: any node
reads the table. Single-node deployments, including the watchman, are unaffected
throughout.

## 12. Open questions

- ~~**`NOTIFY` sizing.**~~ **Moot.** Delivery is a Substrate `Journal` over a platform
  module (§7.3), so the transport is a dependency choice. `LISTEN`/`NOTIFY` is one backend
  among several rather than the mechanism, and its payload ceiling is that backend's
  concern.
- ~~**Jackson 2 vs 3.**~~ **Ruled 2026-09-04:** Nessy upgrades to Jackson 3. Five API types
  move off `com.fasterxml.jackson.databind` (`Schemas`, `Tool`, `ToolCall`,
  `ApprovalRequest`, `ProviderBlock`). Phase 1 work. Confirm during the upgrade which
  package Jackson 3 keeps annotations in — the migration's one unknown.
- **Reasoning is narrated but never kept.** `ModelReplies.drain` discards
  `ReasoningChunk` while retaining `ProviderStateEmitted` — so the record keeps a vendor's
  sealed receipt for reasoning but not the reasoning. Under a fact log, reasoning could be
  a fact that projection drops, which is what would have helped the summarizer that choked
  (§1). Decide in phase 2 rather than inherit.
- **Journal TTL policy.** What retention window makes a reconnect feel instant without the
  stream drifting toward being a second record (§7.3). A deployment knob, but it needs a
  defensible default.
- **Attestation stripping.** What each of the four providers actually does with a
  reasoning block replayed without its attestation. Probe all four before writing §8's
  second clause down as a rule.
- **`AmbientMessage`'s final shape** once `ContextMessage` is gone.
- ~~**Postgres-only coverage.**~~ **Answered 2026-09-04 by measurement** — see §14.

## 13. Invariants

1. Facts are facts. The log never fabricates a model response to look wire-valid.
2. State is a deterministic fold of facts.
3. Effects are durable obligations, never proof that external work occurred.
4. Fact, folded state, emitted effects and reminder changes commit atomically.
5. No external I/O inside a transition.
6. Transitions for one agent are serialized; different agents proceed concurrently.
7. Effect execution is at-least-once. An unknown outcome is not a failure.
8. The fact log is not a provider-valid transcript, and is not required to be.
9. Provider context is a projection. So is narration.
10. An attestation is replayed only to the vendor that minted it.
11. Notifications are wakeups; the table is authoritative. A fact wakeup carries no
    payload, so losing one costs nothing.
12. A delta never reaches a model and is never stored.
13. Ephemeral subscribers cannot affect the correctness of the durable runtime.
14. A turn's domain identity is independent of tracing identity.

## 14. Test substrate (measured)

H2 remains the test substrate for phases 1 and 2. Measured against H2 2.2.224 and
2.4.240 (the build pins 2.3.232, between them; both endpoints behaved identically):

| | H2 |
|---|---|
| `SELECT … FOR UPDATE` | parses |
| `FOR UPDATE SKIP LOCKED` | parses |
| `ORDER BY … FOR UPDATE SKIP LOCKED` | parses |
| same row contended | blocks — serialization is real |
| **different** row while one is held | acquires in 0–1ms — row-level, not table-level |
| `UPDATE` a different row while one is held | succeeds |
| `SKIP LOCKED` with one row locked | returns the other — genuinely skips |

The row-level result is the load-bearing one: two agents do not contend, so invariant 6
is exercised under test rather than silently serialized. H2's timeout message reads
`Timeout trying to lock table "AGENT"` and looks like table-level escalation; it is
generic wording, and the different-row and `SKIP LOCKED` results both disprove it.

**Delivery needs no database at all under test.** Substrate's in-memory journal (§7.3) is
the test substrate for narration, and Substrate owns the backend-specific tests in its own
repository. Nothing in this design forces Testcontainers into Nessy's build; facts,
effects, reminders and the transition run on H2 as measured above.

Consequence for the schema: the ANSI-spelling discipline that `SchemasTest` enforces
stays worth keeping through phases 1 and 2, not for portability — Postgres is the only
supported substrate — but because H2 is the cheap test substrate and Postgres-specific
spellings would cost it. That is a test-economics reason, not a portability one, and the
schema comments should say so rather than continuing to imply portability.
