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

-- MariaDB, verified against the mariadb:11.4 image (the vendor TCK matrix in the test tree
-- pins the same image tag). Unlike MySQL, MariaDB accepts CREATE INDEX IF NOT EXISTS directly —
-- confirmed live, idempotent on a second run — so this file needs none of mysql/schema.sql's
-- prepared-statement guard. `jsonb` becomes `json`, which MariaDB's own manual documents as a
-- plain alias for `longtext` carrying an automatic `CHECK (json_valid(...))` constraint (confirmed
-- live: `SHOW CREATE TABLE` on a `json` column echoes back `longtext ... CHECK (json_valid(...))`
-- verbatim) — real json-shaped validation, not Postgres's binary jsonb, but real validation all
-- the same. Ids/tokens are bounded the same way as mysql/schema.sql, for the same reason.

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

CREATE INDEX IF NOT EXISTS nessy_inbox_conversation ON nessy_inbox (conversation_id, entry_id);
