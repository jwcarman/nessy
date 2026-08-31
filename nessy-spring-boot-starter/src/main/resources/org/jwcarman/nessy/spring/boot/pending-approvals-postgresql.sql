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

-- The pending-approvals projection.
--
-- Applied by the APPLICATION — Flyway, Liquibase, or by hand — exactly like
-- nessy-substrate-jdbc's nessy-postgresql.sql. The starter ships the file and
-- reads the table; it never creates it, because schema is a deployment decision
-- and a library that silently runs DDL is a library that surprises somebody at
-- 3am.
--
-- This table is a PROJECTION, not a source of truth, and it is rebuilt from the
-- agent event stream: a recovered turn re-runs the calls it never settled, which
-- asks its approver again and narrates the question again. Losing this table
-- loses nothing that will not come back as the agents recover.
--
-- reply_token is stored deliberately. It is how a page answers a call minutes or
-- days after the process that asked it has forgotten, and it is sealed with the
-- application's own key. Treat this table as holding credentials: an attacker who
-- can read it can approve anything still waiting.

CREATE TABLE IF NOT EXISTS nessy_pending_approvals (
    call_id      TEXT PRIMARY KEY,
    agent_type   TEXT        NOT NULL,
    agent_id     TEXT        NOT NULL,
    tool         TEXT        NOT NULL,
    action       TEXT        NOT NULL,
    asked_at     TIMESTAMPTZ NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    reply_token  TEXT        NOT NULL,
    answer       TEXT,
    note         TEXT,
    answered_at  TIMESTAMPTZ
);

-- What the page asks for on every load: the unanswered rows, oldest question first.
CREATE INDEX IF NOT EXISTS nessy_pending_approvals_waiting
    ON nessy_pending_approvals (asked_at)
    WHERE answer IS NULL;

-- What a "recently decided" view asks for: newest answer first.
CREATE INDEX IF NOT EXISTS nessy_pending_approvals_answered
    ON nessy_pending_approvals (answered_at DESC)
    WHERE answer IS NOT NULL;
