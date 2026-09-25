# One core, two doors: unifying the queued harness onto the pure fold

**Status: DESIGN. Sections 1–5 are BUILT and on `main`. Section 6 is the remaining move: its three
questions were all ruled on 2026-09-25, so it is ready to be planned. Nothing in §6 is built.**

Date: 2026-09-25. Continues `2026-09-24-inline-inference-and-the-turn-executor-design.md`, which
built the pure core and the direct door. Supersedes the storage mechanics of
`2026-08-21-scoped-store-design.md` for agent state and history. Reverses the backlog decision in
`2026-09-03` (backlog into the agent document), for reasons given in §6.

---

## 1. The question

Two harnesses exist and only one of them runs on the pure fold.

```
DirectHarness   core.AgentState      events, replayed        ask -> Outcome
QueuedHarness   agent.AgentState<O>  a snapshot, row-locked  offer -> void
```

They are peers -- the question that picks between them is whether anybody is waiting -- but they
do not share a fold, a history, or a storage model. Every feature has to be built twice, and the
second build is where it drifts.

## 2. What was actually different

Almost nothing, once looked at. The two event vocabularies already agreed:

| `HistoryEntry` (queued) | `core.AgentEvent` (direct) |
|---|---|
| `ObservationReceived(seq, turn, List<Block>)` | `TurnStarted(seq, turn, PayloadRef)` |
| `InferenceAnswered(seq, turn, List<Block>)` | `InferenceAnswered(seq, turn, PayloadRef)` |
| `InferenceRefused` / `InferenceFailed` | same |
| `InferenceRequestedActions(…, List<Block>)` | `ActionsRequested(…, PayloadRef, List<Requested>)` |
| `ToolSucceeded` / `ToolFailed` / `ToolApproved` / `ToolDenied` | same |
| — | `Terminated` |

Nine arms, the same names. **The only structural difference is that one carries blocks and the
other carries a reference.** Everything else followed from two things behind the fold:

- **Claim-checking.** The queued side wrote content into its own rows; the core never does.
- **Snapshot versus replay.** The queued side stored its folded state as a serialized row and
  *also* wrote a history. That is why its `Decision` returns `stay(newState)`: there is nothing to
  replay from.

The backlog is the third, and it cannot move until the second is done, because it lives inside
that snapshot.

## 3. Why the content had to move (governance)

Content was in three tables, each a serialized blob with blocks inside it:

```sql
nessy_agent_history.payload      -- the story
nessy_agent_state.payload        -- the fold... which contains the Backlog, so pending USER text
nessy_inference_context.payload  -- the whole assembled request, as sent
```

That is a data-governance problem, not an aesthetic one. An engine whose control-plane rows hold
user text, tool output and model output is an engine where every retention, encryption and
deletion question has to be answered three times in three shapes.

The rule adopted: **the tables that describe an agent's life hold references; content lives in one
place.** Those rows become plain JSON nobody has to reason about.

`nessy_inference_context` is dropped rather than claim-checked. Its only unique content was
**ambient** as-sent, and ambient is *defined* as regenerated per call and never written down --
James, 2026-09-25: "we can't rebuild ambient. It's ephemeral and whatever is there at the time of
construction is what gets added." Keeping a table whose sole distinct value is the thing we
declared ephemeral is incoherent. Audit means replaying the story.

## 4. What is built (§4a–4d are on `main`)

### 4a. One fold

`Transcript` (events -> turns) and `EventStreamHistory` (a `TurnHistory` over an event stream)
moved out of `engine.direct` into `engine.history`. They belong to neither door. `TurnBuilder` and
`Turns` die when the queued side writes `AgentEvent`s.

Preparing what a model is shown is one job. Which door started the turn is not part of it.

### 4b. Payloads are gathered, not chased

The fold resolved each reference as it reached it -- free against a map, a round trip per block
against a database, growing with the conversation. It now collects every reference in the window,
asks once, and projects from the map. `PayloadStore` gained a batch read whose default asks one at
a time, so a store with nothing better is still correct.

### 4c. Content: addressed and scoped

```sql
CREATE TABLE nessy_payload (agent_id, hash, content, PRIMARY KEY (agent_id, hash));
```

**Addressed** by SHA-256 of the encoded content: putting the same content twice is one row and the
same reference, so an effect retried after a failure leaves no second copy, and a reference proves
what is behind it. At 256 bits an accidental collision is far less likely than the row being
corrupted underneath us, and a crafted one is not something anybody can do.

**Scoped** by agent: two agents saying the same thing store it twice, deliberately. Forgetting an
agent is then one statement over one table with nothing shared out from under anybody. Counting
references across agents would save a little space and cost the single property this table exists
for -- and a deletion that has to traverse is one somebody eventually gets wrong.

`InMemoryPayloads` is idempotent too, keyed by the blocks themselves. It does **not** hash: a short
hash of an object would trade a certainty for a collision nobody would ever debug.

### 4d. Facts, and where to start reading them

```sql
CREATE TABLE nessy_agent_event (agent_id, seq, starts_turn, payload, PRIMARY KEY (agent_id, seq));
CREATE INDEX ... ON nessy_agent_event (agent_id, seq DESC) WHERE starts_turn;
```

**There is no watermark.** There was one, briefly, and it was derived state kept in a second place:
it answered "where does replay start", which the events already answer, and it could disagree with
them -- written after the append, missed on a crash, wrong in a way nothing detects.

What replaces it is **read the last turn that started, and everything after it, and replay that
onto `Idle`**. The state that comes back is the answer:

| what comes back | what it means |
|---|---|
| `Idle` | the last turn ended; start a new one |
| `Inferring` / `AwaitingCalls` | mid-turn; carry on from there |
| `Terminal` | the agent was ended; refuse everything |

Nothing has to ask which of those happened. That is the fold's job.

Anchoring on the last `TurnStarted` rather than grouping by turn id is what lets `Terminated` stay
as it is: it starts no turn, so it falls inside the last one that did, is replayed every time, and
needs no turn of its own. A query grouping by `turn_id` would have forced one on it.

`starts_turn` is the one thing about an event the table knows without decoding it. Two index
lookups, never a scan.

**`expectedLast` is enforced by the primary key**, not by comparing a version. Two writers that
decided from the same state mint the same seq, so the second violates `(agent_id, seq)` and is
told. The database is the thing that knows.

## 5. Also settled on the way

- **Two doors, named as peers.** `Harness` became `QueuedHarness`; neither is the unmarked default.
  The queued door is named for the promise it keeps -- what arrives goes in the queue, so telling
  an agent something cannot fail and cannot be refused. That is exactly what the direct door trades
  away to hand an answer back.
- **`AgentEvent` had no serialization contract.** It encoded and would not decode; it had only ever
  been held in memory. Jackson stamps a block's type id only where the declared type is `Block`, so
  a bare `List` loses them -- content is written through a record whose field is declared.

## 6. The remaining move, and what is open

The queued fold still runs `agent.AgentState<O>`, returns `Decision.stay(newState)`, snapshots into
`nessy_agent_state`, and carries its backlog inside that snapshot. Collapsing it is one move:

1. The queued harness executes `core.AgentState` and appends `AgentEvent`s.
2. The snapshot goes; state comes back by replaying the last turn.
3. The backlog leaves the fold -- which is what lets `<O>` leave with it, since `Backlog<O>` is the
   only reason the fold is generic.
4. `engine.agent`, `HistoryEntry`, `TurnBuilder`, `Turns`, `JdbcHistoryStore` and
   `nessy_inference_context` are deleted.

This reverses the 2026-09-03 ruling that put the backlog into the agent document. That decision was
made before a pure core existed; James, this session: "I don't want to clutter the core loop with
work offered. That leaks the queueing into the core. Can't the harness manage that stuff itself?"

### RULED 2026-09-25: coalescing survives, on write, over the typed object

James: "Yes, we should still coalesce. I would like to do it on write." And: "It will not be claim
checked. The backlog table will need to contain the object itself."

So `ObservationCoalescer<O>` keeps its shape -- a pure whole-list function, `coalesce(backlog,
incoming) -> backlog` -- and runs when an observation arrives rather than when it is pulled. The
backlog table holds the encoded observation, not a reference to it.

**This is a deliberate exception to §3, and the only one.** It is defensible because the backlog is
a *staging area, not a store of record*: content sits there until it drains into an event, at which
point it is claim-checked like everything else. What §3 is about is the durable record of an
agent's life, and the backlog is not that -- it is the queue in front of it.

Two consequences, recorded rather than discovered later:

- **Forgetting an agent has to clear its backlog too.** Those rows hold user content until they
  drain, so `DELETE FROM nessy_payload WHERE agent_id = ?` is no longer the whole story.
- **The table is typed.** Unlike events, a backlog row is an `O`, so it needs a codec built per
  harness -- the same reason the agent document was built per harness and nothing else was.

**The coalescer is handed a backlog it can operate on, not a list it must rewrite.** The whole-list
signature would have made every strategy pay to read and decode the entire backlog on every
arrival -- affordable only while the backlog sat decoded in the fold's snapshot, which is exactly
what stops being true.

```java
void coalesce(Backlog<O> backlog, BacklogItem<O> incoming);
```

| strategy | calls | costs |
|---|---|---|
| keep everything | `append` | one INSERT |
| keep only the latest | `replaceAll` | DELETE + INSERT |
| a bounded buffer (max 5) | `size`, `dropOldest`, `append` | COUNT + DELETE + INSERT |
| expire by age, reorder | `all` -- the escape hatch | the full read, decoded |

James, 2026-09-25: a bound "is a valid use case I would think. It could be like a bounded buffer
(max size = 5)." So `size` and `dropOldest` stay. Without them a bound would go through `all()` and
pay a full read and decode on every arrival, which is absurd for something that never has to look
at an observation to enforce itself.

Nothing decodes an observation unless a strategy asks to see one, and expensive things look
expensive: `all()` is visibly the door to the whole list, so a strategy that needs it says so at
the call site instead of every strategy paying for the possibility.

What the contract promises changes with it. "A pure function over data" becomes "a script over a
bounded set of operations" -- narrower, and honest: those operations are the only side effects
reachable, and they all land in the transaction that is already holding the agent's row lock.
Purity was never enforced; reachability is.

Rejected on the way: claim-checking the backlog and resolving payloads on every `offer` (drags
content back into the write path), and narrowing the coalescer to a key function (cheap, but loses
caps, reordering and "a full resync supersedes everything", which the contract names).

The operation set is exactly `append`, `replaceAll`, `size`, `dropOldest`, `all`, `rewrite` --
three strategies that read nothing, and a hatch with the two calls it needs. Adding later is easy;
removing is not.

**`all()` hands back a snapshot, and the row lock is what makes that safe.** The coalescer runs
inside the transaction that already holds `nessy_agent` `FOR UPDATE`, so nothing else can write
this agent's backlog while it is running: a snapshot cannot go stale, and being immutable it is
safe to iterate while calling operations that mutate. The only argument for a live view was speed,
and it buys nothing here.

Operations apply immediately rather than accumulating, so the snapshot is taken at the moment of
the call: `all()` -> `dropOldest(1)` -> `all()` sees the drop in the second one.

### RULED 2026-09-25: an agent table, locked with SELECT ... FOR UPDATE

```sql
CREATE TABLE nessy_agent (
    agent_type VARCHAR(64) NOT NULL,
    agent_id   UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (agent_type, agent_id)
);
```

`INSERT ... ON CONFLICT DO NOTHING`, then `SELECT ... FOR UPDATE`. The lock is held exactly as long
as the transaction and released by the database when the connection dies -- so there is no TTL to
tune and none of the "a slow holder is indistinguishable from a dead one" problem that forces
lease-guarded work to be idempotent. The database knows.

The row has to always exist, which is why this is a table rather than a lock over the backlog rows
themselves: two offers to an agent with an empty backlog would both find nothing to lock, both
insert, and neither would have excluded the other.

It earns its place three ways -- it is the lock, it is the referential anchor the history rows
currently take from `nessy_agent_state`, and it is the answer to "does this agent exist", which
nothing else holds once the snapshot is gone.

Rejected: Postgres advisory locks (`pg_advisory_xact_lock`), which avoid the table but hash into a
64-bit space where unrelated agents can share a lock, are invisible in the schema, and are not
portable SQL.

**The two doors exclude differently, and that is correct rather than a seam that failed to close:**

| | unit of work | exclusion |
|---|---|---|
| queued | a transaction | a row lock, `SELECT ... FOR UPDATE` |
| direct | a turn containing a model call | `Locks` -- a lease |

The direct door cannot hold a row lock: its turn spans an inference that may take minutes, and
holding a database transaction open across the network is not a thing to do. The queued door's
transaction is short and holds nothing while inferring -- the effect goes to the outbox and the
transaction commits.

One rule -- one turn at a time per agent -- with the enforcement chosen by whether the work is
inside a transaction.

### RULED 2026-09-25: the backlog is its own table, holding the observation

```sql
CREATE TABLE nessy_agent_backlog (
    agent_type  VARCHAR(64) NOT NULL,
    agent_id    UUID        NOT NULL,
    -- The backlog's OWN arrival ordinal, not an event seq. An item here is not an event yet and
    -- may never become one, since a coalescer is free to drop it -- so sharing the number line
    -- with nessy_agent_event would imply an ordering that does not exist and leave holes where
    -- items were dropped.
    ordinal     BIGINT      NOT NULL,
    arrived_at  TIMESTAMPTZ NOT NULL,
    payload     BYTEA       NOT NULL,   -- the encoded observation, NOT a reference
    PRIMARY KEY (agent_type, agent_id, ordinal)
);
```

**One row per item**, which is what lets the handle's operations be statements rather than a
read-modify-write of a serialized list: `append` is an INSERT, `replaceAll` is a DELETE and an
INSERT, `dropOldest` is a DELETE with a LIMIT, and only `all` reads everything.

Written in the same transaction as the events it will become. `arrived_at` is the arriving item's
own time, because a coalescer that is time-dependent uses it as now -- that is why the field exists
rather than a clock read at pull time.

### Not open (decided)

`<O>` leaves the fold. `Decision.stay` becomes events. The sealing rule (`Inferring.terminate()`
seals the backlog so nothing new is accepted) moves out with the backlog. `engine.agent` is
deleted last, when nothing references it.

---

## 7. How a queued agent keeps driving

Built: the backlog is a place work waits (`nessy_agent_backlog`) and a way to take it
(`Backlog.take()`). Nothing yet *asks*. This is what asks.

### 7a. Keeping alive is the effect side, unchanged

James, 2026-09-25: "we can keep them alive the same way we were on the effect side. That's easy."
`nessy_agent_effect`, the poller and the watchdog stay as they are. That outbox is what lets the
queued transaction be short: the effect commits, the inference happens outside it, and a second
transaction folds the answer in. Nothing about sharing the fold changes that.

### 7b. Nobody has to be woken, because the finisher hands over

The drain happens where a turn ends, in the transaction that ends it:

```
fold the result -> state becomes Idle -> take() -> Item   start the next turn, same transaction
                                                -> Pill   terminate
                                                -> Empty  go quiet
```

So "wake the agent when something arrives" is not a mechanism that has to exist for the normal
path. The thing that finished does the handoff, and an agent goes quiet only because there was
genuinely nothing waiting.

### 7c. An arrival to an idle agent starts a turn, through the backlog

```
offer(agent, o):
  BEGIN
  SELECT ... FROM nessy_agent WHERE ... FOR UPDATE
  if terminated        -> refuse; the backlog stays empty
  else                 -> coalescer.coalesce(backlog, item)
  replay the last turn
  if Idle              -> take() and start the turn, writing its effect to the outbox
  COMMIT
```

**The terminated check comes before the coalescer**, not after. An arrival coalesced into an
emptied backlog would be read as work the next time it is asked, undoing a termination that had
already happened -- which is the one promise the pill makes.

**An arrival to an idle agent still goes through the backlog**, rather than starting a turn
directly. Skipping would be faster and is defensible, because coalescing against an empty backlog
is a no-op for every built-in strategy. It is not done because it is not a promise the interface
makes: a strategy that drops based on the arriving item alone -- "ignore heartbeats" -- would be
bypassed exactly when the agent is idle, which is most of the time, and would surface as a filter
that works under load and not otherwise. The rows exist for microseconds in that case, under a lock
already held.

### 7d. The crash window, and the one sweep that covers it

Committed the turn end, died before draining. An idle agent then sits on a non-empty backlog with
nothing driving it, because the handoff in §7b is the only thing that would have driven it.

That is the existing watchdog's shape with one more query: find agents with work waiting and
nothing in flight, and nudge. It is a recovery path rather than the normal one, and it should be
rare enough that it is worth counting when it fires.

### 7e. Abandoned work is counted

`seal()` returns how many observations were thrown away and logs it. An agent ended mid-turn
abandons whatever was queued behind it, which is correct -- but silently dropping a person's
messages is not something to find out about from a support ticket.

### Still to decide

- `ObservationCoalescer` still has its whole-list signature. The handle exists; the coalescer has
  not been moved onto it, because it has nowhere to be called from until the fold swap.
- Whether `ObservationCoalescer` keeps its name now that it holds bounds and drops work.
  `BacklogPolicy` is closer to what it does.
