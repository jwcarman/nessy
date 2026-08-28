# Ingest and turns

**Status:** proposed, 2026-08-28. Settled in conversation with James except where marked OPEN.

**Companion to** `2026-08-28-principles-and-findings.md`, whose numbered principles this document
cites rather than restates.

**Supersedes** the ingest half of `2026-08-27-inbox-outbox-design.md` and the backlog treatment in
`2026-08-27-actor-runtime-design.md` §4 — specifically that spec's claim that rendering at the
`tell` boundary lets the backlog hold `List<ContentBlock>` and drop its `Codec<O>` requirement.
That trade is reversed here, with reasons, in §3.1.

---

## 1. The problem, measured

An observation arriving while a turn is in flight was **destroyed**. The log said so plainly:

```
REFUSED an observation: a round is already in flight.
It is not queued and it is not coming back.
```

Live, on a one-minute cadence, parked on a single approval: **26 of 31 rounds refused**, later 32
of 38. Not an edge case — the steady state of any agent that both runs continuously and asks a
human anything.

A second defect compounded it. The watchman wrote the observation to the transcript *before*
telling the agent, so a refused observation still appended a `user-message`. The journal
accumulated **consecutive user messages with no assistant turn between them** — a malformed
context that no provider should be asked to accept.

Both are fixed by the same change: **observations are ingested into a durable backlog on arrival,
and become transcript entries only when a turn starts.**

## 2. The shape

```
tell(O)     →  coalesce against the current backlog  →  persist      (no transcript write)
turn start  →  take ONE  →  render  →  user message  →  remember  →  CallingModel
```

Two rules do most of the work:

- **Nothing is written to the transcript at ingest.** A queued observation is invisible to the
  running turn, so a turn reasons against a context that does not shift underneath it, and no
  observation can wedge itself between an assistant turn and its tool results.
- **One observation per turn** — the backlog is a source of *the next* observation, not a batch.
  **AMENDED 2026-08-28 by James**, reversing an earlier draft that drained the whole backlog into
  one turn. The reason is that draining all of it would **silently override the user's own
  coalescing policy**: a vocabulary returning no key is saying "these must never merge", and
  merging them into one `Message.user` at drain does precisely that — later, and without their
  `merge` function. Coalescing on write is the ONLY place observations become one.

  The earlier draft's argument — that N turns would each re-answer content the previous turn saw —
  died when `remember` moved to turn start (§4). Nothing enters the transcript until a turn takes
  it, so successive turns alternate `user → assistant → user → assistant` correctly.

  The cost, accepted: N queued observations become N model calls. Coalescing already collapses the
  repetitive cases, so what remains is genuinely distinct; a vocabulary that would rather batch them
  says so with a `merge`.

### 2.1 Where the backlog lives

**AMENDED 2026-08-28 by James, before implementation.** An earlier draft of this section put the
backlog IN the agent's durable state. It does not. State holds identifiers, status and human
decisions — never content — and observations are content, so keeping them there contradicted the
rule §4a applies to tool arguments and results, and reproduced the very mechanic §4a.1 removes:
every ingest rewriting the whole state document.

**The backlog is a SERVICE the actor depends on** — in James's words, *"simply a source of the next
observation as far as it's concerned"*. It sits alongside `Memories` and `Claims`:

```java
public interface Backlogs<O> {

  /** Coalesce against what is already waiting, then persist. Durable before it returns. */
  void ingest(String agentId, O observation, Instant receivedAt);

  /** Give me what is waiting, if anything. ONE observation — see §2. */
  Optional<Taken<O>> next(String agentId);

  /** The taken observation is removed only after its transcript entry is written (§4.1). */
  void taken(String agentId, String entryId);

  /** What `next` hands back: the observation, plus the entry id its Remembrance key derives from. */
  record Taken<O>(String entryId, O observation) {}
}
```

`Backlogs` is the plural, agent-keyed service; `Backlog<O>` is the immutable value the `Coalescer`
folds over, internal to the implementation.

**The name is ratified, and for a better reason than the one first recorded here.** An earlier draft
defended `Backlog` merely as "already ratified, not worth renaming". James settled it properly on
2026-08-28: **a backlog is a thing you GROOM.** Items are merged, superseded, deprioritised, dropped
— which is exactly the vocabulary of `Coalescer`, where `merge` folds, `byKey` supersedes, returning
the backlog unchanged vetoes, and a `Cancel` clears. Every operation this type supports is a
grooming operation.

Two alternatives were considered and rejected on evidence:

- **`ObservationStream<O>`** — right concept, wrong word here. `pekko-stream` and
  `pekko-stream-typed` are on this module's COMPILE classpath alongside `reactive-streams`, so
  "Stream" already means `Source`/`Flow`: backpressured, composable, materialized. Ours is none of
  those. Worse, a stream does not supersede its own elements, so the name hides the one behaviour
  that makes the type interesting.
- **`Observations<O>`** — collides with Micrometer's `Observation`, which is load-bearing in this
  project's observability seam (`ObservationRegistry`).

The pair does not mirror `Memories`/`Memory`, where the per-agent facade is returned by
`forAgent(agentId)` and here the agent id is a parameter. That asymmetry stands: the singular of
this concept is a snapshot, not a facade.

`Backlog<O>` remains the immutable value the `Coalescer` folds over, now internal to the service
rather than a component of the persisted document. `Coalescer<O>` is unchanged.

**Because state does not reference the backlog, there is no cross-store invariant** — no ordering
rule to obey and no dangling reference possible. The drain's two writes (transcript, then clear)
stay safe by the deterministic key of §4.1, not by atomicity.

The properties the in-state version was chosen for all survive:

- **Durable on arrival.** `ingest` persists before it returns, so a caller told "accepted" is safe.
- **Ordering is free.** One actor per agent id means one mailbox, so `tell`s are serialized by the
  runtime and two concurrent ingests cannot race.
- **Coalescing stays pure.** The service does the I/O around the fold; the fold itself reads no
  clock and touches no store, which is why it is testable with no actor present.

The cost, stated plainly: the backlog's persistence is hand-rolled over `Substrate` rather than
riding `DurableStateBehavior` for free.

### 2.1a The rejected alternative, and why

**In the agent's durable state**, which was the original draft.

The `AgentActor`'s mailbox is already a serialization point: messages are handled one at a time in
arrival order. That supplies ordering for free and removes the read-modify-write race between two
concurrent `tell`s without any new machinery. A dedicated backlog actor would be a child on the
same node with the same lifecycle — an extra hop and a second persistence problem, buying nothing.

Keeping the backlog *in the state* additionally means:

- **Durable on arrival.** One `persist` writes the coalesced backlog, and the caller is told
  "accepted" only after it is on disk. This is the one failure a caller cannot compensate for.
- **Coalescing is a pure function over state** — the reducer shape this codebase already uses,
  testable with no actor present.
- **No second store on the ingest path**, so no cross-store crash window there.

It was rejected because that last point is only true while the backlog stays small, and "small"
was doing the same work a size threshold would — the exact judgement call §4a.2 refuses to make for
tool arguments. Principle 2.1's measurement is the warning it ignored: content that grows without
bound inside durable state grew it 14× in 64 minutes, because every revision rewrites the whole
document. A backlog holding user observations is content by any honest reading.

## 3. Coalescing

### 3.1 The backlog holds `O`, not rendered blocks

The actor-runtime spec chose the opposite, to delete the `Codec<O>` requirement. That is reversed
here, because **once an observation is rendered, the domain object is gone** — and with it any
possibility of the user expressing what should happen when two of them meet. Comparison would be
reduced to matching key strings.

Two things are bought back:

- **Coalescing and folding become user logic over user types.** "Keep the quote with the higher
  price", "merge these two orders", "five errors become a count" are all expressible.
- **Rendering moves to the drain**, so a renderer fix applies to already-queued observations. The
  actor spec listed the opposite as an accepted loss; it is simply recovered.

The costs are real and accepted: `Codec<O>` (or the pinned Jackson default) becomes load-bearing,
and a queued observation must survive a deploy that changes its shape.

`tell` also gets cheaper — it writes the observation and returns, with no rendering on the
caller's thread.

### 3.2 Ingest is a reduction

The general contract is a fold, mirroring `AgentPhase.handle`:

```java
Backlog<O> ingest(Backlog<O> current, Backlog.Entry<O> incoming);
```

The arriving entry carries its own id and `receivedAt`, which is deliberate on both counts. Time is
in the signature because staleness — "drop quotes older than five minutes" — is foreseeable for
anything market- or sensor-shaped, and a pure function must not read a clock; the entry supplies
the only clock reading the fold ever needs. The id is there because a pure function cannot invent a
unique one either. The caller mints both.

This one method expresses everything we could name: keep-latest, fold, veto (return the backlog
unchanged), cross-key supersede (a `Cancel` clearing the queue), ordering and priority, dedup by
content, and a cap if one is ever added.

### 3.3 One interface; the common case is a factory

`Coalescer<O>` **is** the reduction of §3.2. Group-by-key with keep-latest — what most
vocabularies actually want — is a provided implementation rather than a second interface:

```java
public interface Coalescer<O> {

  Backlog<O> ingest(Backlog<O> current, Backlog.Entry<O> incoming);

  /** Never coalesce: every observation accumulates. The default. */
  static <O> Coalescer<O> none() { ... }

  /** Group by key, keep the latest. Twenty cron ticks become one. */
  static <O> Coalescer<O> byKey(Function<O, Optional<String>> key) { ... }

  /** Group by key, fold within the group. Five errors become a count. */
  static <O> Coalescer<O> byKey(Function<O, Optional<String>> key, BinaryOperator<O> merge) { ... }
}
```

A user who wants keep-latest-per-symbol writes `Coalescer.byKey(q -> Optional.of(q.symbol()))` and
never sees a `Backlog`. A user who needs a `Cancel` to clear the queue implements `ingest`
directly. There is one concept, and the simple case costs one line rather than a class.

This resolves what was previously an open question about whether coalescing should be one
interface or two: it is one, and the "two methods" shape (`key` plus `merge`) survives as the
argument list of a factory rather than as public surface of its own.

### 3.4 Declared on the vocabulary, not per call

Today a `coalesceKey` string is passed on every `tell`. That is the wrong place: the policy is a
property of the observation type, and one caller passing an inconsistent key — or `null` —
silently breaks coalescing for every other caller.

The vocabulary declares it once, alongside the renderer. The existing reasoning survives intact —
*"only the sender knows whether its message replaces or accumulates"* — the decision simply moves
from every call site to the single place the sender defines their world.

Generic in `O`, like `ObservationRenderer<O>`. This does not conflict with principle 1.7: that
rule is about *actor message* types, which need class literals for `EntityTypeKey`/`ServiceKey`.
A user's `O` never becomes an actor message — it is rendered at drain, and while queued it lives
inside the state payload, serialized by its codec.

### 3.5 Superseded entries keep their position

An entry replaced by a newer one holds its **original** place in the backlog. The merged user
message then reads in the order topics first arrived, which is what a reader expects of a
conversation.

## 4. Taking the next observation

At turn start, and only then:

1. Take **one** observation from the backlog — `Optional<O>`; empty means nothing is waiting.
2. Render it through `ObservationRenderer<O>`, yielding `List<ContentBlock>`.
3. `remember` it as one `Message.user`.
4. Remove it from the backlog and transition to `CallingModel`.

Blocks, not concatenated strings — a renderer may emit non-text content, and a separator we invent
would be read by the model as if the user had typed it.

### 4.1 Re-taking is safe by determinism, not atomicity

Writing the user message to the transcript and removing the entry from the backlog are two stores,
and cannot be atomic. Per principle 1.2 they are made **deterministic** instead: the message's
`Remembrance` key is derived from the backlog entry's id rather than minted fresh.

A crash between the two writes leaves the entry in the backlog. The next turn takes it again,
produces the same key, and idempotence-by-key makes the transcript write a no-op. Ordering follows
principle 1.1 — the transcript entry is written first, so a crash leaves an orphan entry rather than
a dangling reference.

### 4.2 An idle agent is not a special case

An observation arriving at an idle agent takes the same path: coalesced into the backlog, persisted,
and the agent immediately takes it. There is no fast path to get subtly wrong.

### 4.3 A turn ending checks the backlog again

When a turn completes, the agent asks the backlog for the next observation before going `Idle`. If
one is waiting it starts the next turn immediately; otherwise it rests. That is what makes a queued
observation arrive at a busy agent without needing anything periodic to notice it.

## 4a. What the agent persists

Settled 2026-08-28. The rule is one sentence:

> **State holds identifiers, status, and human decisions. Content lives elsewhere.**

### 4a.1 The heavyweights are tool arguments and results

Measured: with the transcript moved out, the port's durable state is
`{"state":"idle"}` — **16 bytes, flat across 100+ revisions**. Content never routes through
state, because the workers write to `Memory` *before* telling the agent.

`nessy-agent` does not yet follow this, and it is the gap the revamp must close:

```java
// nessy-agent persists both heavyweights
AwaitingTools(Message assistantTurn, Map<String, ToolCallPhase> calls, ModelResponseId responseId)
ToolCallPhase.Completed(ToolResultBlock) / Failed(...) / Denied(...)

// the port persists neither, and runs the same lifecycle
WorkingTools(List<ToolCallRecord> calls)
ToolCallRecord(id, tool, argumentsJson, action, askedAt, decision, settled)
```

**One model response can request N parallel calls**, so this is N arguments *and* N results in
one document — rewritten in full on every subsequent revision of that turn. Five reads returning
500 KB each is 2.5 MB rewritten per state change. The limit that matters is not any backend's item
size (Postgres `bytea` reaches 1 GB and TOASTs transparently); it is that **every revision rewrites
the whole document**, which is the mechanic behind the measured 14× growth.

**Results are simply redundant.** They are already remembered as a `ToolExchange`, and `recall()`'s
fold pairs each `tool_use` with its result. The state records `settled` and nothing more.

### 4a.2 Arguments are always claim-checked

Arguments cannot be recalled during the window that needs them. The fold **withholds** an assistant
message naming `tool_use` ids from every recall until each id has a matching exchange — so for
exactly the duration a call is in flight, `Memory` is designed not to return it. And they must be
durable, because recovery has to re-dispatch a call whose process died before it ran.

So they live in a claim store, **always** — not above a size threshold. Uniformity makes state size
independent of what tools do, and removes a branch and a number to tune.

```java
void put(String owner, String id, byte[] value);
Optional<byte[]> get(String id);
void deleteByOwner(String owner);
```

No ordering, no sequence, no iteration. Chunking, if a backend ever needs it, is an implementation
detail beneath this contract and changes nothing here.

`RunTool` carries the **claim id**; the tool worker resolves it. Arguments stay out of the message
as well as out of the state.

### 4a.3 Claims are owned by the turn, and die with it

Turns acquire an **identity** — currently they are implicit, bounded by `Idle → … → Idle`. "Always
claim" is what forces this: claims need an owner to be deleted together.

Turn ends → `deleteByOwner(turnId)`. One operation, no reference counting, no watermark, no TTL,
and no interaction with `approvalTerm`.

Per principle 1.1 the claim is written **before** the state that references it, so a crash leaves an
orphan claim. Orphans are free here: they carry their owner and are deleted with everything else
when that turn ends. The only leak is a turn that is never completed, swept by the same mechanism
that must already find stalled turns.

A parked turn may own its claims for days. Bounded and deterministic is not the same as short.

### 4a.4 Three stores, three lifetimes

| store | holds | lifetime |
|---|---|---|
| agent state | ids, status, decisions, claim references | the agent's |
| backlog | observations awaiting a turn, coalesced | until drained |
| claims | tool arguments | the turn's |
| `Memory` | the conversation | its own business |

**There is no "never trim" requirement anywhere.** That requirement was an artifact of conflating
three roles — claim-check dereferencing, model context, and audit — into one append-only transcript.
Separated, only audit wants unbounded history, and audit is optional. `Memory` is an SPI: an
append-only journal and a bounded window are equally valid implementations, chosen per deployment.

**Constraint on any `Memory` implementation:** never evict content belonging to the turn in flight.

## 5. What this changes

- **`Agent.drive()` disappears.** Nothing remains for a caller to pump; the agent drives itself.
  `tell` writes the backlog and returns.
- **`tell` no longer renders**, and no longer writes the transcript.
- **The `coalesceKey` parameter leaves `tell`**, moving to the vocabulary (§3.4).
- **An observation is never silently lost.** Dropping remains possible, but only as an *explicit*
  decision expressed in the reducer — never as an accident of a busy agent. Principle 1.8 applies:
  a drop must be visible.

## 6. Deliberately out of scope

- **Debounce** ("coalesce anything arriving within 500 ms"). Unreachable for any pure function:
  it requires waiting, which is a timer, which is an actor concern. Recorded here so it is not
  mistaken for an oversight.
- **I/O during coalescing.** It sits on the ingest hot path, must stay fast and deterministic, and
  would make the reducer untestable in isolation.
- **Phase-aware coalescing** ("coalesce only while a turn runs"). What this actually asks for is
  *preemption* of a running turn, which is a separate feature and must not be smuggled in through
  ingest.
- **Cross-agent coalescing.** The agent id is the partition key for everything downstream.

## 7. Open

1. ~~**Names are not ratified.**~~ **`Coalescer<O>` and `Backlog<O>` ratified 2026-08-28** by
   James — *"we can rename later if need be"*. Taken as working names, not final ones.
2. ~~**One interface or two.**~~ **RESOLVED** in §3.3: one interface, with the key/merge shape
   surviving as factory arguments. `Coalescer<O>` remains separate from `ObservationRenderer<O>`,
   since the renderer is mandatory and coalescing is optional — most vocabularies will take
   `Coalescer.none()` without naming it.
3. **No cap, deliberately deferred.** Coalescing bounds the recurring cases; unkeyed observations
   remain unbounded in principle. The trigger for revisiting is a **high-rate unkeyed producer** —
   a webhook firing faster than turns complete — because backlog-in-durable-state means every
   ingest rewrites the whole state document.
4. **Rejection cannot be signalled to the caller.** The reducer returns a backlog, so its only
   vocabulary is "here is the new state": it can drop, but it cannot tell `tell` "not accepted".
   Nothing needs this today. If a cap ever wants backpressure rather than silent dropping, the
   signature needs a result type instead of a bare `Backlog<O>`.
5. **Item-size limits.** DynamoDB is the ruled second backend and caps an item at 400 KB. A dozen
   small observations is nothing; a dozen large ones is not. If observations can carry large
   payloads, the backlog should claim-check them — holding a reference rather than the bytes, as
   the transcript already does.
6. **Schema evolution for queued observations.** An observation queued by old code must
   deserialize after a deploy that changes its shape. This is the operational cost of §3.1 and has
   no design answer here beyond naming it.
