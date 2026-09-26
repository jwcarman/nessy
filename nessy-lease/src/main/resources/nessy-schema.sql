-- Leases: "only one of us should do this right now."
--
-- A row per (kind, agent type, agent id) that somebody is working on. Taking one is an insert, or
-- an update of a row whose holder let it expire; the holder deletes it when the work is done, and
-- a holder that died leaves a row the next caller takes over once expires_at has passed. Nothing
-- waits and nothing queues: a caller that finds the lease held gives up, which is what makes this
-- fit opportunistic work rather than work somebody is owed.
--
-- Time is the database's, so holders on different machines with different clocks agree on when
-- a lease has expired.
--
-- takeovers counts, for this row, how many times a holder that never released it has been taken
-- over; it is incremented only on the ON CONFLICT DO UPDATE path and resets naturally, because
-- release DELETEs the row -- a row exists only between a take and its release, and the count never
-- outlives the contention it counts.
CREATE TABLE IF NOT EXISTS nessy_lease
(
    kind       VARCHAR(64) NOT NULL,
    agent_type VARCHAR(64) NOT NULL,
    agent_id   UUID        NOT NULL,
    holder     UUID        NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    takeovers  INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (kind, agent_type, agent_id)
);
