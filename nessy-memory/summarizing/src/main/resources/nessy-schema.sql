-- One summary per agent: the head of its story, folded forward. A later fold is shown this
-- summary and the turns that have since fallen out of the tail, and what it writes replaces this
-- row, covering the story from its first turn through a later one.
CREATE TABLE IF NOT EXISTS nessy_summary (
  agent_type   TEXT        NOT NULL,
  agent_id     TEXT        NOT NULL,
  from_turn    BIGINT      NOT NULL,
  through_turn BIGINT      NOT NULL,
  content      TEXT        NOT NULL,
  updated_at   TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (agent_type, agent_id)
);
