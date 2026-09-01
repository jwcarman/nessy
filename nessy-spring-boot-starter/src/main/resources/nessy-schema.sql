-- What the starter's approvals projection keeps: one row per call that was put to a person.
--
-- It lives here rather than in the engine because nothing in the engine reads it. The engine parks
-- a call and arms a reminder; turning that into a page somebody can click is the starter's job, and
-- this is the starter's table.
--
-- Shipped as nessy-schema.sql at this jar's root, gathered by Schemas alongside every other
-- module's. Boot looks for schema.sql, so ours never runs uninvited — the name IS the opt-in.
--
-- Two portability rules, both measured: ANSI spellings only (TIMESTAMPTZ is a PostgreSQL alias H2
-- rejects), and no reserved words as identifiers.
CREATE TABLE IF NOT EXISTS nessy_pending_approvals (
  call_id     TEXT                     NOT NULL,
  agent_type  TEXT                     NOT NULL,
  agent_id    TEXT                     NOT NULL,
  tool        TEXT                     NOT NULL,
  action      TEXT                     NOT NULL,
  asked_at    TIMESTAMP WITH TIME ZONE NOT NULL,
  expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
  reply_token TEXT                     NOT NULL,
  answer      TEXT,
  note        TEXT,
  answered_at TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (call_id)
);

-- The page asks for what is still waiting, oldest first, and that is the only query it makes often.
CREATE INDEX IF NOT EXISTS nessy_pending_approvals_waiting
  ON nessy_pending_approvals (asked_at);
