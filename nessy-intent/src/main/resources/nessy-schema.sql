-- What an agent has declared it is trying to do. One row per agent: a declaration replaces the
-- previous one rather than accumulating.
--
-- Keyed on the TYPE and the id, never the id alone. An agent id is unique within its type and no
-- further — a watchman and a chat agent can each be "agent-a" — so a row keyed on the id alone
-- would let one agent read, and overwrite, the other's declared intent.
--
-- ANSI spellings only, and no reserved words as identifiers — SchemasTest holds this to both.
CREATE TABLE IF NOT EXISTS nessy_intent (
  agent_type  TEXT   NOT NULL,
  agent_id    TEXT   NOT NULL,
  declaration TEXT   NOT NULL,
  -- Bumped on every write. The store retries on a losing write rather than clobbering, so two
  -- callers declaring at once settle one after the other instead of one silently disappearing.
  version     BIGINT NOT NULL,
  PRIMARY KEY (agent_type, agent_id)
);
