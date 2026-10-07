# Trajectory fingerprinting: a behavioural identity for every completed turn

**Status: APPROVED by James on 2026-10-07. BUILT and on `main` (a4408c755).** The rulings in §2
were made in conversation on 2026-10-07 and are his. Every path and name below was checked against
`main` at `acc3a0daf`.

**Amendment, 2026-10-07: the readable trajectory (§4.6, ruling 9). APPROVED by James. BUILT.**

**Amendment, 2026-10-07: the task label (§4.7, ruling 10). APPROVED by James (option 1: the label is
a category). BUILT.**

---

## 1. The problem

A trace says what one execution did. Nothing says what kind of behaviour it was. Two turns that
look up a customer, succeed, and answer are the same behaviour whether the customer is Bob or
Bill, and nothing in Nessy can say so. Without that, the questions a fleet operator asks after a
model or prompt change (did the agent's behaviour shift, did a new path appear, has the agent
collapsed into three paths it could have been code for) have no deterministic answer.

The fold already sees the structure a trace flattens: a model decision, a concurrent round of tool
calls, their outcomes, the next decision, and how the turn ends. This record gives each completed
turn a deterministic **trajectory fingerprint**: a hash of that structure with every
execution-specific value removed, written to a per-turn row in the same transaction as the event
that ends the turn.

## 2. Rulings

Made in conversation on 2026-10-07.

1. **The fingerprint is derived data and lives in a table, not on an event.** The event stream
   stays authoritative and unchanged. The row is a projection that can be rebuilt from the
   turn's event slice.
2. **No incremental hashing.** The folded state holds the completed rounds; the hash is computed
   once, at the terminal event, over the whole list. This also keeps a rounds-only "path
   fingerprint" derivable later without a second accumulator.
3. **Tool outcomes are three**: SUCCESS, FAILED, DENIED. The five ways a call settles collapse by
   the rule "keep a distinction only if it changes what the model can reasonably do next".
4. **Terminal outcomes are five**: ANSWERED, TRUNCATED, REFUSED, FAILED, STOPPED. A truncated
   answer is its own class: not ANSWERED, because the caller got a stump; not FAILED, because the
   cause is the output limit, not the provider.
5. **Names**: `AgentTurns` (backend SPI), `AgentTurn` (the row), table `nessy_agent_turn`,
   `TurnOutcome` (the enum), `Trajectory` (version plus hash). The three-way tool outcome is
   engine-private and gets no public word.
6. **Observability**: trace attributes on the span current at the terminal fold, on both doors.
   No metrics. No new span.
7. **No read API and no analytics in this effort.** The table is queried in SQL. The analytics
   pass is the payoff and a follow-on.
8. **The digest is stored as text**: 64 lowercase hex characters, the same string the span
   carries, readable in SQL without decoding.
9. **The row also carries the trajectory itself, as JSON** (§4.6). A hash says whether two turns
   behaved the same; it cannot say what either did. The JSON is the canonical structure the hash
   encodes, so a query reads it directly and any later similarity measure (n-grams, edit distance,
   the rounds-only path) is computed from it rather than frozen into a stored vector now. It is
   stored plain, not through the codec: tool names and outcome words are not content.
10. **The row carries the turn's label, and a label is a category** (§4.7). The label an
    application gives an input becomes a short, low-cardinality name for the kind of work a turn
    does, never content, and is stored plain on the row. Grouping trajectories by label is what
    separates "this agent's behavior varies" from "this agent does several kinds of work", and
    makes the conditional measures (trajectory given task) a query.

## 3. What the fold knows today, and what it drops

`AgentState` (`nessy-engine`, `engine/core/AgentState.java`) is replayed from the last
`TurnStarted` on every step, so anything added to it is replay-safe by construction and never
lives in a JVM map across calls.

- `AwaitingActions` holds `Map<CallId, OutstandingAction>`; `OutstandingAction` carries the
  `ToolName`. One `ActionsRequested` is one round.
- `discharge` removes the entry on `ToolDenied`, `ToolSucceeded` and `ToolFailed`, keeping
  neither the name nor the outcome. When the map empties the state returns to `Inferring`. That
  moment is the end of a round.
- `TurnStopped` is emitted in the same decision as the last discharge, so a round is always
  complete before any turn-ending event.
- No turn ends with calls outstanding: `InferenceAnswered`, `InferenceRefused` and
  `InferenceFailed` come only from `Inferring`, and `TurnStopped` only after the map empties.
- `TurnStats` (`nessy-api`) already carries `modelCalls`, `toolCalls` and `failedAttempts`, and
  `TurnTally.after` keeps it current. The accumulator below sits beside it.
- `InferenceAttempted` is retry bookkeeping; the fold stays in `Inferring` and the model never saw
  the failed attempt. It does not touch the trajectory.
- `Terminated` is accepted only by `Idle`, so it never ends a turn and has no terminal arm.

## 4. The trajectory

A completed turn's trajectory is an ordered list of rounds followed by the terminal outcome. A
round is a multiset of (tool name, tool outcome). Order within a round does not matter; count
within a round does; round boundaries do.

### 4.1 Tool outcome (engine-private)

| Settling event | Outcome |
|---|---|
| `ToolSucceeded` | SUCCESS |
| `ToolFailed` with `CallFailure.FAILED` | FAILED |
| `ToolFailed` with `CallFailure.PAST_DEADLINE` | FAILED |
| `ToolDenied` | DENIED |
| `ToolFailed` with `CallFailure.NOT_AUTHORISED` | DENIED |

`ToolApproved`, `ApprovalDeferred` and `ToolDeferred` do not settle a call and do not appear.

### 4.2 Terminal outcome: `TurnOutcome`

A public enum in `nessy-api`, beside `AskOutcome`. `AskOutcome` is what a caller gets;
`TurnOutcome` is what the turn did.

| Turn-ending event | `TurnOutcome` |
|---|---|
| `InferenceAnswered` with `truncated == false` | ANSWERED |
| `InferenceAnswered` with `truncated == true` | TRUNCATED |
| `InferenceRefused` | REFUSED |
| `InferenceFailed` | FAILED |
| `TurnStopped` | STOPPED |

The terminal tag table (ANSWERED 1, TRUNCATED 2, REFUSED 3, FAILED 4, STOPPED 5) is engine-internal,
in `TurnTrajectory`; the enum itself carries no tag.

### 4.3 What the fingerprint ignores

Input text, tool arguments, tool results and rendered text, the answer, timestamps, latency,
usage, trace and span ids, agent id, turn id, provider, model, request ids, idempotency keys,
`InferenceAttempted`, deferrals, who approved, and the facts on approval events.

### 4.4 Canonical encoding, version 1

Hash: SHA-256 over the following bytes. Every integer is big-endian and unsigned. Every string is
UTF-8 with a 32-bit length prefix. The framing is length-prefixed throughout so that no two
distinct trajectories can produce the same bytes. A tool name that is not well-formed UTF-16 (an
unpaired surrogate) is written as 0xFF followed by its UTF-16BE code units; 0xFF never occurs in
UTF-8, so no such name equals a well-formed one.

```
"NESSY_TRAJECTORY"        16 bytes, ASCII, domain separation
version                   u16   = 1
roundCount                u32
for each round, in order:
  entryCount              u32
  for each entry, sorted:
    toolName              u32 length + bytes
    toolOutcome           u8    SUCCESS=1, FAILED=2, DENIED=3
terminalMarker            u8    = 0xFF
terminalOutcome           u8    ANSWERED=1, TRUNCATED=2, REFUSED=3, FAILED=4, STOPPED=5
```

Entries within a round sort by tool-name bytes (unsigned, lexicographic), then by outcome tag.
Duplicates are kept. A path fingerprint, if ever wanted, is the same bytes up to and excluding the
terminal marker; it is not computed in this effort.

### 4.5 `Trajectory`

A public record in `nessy-api`: `Trajectory(short version, String hash)`, where `hash` is the
digest as 64 lowercase hex characters. One value serves the record, the row and the span, with no
decoding anywhere. Version 1 digests are SHA-256, so always 64 characters.

### 4.6 The readable trajectory (amendment)

The row carries the canonical structure of §4.4 as JSON: the same rounds in the same order, each
round's entries in the same sorted order, and the turn's outcome.

```json
{"rounds": [[{"tool": "containers", "outcome": "SUCCESS"},
             {"tool": "disk_usage", "outcome": "SUCCESS"}]],
 "outcome": "ANSWERED"}
```

- `rounds` is an array of rounds in the order they happened; a turn that called no tool has
  `"rounds": []`. Each round is an array of entries, sorted as §4.4 sorts them, duplicates kept.
- `tool` is the tool name; `outcome` is `SUCCESS`, `FAILED` or `DENIED`; the top-level `outcome` is
  the `TurnOutcome` name.
- **A name JSON cannot carry.** Postgres `jsonb` refuses an unpaired surrogate and the NUL
  character, and a refused insert would roll back the turn's ending and wedge the agent on every
  retry. So a tool name that is not well-formed UTF-16, or that contains U+0000, is written with
  each unpaired surrogate and each NUL replaced by the six ASCII characters `\uXXXX` (uppercase
  hex), and its entry gains `"escaped": true`. The flag keeps the rendering one-to-one: a real name
  that happens to contain the characters `\uD800` has no flag. Well-formed names are written as
  they are. In such a name each backslash is written as `\u005C` too, so that every backslash in an
  escaped name begins an escape and two different escaped names never render alike.
- **The hash is not computed over this text.** The digest stays over the binary encoding of §4.4;
  `jsonb` reorders keys and drops whitespace, so JSON text is no stable preimage. The guarantee is
  structural: under one `trajectory_version`, two rows have equal `trajectory` values if and only
  if they have equal `trajectory_hash` values.
- **Versioned with the hash.** A change to the JSON shape is a change of `trajectory_version`.

What this enables in plain SQL, for example every turn in which a person denied `prune_images`:

```sql
SELECT turn_id, ended_at FROM nessy_agent_turn
 WHERE trajectory @> '{"rounds": [[{"tool": "prune_images", "outcome": "DENIED"}]]}';
```

### 4.7 The task label (amendment)

`TurnStarted` already carries a `label`: what the application's `inputLabel` stringifier said about
the input, made one line and cut to `ToolConfig.LINE_CAP` (1,000) characters, or the input's simple
class name when the application gave none or its stringifier failed. The amendment does three
things.

- **The contract becomes "a category".** The javadoc of `inputLabel` on both
  `DirectHarnessConfig` and `QueuedHarnessConfig`, and the docs, say: a label names the kind of
  work an input starts (`rounds`, `invoice:PRICE_VARIANCE`), from a small set of values; it is
  stored plain, unencrypted, beside the trajectory; it must not carry the input's content. Nothing
  enforces the cardinality -- an application that labels each input uniquely only gets a useless
  classifier -- but the content rule is the application's to keep, and the docs say so plainly.
- **The fold keeps it.** `TurnTrajectory.State` gains the label from `TurnStarted`, as it already
  keeps `arrivedAt`, so the row is still built from the folded state alone and replay gives the same
  value.
- **The row carries it.** `AgentTurn` gains `String label` and `nessy_agent_turn` a `label` column.
  The label is not part of the fingerprint: the fingerprint is behavior, the label is the task, and
  keeping them apart is what makes "trajectory given task" measurable.

**Characters the column cannot hold.** The label is made one line, but a NUL is not whitespace and
survives, and Postgres rejects U+0000 in text; a refused insert would roll back the turn's ending on
every retry. The row's label therefore has each U+0000 and each unpaired surrogate replaced by
U+FFFD. The event keeps the label as given, since the event goes through the codec as bytes.

What it enables, for example how predictable each kind of work is:

```sql
SELECT label, COUNT(*) AS turns, COUNT(DISTINCT trajectory_hash) AS trajectories
FROM nessy_agent_turn
WHERE agent_type = 'ap-agent' AND trajectory_version = 1
GROUP BY label
ORDER BY turns DESC;
```

## 5. The fold: `TurnTrajectory`

An engine-internal accumulator in `engine/core`, parallel to `TurnTally`. Its state is carried by
`Inferring` and `AwaitingActions` beside `stats`, and discarded with them when the turn ends.

As built, in `TurnTrajectory`: `State` (arrival time, completed rounds, the open round, retries, label),
`Round`, `Entry`, and the engine-internal `CallOutcome` (SUCCESS, FAILED, DENIED). `TurnRecorder`
folds the decision's events after the append.

Per event:

- `TurnStarted`: fresh, empty state.
- `ActionsRequested`: `current` is empty (the previous round closed). Nothing to record yet; the
  tool names are already in `outstanding`.
- `ToolDenied`, `ToolSucceeded`, `ToolFailed`: look the call's `ToolName` up in `outstanding`
  before discharge, add the (name, outcome) to `current`. When `outstanding` is now empty, sort
  `current`, append it to `completed` as a round, clear `current`.
- Everything else: unchanged.

The exact shape is the implementer's; the requirements are that completion order cannot change
the result, duplicates survive, and the state is a value.

`TurnTrajectory.finish(TrajectoryState, TurnOutcome)` produces the `Trajectory` by encoding §4.4.
Because the terminal event collapses the state to `Idle`, the row is built from the state *before*
the terminal event and the event itself. A single engine-internal helper does this for both doors
so neither harness carries the switch.

## 6. The row: `AgentTurn` and `nessy_agent_turn`

### 6.1 Backend SPI

`org.jwcarman.nessy.backend.turn.AgentTurns`, beside `Chapters`, `Effects` and `AgentEvents`,
exposed by both `DirectBackend` and `QueuedBackend`:

```java
public interface AgentTurns {
  /** Writes the row for a turn that has just ended. Same transaction as the terminal event. */
  void append(AgentType type, AgentId agent, AgentTurn turn);

  /** The turns of one agent, oldest first. For tests and audit; analytics use SQL. */
  List<AgentTurn> of(AgentType type, AgentId agent);
}
```

`AgentTurn` is a record with exactly the columns below. It lives in the SPI, not the public API:
nothing public reads it in this effort. The amendment adds one component, `String trajectoryJson`:
the engine renders it (§4.6) and the stores write it as given, so neither the SPI nor the API
learns the engine's round and entry types.

### 6.2 Schema

Appended to `nessy-backend/jdbc/src/main/resources/nessy-schema.sql`, following its conventions
(`CREATE TABLE IF NOT EXISTS`, same column name for the same id everywhere, timestamps with time
zone).

```sql
CREATE TABLE IF NOT EXISTS nessy_agent_turn
(
    agent_type             VARCHAR(64)              NOT NULL,
    agent_id               UUID                     NOT NULL,
    turn_id                BIGINT                   NOT NULL,  -- seq of TurnStarted
    ending_seq             BIGINT                   NOT NULL,  -- seq of the turn-ending event
    arrived_at             TIMESTAMP WITH TIME ZONE NOT NULL,  -- TurnStarted.arrivedAt
    started_at             TIMESTAMP WITH TIME ZONE NOT NULL,  -- TurnStarted.startedAt
    ended_at               TIMESTAMP WITH TIME ZONE NOT NULL,  -- the append's `at`
    trajectory_version     SMALLINT                 NOT NULL,
    trajectory_hash        CHAR(64)                 NOT NULL,  -- lowercase hex of the digest
    trajectory             JSONB                    NOT NULL,  -- the canonical structure, §4.6
    label                  VARCHAR(1000)            NOT NULL,  -- the task label, §4.7
    outcome                VARCHAR(16)              NOT NULL,  -- TurnOutcome name
    round_count            INTEGER                  NOT NULL,
    tool_call_count        INTEGER                  NOT NULL,
    tool_success_count     INTEGER                  NOT NULL,
    tool_failure_count     INTEGER                  NOT NULL,
    tool_denied_count      INTEGER                  NOT NULL,
    inference_call_count   INTEGER                  NOT NULL,
    inference_retry_count  INTEGER                  NOT NULL,
    PRIMARY KEY (agent_type, agent_id, turn_id)
);

CREATE INDEX IF NOT EXISTS ix_nessy_agent_turn_trajectory
    ON nessy_agent_turn (agent_type, trajectory_version, trajectory_hash);
```

The primary key already serves "this agent's turns in order", so no second agent index. No
time-oriented index until a query needs it.

Column sources:

- `turn_id`, `arrived_at`, `started_at`: from `TurnStarted`. `arrived_at` and `started_at` are
  already in `TurnStats.startedAt` and the event; the fold keeps `arrivedAt` too.
- `ending_seq`, `ended_at`: the terminal event's seq and the `at` passed to `append`.
- `trajectory`: `TurnTrajectory` renders the folded rounds and the outcome as §4.6 says; JDBC
  writes it with a `CAST(? AS JSONB)`, in-memory keeps the string.
- `label`: `TurnStarted.label` as the fold kept it, with U+0000 and unpaired surrogates replaced
  by U+FFFD (§4.7).
- `round_count`: `completed.size()`.
- `tool_*_count`: tallied over every entry in every round. `tool_call_count` equals the number of
  settled calls, which equals the number requested because no turn ends with calls outstanding.
- `inference_call_count`: `TurnStats.modelCalls`. `inference_retry_count`:
  the number of `InferenceAttempted` events, not `TurnStats.failedAttempts`, which also counts the
  failure that ends a turn.

`[turn_id, ending_seq]` is the turn's exact slice of `nessy_agent_event`, so a stored hash can be
audited by re-folding that slice.

### 6.3 Implementations

- JDBC: `JdbcAgentTurns`, a plain insert through `JdbcClient`, relying on the caller's ambient
  transaction exactly as `JdbcAgentEvents.append` does. Wired in `JdbcQueuedBackend` and
  `JdbcDirectBackend`.
- In-memory: `InMemoryAgentTurns`, wired in both in-memory backends, so the engine's tests and the
  no-database examples see the same behaviour.

### 6.4 Atomicity: where the row is written

Six sites append events; three can carry a turn-ending event.

| Harness | Site | Can end a turn |
|---|---|---|
| Direct | `terminate` | No (`Terminated` only) |
| Direct | `beginTurn` | No (`TurnStarted` only) |
| Direct | `executeStep` | Yes |
| Direct | `recoverToIdle` | Yes (overdue and undispatchable discharges) |
| Queued | deferral path | No |
| Queued | `apply` | Yes |

Each of the three calls one shared engine helper after `events().append(...)` and inside the same
`Locks.withLock` transaction: fold the decision's events onto the reconstituted state one at a
time; at a turn-ending event, build the `AgentTurn` from the state before it, the event, and `at`,
and call `AgentTurns.append`. The helper is also where the span is tagged (§7). The three
"cannot" sites are left alone, and a test guards each "can" site.

Because it is one transaction, a committed terminal event always has its row and a rolled-back
one never does. The primary-key conflict on `nessy_agent_event` that guards concurrent writers
guards the row too.

## 7. Observability

At the terminal fold, `TurnRecorder` tags `ObservationRegistry.getCurrentObservation()` directly,
when one exists. Which span that is differs by door and needs no new span:

- Direct: the `invoke_agent` observation that wraps the whole ask.
- Queued: the `nessy.effect` span of the effect whose outcome ended the turn, when an effect ended
  it.
- When a person's reply ends the turn (it settles the last call and the policy stops the turn),
  the current observation is the replier's own, so the tags land there.

The row is always written; the tags are best-effort trace annotation. All attributes are
high-cardinality so none becomes a dimension of the `gen_ai.client.operation.duration` timer that
the direct door's observation also drives.

| Attribute | Value |
|---|---|
| `nessy.trajectory.hash` | the hash, as stored |
| `nessy.trajectory.version` | `1` |
| `nessy.turn.outcome` | `TurnOutcome` name |
| `nessy.turn.rounds` | `round_count` |
| `nessy.turn.tool_calls` | `tool_call_count` |
| `nessy.turn.tool_failures` | `tool_failure_count` |
| `nessy.turn.tool_denials` | `tool_denied_count` |

Nothing is metered. The plan verifies, with a test against a real `ObservationRegistry`, that a
key-value added after an observation starts reaches the finished span; the comment on
`nameCurrent` warns that tags are fixed at start, which is true of metric dimensions and must be
confirmed for trace attributes.

## 8. Tests

Prose style, no mocking library, as the design of record requires.

**Canonicalisation** (`TurnTrajectory`, pure):

- `[A:S, B:S]` equals `[B:S, A:S]`.
- `[A:S, A:S, B:S]` differs from `[A:S, B:S]`.
- rounds `[A:S, B:S] | [C:S]` differ from `[A:S] | [B:S, C:S]`.
- the same rounds ending ANSWERED, TRUNCATED, REFUSED, FAILED and STOPPED are five hashes.
- a tool name containing a byte that sorts before another's prefix still orders by bytes.
- the five settling shapes collapse to three outcomes per §4.1.

**Independence** (fold-level, driving `AgentState` with hand-built events):

- different inputs, arguments and result payloads with the same path: same hash.
- the three calls of one round settling in every permutation: same hash.
- `InferenceAttempted` events before the answer: same hash.
- deferrals and approvals in the middle of a round: same hash.

**Readable trajectory** (amendment, §4.6):

- the rendering of a sample of trajectories (no rounds; one round; repeated tools; three rounds;
  each outcome) is the exact JSON §4.6 shows, entries in the same order as the hash's encoding.
- over every trajectory the canonicalisation tests build, two renderings are equal if and only if
  the two hashes are equal.
- an unpaired surrogate and a NUL in a tool name render as `\uXXXX` text with `"escaped": true`,
  and such a row inserts into Postgres (`@Tag("container")`); a well-formed name containing the
  literal characters `\uD800` renders differently, without the flag.
- the containment query of §4.6 finds a denied call in a container test.

**Task label** (amendment, §4.7):

- a turn's row carries the label its `TurnStarted` carried, on both doors; an application with no
  `inputLabel` gets the input's simple class name.
- two turns with different labels and the same behavior have the same `trajectory_hash` and
  different `label`s.
- a label containing U+0000 or an unpaired surrogate is written with U+FFFD and the row inserts
  into Postgres
  (`@Tag("container")`); the event keeps the original.
- replaying a turn's slice reproduces the row's label.

**Replay**: fold a turn live, then fold its persisted slice from `sinceLastTurnStarted`, and the
two `Trajectory` values are equal.

**Persistence** (`@Tag("container")`, both JDBC backends; and in-memory):

- a turn that ends has exactly one row; `turn_id` is the `TurnStarted` seq, `ending_seq` the
  terminal seq, and every event of the turn lies in the range.
- an `AgentTurns.append` that throws rolls the terminal event back too.
- each of the three "can end a turn" sites produces a row: a normal answer, a policy stop out of
  `apply`, and an overdue discharge out of `recoverToIdle`.
- the row's counts match `TurnTally.of` for the same turn.

**Observability**: with a real `SimpleObservationRegistry`, the attributes of §7 are on the
finished observation on the direct door, and on the effect span on the queued door.

## 9. Out of scope, by ruling

- Incremental hashing.
- A path fingerprint (rounds only). The encoding makes it a prefix; nothing computes it.
- A public read API over `nessy_agent_turn`, and any analytics (cardinality, concentration,
  entropy, novelty, drift, transitions). Those are the follow-on, done first in SQL against this
  table.
- Metrics of any kind.
- A turn-wide span on the queued door.
- Token usage on the row. Usage stays on the inference events, where its provider semantics are
  right; the row would mix models.
- A stored similarity vector (amendment). Similarity is computed from the `trajectory` JSON when an
  agent type has enough distinct trajectories to need it; storing one feature choice now would
  freeze it before the question is known.
- An index on `label` (amendment). The primary key and the trajectory index serve the queries in
  §4.7 at today's sizes; add one when a real query needs it.
- A span attribute for the label (amendment). The row is where the conditional analysis happens.
- Labels for chat-web (amendment). Its input is free text, so its labels stay the class name until
  the example has a category worth giving.
- Migrating an existing `nessy_agent_turn` (amendment). `CREATE TABLE IF NOT EXISTS` does not add
  the column to a table that already exists; per the no-backward-compatibility rule, a database
  that has the table drops it and lets the schema recreate it.

## 10. Modules touched

| Module | Change |
|---|---|
| `nessy-api` | `TurnOutcome`, `Trajectory` |
| `nessy-backend-spi` | `AgentTurns`, `AgentTurn` (amendment: `trajectoryJson`); `DirectBackend` and `QueuedBackend` expose it |
| `nessy-backend-jdbc` | `JdbcAgentTurns`, schema (amendment: the `trajectory` column), wiring |
| `nessy-backend-inmemory` | `InMemoryAgentTurns`, wiring |
| `nessy-engine` | `TurnTrajectory` (amendment: the JSON rendering), state fields on `Inferring` and `AwaitingActions`, the shared terminal-fold helper, `TurnRecorder`, both harnesses |
| `docs/` | a concepts page describing the fingerprint and the table, written as what is |
