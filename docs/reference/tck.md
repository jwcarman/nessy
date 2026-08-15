# TCK

The `nessy-tck` module is a certification kit: five abstract JUnit 5 test
classes, one per storage seam, that pin the invariants the durable loop
relies on. A backend that passes all five extends the contracts and supplies
nothing but a factory method — it earns the same trust as the in-memory and
JDBC implementations that ship in this repository.

## The five contracts

All five live in `org.jwcarman.nessy.tck`.

| Contract | Certifies | The seam under test |
|---|---|---|
| `ConversationStoreContract` | the fenced save and the inbox | `ConversationStore` |
| `ParksContract` | registration, lookup, idempotent re-registration, survival past resolution | `Parks` |
| `TranscriptContract` | append-only ordering, the last-row read, paging | `Transcript` |
| `SummaryStoreContract` | the summary watermark's save/load | `SummaryStore` |
| `PlanStoreContract` | save-then-find, wholesale replacement, ordering, empty-save-clears, absence, last-write-wins, per-conversation isolation | `PlanStore` |

Each contract needs exactly one abstract method implemented. `ConversationStoreContract`
rebuilds a fresh, empty store before every test (`newStore()`, called from a
`@BeforeEach` the contract itself owns). The other four hand back one
already-constructed instance for the whole of one test (`parks()`,
`transcript()`, `summaries()`, `plans()`) and expect the subclass to keep it
fresh — a field initializer for something cheap to rebuild, or a
`@BeforeEach` that truncates the backing table for something durable.

## Depending on it

The contracts ship as production code in `nessy-tck`, at test scope for a
consumer:

```xml
<dependency>
  <groupId>org.jwcarman.nessy</groupId>
  <artifactId>nessy-tck</artifactId>
  <scope>test</scope>
</dependency>
```

`nessy-tck` brings only `nessy-core`, `junit-jupiter-api`, and `assertj-core`
— no test engine. Your own POM already carries `junit-jupiter-engine` (or
`junit-platform-launcher`) for its own tests to run at all, and that engine
is what actually executes the contracts once extended.

## Certifying a `ConversationStore`

Extend the contract, implement the one abstract method, done:

```java
package org.jwcarman.nessy.spi.conversation;

import org.jwcarman.nessy.tck.ConversationStoreContract;

class MyConversationStoreTest extends ConversationStoreContract {

  @Override
  protected ConversationStore newStore() {
    return new MyConversationStore(/* ... */);
  }
}
```

`ParksContract` follows the same shape but with a durable, per-test-run field
instead of a per-test factory, since a `Parks` implementation is typically
cheap to keep around and reset rather than rebuild:

```java
package org.jwcarman.nessy.spi.conversation;

import org.jwcarman.nessy.tck.ParksContract;

class MyParksTest extends ParksContract {

  private final Parks parks = new MyParks(/* ... */);

  @Override
  protected Parks parks() {
    return parks;
  }
}
```

`TranscriptContract`, `SummaryStoreContract`, and `PlanStoreContract` follow
the identical pattern against `transcript()` / `summaries()` / `plans()`. A
JDBC-backed implementation typically adds a `@BeforeEach` that truncates the
backing table instead of a bare field.

!!! warning "Every contract `@Test` is `public`, not package-private"
    A subclass that nests a contract inside a `@Nested` class in a
    *different* package from the contract itself silently discovers **zero**
    inherited tests unless those `@Test` methods are `public` — JUnit 5's
    cross-package `@Nested` discovery cannot see package-private inherited
    members. `nessy-jdbc` hit this once, with the plan certification nested
    under a class in another package; the fix was moving it to its own
    top-level class. If your certification test reports "0 tests run,"
    check the package and nesting before assuming your store is broken.

## The vendor-matrix pattern

`nessy-jdbc` runs all five contracts once per implementation and again per
supported database vendor: one test class per vendor
(`MySqlStoreTckTest`, `MariaDbStoreTckTest`, `SqlServerStoreTckTest`,
`OracleStoreTckTest`, plus Postgres's own classes), each running the five
`nessy-tck` contracts against a real Testcontainers instance for that
vendor, plus a dialect-resolution pin. The vendor classes carry an
additional `@Tag("vendor")` alongside `@Tag("container")`, so CI can run the
Postgres-backed `container` suite on every push while reserving the full
five-vendor matrix for a local or scheduled run
(`-Dnessy.excludedGroups=live,vendor` excludes it; plain
`-Dnessy.excludedGroups=live` runs it). This is the shape to copy for a
custom backend that ships more than one supported dialect or provider: one
contract run per real target, sharing the same five contract classes.

## Where next

- [Storage](../concepts/storage.md) — the five storage doors and where each
  one fits in the durable loop.
- [Configuration](configuration.md) — the properties that wire the built-in
  JDBC doors.
- [Durable Persistence](../guides/durable-persistence.md) — what surviving a
  restart requires end to end.
