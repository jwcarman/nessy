-- What an agent's older history was compressed into. A SIDECAR: the transcript is never touched,
-- and this says only "for this agent, everything through sequence N is covered by this text".
--
-- ONE row per agent, and only once something has been summarized. A summary replaces the previous
-- one rather than accumulating, because what matters is the single paragraph carried into the next
-- model call; unbounded summary rows would reintroduce the growth this table exists to stop.
--
-- Keyed on the TYPE and the id, like every other table here: an id is unique within its type and
-- no further.
--
-- ANSI spellings only, and no reserved words as identifiers -- SchemasTest holds this to both.
CREATE TABLE IF NOT EXISTS nessy_summary (
  agent_type     TEXT   NOT NULL,
  agent_id       TEXT   NOT NULL,
  -- The transcript sequence this summary accounts for. A SEQUENCE, not a count: a count could
  -- only be applied by reading the whole transcript and discarding the covered part, which is
  -- wrong for a memory that returns a moving window and is exactly the cost summarizing exists to
  -- avoid. With a sequence the covered messages are never read at all.
  covers_through BIGINT NOT NULL,
  summary        TEXT   NOT NULL,
  updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (agent_type, agent_id)
);
