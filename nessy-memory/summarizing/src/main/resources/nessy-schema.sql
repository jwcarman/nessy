-- Summaries of the head of an agent's story, one row per range of turns, written once and never
-- rewritten: a later summary covers later turns, it does not re-summarise the earlier ones. That
-- is what keeps a long conversation from decaying -- nothing is compressed twice.
CREATE TABLE IF NOT EXISTS nessy_summary (
  agent_type   TEXT        NOT NULL,
  agent_id     TEXT        NOT NULL,
  from_turn    BIGINT      NOT NULL,
  through_turn BIGINT      NOT NULL,
  content      TEXT        NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (agent_type, agent_id, from_turn)
);
