-- The board: one row per call that was put to a person.
--
-- This is the WATCHMAN'S table, not the engine's. The engine parks a call and holds it open until
-- its deadline; turning that into a page somebody can click is this application's job. It is a
-- PROJECTION, and it rebuilds itself: a recovered turn asks its approver again, and the approver
-- writes the row again. Losing it loses nothing that will not come back as the agent recovers.
--
-- reply_token is stored deliberately, and it is a CREDENTIAL. It is how a page answers a call days
-- after the process that asked has forgotten, and it is sealed with the application's own key.
-- Anyone who can read this table can approve anything still waiting.
--
-- Keyed on the AGENT and the call, never the call alone. A model's call id is unique within one
-- response and no further, so two agents can each be waiting on a "call_1".
CREATE TABLE IF NOT EXISTS watchman_pending_approval (
  agent_type  TEXT        NOT NULL,
  agent_id    TEXT        NOT NULL,
  call_id     TEXT        NOT NULL,
  tool        TEXT        NOT NULL,
  action      TEXT        NOT NULL,
  asked_at    TIMESTAMPTZ NOT NULL,
  expires_at  TIMESTAMPTZ NOT NULL,
  reply_token TEXT        NOT NULL,
  answer      TEXT,
  note        TEXT,
  answered_at TIMESTAMPTZ,
  PRIMARY KEY (agent_type, agent_id, call_id)
);

-- The page asks for what is still waiting, oldest first, and that is the only query it makes often.
CREATE INDEX IF NOT EXISTS watchman_pending_approval_waiting
  ON watchman_pending_approval (asked_at);
