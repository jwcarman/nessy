# Nessy Store TCK

The certification promise: a store implementation that passes every contract
in this kit honors every invariant the loop relies on — whether it ships from
this repository or a third party's. The four contracts moved here from
`nessy-core`'s test-jar (design §5) so that promise stands on a published,
main-scope artifact rather than a test-only classifier — a third-party
implementer depends on `nessy-store-tck` directly, extends the contract
classes, and supplies its own JUnit engine at test scope.

## What the kit covers

Four contract classes, `org.jwcarman.nessy.store.tck`, one per SPI seam:

| Contract | Certifies | The seam under test |
|---|---|---|
| `ConversationStoreContract` | the fenced save and the inbox | `ConversationStore` |
| `ParksContract` | registration, lookup, idempotent re-registration, survival past resolution | `Parks` |
| `TranscriptContract` | append-only ordering, the last-row read, paging | `Transcript` |
| `SummaryStoreContract` | the summary watermark's save/load | `SummaryStore` |

Each contract is an abstract JUnit 5 test class with exactly one abstract
factory method a concrete subclass supplies — nothing else to configure.
`ConversationStoreContract` re-builds a fresh, empty store before every test
(`newStore()`, called from a `@BeforeEach` the contract owns); the other
three hand back one already-constructed instance for the whole of one test
(`parks()` / `transcript()` / `summaries()`) and expect the subclass's own
`@BeforeEach` (or a field initializer, for a store cheap enough to rebuild
per test) to keep it fresh — the shape each contract already uses is the
shape to copy, not a rule enforced by the kit itself.

## Wiring it against a real implementation

Extend the contract, implement the one abstract method, done. Against
`ConversationStore.inMemory()`:

```java
package org.jwcarman.nessy.spi.conversation;

import org.jwcarman.nessy.store.tck.ConversationStoreContract;

class InMemoryConversationStoreTest extends ConversationStoreContract {

  @Override
  protected ConversationStore newStore() {
    return ConversationStore.inMemory();
  }
}
```

`ParksContract` follows the same shape but with a durable-per-test-run field
instead of a per-test factory, since `Parks.inMemory()` (or a JDBC-backed
implementation truncated between tests) is cheap to keep around and reset
rather than rebuild:

```java
package org.jwcarman.nessy.spi.conversation;

import org.jwcarman.nessy.store.tck.ParksContract;

class InMemoryParksTest extends ParksContract {

  private final Parks parks = Parks.inMemory();

  @Override
  protected Parks parks() {
    return parks;
  }
}
```

`TranscriptContract` and `SummaryStoreContract` follow the identical pattern
against `transcript()`/`summaries()`. A JDBC-backed implementation typically
adds a `@BeforeEach` that truncates the backing table(s) instead of a bare
field — see `nessy-store-jdbc`'s five vendor-matrix test classes for that
shape multiplied across five real databases sharing one Testcontainers
instance per vendor.

## The reference certifications

`nessy-store-tck`'s own test tree carries the in-memory implementations as
the worked example: `InMemoryConversationStoreTest`, `InMemoryParksTest`,
`InMemoryTranscriptTest`, `InMemorySummaryStoreTest`
(`org.jwcarman.nessy.spi.{conversation,memory}`), each a few lines extending
its contract exactly as shown above. `nessy-store-jdbc` runs the same four
contracts a second time, per vendor, against Postgres/MySQL/MariaDB/SQL
Server/Oracle — see that module's own README for the dialect story.

## Depending on it

Main scope, not test: the contracts are abstract JUnit/AssertJ-based classes
shipped as production code so a downstream project can `extend` them
directly, at whatever scope its own test tree needs.

```xml
<dependency>
  <groupId>org.jwcarman.nessy</groupId>
  <artifactId>nessy-store-tck</artifactId>
  <scope>test</scope>
</dependency>
```

The artifact itself brings only `nessy-core`, `junit-jupiter-api`, and
`assertj-core` — no test engine. A consumer's own POM already carries
`junit-jupiter-engine` (or `junit-platform-launcher`) for its own tests to
run at all, and that's what actually executes the contracts once extended.
