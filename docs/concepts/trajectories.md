# Trajectories

A trajectory is the behavioral identity of a completed turn: which tools
ran, in which rounds, what each one came to, and how the turn ended. Two
turns with the same trajectory behaved the same way, whatever they were
asked and whatever they said. Nessy gives each completed turn a
fingerprint of its trajectory, so "how does this agent behave?" becomes a
query.

Bob asks for the status of order 41 and Bill asks about order 97. Each
turn calls `lookup_order` once, the call succeeds, and the model answers.
The inputs, results and answers differ. The trajectory is the same, so the
fingerprint is the same.

## What is in it

A trajectory is an ordered list of rounds, then the ending. A round is one
batch of tool calls the model asked for together. Each round is a multiset
of (tool name, outcome) pairs. Order within a round does not matter, the
count of each pair does, and the boundaries between rounds do. Calling
`a` then `b` in one round is the same trajectory as `b` then `a`. Calling
`a` in one round and `b` in the next is a different one.

A call settles three ways, as far as the model can tell:

| Settled as | Which events |
|---|---|
| `SUCCESS` | `ToolSucceeded` |
| `FAILED` | `ToolFailed` with `FAILED` or `PAST_DEADLINE` |
| `DENIED` | `ToolDenied`, or `ToolFailed` with `NOT_AUTHORISED` |

The rule is to keep a distinction only if it changes what the model can
reasonably do next. A timeout and a thrown exception both read as "try
again or not". A refusal by a person and an approval that never came both
read as "you may not".

A turn ends one of five ways, the public `TurnOutcome`:

| `TurnOutcome` | The turn ended because |
|---|---|
| `ANSWERED` | the model answered in full |
| `TRUNCATED` | the model answered and was cut off at its output limit |
| `REFUSED` | the model refused |
| `FAILED` | inference failed, after any retries |
| `STOPPED` | the turn policy stopped the turn |

A truncated answer is its own class because the caller got a stump, and the
cause is the output limit rather than the provider or the question. Folding
it into `ANSWERED` would hide the turns that most need a different fix.

`TurnOutcome` is not `AskOutcome`. `AskOutcome` is what a caller gets back.
`TurnOutcome` is what the turn did, and it keeps apart a policy stop and an
inference failure that both reach a caller as failed.

## What is not in it

- Inputs, tool arguments, tool results, the answer text.
- Timing, latency, usage and cost.
- Ids: agent, turn, trace, span and request ids, idempotency keys.
- Provider and model.
- Inference retries, deferrals, and who approved a call.

Anything that varies from one run of the same behavior to the next is
left out, or the fingerprint would never repeat.

## Where it is computed

The fold computes it. The agent's state carries a trajectory accumulator in
the busy states, beside the tally, and moves it on as each call settles. At
the event that ends the turn, the harness hashes the accumulator once into
a `Trajectory`: a version and a hash.

Nothing is stored for the accumulator between events. Replaying a turn's
events through the fold gives the same trajectory, on either door, and
that includes a replay after a crash.

## The row

Each completed turn writes one row to `nessy_agent_turn`, through the
backend's `AgentTurns` store. The row commits in the same transaction as
the event that ends the turn, so a committed ending always has its row and
a rolled-back one never does.

| Column | Holds |
|---|---|
| `agent_type`, `agent_id`, `turn_id` | the primary key |
| `ending_seq` | the seq of the turn-ending event |
| `arrived_at` | when the input reached the harness |
| `started_at` | when the turn opened |
| `ended_at` | when the ending event was written |
| `trajectory_version` | the encoding version of the hash |
| `trajectory_hash` | the fingerprint, 64 lowercase hex characters |
| `trajectory` | the same behavior as JSON: rounds of `{tool, outcome}`, then the outcome |
| `outcome` | the `TurnOutcome` name |
| `round_count` | rounds of tool calls |
| `tool_call_count` | calls settled, the sum of the next three |
| `tool_success_count`, `tool_failure_count`, `tool_denied_count` | calls by outcome |
| `inference_call_count` | every model call, retries included |
| `inference_retry_count` | attempts that failed and were tried again |
| `label` | the task label of the input that started the turn, `VARCHAR(1000) NOT NULL` |

`turn_id` is the seq of `TurnStarted` and `ending_seq` is the seq of the
ending event. Between them, inclusive, are the turn's events, and folding
that slice reproduces the row. The failure that ends a turn is not a retry:
`inference_retry_count` counts only attempts that were tried again.

The row is a projection of the event stream, not a second source of truth.
If the table is lost, every row can be rebuilt from the events.

## The hash

Version 1 is SHA-256 over a framing in which every integer is big-endian
and unsigned, and every string is UTF-8 with a 32-bit length prefix, so no
two distinct trajectories serialize to the same bytes:

```
"NESSY_TRAJECTORY"  u16 version  u32 rounds
  per round: u32 entries, per entry (sorted by name bytes then outcome): u32 len, name, u8 outcome
0xFF  u8 terminal outcome
```

Entries sort by the name bytes, unsigned, then by outcome tag. Outcome tags
are `SUCCESS` 1, `FAILED` 2, `DENIED` 3, and for the ending `ANSWERED` 1,
`TRUNCATED` 2, `REFUSED` 3, `FAILED` 4, `STOPPED` 5.

A tool name that is not well-formed UTF-16 (a lone surrogate) is written as
the byte `0xFF` followed by its UTF-16BE code units. `0xFF` never occurs in
UTF-8, so such a name can never equal a well-formed one.

The hash is stored as 64 lowercase hex characters, and the trace carries
the same string. The version is stored beside it. A comparison across
versions means nothing, so always filter on `trajectory_version`.

## On the trace

Seven attributes carry the same values on the turn's span:
`nessy.trajectory.hash`, `nessy.trajectory.version`, `nessy.turn.outcome`,
`nessy.turn.rounds`, `nessy.turn.tool_calls`, `nessy.turn.tool_failures` and
`nessy.turn.tool_denials`.

On the direct door they go on the `invoke_agent` span. On the queued door
they go on the `nessy.effect` span of the effect whose outcome ended the
turn, when an effect ended it. When a person's reply ends the turn, they go
on the replier's current observation instead. The row is always written; the
tags are best-effort trace annotation. When a later call recovers an abandoned turn on the direct door, the
abandoned turn's attributes land on the new call's span and the new turn
then overwrites them, so the abandoned turn's trajectory is in the row only.

All seven are high-cardinality span attributes. None is a metric tag: a hash
has as many values as the agent has behaviors, and that would grow a
metric's series without bound. Aggregate in SQL instead.

## Starter queries

How many distinct trajectories has each agent type produced?

```sql
SELECT agent_type, COUNT(DISTINCT trajectory_hash) AS trajectories, COUNT(*) AS turns
FROM nessy_agent_turn
WHERE trajectory_version = 1
GROUP BY agent_type
ORDER BY agent_type;
```

The five most common trajectories of one agent type, by share:

```sql
SELECT trajectory_hash,
       COUNT(*) AS turns,
       ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 1) AS percent
FROM nessy_agent_turn
WHERE agent_type = 'support' AND trajectory_version = 1
GROUP BY trajectory_hash
ORDER BY turns DESC
LIMIT 5;
```

Turns whose trajectory first appeared in the last day:

```sql
SELECT t.agent_type, t.agent_id, t.turn_id, t.trajectory_hash
FROM nessy_agent_turn t
WHERE t.trajectory_version = 1
  AND t.ended_at >= now() - INTERVAL '1 day'
  AND NOT EXISTS (
    SELECT 1
    FROM nessy_agent_turn earlier
    WHERE earlier.agent_type = t.agent_type
      AND earlier.trajectory_version = t.trajectory_version
      AND earlier.trajectory_hash = t.trajectory_hash
      AND earlier.ended_at < now() - INTERVAL '1 day')
ORDER BY t.ended_at;
```

## Trajectories by task

The `label` column holds the label the application's `inputLabel` gave the
input that started the turn, or the input's simple class name when the
application set none. A label is a category. It names the kind of work,
from a small set of values such as `rounds` or `invoice:PRICE_VARIANCE`.
It is stored plain, unencrypted, so it must never carry the input's
content.

The label is not part of the fingerprint. The fingerprint is behavior and
the label is the task, and keeping them apart is what lets a query ask how
predictable each kind of work is:

```sql
SELECT label, COUNT(*) AS turns, COUNT(DISTINCT trajectory_hash) AS trajectories
FROM nessy_agent_turn
WHERE agent_type = 'ap-agent' AND trajectory_version = 1
GROUP BY label
ORDER BY turns DESC;
```

A label with one trajectory is a workflow candidate: the model does the
same thing every time, and code could do it. A label with many is where
judgment lives.

The reverse question is just as useful. Which paths are shared by
different kinds of work?

```sql
SELECT trajectory_hash, array_agg(DISTINCT label) AS labels
FROM nessy_agent_turn WHERE trajectory_version = 1
GROUP BY trajectory_hash HAVING COUNT(DISTINCT label) > 1;
```

Two kinds of work that follow the same path share one hash. That is
deliberate, and it is worth a look.

On the row, a NUL or an unpaired surrogate in a label is replaced by
U+FFFD, because the column cannot hold it. The event keeps the label as
given.

Nothing enforces how many values a label takes. An application that gives
each input a unique label gets one trajectory per label and learns nothing.

## Reading a trajectory

The `trajectory` column is `JSONB NOT NULL`. The engine renders it in
`TurnTrajectory.json`: the rounds in the order they happened, each round
as an array of entries, then the turn's outcome. A turn that called no
tool has `"rounds": []`. Here is one round of two successful calls, then
an answer:

```json
{"rounds": [[{"tool": "containers", "outcome": "SUCCESS"},
             {"tool": "disk_usage", "outcome": "SUCCESS"}]],
 "outcome": "ANSWERED"}
```

Entries within a round are in the order the hash encodes them, and
duplicates are kept. Postgres sorts the keys and drops whitespace, but it
keeps array order. So under one `trajectory_version`, two rows have equal
`trajectory` exactly when they have equal `trajectory_hash`. The hash is
still computed over the binary encoding, not over the JSON.

The column is stored plain, not through the storage codec, because tool
names and outcome words are not content. The count columns (`outcome`,
`round_count` and the tool counts) sit beside it and agree with it.
`inference_call_count` and `inference_retry_count` are not in the JSON.

`jsonb` refuses an unpaired surrogate and the NUL character. A tool name
that is not well-formed UTF-16, or that contains NUL, is written with each
unpaired surrogate, each NUL and each backslash as six-character `\uXXXX`
text (a backslash is `\u005C`), and its entry gains `"escaped": true`.

Containment finds turns by what they did. This finds the turns in which a
person denied `prune_images`:

```sql
SELECT turn_id, ended_at FROM nessy_agent_turn
 WHERE trajectory @> '{"rounds": [[{"tool": "prune_images", "outcome": "DENIED"}]]}';
```

## Where next

- [Turns](turns.md) for what a turn is and how it ends.
- [Outcomes](outcomes.md) for what a caller gets back.
- [Storage](storage.md) for the tables the rest of an agent lives in.
