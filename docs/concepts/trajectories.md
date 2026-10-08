# Trajectories

A trajectory is the normalized behavioral shape of a completed turn: which
tools ran, in which rounds, what each one came to, and how the turn ended.
Two turns with the same trajectory followed the same observable control
path: the same tools, in the same rounds, with the same normalized
outcomes, and they ended the same way. Their inputs, arguments, results,
reasoning and answers may be completely different. Nessy gives each
completed turn a fingerprint of its trajectory.

Every completed turn is a member of one behavioral equivalence class: the
set of turns with that fingerprint. Thousands of turns with different
users, arguments, data, answers and timings can share one. That turns "how
does this agent behave?" from an inspection of single traces into a query
over a population of turns.

Bob asks for the status of order 41 and Bill asks about order 97. Each
turn calls `lookup_order` once, the call succeeds, and the model answers.
The inputs, results and answers differ. The trajectory is the same, so the
fingerprint is the same.

A trace tells you what happened in one execution. A trajectory fingerprint
tells you which class of behavior that execution belongs to. Nessy does
not just record what agents do. It gives their behavior a durable
identity.

## Why this matters

With a fingerprint stored per turn, these are queries:

- How many distinct behaviors does this agent have?
- What share of turns do the five most common paths take?
- Is behavior diversifying over time?
- Did a model or prompt change introduce new paths?
- Which trajectories account for most failures?
- Which are unusually slow or tool-heavy?
- Is one agent behaving differently from its peers?
- Has a trajectory appeared that was never seen before?
- Is an apparently agentic process choosing from a handful of stable paths?

Three uses follow from those queries, and each has its own section under
[Analyzing behavior](#analyzing-behavior): [evals](#evals), [anomalies](#anomalies)
and [one agent over time](#one-agent-over-time).

If nearly all turns occupy a small, stable set of trajectories, some of
those paths may be candidates for deterministic implementation rather than
repeated inference. That is a lead, not a verdict. Low variety within one
kind of work is the stronger signal; see
[Trajectories by task](#trajectories-by-task).

### Trace and trajectory

A trace is instance-specific. A trajectory is deliberately
instance-independent. Four representations of a turn answer four
questions:

| Representation | Question it answers |
|---|---|
| Event stream | What durable facts happened? |
| Trace | What happened during this execution? |
| Trajectory | What behavioral class did this execution belong to? |
| Turn table | How does this population of turns behave? |

### Trajectory and fingerprint

The two words name two things. The trajectory is the normalized behavioral
structure. The fingerprint is the deterministic identifier of that
structure. The fingerprint is not the behavior; it names the behavior's
class.

```text
trajectory structure  ->  canonical representation  ->  fingerprint
```

The public `Trajectory` record holds the fingerprint: an encoding version
and a hash. The structure itself is stored beside it as JSON; see
[Structured representation](#structured-representation).

## An example

Here is the shape of one turn, as rounds:

```text
Round 1
  lookup_customer  SUCCESS
  lookup_orders    SUCCESS
Round 2
  calculate_offer  SUCCESS
ANSWERED
```

Round 1 is one batch of two calls the model asked for together. Round 2 is
the next batch. Any turn that makes these calls in these rounds, with these
outcomes, and then answers, has this trajectory, whatever the arguments
and results were.

A turn that calls `lookup_orders` and `lookup_customer` together in round 1
has the same trajectory, because order within a round does not matter. A
turn that calls `lookup_customer` alone in round 1, then `lookup_orders`
and `calculate_offer` together in round 2, has a different one, because
the round boundaries moved.

## What defines a trajectory

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

The ending belongs to the trajectory because identical action paths that
end differently are materially different completed behavior. A turn that
ran `search` successfully and then answered is not the same as one that ran
`search` successfully and then failed.

## What does not define a trajectory

- Inputs, tool arguments, tool results, the answer text.
- Timing, latency, usage and cost.
- Ids: agent, turn, trace, span and request ids, idempotency keys.
- Provider and model.
- Inference retries, deferrals, and who approved a call.

Anything that varies from one run of the same behavior to the next is
left out, or the fingerprint would never repeat.

Inference retries are the clearest case. They are recorded on the row for
operational analysis, and kept out of the fingerprint, because a transient
provider or network failure is not a different decision by the agent.

## What Nessy does automatically

!!! note "No instrumentation required"
    Applications do not construct trajectories, emit them, or reconstruct
    them from spans. Nessy derives the trajectory while folding the turn,
    finalizes the fingerprint when the turn ends, writes the turn row in
    the same transaction as the ending event, and annotates the turn's
    span where it can. If Nessy runs the turn, the behavioral record
    exists.

Nessy does not reconstruct trajectories from traces after the fact. The
runtime already knows where a round begins, which calls belong to it, when
and how each one settles, when the next model decision happens and how the
turn ends. The fold that runs the agent maintains the trajectory as it
goes and finalizes it when the turn ends.

### Where it is computed

The fold computes it. The agent's state carries a trajectory accumulator in
the busy states, beside the tally, and moves it on as each call settles. At
the event that ends the turn, the harness hashes the accumulator once into
a `Trajectory`: a version and a hash.

Nothing is stored for the accumulator between events. Replaying a turn's
events through the fold gives the same trajectory, on either door, and
that includes a replay after a crash.

## The turn record

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
| `label` | the task label of the input that started the turn, `VARCHAR(256) NOT NULL` |
| `novel` | `true` when this was the first turn of its agent type and label to take this trajectory under this version, see [Novelty](#novelty) |
| `outcome` | the `TurnOutcome` name |
| `round_count` | rounds of tool calls |
| `tool_call_count` | calls settled, the sum of the next three |
| `tool_success_count`, `tool_failure_count`, `tool_denied_count` | calls by outcome |
| `inference_call_count` | every model call, retries included |
| `inference_retry_count` | attempts that failed and were tried again |

The columns fall in two groups. The behavioral ones are the trajectory
columns, `outcome`, `round_count` and the three tool counts. The
operational ones are `arrived_at`, `started_at`, `ended_at`,
`inference_call_count` and `inference_retry_count`. Only the behavioral
ones describe what the agent decided. `novel` is behavioral too: it is
derived from the trajectory when the turn ends, in the same transaction.
`label` is neither: it names the task, and is outside the fingerprint.

`turn_id` is the seq of `TurnStarted` and `ending_seq` is the seq of the
ending event. Between them, inclusive, are the turn's events, and folding
that slice reproduces the row. So the row is also an index into the
durable event stream: the interval `[turn_id, ending_seq]` is the evidence
the trajectory comes from. The failure that ends a turn is not a retry:
`inference_retry_count` counts only attempts that were tried again.

The row is a projection of the event stream, not a second source of truth.
If the table is lost, every row can be rebuilt from the events.

## Structured representation

The fingerprint answers one question: is this exactly the same normalized
trajectory? It serves equality and grouping. The `trajectory` column
answers another: what was the trajectory, and how does it differ from
another one? It lets analysis inspect and compare paths without replaying
the event stream.

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

Nessy defines equality. It defines no similarity: two different
fingerprints may differ by one extra tool call or by an entirely different
path. The JSON is a sequence of categorical values, not text, so the
measure of closeness is left to the analysis. A reader might try a
round-aware edit distance, a multiset distance within rounds, process
mining, or something else. The stored structure supports all of them.

## On the trace

Eight attributes carry the same values on the turn's span:
`nessy.trajectory.hash`, `nessy.trajectory.version`,
`nessy.trajectory.novel` (`true` or `false`), `nessy.turn.outcome`,
`nessy.turn.rounds`, `nessy.turn.tool_calls`, `nessy.turn.tool_failures` and
`nessy.turn.tool_denials`.

On the direct door they go on the `invoke_agent` span. On the queued door
they go on the `nessy.effect` span of the effect whose outcome ended the
turn, when an effect ended it. When a person's reply ends the turn, they go
on the replier's current observation instead. The row is always written; the
tags are best-effort trace annotation. When a later call recovers an abandoned turn on the direct door, the
abandoned turn's attributes land on the new call's span and the new turn
then overwrites them, so the abandoned turn's trajectory is in the row only.

All eight are high-cardinality span attributes. None is a metric tag: a hash
has as many values as the agent has behaviors, and that would grow a
metric's series without bound. Aggregate in SQL instead.

The trace is the place to look at one turn. The table is the place to ask
about many. See [Observability](../guides/observability.md) for how
trajectories sit beside narration, events, traces and metrics.

## Novelty

Each turn's row says whether its trajectory was new: `novel` is `true` when
no earlier turn of the same agent type, with the same label, took the same
trajectory under the same `trajectory_version`. Any one of the four values
differing makes the path new. The same path under a new label is novel, and
a version bump starts every path over.

The bit is decided by the database as the turn ends. The first turn to
record a path inserts a row into `nessy_known_trajectory`, keyed by
`(agent_type, label, trajectory_version, trajectory_hash)` with the
`first_seen` time. That insert is in the same transaction as the turn's row
and its ending event, so a rolled-back turn leaves no known path behind. Of
two agents of one type that end a turn on a new path at the same moment,
exactly one is novel.

`nessy_known_trajectory` is not owned by an agent and is not removed when
one is. A path stays known after the turns that walked it are gone. It holds
no count and no last-seen time, only the first sighting.

!!! warning "The label is part of the key, and it is cut to 256 characters"
    Novelty is per label, and a label over 256 characters is cut to its first
    253 characters followed by `...`, silently, with nothing logged. Two such
    labels that agree in those 253 are one category here too: a path new under the second one is already known. Put
    what tells labels apart first. See [Trajectories by task](#trajectories-by-task).

### Warm-up

A fresh install, an upgrade and a `trajectory_version` bump all start with
an empty set of known paths, so every turn is novel until its path repeats.
Arm an alert on `nessy.trajectory.novel = true` only after paths have
repeated. How long that takes depends on how many distinct paths and labels
the agent type has.

### Upgrading a database that has `nessy_agent_turn`

`CREATE TABLE IF NOT EXISTS` does not add the `novel` column to a table that
exists, and Nessy runs no migration. Keep the application stopped through
every step:

1. Stop the application.
2. Create `nessy_known_trajectory`: run its `CREATE TABLE` statement from
   `nessy-schema.sql` in `nessy-backend-jdbc`.
3. Optional: seed it from the turn rows, so the warm-up is skipped. The
   statement cuts each label as Nessy does, so the key is the one a new turn
   would write:

    ```sql
    INSERT INTO nessy_known_trajectory
           (agent_type, label, trajectory_version, trajectory_hash, first_seen)
    SELECT agent_type, cut, trajectory_version, trajectory_hash, MIN(ended_at)
    FROM (SELECT agent_type, trajectory_version, trajectory_hash, ended_at,
                 CASE WHEN char_length(label) > 256
                      THEN left(label, 253) || '...'
                      ELSE label END AS cut
          FROM nessy_agent_turn) t
    GROUP BY agent_type, cut, trajectory_version, trajectory_hash;
    ```

    Nessy does not run it. Skipping it means the warm-up applies: every
    path is novel once.
4. Drop the old table. Its rows are lost:

    ```sql
    DROP TABLE nessy_agent_turn;
    ```

5. Start the application. It recreates `nessy_agent_turn` with the `novel`
   column.

### Reading it

An alert rule on the span attribute `nessy.trajectory.novel = true` for the
agent types you watch is the push. The `novel` column and the table are the
query. No in-process listener is told about novelty. The
[starter queries](#starter-queries) show both reads.

## Analyzing behavior

Nessy stores the data and ships no analytics over it. The terms below name
what a query over `nessy_agent_turn` can measure.

| Term | Meaning |
|---|---|
| Trajectory cardinality | the number of distinct fingerprints observed |
| Trajectory concentration | the share of turns taken by the most common trajectories |
| Trajectory novelty | a trajectory not seen before in the comparison population or window |
| Trajectory drift | change in the distribution of trajectories over time |
| Behavioral entropy | the statistical dispersion of that distribution |

None of these is a good or bad number by itself. A bounded transactional
agent may legitimately have a handful of trajectories, and a research or
coding agent may have thousands. Compare a count with the role the agent
is meant to play and with the agent's own history.

Concentration tells a different story at each extreme. Five trajectories
holding 99 percent of turns and five hundred trajectories of about equal
share are different agents. A distribution can also change when no single
trajectory is new, so novelty and drift are separate checks.

The [starter queries](#starter-queries) below measure the first three, and [Novelty](#novelty) covers the fourth.

### Evals

Most evals ask whether the result was acceptable. The turn row adds a
second question: did the agent reach the result by an expected path?

An eval run can compare, per scenario (the label, or the eval system's own scenario id
joined on the primary key):

- the outcome and the fingerprint,
- `round_count` and `tool_call_count`,
- `tool_failure_count` and `tool_denied_count`,
- the time between `started_at` and `ended_at`.

That can show a regression in which answer quality holds and behavior
changes. A prompt change may add tool loops. A model upgrade may give the
same answers in twice as many rounds. An agent may start using a tool it
rarely needed, and tasks that always took one path may produce paths no
earlier run took.

### Anomalies

A trace tells you what happened. The trajectory distribution helps tell
you whether it was normal. These are signals a reader can look for in the
table:

- a trajectory never seen before for the agent type and label (the `novel` column, see [Novelty](#novelty)),
- a trajectory that is rare for the agent type, or for one `agent_id`,
- a sudden rise in the number of distinct trajectories,
- an unusual `round_count`, `tool_call_count` or `tool_failure_count`,
- the same trajectory with a very different time between `started_at` and
  `ended_at`,
- an unusual step from one trajectory to the next for one agent.

### One agent over time

An agent id is durable scope, not one invocation, so the rows of one
agent are a series. For one long-lived agent the series might read:

```text
A  A  A  B  C  A
```

Ordering one agent's rows by `ended_at` gives that series:

```sql
SELECT turn_id, ended_at, trajectory_hash, outcome
FROM nessy_agent_turn
WHERE agent_type = 'support' AND agent_id = '0199a3c4-5e2f-7b10-9a21-3c4d5e6f7a8b' AND trajectory_version = 1
ORDER BY ended_at;
```

From such a series a reader can set a baseline for one agent, watch it
drift, find a failure that keeps recurring, count the steps between one
trajectory and another, and rebuild what happened around an incident. A
series that shifts as an agent's history grows is one place to look for
effects of the agent's context or memory; the table does not say why it
shifted.

### Path and ending

The fingerprint covers rounds and ending together. The path alone is the
rounds without the ending, and it answers a different question: given the
same path, how often did the turn answer, fail, refuse or stop? In the
canonical encoding the path is every byte except the last two (the
`0xFF` marker and the ending tag). In SQL, the `rounds` of the JSON column give the same grouping:

```sql
SELECT trajectory -> 'rounds' AS path, outcome, COUNT(*) AS turns
FROM nessy_agent_turn
WHERE agent_type = 'support' AND trajectory_version = 1
GROUP BY trajectory -> 'rounds', outcome
ORDER BY path, turns DESC;
```

### Trajectories by task

A raw distribution describes an agent type as a whole, and part of its
variety comes from the variety of its inputs. To ask how many different
paths one kind of task takes, group by the label.

The `label` column holds the label the application's `inputLabel` gave the
input that started the turn, or the input's simple class name when the
application set none or its label failed (threw, or returned null or
blank). A label is a category. It names the kind of work,
from a small set of values such as `rounds` or `invoice:PRICE_VARIANCE`.
It is stored plain, unencrypted, so it must never carry the input's
content.

!!! warning "A label is cut to 256 characters, silently"
    A label longer than 256 characters is cut to 256: its **first 253
    characters followed by `...`**. Nothing is logged and nothing fails when
    the cut happens.

    - Put the parts that tell labels apart **first**: `price-variance:invoice`,
      not a long shared prefix with the discriminator last.
    - Two labels over 256 characters that agree in their first 253 become
      **one category**: in the turn rows, in trajectory statistics, and in novelty
      (`nessy_known_trajectory`).
    - A label is a category from a small closed set. It is never content,
      ids, names or free text.

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

Nothing enforces how many values a label takes, only how long one is. An application that gives
each input a unique label gets one trajectory per label and learns nothing.

Nessy defines no task taxonomy beyond the label. Richer metadata, such as
a scenario, a release, a tenant or an experiment, can live in the
application's own tables and be joined to the turn row on
`(agent_type, agent_id, turn_id)`, which is the row's primary key. An
eval system can do the same with its run records.

## Starter queries

How many distinct trajectories has each agent type produced?

```sql
SELECT agent_type, COUNT(DISTINCT trajectory_hash) AS trajectories, COUNT(*) AS turns
FROM nessy_agent_turn
WHERE trajectory_version = 1
GROUP BY agent_type
ORDER BY agent_type;
```

A low count is not good or bad on its own; read it against the role of the
agent, as under [Analyzing behavior](#analyzing-behavior).

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

The percentages are the concentration of the agent type's turns in its
top five paths.

Turns that took a new trajectory in the last day (see [Novelty](#novelty)):

```sql
SELECT agent_type, agent_id, turn_id, label, trajectory_hash, ended_at
FROM nessy_agent_turn
WHERE novel AND ended_at >= now() - INTERVAL '1 day'
ORDER BY ended_at;
```

The trajectories themselves, from the table of known paths:

```sql
SELECT agent_type, label, trajectory_hash, first_seen
FROM nessy_known_trajectory
WHERE trajectory_version = 1 AND first_seen >= now() - INTERVAL '1 day'
ORDER BY first_seen;
```

## Canonical encoding

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

## Where next

- [Turns](turns.md) for what a turn is and how it ends.
- [Outcomes](outcomes.md) for what a caller gets back.
- [Observability](../guides/observability.md) for traces and metrics, and where trajectories sit beside them.
- [Storage](storage.md) for the tables the rest of an agent lives in.
