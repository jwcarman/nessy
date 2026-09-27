-- Every table is keyed by a meaningless version 7 UUID, and a given id has the same
-- column name wherever it appears, primary key or foreign key. That is what lets a
-- query say USING (agent_id) rather than spelling out a join condition.

-- An agent, so there is something to lock and something to point at.
--
-- Taken with SELECT ... FOR UPDATE, which holds for exactly the transaction and is released by the
-- database when a connection dies -- no time-to-live to tune, and none of the trouble a lease has
-- telling a slow holder from a dead one. It has to be a row that always exists: locking the
-- backlog rows instead would leave two arrivals to an empty backlog with nothing to contend for.
CREATE TABLE IF NOT EXISTS nessy_agent
(
    agent_type   VARCHAR(64) NOT NULL,
    agent_id     UUID        NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    -- When it was told to end, and null while it has not been.
    --
    -- Terminating cannot be delivered to an agent in the middle of a turn, because the fold takes
    -- it only from idle. So it is recorded here, the backlog is emptied, and every read of the
    -- backlog afterwards answers with the pill. The next time the agent is idle and asks for work,
    -- ending IS the work.
    terminated_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (agent_type, agent_id)
);

-- The outbox. Rows are written in the transition's transaction and performed later.
--
-- An effect has no row naming its cause. The cause is the transition that emitted it, and
-- the transaction that wrote state, story and effect together is what makes that hold --
-- a foreign key would only have restated it, and could not have pointed anywhere true:
-- a fold may emit an effect while recording no message at all.
CREATE TABLE IF NOT EXISTS nessy_agent_effect
(
    effect_id   UUID        PRIMARY KEY,
    agent_id    UUID        NOT NULL,
    agent_type  VARCHAR(64) NOT NULL,
    FOREIGN KEY (agent_type, agent_id) REFERENCES nessy_agent (agent_type, agent_id),
    payload     BYTEA       NOT NULL,
    -- Attached at emit, from the binding for this effect. Here rather than looked up when
    -- marking, because marking must not decode the payload: it happens before anything knows what
    -- kind of work this is. Frozen at emit, so a timeout changed in configuration reaches work
    -- emitted afterwards, not work already queued. It is how long the work gets once it starts,
    -- and it is read again at the first claim to fix the deadline below.
    timeout_millis BIGINT   NOT NULL,
    -- What to tell the agent if this effect can never be dispatched at all -- written when the
    -- effect is, in its own blob so that a payload which will not decode does not take the
    -- handling of that failure down with it. Read only in that emergency; an ordinary failure
    -- still produces its outcome the rich way, from the handler that knows what went wrong.
    --
    -- The case is not only corruption. A rolled-back deploy leaves rows naming an effect type the
    -- running build has never heard of, which fails at decode identically -- and an agent hanging
    -- forever on one of those is a deploy incident rather than a bad byte.
    failure_payload BYTEA  NOT NULL,
    -- When this effect stops being worth doing. The agent declaring how long it is willing to
    -- wait for an answer, so it is measured from when the effect was written down rather than
    -- from when somebody got round to it: time spent queued is time the agent spent waiting, and
    -- a budget that ignored it would be bounding the wrong thing.
    --
    -- Frozen at emit like timeout_millis above, and never touched again, so retries spend one
    -- budget rather than restarting it. Distinct from actionable_at, which moves with every
    -- attempt; this never moves.
    deadline    TIMESTAMP WITH TIME ZONE NOT NULL,
    -- The trace this effect belongs to, in W3C's own format, captured as the row was written.
    --
    -- Here because the thread that performs this row has nothing to inherit from: the emitting
    -- transaction committed minutes ago and may have been in a process that has since died. A
    -- turn comes back as one trace only if its parent was written down beside the work.
    --
    -- Nullable, and losing it costs a parent rather than a turn: a row written before tracing was
    -- switched on, or by an application that never will, simply starts a trace of its own.
    trace_context TEXT,
    status      VARCHAR(16) NOT NULL,
    -- attempts_made is a fact the row records, and nothing yet judges it. actionable_at is the
    -- one thing a query must see: before an attempt it is when to try, during one it is when the
    -- attempt stops being believed. Either way it is when the row is due, which is why one column
    -- serves both and no query needs to know which it is looking at.
    --
    -- It never runs past deadline. A row coming due at its deadline comes due to be given up on,
    -- not to be tried again, and a backoff that would land beyond it is not a later retry.
    attempts_made INT          NOT NULL CHECK (attempts_made >= 0),
    actionable_at TIMESTAMP WITH TIME ZONE   NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE
);

-- Shaped for the one query that matters: due work of one agent type, oldest first.
CREATE INDEX IF NOT EXISTS ix_nessy_agent_effect_actionable
    ON nessy_agent_effect (agent_type, status, actionable_at);
-- Content, kept away from the record of what happened to it.
--
-- Everything a model was shown or said -- inputs, answers, tool results -- lives here and
-- nowhere else. The tables that describe an agent's life hold references, so they are plain rows
-- nobody has to reason about: no user text, no tool output, nothing anybody has to encrypt,
-- redact, or hunt through to answer a question about what is retained.
--
-- Addressed by the hash of its own bytes, which buys idempotence: an effect retried after a
-- failure writes the same row rather than a second copy. Scoped by agent, which buys forgetting:
-- deleting everything one agent ever said is one statement over one table, with nothing shared
-- out from under another agent. Identical content in two agents is stored twice, and that is the
-- trade -- cross-agent sharing is rare, and a deletion that has to count references is a deletion
-- somebody eventually gets wrong.
CREATE TABLE IF NOT EXISTS nessy_payload
(
    agent_id   UUID        NOT NULL,
    -- SHA-256 of the encoded content. Large enough that an accidental collision is far less
    -- likely than the row being corrupted underneath us, and strong enough that a crafted one
    -- is not a thing anybody can do.
    hash       BYTEA       NOT NULL,
    content    BYTEA       NOT NULL,
    written_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (agent_id, hash)
);

-- What happened to an agent, in order, append-only. No content: every block is a reference into
-- nessy_payload, so this table is a list of facts about a life rather than a copy of it.
--
-- The primary key is the concurrency control. Two writers that decided from the same state mint
-- the same seq, so the second one violates it and takes its own transaction down -- which is the
-- right way to find out that the state it decided against no longer holds.
CREATE TABLE IF NOT EXISTS nessy_agent_event
(
    agent_id    UUID        NOT NULL,
    seq         BIGINT      NOT NULL,
    -- Where a turn begins, which is the only thing about an event this table needs to know
    -- without decoding it. Reading an agent back means replaying its last turn, and finding
    -- where that starts is a lookup rather than a scan because of this column. A stored
    -- watermark would answer the same question as a second copy of it that can disagree; this
    -- is the events saying it themselves.
    starts_turn BOOLEAN     NOT NULL,
    payload     BYTEA       NOT NULL,
    written_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (agent_id, seq)
);

-- Only the turn starts, which is all the boundary lookup reads. A handful of rows per agent
-- however long the conversation.
CREATE INDEX IF NOT EXISTS nessy_agent_event_turn_starts
    ON nessy_agent_event (agent_id, seq DESC) WHERE starts_turn;

-- An agent lock has no table. It is a Postgres advisory lock, taken with
-- pg_advisory_xact_lock on a hash of (kind, agent type, agent id) and released by the database
-- when the transaction ends -- committed, rolled back, or its connection simply dropped. There is
-- no stale holder to fence against and nothing for an expiry to time, which is the same argument
-- the row this replaces was making; the row was only ever something for FOR UPDATE to point at,
-- carried no data, and was never deleted. A lease still needs its own table, and the difference is
-- the whole distinction between the two: a lease must outlive the process that took it, so it is a
-- fact to store; a lock exists only while a transaction is running, so there is nothing to keep.
--
-- An existing database keeps its now-unused nessy_lock table; nothing reads it, and this file has
-- never destroyed anything.

-- Work offered to an agent that is busy, waiting its turn.
--
-- One row per item, which is what lets a coalescer say "append" or "keep only this" in a statement
-- rather than rewriting a list. A single row holding a serialized backlog would make every
-- strategy a read-modify-write of the whole thing.
--
-- The input itself, NOT a claim check. This is the one place content sits in a control-plane
-- table, and it is deliberate: a backlog is a staging area rather than a record, and what is here
-- is on its way into an event where it WILL be claim-checked. Forgetting an agent has to clear
-- this table as well as its payloads.
CREATE TABLE IF NOT EXISTS nessy_agent_backlog
(
    agent_type VARCHAR(64) NOT NULL,
    agent_id   UUID        NOT NULL,
    -- The backlog's own arrival ordinal, not an event seq: an item here is not an event yet and
    -- may never become one, since a coalescer is free to drop it.
    ordinal    BIGINT      NOT NULL,
    arrived_at TIMESTAMP WITH TIME ZONE NOT NULL,
    payload    BYTEA       NOT NULL,
    PRIMARY KEY (agent_type, agent_id, ordinal)
);

-- Leases: "only one of us should do this right now."
--
-- A row per (kind, agent type, agent id) that somebody is working on. Taking one is an insert, or
-- an update of a row whose holder let it expire; the holder deletes it when the work is done, and
-- a holder that died leaves a row the next caller takes over once expires_at has passed. Nothing
-- waits and nothing queues: a caller that finds the lease held gives up, which is what makes this
-- fit opportunistic work rather than work somebody is owed.
--
-- Time is the database's, so holders on different machines with different clocks agree on when
-- a lease has expired.
--
-- takeovers counts, for this row, how many times a holder that never released it has been taken
-- over; it is incremented only on the ON CONFLICT DO UPDATE path and resets naturally, because
-- release DELETEs the row -- a row exists only between a take and its release, and the count never
-- outlives the contention it counts.
CREATE TABLE IF NOT EXISTS nessy_lease
(
    kind       VARCHAR(64) NOT NULL,
    agent_type VARCHAR(64) NOT NULL,
    agent_id   UUID        NOT NULL,
    holder     UUID        NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    takeovers  INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (kind, agent_type, agent_id)
);
