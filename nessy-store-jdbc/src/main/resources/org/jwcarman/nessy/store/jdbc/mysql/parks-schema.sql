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

-- MySQL — see schema.sql's header for the verified-version and type-mapping notes this file
-- follows too (varchar(255) keys, native json, the guarded CREATE INDEX dance MySQL has no
-- IF NOT EXISTS form for). One more MySQL-only wrinkle, found live rather than anticipated by the
-- design: `call` is a reserved word in MySQL's grammar (unlike Postgres, SQL Server, and Oracle,
-- all confirmed to accept it bare as a column name), so it needs backtick-quoting here and
-- wherever JdbcParks references it -- see JdbcStatements#parkedCallColumn.

CREATE TABLE IF NOT EXISTS nessy_parks (
  token           varchar(255) PRIMARY KEY,
  conversation_id varchar(255) NOT NULL,
  `call`          json NOT NULL,
  agent_name      varchar(255) NOT NULL
);

SET @nessy_parks_conversation_exists = (
  SELECT COUNT(1) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'nessy_parks'
    AND index_name = 'nessy_parks_conversation'
);
SET @nessy_parks_conversation_ddl = IF(
  @nessy_parks_conversation_exists = 0,
  'CREATE INDEX nessy_parks_conversation ON nessy_parks (conversation_id)',
  'DO 0'
);
PREPARE nessy_parks_conversation_stmt FROM @nessy_parks_conversation_ddl;
EXECUTE nessy_parks_conversation_stmt;
DEALLOCATE PREPARE nessy_parks_conversation_stmt;
