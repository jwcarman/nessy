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

-- Oracle — see schema.sql's header. No separate index: the composite primary key already orders
-- and indexes by (conversation_id, version).

CREATE TABLE IF NOT EXISTS nessy_transcript (
  conversation_id varchar2(255) NOT NULL,
  version         number(19) NOT NULL,
  message         clob NOT NULL,
  PRIMARY KEY (conversation_id, version)
);
