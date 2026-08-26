-- The pending-approvals projection (watchman spec §1.3).
--
-- Applied by the APPLICATION — Flyway, Liquibase, or by hand — exactly like
-- nessy-substrate-jdbc's nessy-postgresql.sql and continuum-jdbc's
-- continuum-postgresql.sql. The starter ships the file and reads the table; it
-- never creates it, because schema is a deployment decision and a library that
-- silently runs DDL is a library that surprises somebody at 3am.
--
-- This table is a PROJECTION, not a source of truth. It is at-least-once, it is
-- rebuilt from the harness fact stream, and losing it loses nothing: the phase
-- is the ledger. Approve and deny go through ApprovalDesk; a row changes when
-- the fold's fact arrives, not when a page posts a form.
--
-- Every column but computation_id is nullable on purpose. Facts for one scope
-- are NOT guaranteed to arrive in commit order (see HarnessObserver's contract),
-- so an answer can land before the park it answers. Both directions upsert, and
-- neither ever overwrites the other's columns.

CREATE TABLE IF NOT EXISTS nessy_pending_approvals (
    computation_id TEXT PRIMARY KEY,
    agent_type     TEXT,
    agent_id       TEXT,
    call_id        TEXT,
    action         TEXT,
    request_json   JSONB,
    parked_at      TIMESTAMPTZ,
    answer         TEXT,
    reference      TEXT,
    note           TEXT,
    answered_at    TIMESTAMPTZ
);

-- What the page asks for on every load: the unanswered rows, oldest park first.
CREATE INDEX IF NOT EXISTS nessy_pending_approvals_waiting
    ON nessy_pending_approvals (parked_at)
    WHERE answer IS NULL;

-- What /recent asks for: the answered rows, newest answer first.
CREATE INDEX IF NOT EXISTS nessy_pending_approvals_answered
    ON nessy_pending_approvals (answered_at DESC)
    WHERE answer IS NOT NULL;
