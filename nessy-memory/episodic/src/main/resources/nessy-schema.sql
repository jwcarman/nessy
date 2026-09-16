-- An agent's story cut into episodes: stretches of turns the model itself named as it went. The
-- open episode has no through_turn; a closed one is summarised in the background, and the summary
-- stands in for its turns from then on. The embedding is the summary's, by the model named beside
-- it, so a store handed a different embedder knows which rows it cannot compare.
CREATE TABLE IF NOT EXISTS nessy_episode (
  agent_type      TEXT        NOT NULL,
  agent_id        TEXT        NOT NULL,
  episode_no      INTEGER     NOT NULL,
  from_turn       BIGINT      NOT NULL,
  through_turn    BIGINT,
  title           TEXT        NOT NULL,
  reason          TEXT        NOT NULL,
  summary         TEXT,
  embedding       REAL[],
  embedding_model TEXT,
  opened_at       TIMESTAMPTZ NOT NULL,
  closed_at       TIMESTAMPTZ,
  PRIMARY KEY (agent_type, agent_id, episode_no)
);
