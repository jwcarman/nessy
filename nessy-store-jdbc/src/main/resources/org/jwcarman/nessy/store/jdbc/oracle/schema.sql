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

-- Oracle, verified against the gvenzl/oracle-free:23-slim-faststart image (reported version at
-- verification time: "Oracle AI Database 26ai Free Release 23.26.2.0.0" — see Task 2's report --
-- Task 3's container matrix pins the same tag). CREATE TABLE IF NOT EXISTS and CREATE INDEX IF NOT
-- EXISTS are both Oracle Database 23c+ features and both confirmed live here, idempotent on a
-- second run. The classic pre-23c idiom (an anonymous PL/SQL block catching ORA-00955) is not
-- needed against this image and was not implemented -- an older Oracle image would need it instead.
-- Ids/tokens are `varchar2(255)` (Oracle's own max for that type outside extended mode is 4000
-- bytes, so 255 is comfortable headroom for a UUID-shaped string). `jsonb`/`text` both become
-- `clob` — payload/state/message/summary are unbounded blobs of JSON or free text, and `clob` reads
-- back through `ResultSet.getString` exactly like every other dialect's text column, so no read-side
-- branch is needed (see JdbcStatements' javadoc and the Task 2 report for the getString check).

CREATE TABLE IF NOT EXISTS nessy_conversation (
  id       varchar2(255) PRIMARY KEY,
  version  number(19) NOT NULL,
  state    clob NOT NULL
);

CREATE TABLE IF NOT EXISTS nessy_inbox (
  entry_id        varchar2(255) PRIMARY KEY,
  conversation_id varchar2(255) NOT NULL,
  kind            varchar2(32) NOT NULL,
  payload         clob NOT NULL
);

CREATE INDEX IF NOT EXISTS nessy_inbox_conversation ON nessy_inbox (conversation_id, entry_id);
