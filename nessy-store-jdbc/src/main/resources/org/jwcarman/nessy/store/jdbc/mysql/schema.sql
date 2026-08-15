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

-- MySQL, verified against the mysql:8.0 image (see Task 2's report for the verification notes --
-- Task 3's container matrix pins the same tag). Every table statement is idempotent (CREATE TABLE
-- IF NOT EXISTS). MySQL has no CREATE INDEX IF NOT EXISTS at all (confirmed live -- it is a syntax
-- error, not a no-op), so the index below guards itself with a small prepared-statement dance
-- instead. Postgres's unbounded `text` primary/foreign key columns become bounded `varchar(255)` —
-- MySQL cannot index an unbounded text column without an explicit prefix length, and every id/token
-- this module mints is a short UUID-shaped string in practice, so 255 is generous headroom, not a
-- tight fit. `jsonb` becomes MySQL's native `json` type, which validates on write the same way
-- Postgres's does.

CREATE TABLE IF NOT EXISTS nessy_conversation (
  id       varchar(255) PRIMARY KEY,
  version  bigint NOT NULL,
  state    json NOT NULL
);

CREATE TABLE IF NOT EXISTS nessy_inbox (
  entry_id        varchar(255) PRIMARY KEY,
  conversation_id varchar(255) NOT NULL,
  kind            varchar(32) NOT NULL,          -- 'told' | 'resolved'
  payload         json NOT NULL
);

SET @nessy_inbox_conversation_exists = (
  SELECT COUNT(1) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'nessy_inbox'
    AND index_name = 'nessy_inbox_conversation'
);
SET @nessy_inbox_conversation_ddl = IF(
  @nessy_inbox_conversation_exists = 0,
  'CREATE INDEX nessy_inbox_conversation ON nessy_inbox (conversation_id, entry_id)',
  'DO 0'
);
PREPARE nessy_inbox_conversation_stmt FROM @nessy_inbox_conversation_ddl;
EXECUTE nessy_inbox_conversation_stmt;
DEALLOCATE PREPARE nessy_inbox_conversation_stmt;
