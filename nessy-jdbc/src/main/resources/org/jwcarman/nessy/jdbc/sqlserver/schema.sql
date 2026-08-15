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

-- SQL Server, verified against the mcr.microsoft.com/mssql/server:2022-latest image (the vendor TCK
-- matrix in the test tree pins the same image tag). SQL Server has no CREATE TABLE/INDEX IF
-- NOT EXISTS at all, so every statement guards itself against sys.tables/sys.indexes instead —
-- confirmed live, idempotent on a second run, and confirmed to run as one JDBC Statement.execute()
-- call with no GO batch separator needed (GO is a sqlcmd/SSMS client convention, not real T-SQL,
-- and this module's bootstrap loop sends whole statements straight to the driver). Note the
-- absence of any semicolon anywhere below, including in this comment block itself: this module's
-- bootstrap loop naively splits schema resources on the semicolon character to run each statement
-- in turn, with no awareness of comments, so a semicolon anywhere in this file -- SQL or prose --
-- would shear a fragment in half. `jsonb`/`text` both become `nvarchar(max)` — SQL Server 2022 has
-- no native JSON type (that lands in a later release), so the json cast placeholder JdbcStatements
-- falls back to off Postgres -- the bare parameter marker -- is exactly right here too.

IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'nessy_conversation')
BEGIN
  CREATE TABLE nessy_conversation (
    id       nvarchar(255) PRIMARY KEY,
    version  bigint NOT NULL,
    state    nvarchar(max) NOT NULL
  )
END

IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'nessy_inbox')
BEGIN
  CREATE TABLE nessy_inbox (
    entry_id        nvarchar(255) PRIMARY KEY,
    conversation_id nvarchar(255) NOT NULL,
    kind            nvarchar(32) NOT NULL,
    payload         nvarchar(max) NOT NULL
  )
END

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'nessy_inbox_conversation')
BEGIN
  CREATE INDEX nessy_inbox_conversation ON nessy_inbox (conversation_id, entry_id)
END
