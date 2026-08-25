CREATE TABLE IF NOT EXISTS nessy_document (
  kind        TEXT             NOT NULL,
  key         TEXT COLLATE "C" NOT NULL,
  payload     BYTEA            NOT NULL,
  version     BIGINT           NOT NULL,
  updated_at  TIMESTAMPTZ      NOT NULL,
  PRIMARY KEY (kind, key)
);

CREATE TABLE IF NOT EXISTS nessy_journal (
  kind         TEXT             NOT NULL,
  key          TEXT COLLATE "C" NOT NULL,
  seq          BIGINT           NOT NULL,
  payload      BYTEA            NOT NULL,
  appended_at  TIMESTAMPTZ      NOT NULL,
  PRIMARY KEY (kind, key, seq)
);
