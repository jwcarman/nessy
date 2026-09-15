-- Leases: "only one of us should do this right now."
--
-- A row per (kind, key) that somebody is working on. Taking one is an insert, or an update of a
-- row whose holder let it expire; the holder deletes it when the work is done, and a holder that
-- died leaves a row the next caller takes over once expires_at has passed. Nothing waits and
-- nothing queues: a caller that finds the lease held gives up, which is what makes this fit
-- opportunistic work rather than work somebody is owed.
--
-- Time is the database's, so holders on different machines with different clocks agree on when
-- a lease has expired.
CREATE TABLE IF NOT EXISTS nessy_lease
(
    kind       VARCHAR(64)  NOT NULL,
    key        VARCHAR(255) NOT NULL,
    holder     UUID         NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (kind, key)
);
