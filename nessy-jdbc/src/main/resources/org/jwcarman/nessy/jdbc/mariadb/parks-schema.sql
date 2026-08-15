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

-- MariaDB — see schema.sql's header for the verified-version and type-mapping notes. `call` is
-- reserved the same way it is on MySQL (protocol-compatible grammar) -- see
-- mysql/parks-schema.sql's note and JdbcStatements#parkedCallColumn.

CREATE TABLE IF NOT EXISTS nessy_parks (
  token           varchar(255) PRIMARY KEY,
  conversation_id varchar(255) NOT NULL,
  `call`          json NOT NULL,
  agent_name      varchar(255) NOT NULL
);

CREATE INDEX IF NOT EXISTS nessy_parks_conversation ON nessy_parks (conversation_id);
