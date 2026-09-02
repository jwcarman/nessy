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

-- Everything the watchman needs a database to already have.
--
-- Postgres runs every .sql in this directory once, on FIRST start of an empty data volume. That is
-- the only moment either of these can happen: the application creates its own tables, but it cannot
-- create the SCHEMA it is configured to put them in, and it does not own Pekko's tables at all.
--
-- Found the hard way. Against a clean volume the app logs "Started WatchmanApplication" and then
-- dies on its first round with
--
--   Exception during recovery. PersistenceId [watchman|watchman].
--   ERROR: relation "durable_state" does not exist
--
-- because Pekko reads its state lazily, so a missing table is a RUNTIME failure rather than a
-- startup one. Every earlier run only worked because these tables happened to exist already.

-- 1. The schema. application.yml asks for ?currentSchema=watchman, and CREATE TABLE IF NOT EXISTS
--    cannot conjure a schema to be created in.
CREATE SCHEMA IF NOT EXISTS watchman AUTHORIZATION watchman;

-- 2. Pekko's own tables, which it does NOT create for itself.
--
--    Copied from schema/postgres/postgres-create-schema.sql inside
--    pekko-persistence-jdbc_2.13-pekko-persistence-jdbc_3-1.3.0.jar, with two changes: public. becomes watchman. (Pekko's
--    copy hardcodes public, and this application does not live there), and CONCURRENTLY is dropped
--    from the index builds (it cannot run inside a transaction, and there is nothing to build
--    concurrently with on an empty table).
--
--    Vendored rather than referenced because a container cannot read a jar. If the Pekko version
--    in the root pom moves, diff this against the new jar's copy.
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

CREATE UNIQUE INDEX event_journal_ordering_idx ON watchman.event_journal(ordering);

CREATE TABLE IF NOT EXISTS watchman.event_tag(
    event_id BIGINT,
    tag VARCHAR(256),
    PRIMARY KEY(event_id, tag),
    CONSTRAINT fk_event_journal
      FOREIGN KEY(event_id)
      REFERENCES event_journal(ordering)
      ON DELETE CASCADE
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
CREATE INDEX state_tag_idx on watchman.durable_state (tag);
CREATE INDEX state_global_offset_idx on watchman.durable_state (global_offset);
