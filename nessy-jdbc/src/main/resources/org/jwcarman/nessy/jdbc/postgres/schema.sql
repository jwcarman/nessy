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

-- Every statement is idempotent (IF NOT EXISTS) so JdbcConversationStore.create can run this
-- file against a database it has bootstrapped before without failing.

CREATE TABLE IF NOT EXISTS nessy_conversation (
  id       text PRIMARY KEY,
  version  bigint NOT NULL,
  state    jsonb  NOT NULL
);

CREATE TABLE IF NOT EXISTS nessy_inbox (
  entry_id        text PRIMARY KEY,
  conversation_id text NOT NULL,
  kind            text NOT NULL,          -- 'told' | 'resolved'
  payload         jsonb NOT NULL
);

CREATE INDEX IF NOT EXISTS nessy_inbox_conversation ON nessy_inbox (conversation_id, entry_id);
