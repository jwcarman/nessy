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
