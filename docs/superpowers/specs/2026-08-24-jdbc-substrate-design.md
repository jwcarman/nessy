# A JDBC Substrate, and a contract battery to certify it

**Date:** 2026-08-24
**Status:** draft for review
**Depends on:** `2026-08-24-continuum-adoption-design.md` — its §11.1 durability
rule is the reason this exists, and its Task 6 removes the only callers of
`Substrate.keys` that would have made this hard.

Nessy ships one `Substrate`: `InMemorySubstrate`, documented as "not a durable
substrate." Everything Nessy stores — agent state, transcripts, memory, intent,
backlogs, the dispatch index — therefore dies with the process. This spec adds a
PostgreSQL-backed `Substrate` and a contract battery that certifies both
implementations against the same behaviour.

## 1. Why now

Continuum's half of durability already ships: `continuum-jdbc` is a
TCK-certified PostgreSQL provider for computations. Nessy's half does not
exist. Until it does, the adoption spec's §11.1 forbids wiring the durable
computation store at all, because a durable computation store against a
volatile substrate silently drops every delivery — the fold lands on a scope
restored to `Idle`, which ignores it.

So this is the piece that unblocks durability end to end. It is also what makes
the migration's central claims testable for the first time: every crash-window
argument in the adoption spec (the orphaned approval, create-then-index
ordering, an index entry outliving its computation) has been reasoned about
rather than observed, because both halves have run in memory.

## 2. Scope

**One new module.** `nessy-substrate-jdbc` — `JdbcSubstrate` over a
`DataSource`, PostgreSQL driver at `provided` scope, testcontainers at test
scope.

**The contract battery goes in `nessy-testing`**, beside the existing
`MemoryContractTest`. That module already depends on `nessy-spi`, JUnit and
AssertJ, and already holds a contract test — the pattern exists and does not
need a module of its own. `nessy-testing`'s own tests certify
`InMemorySubstrate`; `nessy-substrate-jdbc`'s tests certify `JdbcSubstrate`.

**PostgreSQL only.** No dialect abstraction, no `accent` integration yet.
Widening later is cheap because `Substrate`'s differences across databases are
genuine dialect differences — types, upsert syntax, identifier quoting, the
same query in different spellings — which is what `accent` is for. But the
end-to-end durability test is what validates this work, and every extra dialect
adds a schema variant and a container matrix ahead of it.

## 3. Schema — two tables, and they are yours

The mapping `docs/concepts/storage.md` already ratifies:

```sql
CREATE TABLE IF NOT EXISTS nessy_document (
  kind        TEXT        NOT NULL,
  key         TEXT        NOT NULL,
  payload     BYTEA       NOT NULL,
  version     BIGINT      NOT NULL,
  updated_at  TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (kind, key)
);

CREATE TABLE IF NOT EXISTS nessy_journal (
  kind         TEXT        NOT NULL,
  key          TEXT        NOT NULL,
  seq          BIGINT      NOT NULL,
  payload      BYTEA       NOT NULL,
  appended_at  TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (kind, key, seq)
);
```

**Nessy never creates or migrates schema.** The DDL ships as a classpath
resource at `org/jwcarman/nessy/substrate/jdbc/nessy-postgresql.sql`, to be
copied into the application's own migration tool. The integration tests execute
that shipped file verbatim, so the file a user copies is the file that is
proven. This mirrors `continuum-jdbc` exactly, and for the same reason: schema
ownership belongs to the application's existing discipline, not to a library.

No secondary indexes beyond the primary keys. Every access path is a primary-key
lookup or a prefix scan of one, which the PK's own index serves.

## 4. The seven methods

| Method | SQL |
|---|---|
| `read(kind, key)` | `SELECT payload, version, updated_at … WHERE kind = ? AND key = ?` |
| `write(…, expectedVersion = 0)` | `INSERT`; a primary-key violation **is** the conflict |
| `write(…, expectedVersion = v)` | `UPDATE … SET payload = ?, version = version + 1, updated_at = ? WHERE kind = ? AND key = ? AND version = ?`; **zero affected rows is the conflict** |
| `delete(kind, key, v)` | `DELETE … WHERE kind = ? AND key = ? AND version = ?`; zero affected rows does **not** by itself mean conflict — `Substrate#delete`'s own contract makes deleting a genuinely absent document at `expectedVersion == 0` an idempotent no-op success, so on zero affected rows check existence within the same transaction: absent **and** `expectedVersion == 0` is success; present-at-another-version (or absent at any other `expectedVersion`) is the conflict |
| `keys(kind, limit)` | `SELECT key … WHERE kind = ? ORDER BY key LIMIT ?` — ascending, per storage.md |
| `append(…, expectedSeq)` | `INSERT`; a primary-key violation on `(kind, key, seq)` is the conflict |
| `entries(kind, key, fromSeq)` | `SELECT … WHERE kind = ? AND key = ? AND seq >= ? ORDER BY seq` — inclusive, ascending |
| `batch(ops)` | one transaction; each op's CAS checked by affected rows; **any** miss rolls back every op and throws `ConflictException` |

**Optimistic only.** `storage.md` is explicit: *"The store is the lock: every
mutation carries a CAS expectation, and a miss is a conflict, never a wait.
There are no locks and no waits."* A `SELECT … FOR UPDATE` implementation would
satisfy the method signatures and violate the contract — a caller that loses a
race must get a `ConflictException` to retry against, not block. This is a
correctness requirement, not a performance preference.

**Timestamps come from a JVM `Clock`, not SQL `now()`.** `InMemorySubstrate`
takes a `Clock` and stamps `updatedAt`/`appendedAt` in the JVM;
`JdbcSubstrate` does the same so the two agree and the battery can control
time. `updated_at` is informational — `version` is the CAS token and `seq` is
the journal's order — so a single database time authority buys nothing, and a
divergence between the two implementations would surface only as a confusing
contract-test failure.

**Connections are owned**, acquired from the `DataSource` per unit of work and
closed after, exactly as `JdbcContinuumRepository` does. No ambient-transaction
participation and no seam for one. The adoption spec §4 settled that no
cross-store transaction is needed: the reducer's tool-call-id dedup makes
at-least-once delivery safe, so nothing requires a Nessy write and a Continuum
write to commit together.

## 5. `Substrate.keys` is no longer a hot path

Worth recording, because it removes the objection that `Substrate`'s shape is
too weak for a real database. Before the Continuum adoption, `keys(kind, limit)`
— unfiltered and unordered — was how the reaper found overdue computations, so
every sweep scanned and decoded every pending document, capped at 20,000 and
silently truncating past that.

After adoption, the only two production callers are gone: Continuum owns expiry
through an indexed range query. `keys` survives as a contract method with
test-only callers. It still needs a correct implementation; it no longer needs
to be fast, and no index exists for it beyond the primary key.

## 6. The contract battery

`SubstrateContract` in **`nessy-testing`'s main sources** — so
`nessy-substrate-jdbc` can extend it at test scope — an abstract JUnit class
with one abstract factory method, mirroring how `ContinuumTck` and the existing
`MemoryContractTest` work:

```java
public abstract class SubstrateContract {
  protected abstract Substrate createSubstrate();
}
```

`nessy-testing`'s own **test** sources hold the subclass that certifies
`InMemorySubstrate`. That keeps the dependency one-directional: the battery
depends on `nessy-spi` for the interface, and nothing depends on
`nessy-testing`'s tests.

It certifies, for both implementations:

**Document semantics.** A read of an unknown key is empty, and an absent key
behaves as version 0. `write` at version 0 creates; at a stale version
conflicts; at the current version succeeds and stores **exactly
`expectedVersion + 1`** — verified against `InMemorySubstrate`, which does
`new Document(payload, expectedVersion + 1, now)`, so the JDBC side's
`SET version = version + 1` agrees rather than merely resembling it. `delete` at the current version removes; at a stale version
conflicts. A deleted key reads as absent, and writing it again requires version
0. Payload bytes round-trip unchanged, and the store does not alias the caller's
array — mutating the array after a write must not change stored truth, and
mutating a returned array must not either.

**Journal semantics.** Sequences start at 1. `append` at a taken seq conflicts
rather than overwriting. `entries` from a seq is inclusive and ascending.
`entries` beyond the end is empty.

**Listing.** `keys` returns ascending key order, respects its limit, and is
scoped to one kind — a key in another kind must not appear.

**Batch atomicity, across both shapes.** A document write and a journal append
in one batch either both land or neither does. Any CAS or seq miss anywhere in
the batch rolls back every op, including ops that would individually have
succeeded. This is the property with the most leverage: the adoption's fold
depends on the state write and the index delete moving together.

**Concurrency.** Two writers at the same version produce exactly one winner and
one `ConflictException`. Two appends at the same seq, likewise. Assertions are
on observable contract, never on mechanism — the same discipline that lets
Continuum's TCK certify providers with different locking strategies.

## 7. Testing

`nessy-testing` runs the battery against `InMemorySubstrate` in its own tests.
`nessy-substrate-jdbc` runs it against a testcontainers PostgreSQL whose schema
is created by executing the shipped DDL resource verbatim. Testcontainers
1.21.4 and PostgreSQL driver 42.7.13 are already pinned in the root pom.

Certifying the in-memory implementation is not ceremony. It is the reference
every other test in the repo runs against, while the JDBC one is what production
would use; if they disagree about anything the battery does not cover, the whole
suite is green against a store that behaves differently in production. Expect
the battery to find at least one divergence — that is the point, and each one
found here is one not found in production.

## 8. Rejected alternatives

**A single table with a discriminator.** Mixes two access patterns and makes the
journal's sequence ordering awkward for no saving; two tables is the ratified
mapping.

**A table per kind.** Better per-kind indexing, but `kind` is a runtime string,
so this would require DDL for every kind a feature invents. It breaks the
contract that a caller may name a kind freely.

**Pessimistic `SELECT … FOR UPDATE`.** Violates storage.md's no-locks-no-waits
contract (§4).

**A separate TCK module.** `nessy-testing` already holds a contract test and
already has the right dependencies. A new module for an established pattern is
a jar that has to justify itself, and it cannot.

## 9. Needs sign-off

1. **`nessy-substrate-jdbc` as a new module** — the module count goes 13 → 14.
   The alternative is JDBC in `nessy-spi`, which would put the PostgreSQL
   driver on every consumer's classpath.
2. **`SubstrateContract` as a new public type in `nessy-testing`** — an
   abstract class third parties extend, so it is API surface.

## 10. Deliberately not done

- **No `HarnessConfig.continuum(...)` seam**, and therefore **no end-to-end
  durability test yet.** That test — park a tool call, discard the harness,
  resume in a fresh one against the same database — is the payoff, and it needs
  a way to hand Nessy a `continuum-jdbc`-backed `Continuum`. That seam is a
  separate decision. This spec makes the test possible; it does not deliver it.
- No Spring auto-configuration.
- No migration tooling.
- No dialects beyond PostgreSQL.
- No change to `Substrate` itself. `keys` stays despite having no production
  callers — it has test callers and removing it is an SPI break for no gain.
