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

-- What an agent has been told worth keeping.
--
-- Unlike a claim, this is USER DATA with no other copy: nothing rebuilds a note, so nothing here
-- may be treated as scratch or given a retention policy.
--
-- ANSI spellings only, and no reserved words as identifiers — SchemasTest runs this against H2, so
-- a vendor alias or a reserved column name fails there rather than on someone's deployment.
CREATE TABLE IF NOT EXISTS nessy_note (
  agent_type TEXT   NOT NULL,
  agent_id   TEXT   NOT NULL,
  note_id    TEXT   NOT NULL,
  hook       TEXT   NOT NULL,
  body       TEXT   NOT NULL,
  -- Insertion order, which the index the model reads depends on. A note is written once and revised
  -- in place, so its position never changes and a revision cannot reshuffle the list.
  ordinal    BIGINT NOT NULL,
  PRIMARY KEY (agent_type, agent_id, note_id)
);

CREATE INDEX IF NOT EXISTS nessy_note_ordinal ON nessy_note (agent_type, agent_id, ordinal);
