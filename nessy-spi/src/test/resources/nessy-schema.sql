-- A module's DDL, as every module ships it.
--
-- Two rules make one file serve every database, and both are enforced by SchemasTest rather than by
-- anyone remembering them:
--   1. ANSI spellings, never vendor aliases. TIMESTAMPTZ is a PostgreSQL alias H2 rejects;
--      TIMESTAMP WITH TIME ZONE is standard and both accept it.
--   2. No reserved words as identifiers. "key" is reserved in H2 and merely unreserved in
--      PostgreSQL, so a column named key passes there and fails here.
CREATE TABLE IF NOT EXISTS nessy_schema_probe (
  entry_key  TEXT                     NOT NULL,
  payload    BYTEA                    NOT NULL,
  seq        BIGINT                   NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (entry_key, seq)
);

CREATE INDEX IF NOT EXISTS nessy_schema_probe_created_at ON nessy_schema_probe (created_at);
