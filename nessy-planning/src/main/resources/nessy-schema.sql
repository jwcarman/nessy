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

-- The task list an agent holds across turns.
--
-- One row per task rather than a blob, so the order the model sent is the order the column says,
-- and so a plan can be read in a database without a JVM. The WHOLESALE replacement the tool
-- performs is still wholesale: saving deletes this agent's rows and inserts the new list.
--
-- ANSI spellings only, and no reserved words as identifiers — SchemasTest holds this to both.
CREATE TABLE IF NOT EXISTS nessy_plan_task (
  agent_type TEXT   NOT NULL,
  agent_id   TEXT   NOT NULL,
  ordinal    BIGINT NOT NULL,
  title      TEXT   NOT NULL,
  status     TEXT   NOT NULL,
  PRIMARY KEY (agent_type, agent_id, ordinal)
);
