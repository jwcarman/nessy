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

-- SQL Server — see schema.sql's header. No semicolons below, same reason.

-- NONCLUSTERED, not the CLUSTERED default: two nvarchar(255) key columns is a 1020-byte key, over the 900-byte clustered cap (CREATE only warns) but under the 1700-byte nonclustered one, so inserts with long combined subject_id+name would fail at runtime (msg 1946) under the default.
IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'nessy_notebook')
BEGIN
  CREATE TABLE nessy_notebook (
    subject_id nvarchar(255)  NOT NULL,
    name       nvarchar(255)  NOT NULL,
    hook       nvarchar(1024) NOT NULL,
    body       nvarchar(max)  NOT NULL,
    PRIMARY KEY NONCLUSTERED (subject_id, name)
  )
END
