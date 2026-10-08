# Trajectory novelty: a signal when a turn takes a path never seen before

**Status: APPROVED. The rulings in §2 were made by James in conversation on 2026-10-08 and are his,
including the answers in §10 to the questions the first draft could not settle alone.** Every path and name below was checked against `main` at `e8f3327ca`.

Amends `2026-10-07-trajectory-fingerprinting-design.md`, which stays the design of record for the
fingerprint, the row and the span attributes it defines.

---

## 1. The problem

An agent that is expected to walk well-trodden paths and one day walks a path nobody has seen
before may be telling the operator something: a prompt change that opened a new branch, a tool
that started failing, a model that found a way around a policy. Today the question "did a new
trajectory appear?" has an answer only in SQL, by polling (`docs/concepts/trajectories.md:480-497`
runs a `NOT EXISTS` over `nessy_agent_turn` for the last day). Nothing says it at the moment the
turn ends, so nothing can alert on it.

This record gives every completed turn a **novelty bit**: whether its trajectory, for its agent
type and task label, had been seen before. The bit is decided by the database at turn end, written
on the turn's row in the same transaction as the ending event, carried on the turn's span, and kept
in a small table of every trajectory ever seen that outlives the turns that produced it.

## 2. Rulings

Made in conversation on 2026-10-08. These are decided.

1. **A table of known trajectories, `nessy_known_trajectory`**, keyed by
   `(agent_type, label, trajectory_version, trajectory_hash)`, with one more column, `first_seen`:
   the `ended_at` of the first turn that took the path. Nothing else: no count, no `last_seen`, no
   foreign key or pointer to a turn row.
2. **Written write-once at turn end** with `INSERT ... ON CONFLICT (...) DO NOTHING`, in the same
   transaction as the `nessy_agent_turn` row. An update count of 1 means the turn is novel; 0 means
   the path was known. A rolled-back turn leaves no known-trajectory row.
3. **The signal is named `novel`** and is carried three ways: a `boolean novel` component on
   `AgentTurn`, a `novel` column on `nessy_agent_turn`, and a `nessy.trajectory.novel` attribute on
   the turn's span beside `nessy.trajectory.hash`. Every turn of every agent type gets it. There is
   no per-type opt-in: alert rules choose which types they watch.
4. **PostgreSQL only.** The JDBC backend already depends on `ON CONFLICT` and `JSONB`.
5. **No metric.** The fingerprinting record excluded metrics; a novelty counter belongs to a
   future metrics roster.
6. **The store's new method is `firstSighting`**, a second method on `AgentTurns` called before
   `append` (§5.1), so the row is complete and true when it is built.
7. **A label too long for the key is capped in bytes**, if the test in §9 proves Postgres refuses
   it: `columnSafe` caps the label in UTF-8 bytes as well as characters, on the row and in the known
   table alike (§10.2).
8. **Seeding the known table on upgrade is documentation only**: an optional step the docs show,
   not code (§7, §10.3).
9. **No in-process push.** The span attribute and the column are the signal; listeners do not see
   `novel` (§10.4).

### Why these, briefly

- **A table, not an `EXISTS` probe on `nessy_agent_turn`.** Agent garbage collection is planned:
  everything about an agent long done (about 30 days) will be deleted, turn rows included. A
  known trajectory must survive that, so it cannot live only on turn rows. The table also grows
  with distinct paths, not with turns: it stays small, and its primary key stays in cache.
- **No count, no `last_seen`.** Postgres takes the row lock on the conflicting row for
  `ON CONFLICT DO UPDATE` even when the `DO UPDATE`'s `WHERE` is false. Maintaining a count or a
  last-seen time would make every common path a hot row that concurrent turns of that type queue
  on inside their commit transaction. `DO NOTHING` only checks the index.
- **In the turn's transaction, and `ON CONFLICT` rather than a pre-check.** The row and the known
  entry commit or roll back together, so the row's `novel` is never a lie about a known table that
  does not say the same. A `SELECT` before the insert can flag two concurrent first sightings of
  one path as both novel; `ON CONFLICT` gives exactly one, because the second insert of the same
  key waits on the first and then does nothing.

## 3. What the code does today

- `TurnRecorder.recordEnding` (`nessy-engine/src/main/java/org/jwcarman/nessy/engine/core/TurnRecorder.java:86-109`)
  folds the step's events, and at the one that ends a turn builds the `AgentTurn` from the
  `Inferring` state before it (`summarise`, lines 111-133), calls `AgentTurns.append` (line 95),
  and then tags the current observation (`tag`, lines 160-173). `append` is `void`
  (`nessy-backend/spi/src/main/java/org/jwcarman/nessy/backend/turn/AgentTurns.java:40`) and the
  row is complete before it is called. **So nothing today lets the store tell the engine anything
  about the row it wrote**, and the novelty bit is something only the store can know (§4.3).
- The three sites that can end a turn each call `recordEnding` right after
  `backend.events().append(...)` and discard its `Optional<AgentTurn>`:
  `DefaultDirectHarness.executeStep` (`...harness/direct/DefaultDirectHarness.java:411-421`),
  `DefaultDirectHarness.recoverToIdle` (lines 577-592) and `DefaultQueuedHarness.apply`
  (`...harness/queued/DefaultQueuedHarness.java:459-478`). All three run inside
  `Locks.withLock`, which on JDBC is one transaction
  (`nessy-backend/jdbc/src/main/java/org/jwcarman/nessy/backend/jdbc/JdbcRowLocks.java:133-141`)
  and in memory is a `ReentrantLock` with no rollback of anything
  (`nessy-backend/inmemory/src/main/java/org/jwcarman/nessy/backend/inmemory/InMemoryLocks.java:68-82`).
- `JdbcAgentTurns` (`nessy-backend/jdbc/.../JdbcAgentTurns.java:42-50, 72-104`) is one plain
  `INSERT` through `JdbcClient` on the caller's ambient transaction. `JdbcPayloads.PUT`
  (`JdbcPayloads.java:60-65`) is the existing `ON CONFLICT (...) DO NOTHING`, and the comment in
  `JdbcChapters.store` (`JdbcChapters.java:140-144`) already records the waiting behaviour this
  design relies on: the loser's `ON CONFLICT DO NOTHING` waits for the winner to commit and then
  stores nothing.
- `InMemoryAgentTurns` (`nessy-backend/inmemory/.../InMemoryAgentTurns.java:31-33`) keys rows by
  `(AgentType, AgentId)` only. Nothing in it is keyed by trajectory, so there is nothing to reuse;
  it needs a set of its own (§5.2).
- The span is tagged **before** the transaction commits and while the observation is still open:
  on the direct door the `invoke_agent` observation wraps the whole ask
  (`DefaultDirectHarness.java:299-304`), on the queued door the `nessy.effect` span wraps the
  effect's work, including `apply`. The `TurnRecorder` javadoc (lines 51-55) already accepts that a
  rolled-back step can leave a hash on a span. Nothing about that order changes here; §6 says what
  it means for `novel`.
- **`AgentTurn` never reaches a listener.** `NarrationListener.on` receives a `Narrated`
  (`nessy-api/src/main/java/org/jwcarman/nessy/api/Narrated.java`), which carries a `Narration`
  event and a position, never a row. `AfterCommit`
  (`nessy-engine/src/main/java/org/jwcarman/nessy/engine/narration/AfterCommit.java:43-56`)
  delivers a step's narrations only once `withLock` has returned, so a listener is never told
  about anything a rollback undid. There is no listener-facing carrier for `novel` today; §6
  states what a listener can and cannot see, and §10 asks whether one is wanted.
- No TCK covers `AgentTurns`. The only TCK in the repository is the JDBC dialects one
  (`docs/superpowers/specs/2026-08-15-jdbc-dialects-tck-design.md`); the two stores are tested
  by `JdbcAgentTurnsTest` (`@Tag("container")`) and `InMemoryAgentTurnsTest` separately.

## 4. The known trajectory

### 4.1 Identity

A known trajectory is identified by `(agent_type, label, trajectory_version, trajectory_hash)`.
The label is part of the identity because a label is a category (fingerprinting §4.7): the same
path under a new kind of work is news, and "trajectory given task" is the measure the
fingerprinting record set up. The label in the key is the **row's** label, after `columnSafe`
(`TurnRecorder.java:139-154`) has replaced what Postgres cannot hold, so the known-trajectory key
and the turn row always agree and join on equal strings.

The version is part of the identity because a hash means nothing across versions (fingerprinting
§4.5). A bump of `trajectory_version` therefore starts a new, empty population of known paths
(§7).

### 4.2 Schema

Appended to `nessy-backend/jdbc/src/main/resources/nessy-schema.sql` after `nessy_agent_turn`
(line 262-288), in its conventions: `CREATE TABLE IF NOT EXISTS`, the same column name for the
same value everywhere, timestamps with time zone, and a comment that says what the table is for.

```sql
-- Every trajectory ever seen, once: the first time an agent type, doing one kind of work (label),
-- ended a turn on this path under this encoding version. Written with the turn row, in the same
-- transaction, by INSERT ... ON CONFLICT DO NOTHING: the insert that stores a row is the turn that
-- was novel. Deliberately not owned by any agent and not removed with one, so that a path stays
-- known after the turns that walked it are gone. No count and no last_seen, on purpose: a DO
-- UPDATE would lock the row of every common path under every concurrent turn's commit.
CREATE TABLE IF NOT EXISTS nessy_known_trajectory
(
    agent_type         VARCHAR(64)              NOT NULL,
    label              VARCHAR(1000)            NOT NULL,
    trajectory_version SMALLINT                 NOT NULL,
    trajectory_hash    CHAR(64)                 NOT NULL,
    first_seen         TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (agent_type, label, trajectory_version, trajectory_hash)
);
```

The primary key is the arbiter of the `ON CONFLICT`; no second index. The column types match
`nessy_agent_turn`'s exactly so the two tables join without casts.

`nessy_agent_turn` gains one column, after `label`:

```sql
    novel                 BOOLEAN                  NOT NULL,
```

The table's comment gains a line: `novel` says whether this turn was the first of its agent type
and label to take its trajectory, as decided by `nessy_known_trajectory` in the same transaction.

### 4.3 The sighting

One statement, on the caller's ambient transaction, run **before** the turn row's insert and in
the same `recordEnding` call:

```sql
INSERT INTO nessy_known_trajectory
       (agent_type, label, trajectory_version, trajectory_hash, first_seen)
VALUES (?, ?, ?, ?, ?)
    ON CONFLICT (agent_type, label, trajectory_version, trajectory_hash) DO NOTHING
```

`first_seen` is the `at` the ending is written with, which is the turn row's `ended_at`. The
update count is the novelty bit: 1 is novel, 0 is known. The turn row is then inserted with that
bit in its `novel` column. Both statements are inside `Locks.withLock`, so both commit with the
ending event or neither does.

**Concurrency.** The agent lock is per agent, so two agents of one type can end turns at the same
moment on the same new path in two transactions. Both run the insert. The second finds the first's
uncommitted index entry, waits for that transaction to finish, and then does nothing (count 0,
known) if it committed, or inserts (count 1, novel) if it rolled back. Exactly one of two
concurrent first sightings is novel, and the wait is bounded by the remainder of a step, which
holds no model call.

### 4.4 The bit on the row, the record and the span

- `AgentTurn` gains `boolean novel`. The record's compact constructor needs no new check.
- `nessy_agent_turn.novel` is written from it, as given, like every other column.
- `TurnRecorder.tag` adds `nessy.trajectory.novel` with the value `true` or `false`, as a
  high-cardinality key value like its neighbours (two values would be a fine metric dimension,
  but the observation also drives the `gen_ai.client.operation.duration` timer and nothing on this
  span is to become one; §7 of the fingerprinting record).

## 5. Where the bit is computed

### 5.1 The engine

`recordEnding` becomes, at a turn-ending event: build the fingerprint and the column-safe label;
ask the store whether this `(type, label, trajectory)` is a first sighting, passing `at`; build the
`AgentTurn` with the answer in `novel`; `append` it; tag the observation. The store answers from
the database (JDBC) or its own set (in-memory); the engine never keeps a map of what it has seen
(the no-in-process-memory rule). `summarise` takes the bit as one more argument so the row is
still built from the folded state, the event, `at`, and now the store's answer.

This needs one new question on the `AgentTurns` SPI, because `append` is `void` and the row must
carry the bit before it is written. The shape proposed is a second method beside `append`, called
first in the same unit of work:

```java
/** Records that this agent type, on this kind of work, ended a turn on this trajectory.
 *  @return true if it is the first time: the turn is novel */
boolean firstSighting(AgentType type, String label, Trajectory trajectory, Instant at);
```

The name was ruled in §2.6; the alternative, `append` returning the bit, was not taken (§10.1).

### 5.2 The stores

- **JDBC**: `JdbcAgentTurns` runs the statement of §4.3 and returns `update() == 1`. It opens no
  transaction of its own, exactly as its insert does not (`JdbcAgentTurns.java:37-39`), so the
  sighting and the row are two statements on the step's one transaction.
- **In-memory**: `InMemoryAgentTurns` gains a `Set` of `(AgentType, String label, Trajectory)`
  beside its map of rows, under the same `synchronized`, and answers `add(...)`. The set is
  process-lifetime and never trimmed, which is the in-memory analogue of a table nothing deletes
  from. Under the `ReentrantLock` of `InMemoryLocks` two agents of one type cannot sight the same
  path at once per lock key, but the keys differ per agent, so the `synchronized` on the store is
  what makes exactly one sighting novel; the same monitor already guards `append`.

  In-memory has no rollback: a throw after the sighting leaves the path known, as a throw after
  `events().append` leaves the events. That is the in-memory backend's existing contract, not a
  new exception to it. Each in-memory backend instance has its own set, as it has its own events;
  an application running both doors over two in-memory backends has two populations, exactly as it
  has two stories.

Both stores must agree on the semantics the tests in §9 state: first sighting novel, repeat known,
label and version part of the identity, type part of the identity.

## 6. When the bit is visible

- **On the row**: after commit, like every column. A query sees `novel` only for committed turns.
- **On the span**: at the terminal fold, before commit, as today for the hash. If the step then
  rolls back, the span carries `novel = true` for a turn that did not commit, and its known row is
  gone with it; the retry sights the path again and is novel again, so the two spans agree with
  each other and the committed row. The row is the record of truth; the span is best-effort
  annotation, as the fingerprinting record already rules.
- **To listeners**: not at all, today. No listener receives `AgentTurn`; the ending event a
  listener is told about carries no trajectory, and `AfterCommit` would deliver it after commit
  in any case. An operator who wants a push gets it from the trace (an alert rule on
  `nessy.trajectory.novel = true` for the agent types they watch) or from a query on the column.
  Whether an in-process push is wanted is asked in §10, because it is a new public thing.

## 7. Cold start, upgrade, version bump

All three look the same from the outside: an empty population of known paths, so every turn is
novel until paths repeat. The docs must say so and tell operators to arm novelty alerts only after
a warm-up, the length of which depends on how many distinct paths and labels the type has.

- **Fresh install**: `Schemas.initialize` creates both tables; the first turn of every
  `(type, label, version, hash)` is novel.
- **Upgrade from a database that has `nessy_agent_turn`**: `CREATE TABLE IF NOT EXISTS` does not
  add `novel` to a table that exists, and per the no-backward-compatibility rule there is no
  in-code `ALTER` and no migration. The upgrade instruction is: `DROP TABLE nessy_agent_turn;`
  and start the application; the schema recreates it with the column and creates
  `nessy_known_trajectory` empty. The turn rows are a projection and are lost with the drop;
  an operator who wants to skip the warm-up can carry their paths into the known table first, with
  the optional statement the docs show (§10.3). Nessy does not run it.
- **A `trajectory_version` bump**: a new version is a new key space. Every path is novel again
  under the new version; rows and known entries under the old version stay and never collide.

## 8. What this does not do

- **Garbage collection.** Its own future record. This one says only that
  `nessy_known_trajectory` is deliberately not owned by any agent, has no agent id, and is not
  removed when an agent is.
- **A count or recency per path.** A query on `nessy_agent_turn` answers both while the turn rows
  exist, and no hot row is paid for either.
- **A per-type switch.** Every turn is sighted. Choosing types is the alert rule's job.
- **A metric.** Ruled out (§2.5).
- **A read API.** `nessy_known_trajectory` is queried in SQL, like `nessy_agent_turn`.

## 9. Tests

Prose style, no mocking library, as the design of record requires. There is no TCK for
`AgentTurns` (§3), so the store cases run in both `InMemoryAgentTurnsTest` and
`JdbcAgentTurnsTest` (`@Tag("container")`), worded the same.

**Store** (both stores):

- the first sighting of a trajectory for a type and label is novel, and the second is not.
- the same trajectory under another label is novel again; under another agent type is novel
  again; under another version is novel again.
- a sighting records `first_seen` as the `at` it was given, and a repeat does not move it (JDBC:
  read the column; in-memory: the set is what it is, so this case is JDBC only).
- a row appended with `novel` true reads back true, and one with false reads back false.

**Atomicity** (JDBC, `@Tag("container")`):

- a sighting followed by a rolled-back transaction leaves no known-trajectory row, and the next
  sighting of the path is novel.
- an `append` that throws after a sighting rolls the sighting back with the ending (the existing
  case "an `AgentTurns.append` that throws rolls the terminal event back too" gains the assertion
  on `nessy_known_trajectory`).

**Concurrency** (JDBC, `@Tag("container")`):

- two transactions on two connections sight the same new path at once, the second blocked on the
  first until it commits: exactly one is novel, and `nessy_known_trajectory` has one row. The same
  with the first rolling back: the second is novel.

**The label in the key** (JDBC, `@Tag("container")`), required by §10.2:

- a label of `ToolConfig.LINE_CAP` (1,000) characters each four bytes in UTF-8 is sighted. The
  test states which it is: the insert succeeds, or Postgres refuses the index entry. If Postgres
  refuses it, the byte cap of §2.7 is implemented and this test is its red test; a second case pins
  that a label at the cap inserts. If Postgres accepts it, no cap is added and the test stays as
  the proof.

**Engine** (`TurnRecorderTest`, with a store fake that answers a scripted bit):

- the row carries the bit the store answered, and the sighting is asked with the row's
  column-safe label, its trajectory, and the row's `ended_at`.
- the sighting is asked before the row is appended, once per ending, and not at all for events
  that end no turn.
- the current observation is tagged `nessy.trajectory.novel` with `true` and with `false`; with
  no observation in force, nothing is tagged and the row is still written.

**Doors** (`DirectHarnessTrajectoryTest`, `QueuedHarnessTrajectoryTest`, in-memory):

- two turns of one agent on the same path: the first row is novel, the second is not, on both
  doors.
- two agents of one type on the same path: one novel row between them.
- the attribute reaches the finished observation on the direct door and the effect span on the
  queued door, beside `nessy.trajectory.hash`.

**Docs**: the starter queries in §11 run against a container and return what they say.

## 10. Questions the first draft left open, and their answers

Each was asked of James on 2026-10-08 and answered the same day.

1. **The name of the store's new method.** `append` is `void` and the row must carry the bit before
   it is written. Answer: a second method, `firstSighting`, called before `append` in the same unit
   of work. Having `append` return the bit was not taken: the row would be built with a `novel` that
   is wrong until after the write.
2. **A label that breaks the primary key.** `label` is `VARCHAR(1000)` in characters, up to about
   4,000 bytes in UTF-8, and is part of `nessy_known_trajectory`'s primary key. A Postgres btree
   entry has a limit of about 2,700 bytes ("index row size exceeds btree version 4 maximum 2704";
   not yet proven here). Over it, the sighting fails and rolls back the turn's ending on every
   retry. Answer: the test in §9 decides whether this is real; if it is, `columnSafe` caps the label
   in UTF-8 bytes as well as characters. A label is a category from a small set, so a label near the
   cap is already a misuse, and the cap also makes `label` safe to index on `nessy_agent_turn`.
   Keying on a hash of the label, or dropping the label from the key, were not taken.
3. **Seeding the known table on upgrade.** Answer: documentation only. The docs show, as an
   optional step before `DROP TABLE nessy_agent_turn`:
   `INSERT INTO nessy_known_trajectory SELECT agent_type, label, trajectory_version, trajectory_hash, MIN(ended_at) FROM nessy_agent_turn GROUP BY 1, 2, 3, 4;`
   Without it, the warm-up of §7 applies.
4. **An in-process push.** Answer: not now. The span attribute and the column are enough for this
   record; a listener-visible event would be a new public type and its own design.

## 11. Docs impact

`docs/concepts/trajectories.md`:

- the turn record table (lines 190-215) gains `novel`; the "two groups" paragraph says it is
  behavioral, derived at turn end.
- "On the trace" (lines 283-287) becomes eight attributes, adding `nessy.trajectory.novel`.
- "Anomalies" (lines 350-361): "a trajectory never seen before" points at `novel`.
- a new section, "Novelty", says what the bit means, that it is per type and label and version,
  the warm-up, and the upgrade step of §7, and the optional seed of §10.3.
- the starter query "Turns whose trajectory first appeared in the last day" (lines 480-497) is
  replaced by two:

```sql
SELECT agent_type, agent_id, turn_id, label, trajectory_hash, ended_at
FROM nessy_agent_turn
WHERE novel AND ended_at >= now() - INTERVAL '1 day'
ORDER BY ended_at;
```

```sql
SELECT agent_type, label, trajectory_hash, first_seen
FROM nessy_known_trajectory
WHERE trajectory_version = 1 AND first_seen >= now() - INTERVAL '1 day'
ORDER BY first_seen;
```

`docs/concepts/storage.md`: a row in the table of tables (line 18) for `nessy_known_trajectory`,
retention "forever; not removed with an agent", and line 222's list of columns stored plain gains
`nessy_known_trajectory.label`. `ContentColumnsTest`
(`nessy-examples/chat-web/src/test/java/org/jwcarman/nessy/examples/chatweb/ContentColumnsTest.java:53-73`)
enumerates the plain columns of the schema and must learn the new table's.

## 12. Modules touched

| Module | Change |
|---|---|
| `nessy-backend-spi` | `AgentTurn.novel`; `AgentTurns.firstSighting` (§10.1) |
| `nessy-backend-jdbc` | `nessy_known_trajectory` and `nessy_agent_turn.novel` in the schema; the sighting and the column in `JdbcAgentTurns` |
| `nessy-backend-inmemory` | the known set in `InMemoryAgentTurns` |
| `nessy-engine` | `TurnRecorder`: the sighting before `summarise`, the bit on the row, `nessy.trajectory.novel` on the span |
| `nessy-examples/chat-web` | `ContentColumnsTest` learns the new table |
| `docs/` | §11 |
