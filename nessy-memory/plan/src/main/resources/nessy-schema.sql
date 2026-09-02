-- The task list an agent holds across turns.
--
-- One row per task rather than a blob, so the order the model sent is the order the column says,
-- and so a plan can be read in a database without a JVM. The WHOLESALE replacement the tool
-- performs is still wholesale: saving deletes this agent's rows and inserts the new list.
--
-- ANSI spellings only, and no reserved words as identifiers — SchemasTest holds this to both.
CREATE TABLE IF NOT EXISTS nessy_plan_task (
  agent_type TEXT   NOT NULL,
  agent_id   TEXT   NOT NULL,
  ordinal    BIGINT NOT NULL,
  title      TEXT   NOT NULL,
  status     TEXT   NOT NULL,
  PRIMARY KEY (agent_type, agent_id, ordinal)
);
