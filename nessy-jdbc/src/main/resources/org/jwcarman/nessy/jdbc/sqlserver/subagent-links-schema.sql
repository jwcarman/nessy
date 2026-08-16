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

-- SQL Server — see schema.sql's header. No semicolons below, same reason.

-- A single nvarchar(255) key column is a 510-byte key, comfortably under the 900-byte clustered
-- cap, so the default CLUSTERED primary key applies here -- unlike the notebook's composite
-- (subject_id, name) key, this table has no need for NONCLUSTERED.
IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'nessy_subagent_links')
BEGIN
  CREATE TABLE nessy_subagent_links (
    child_conversation_id nvarchar(255) PRIMARY KEY,
    parent_token          nvarchar(255) NOT NULL
  )
END
