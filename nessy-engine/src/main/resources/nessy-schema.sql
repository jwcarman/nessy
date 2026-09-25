-- Every table is keyed by a meaningless version 7 UUID, and a given id has the same
-- column name wherever it appears, primary key or foreign key. That is what lets a
-- query say USING (agent_id) rather than spelling out a join condition.

-- The agent's current state. One row per agent; the row lock taken on it is what
-- serializes transitions for that agent while leaving other agents free.
--
-- agent_id is the key rather than a separate surrogate because an agent has no natural
-- identity to begin with -- it is already a minted UUID, so a second one would be inert.
--
-- version is maintained by Spring Data JDBC, and counts FOLDS -- not messages. A fold may
-- record two messages, one, or none, so the story's seq is its own counter, minted by
-- max(seq) + 1 under this row's lock. Conflating them would make a fold that recorded
-- nothing look like a gap in the story.
CREATE TABLE IF NOT EXISTS nessy_agent_state
(
    agent_id   UUID        PRIMARY KEY,
    agent_type VARCHAR(64) NOT NULL,
    version    BIGINT      NOT NULL,
    state_type VARCHAR(64) NOT NULL,
    payload    BYTEA       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- The story. One row per message, in the order it happened. Append-only.
--
-- turn_id is the seq of the observation that opened the turn, so a turn needs no identifier of
-- its own: turns are ordered by comparing integers, and a turn's first message is the row where
-- seq = turn_id. No discriminator column either -- the codec's payload names its own type.
CREATE TABLE IF NOT EXISTS nessy_agent_history
(
    agent_type VARCHAR(64) NOT NULL,
    agent_id   UUID        NOT NULL REFERENCES nessy_agent_state (agent_id),
    seq        BIGINT      NOT NULL,
    turn_id    BIGINT      NOT NULL,
    -- Roughly what this message costs a model's context, estimated when it is written. Here so a
    -- budget can be applied in the query -- a running sum over turns, stopping at the oldest that
    -- fits -- rather than by loading a whole conversation to measure it. An estimate and never the
    -- authority: the provider's tokenizer decides, and being wrong is survivable because a request
    -- refused for length is retried rather than lost.
    tokens     INT         NOT NULL,
    payload    BYTEA       NOT NULL,
    PRIMARY KEY (agent_type, agent_id, seq)
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
    agent_id    UUID        NOT NULL REFERENCES nessy_agent_state (agent_id),
    agent_type  VARCHAR(64) NOT NULL,
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
    deadline    TIMESTAMPTZ NOT NULL,
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
    actionable_at TIMESTAMPTZ   NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ
);

-- Shaped for the one query that matters: due work of one agent type, oldest first.
CREATE INDEX IF NOT EXISTS ix_nessy_agent_effect_actionable
    ON nessy_agent_effect (agent_type, status, actionable_at);

-- What a model was shown, call by call: the whole request as rendered -- system prompt,
-- summaries, tail, ambient, tools, options -- written before the provider is asked. It cannot be
-- reconstructed afterwards: the head summary replaces itself, ambient changes every call, and
-- the system prompt is a template rendered per call. Stored whole rather than by reference to
-- the story so that a row means something on its own, wherever it is read.
CREATE TABLE IF NOT EXISTS nessy_inference_context
(
    context_id   UUID         PRIMARY KEY,
    agent_type   VARCHAR(64)  NOT NULL,
    agent_id     UUID         NOT NULL REFERENCES nessy_agent_state (agent_id),
    -- The open turn the call was made for: the last turn in the context.
    turn_id      BIGINT       NOT NULL,
    requested_at TIMESTAMPTZ  NOT NULL,
    model        VARCHAR(128) NOT NULL,
    payload      BYTEA        NOT NULL,
    -- How the call came back: answer, actions, refusal, fault -- or null while it is in flight or
    -- if the process died before it returned.
    outcome      VARCHAR(16),
    completed_at TIMESTAMPTZ,
    -- What the call cost, as the vendor counted it; null until it returns, or if nobody counted.
    input_tokens  BIGINT,
    output_tokens BIGINT
);

-- A table from before the cost was recorded gains the columns.
ALTER TABLE nessy_inference_context ADD COLUMN IF NOT EXISTS input_tokens BIGINT;
ALTER TABLE nessy_inference_context ADD COLUMN IF NOT EXISTS output_tokens BIGINT;

CREATE INDEX IF NOT EXISTS ix_nessy_inference_context_agent
    ON nessy_inference_context (agent_type, agent_id, requested_at);

-- Content, kept away from the record of what happened to it.
--
-- Everything a model was shown or said -- observations, answers, tool results -- lives here and
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
    written_at TIMESTAMPTZ NOT NULL DEFAULT now(),
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
    written_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (agent_id, seq)
);

-- Only the turn starts, which is all the boundary lookup reads. A handful of rows per agent
-- however long the conversation.
CREATE INDEX IF NOT EXISTS nessy_agent_event_turn_starts
    ON nessy_agent_event (agent_id, seq DESC) WHERE starts_turn;

-- An agent, so there is something to lock and something to point at.
--
-- Taken with SELECT ... FOR UPDATE, which holds for exactly the transaction and is released by the
-- database when a connection dies -- no time-to-live to tune, and none of the trouble a lease has
-- telling a slow holder from a dead one. It has to be a row that always exists: locking the
-- backlog rows instead would leave two arrivals to an empty backlog with nothing to contend for.
CREATE TABLE IF NOT EXISTS nessy_agent
(
    agent_type VARCHAR(64) NOT NULL,
    agent_id   UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (agent_type, agent_id)
);

-- Work offered to an agent that is busy, waiting its turn.
--
-- One row per item, which is what lets a coalescer say "append" or "keep only this" in a statement
-- rather than rewriting a list. A single row holding a serialized backlog would make every
-- strategy a read-modify-write of the whole thing.
--
-- The observation itself, NOT a claim check. This is the one place content sits in a control-plane
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
    arrived_at TIMESTAMPTZ NOT NULL,
    payload    BYTEA       NOT NULL,
    PRIMARY KEY (agent_type, agent_id, ordinal)
);
