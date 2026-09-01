--
-- Copyright © 2026 James Carman
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--

-- The DDL this application owns. pekko-persistence-jdbc creates nothing.
--
-- A schema of its own so the ported watchman and the original can run side by side against one
-- database without either seeing the other's tables:
--
--   docker exec -i watchman-postgres psql -U watchman -d watchman < schema.sql

CREATE SCHEMA IF NOT EXISTS watchman;

CREATE TABLE IF NOT EXISTS watchman.event_journal(
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
  ON watchman.event_journal(ordering);

CREATE TABLE IF NOT EXISTS watchman.event_tag(
  event_id BIGINT,
  tag VARCHAR(256),
  PRIMARY KEY(event_id, tag),
  CONSTRAINT fk_event_journal
    FOREIGN KEY(event_id) REFERENCES watchman.event_journal(ordering) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS watchman.snapshot (
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

CREATE TABLE IF NOT EXISTS watchman.durable_state (
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

-- Nessy's own tables are NOT here. The engine ships them as nessy-schema.sql at the root of each
-- jar that needs one, and this application applies them itself at startup — the engine initializes
-- only a DataSource it created, and this one is ours. See WatchmanConfiguration#nessySchema.
--
-- They used to be copied in by hand (nessy_document and nessy_journal, from the substrate). Copying
-- DDL is how a schema drifts from the code that reads it: those two tables outlived the substrate
-- by a day and were still being created here after nothing read them.

CREATE INDEX IF NOT EXISTS state_tag_idx ON watchman.durable_state (tag);
CREATE INDEX IF NOT EXISTS state_global_offset_idx ON watchman.durable_state (global_offset);
