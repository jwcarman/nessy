-- The engine's own bookkeeping. Not application data: nothing here outlives the turn that wrote it,
-- and nothing outside the engine reads it.
--
-- Two portability rules, enforced by SchemasTest running this against H2 rather than by anyone
-- remembering them: ANSI spellings only (TIMESTAMPTZ is a PostgreSQL alias H2 rejects), and no
-- reserved words as identifiers ("key" is reserved in H2 and merely unreserved in PostgreSQL).

-- What a turn must keep for its own duration and no longer: the message the model asked with, and
-- what each tool answered. Content-sized, so it cannot live on the turn's own document without
-- making that document grow with whatever a tool decided to hand back.
CREATE TABLE IF NOT EXISTS nessy_claim (
  agent_id   TEXT   NOT NULL,
  turn_id    TEXT   NOT NULL,
  claim_key  TEXT   NOT NULL,
  payload    BYTEA  NOT NULL,
  PRIMARY KEY (agent_id, turn_id, claim_key)
);

-- A deadline that outlives the actor which set it.
--
-- An in-memory timer dies with its actor, which is why an approval parked on a person for three
-- days used to require the agent to stay resident for three days. A row does not.
-- Columns rather than a composed key and an opaque payload, which is what this was.
--
-- It held the agent and the call TWICE: concatenated into a primary key so a settled call could
-- cancel its own alarm, and again as JSON so the sweep knew who to tell. That is a key-value
-- store's shape, and it brought a key-value store's hazard — an agent id containing the separator
-- collides with a different call, and the collision lands in a PRIMARY KEY.
CREATE TABLE IF NOT EXISTS nessy_reminder (
  agent_type TEXT                     NOT NULL,
  agent_id   TEXT                     NOT NULL,
  call_id    TEXT                     NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (agent_type, agent_id, call_id)
);

-- The sweep reads from the front of this index and stops at the first row not yet due, so its cost
-- is the number of EXPIRED reminders rather than the number outstanding.
CREATE INDEX IF NOT EXISTS nessy_reminder_expires_at ON nessy_reminder (expires_at);

-- What is waiting to become a turn.
--
-- Out of the agent's document on purpose: a document holding a queue is rewritten every time
-- anything changes, and its size is whatever the application decided an observation is.
--
-- item_id is the TURN id. One observation is exactly one turn, so minting a second id would only
-- create something that can disagree with the first — and it is what makes a take idempotent
-- across a crash, since a retry finds the id already minted rather than inventing another.
--
-- taken_claim is NULL until the row is handed to an agent. A row that has one has been rendered
-- and held, and the next take either sweeps it (because the agent named it) or hands it back
-- unchanged (because the agent died before recording it). Those two histories are
-- indistinguishable from the agent's phase alone, which is why the sweep names an id rather than
-- inferring one.
-- ordinal is the coalescer's ORDER, not arrival order. The coalescer returns the list the backlog
-- becomes and may drop, merge or reorder it, so what comes next is its answer and not a timestamp
-- comparison the engine invented. received_at is data the coalescer reads, never a sort key.
CREATE TABLE IF NOT EXISTS nessy_backlog (
  agent_id    TEXT                     NOT NULL,
  item_id     TEXT                     NOT NULL,
  ordinal     INTEGER                  NOT NULL,
  received_at TIMESTAMP WITH TIME ZONE NOT NULL,
  observation BYTEA                    NOT NULL,
  taken_claim TEXT,
  PRIMARY KEY (agent_id, item_id)
);

CREATE INDEX IF NOT EXISTS nessy_backlog_waiting ON nessy_backlog (agent_id, ordinal);

-- A forget, waiting to be taken.
--
-- Telling an agent to forget itself used to be a message straight to the actor, which meant it was
-- ordered against nothing: instruction batches are one task each on the blocking executor, and an
-- agent calls itself idle the moment a turn's decision is returned rather than when that decision's
-- writes have landed. So a delete could overtake the answer it was supposed to follow.
--
-- A row cannot overtake anything. The agent finds out it is doomed by TAKING it, and a reply to a
-- take cannot arrive before the batch that asked for it has finished.
--
-- Keyed like nessy_backlog, which it is checked alongside. NOTE that nessy_backlog and nessy_claim
-- are keyed on agent_id alone with no agent_type, unlike nessy_reminder -- so two agent types
-- sharing a database share their backlogs. Giving this table a type column alone would imply an
-- isolation the table it guards does not provide.
CREATE TABLE IF NOT EXISTS nessy_poison (
  agent_id    TEXT                     NOT NULL,
  offered_at  TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (agent_id)
);

-- One agent's whole durable self: a phase, a turn id, and two short strings.
--
-- Keyed on (agent_type, agent_id) rather than agent_id alone. nessy_backlog and nessy_claim are
-- keyed on the id by itself, so two agent types sharing a database share their backlogs; this
-- table does not repeat that, because a state document is the one thing that must never be
-- confused between two kinds of agent.
--
-- last_touched_at is not diagnostics. It is what lets a reaper find an agent that is mid-turn and
-- has not moved in minutes -- a stall detector needing no heartbeat and no leader election.
CREATE TABLE IF NOT EXISTS nessy_agent (
  agent_type      TEXT                     NOT NULL,
  agent_id        TEXT                     NOT NULL,
  version         BIGINT                   NOT NULL,
  state           BYTEA                    NOT NULL,
  last_touched_at TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (agent_type, agent_id)
);

CREATE INDEX IF NOT EXISTS nessy_agent_touched ON nessy_agent (agent_type, last_touched_at);

-- Work the agent decided on, committed with the decision that caused it.
--
-- An effect is an OBLIGATION, never proof that the work happened. It is inserted in the same
-- transaction as the state it came from, so it cannot exist without its cause and cannot be lost
-- after it -- which is the whole reason a crash between "decided to call the model" and "called
-- the model" is now recoverable rather than silent.
--
-- ordinal is load bearing, not cosmetic. A decision's instructions are ORDERED: endTurn remembers
-- before it releases, because releasing drops the claims the exchange is written from. Executing
-- them out of order writes an empty exchange.
--
-- actionable_at is ONE column with THREE meanings, decided by status (design of record
-- 2026-09-04, Task 7): PENDING -- when this may next be attempted, first try or a backoff;
-- RUNNING -- the deadline for the CURRENT attempt, and passing it is a timeout failure, not a
-- special case -- it goes through the same retry policy as any other failure; RUNNING far in the
-- future -- a deferral, parked on a person and correctly untouched until the term lapses. There is
-- no separate reaper: the same poller query that finds newly-actionable PENDING rows also finds
-- RUNNING rows whose deadline has quietly passed, because both are just "actionable_at <= now()".
-- Nullable: FAILED is a fourth status, retired rather than actionable at all, and its row's
-- actionable_at is cleared to NULL -- "never due again" rather than a sentinel far-future value a
-- reader might mistake for a very long deferral.
--
-- turn_id is nullable: an effect can be inserted before the agent has ever started a turn (the
-- first TakeWork of an agent's life), and a null column means exactly that -- no turn yet -- not
-- an empty string standing in for one.
--
-- call_id is nullable: only AskApprover and RunTool name one, and it exists so a settled call can
-- discharge its OWN effect row directly. AgentLogic.settle always emits CancelAlarm(callId)
-- whichever way a call ends, so Transition, processing CancelAlarm, deletes this call's effect row
-- in the same transaction it cancels the reminder in. Without this, a tool that defers for days
-- and then answers leaves its row RUNNING with an actionable_at now in the past, and the next
-- poller re-runs a tool whose call was already settled.
--
-- attempts counts FAILURES, not starts -- how many times this obligation has already failed, not
-- how many times it has been picked up. Picking a row up (the poller's SELECT+UPDATE) never
-- touches it; only the failure write-back does (EffectStore#retry, EffectStore#complete's sibling
-- EffectStore#abandon), which is also where RetryPolicy is consulted. A bare column name gives no
-- hint of direction, and the natural wrong guess is "attempts so far including the one in
-- flight" -- it is not that. A first-ever attempt reads 0.
--
-- reason is separate from payload. A failed effect is retired, not discharged, and the payload is
-- still the obligation it never got to keep -- overwriting it with why it stopped would leave the
-- one row that could tell an operator what the agent was trying to do saying only why it gave up.
-- reason itself stays TEXT: it is a human-readable message, not codec output.
--
-- payload is BYTEA, not TEXT, matching nessy_claim.payload and nessy_backlog.observation. It is
-- codec-encoded content: CodecPipeline frames its output with a binary magic header, and a
-- composed codec (compression, encryption) emits bytes a TEXT column would corrupt.
--
-- observability is TEXT, deliberately unlike payload -- and the two are TEXT/BYTEA for opposite
-- reasons, not the same reason as reason. payload is BYTEA because a CODEC OWNS IT: application
-- content, possibly compressed or encrypted, not guaranteed to be UTF-8, and never meant to be
-- read by a person. observability is TEXT because it is a W3C propagation CARRIER -- traceparent,
-- tracestate, and any intentionally propagated baggage, serialized as JSON by the caller and
-- stored verbatim: ASCII by specification, carrying no application content (no tool arguments, no
-- observations, no credentials), and it already travels in the clear in HTTP headers by design.
-- Its entire purpose is operational correlation -- an operator looking at a stuck effect row must
-- be able to read the trace id out and paste it into a trace viewer. Encoding it would defeat the
-- only reason to store it. It is nullable: an effect created outside any trace has no context to
-- carry.
CREATE TABLE IF NOT EXISTS nessy_effect (
  effect_id      TEXT                     NOT NULL,
  agent_type     TEXT                     NOT NULL,
  agent_id       TEXT                     NOT NULL,
  turn_id        TEXT,
  call_id        TEXT,
  ordinal        INTEGER                  NOT NULL,
  payload        BYTEA                    NOT NULL,
  observability  TEXT,
  status         TEXT                     NOT NULL,
  attempts       INTEGER                  NOT NULL,
  reason         TEXT,
  actionable_at  TIMESTAMP WITH TIME ZONE,
  created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (effect_id)
);

-- The poller reads the front of this and stops at the first row not yet actionable, so its cost is
-- the number of due effects rather than the number outstanding. agent_type leads because the
-- poller always filters by it -- without it here, one type's pass scans every other type's rows
-- too. This ONE index now serves both what nessy_effect_pending and nessy_effect_expires used to
-- serve separately: attempt() no longer distinguishes PENDING-due from RUNNING-expired, so there
-- is no longer a second query shape to index for.
CREATE INDEX IF NOT EXISTS nessy_effect_actionable
  ON nessy_effect (agent_type, actionable_at);

-- CancelAlarm discharges a call's effect row directly, by (agent_type, agent_id, turn_id,
-- call_id) -- see EffectStore#deleteForCall.
CREATE INDEX IF NOT EXISTS nessy_effect_call
  ON nessy_effect (agent_type, agent_id, turn_id, call_id);
