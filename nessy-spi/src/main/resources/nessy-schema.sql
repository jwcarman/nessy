-- The record of what happened: the transcript a Memory reads back.
--
-- The ONLY permanent data Nessy stores. Claims are per-turn, reminders are transient, notes and
-- plans are the agent's own; this is the conversation, and nothing rebuilds it.
--
-- ANSI spellings only, and no reserved words as identifiers — SchemasTest holds this to both.
CREATE TABLE IF NOT EXISTS nessy_transcript (
  agent_type TEXT   NOT NULL,
  agent_id   TEXT   NOT NULL,
  seq        BIGINT NOT NULL,
  payload    TEXT   NOT NULL,
  -- What this message costs a context window, counted once at write time. A bounded recall reads
  -- backwards until the budget is spent, so the cost of a recall is the history it KEEPS rather
  -- than the history that exists.
  chars      BIGINT NOT NULL,
  PRIMARY KEY (agent_type, agent_id, seq)
);
