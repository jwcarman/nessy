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
CREATE TABLE IF NOT EXISTS nessy_reminder (
  reminder_key TEXT                     NOT NULL,
  expires_at   TIMESTAMP WITH TIME ZONE NOT NULL,
  payload      BYTEA                    NOT NULL,
  PRIMARY KEY (reminder_key)
);

-- The sweep reads from the front of this index and stops at the first row not yet due, so its cost
-- is the number of EXPIRED reminders rather than the number outstanding.
CREATE INDEX IF NOT EXISTS nessy_reminder_expires_at ON nessy_reminder (expires_at);
