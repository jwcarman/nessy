-- The DDL this application owns. pekko-persistence-jdbc creates nothing.
--
-- A schema of its own so the ported watchman and the original can run side by side against one
-- database without either seeing the other's tables:
--
--   docker exec -i watchman-postgres psql -U watchman -d watchman < schema.sql

CREATE SCHEMA IF NOT EXISTS watchman_pekko;

CREATE TABLE IF NOT EXISTS watchman_pekko.event_journal(
  ordering BIGSERIAL,
  persistence_id VARCHAR(255) NOT NULL,
  sequence_number BIGINT NOT NULL,
  deleted BOOLEAN DEFAULT FALSE NOT NULL,
  writer VARCHAR(255) NOT NULL,
  write_timestamp BIGINT,
  adapter_manifest VARCHAR(255),
  event_ser_id INTEGER NOT NULL,
  event_ser_manifest VARCHAR(255) NOT NULL,
  event_payload BYTEA NOT NULL,
  meta_ser_id INTEGER,
  meta_ser_manifest VARCHAR(255),
  meta_payload BYTEA,
  PRIMARY KEY(persistence_id, sequence_number)
);

CREATE UNIQUE INDEX IF NOT EXISTS event_journal_ordering_idx
  ON watchman_pekko.event_journal(ordering);

CREATE TABLE IF NOT EXISTS watchman_pekko.event_tag(
  event_id BIGINT,
  tag VARCHAR(256),
  PRIMARY KEY(event_id, tag),
  CONSTRAINT fk_event_journal
    FOREIGN KEY(event_id) REFERENCES watchman_pekko.event_journal(ordering) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS watchman_pekko.snapshot (
  persistence_id VARCHAR(255) NOT NULL,
  sequence_number BIGINT NOT NULL,
  created BIGINT NOT NULL,
  snapshot_ser_id INTEGER NOT NULL,
  snapshot_ser_manifest VARCHAR(255) NOT NULL,
  snapshot_payload BYTEA NOT NULL,
  meta_ser_id INTEGER,
  meta_ser_manifest VARCHAR(255),
  meta_payload BYTEA,
  PRIMARY KEY(persistence_id, sequence_number)
);

CREATE TABLE IF NOT EXISTS watchman_pekko.durable_state (
  global_offset BIGSERIAL,
  persistence_id VARCHAR(255) NOT NULL,
  revision BIGINT NOT NULL,
  state_payload BYTEA NOT NULL,
  state_serial_id INTEGER NOT NULL,
  state_serial_manifest VARCHAR(255),
  tag VARCHAR,
  state_timestamp BIGINT NOT NULL,
  PRIMARY KEY(persistence_id)
);

CREATE INDEX IF NOT EXISTS state_tag_idx ON watchman_pekko.durable_state (tag);
CREATE INDEX IF NOT EXISTS state_global_offset_idx ON watchman_pekko.durable_state (global_offset);
