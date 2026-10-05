-- The board: one row per call that was put to a person.
--
-- This is the WATCHMAN'S table, not the engine's. The engine parks a call and holds it open until
-- its deadline; turning that into a page somebody can click is this application's job. It is a
-- PROJECTION, and it rebuilds itself: a recovered turn asks its approver again, and the approver
-- writes the row again. Losing it loses nothing that will not come back as the agent recovers.
--
-- The agent type, the agent id and the idempotency key are stored deliberately: they are how a page
-- answers a call days after the process that asked has forgotten.
--
-- Keyed on the call's idempotency key, which Nessy makes once per call and hands to every ask of
-- it. Not on the call id: a model's call id is unique within one of its replies and no further, so
-- one agent can be asked about a second "call_1" after the first was answered, and two agents can
-- each be waiting on one. The call id is kept to show, not to find a row by.
CREATE TABLE IF NOT EXISTS watchman_pending_approval (
  idempotency_key TEXT    PRIMARY KEY,
  agent_type  TEXT        NOT NULL,
  agent_id    TEXT        NOT NULL,
  call_id     TEXT        NOT NULL,
  tool        TEXT        NOT NULL,
  action      TEXT        NOT NULL,
  asked_at    TIMESTAMPTZ NOT NULL,
  expires_at  TIMESTAMPTZ NOT NULL,
  answer      TEXT,
  note        TEXT,
  answered_at TIMESTAMPTZ
);

-- The page asks for what is still waiting, oldest first, and that is the only query it makes often.
CREATE INDEX IF NOT EXISTS watchman_pending_approval_waiting
  ON watchman_pending_approval (asked_at);
