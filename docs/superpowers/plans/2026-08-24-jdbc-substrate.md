# JDBC Substrate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Nessy a PostgreSQL-backed `Substrate`, and a contract battery that certifies it and `InMemorySubstrate` against the same behaviour.

**Architecture:** `SubstrateContract` is an abstract JUnit class in `nessy-testing`'s main sources with one factory method, exactly mirroring the existing `MemoryContractTest`. `nessy-testing`'s own tests certify `InMemorySubstrate`; a new `nessy-substrate-jdbc` module certifies `JdbcSubstrate` against a testcontainers PostgreSQL whose schema comes from the shipped DDL resource. The battery is written and proven against the reference implementation *before* the JDBC one exists.

**Tech Stack:** Java 25, Maven, PostgreSQL 42.7.13 driver, testcontainers 1.21.4, JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-08-24-jdbc-substrate-design.md`

## Global Constraints

- **Contract classes import only the annotation and assertion API, never the engine.** This is an established convention with a comment in both `pom.xml` (lines 312-315) and `nessy-testing/pom.xml` (lines 37-40): a third party extending a contract class must not gain `junit-jupiter-engine` on its classpath just by depending on the artifact. Depend on `junit-jupiter-api`, never the `junit-jupiter` aggregator, in any module whose *main* sources hold a contract class.
- **Optimistic only.** `docs/concepts/storage.md`: *"The store is the lock: every mutation carries a CAS expectation, and a miss is a conflict, never a wait. There are no locks and no waits."* A `SELECT … FOR UPDATE` implementation satisfies the signatures and violates the contract.
- **Nessy never creates or migrates schema.** DDL ships as a classpath resource. Tests execute that shipped file verbatim.
- **Build economics.** Iterate with `./mvnw -q -pl <module> -am test`. Run `./mvnw -q clean verify` ONCE per task, before its final commit. Never two Maven processes at once in one worktree.
- **Before every commit:** `./mvnw license:format -Plicense && ./mvnw spotless:apply`
- **No star imports.** No `@SuppressWarnings`.
- **Exception-assertion lambdas** contain exactly ONE throwing invocation; all setup outside (S5778).
- **Assert emptiness before any all/none-match predicate** on the same collection (S5841).
- **Prose test style, no mocking library.** Hand-written fakes only.
- Every public type and public method carries javadoc with `@param`/`@return`. The release profile runs doclint; a missing tag or dangling `{@link}` breaks it even when `verify` passes.

---

## File Structure

**Created:**
- `nessy-testing/src/main/java/org/jwcarman/nessy/testing/SubstrateContract.java` — the battery. One abstract factory method; every assertion is on observable contract, never mechanism.
- `nessy-testing/src/test/java/org/jwcarman/nessy/testing/InMemorySubstrateContractTest.java` — certifies the reference implementation.
- `nessy-substrate-jdbc/pom.xml`
- `nessy-substrate-jdbc/src/main/java/org/jwcarman/nessy/substrate/jdbc/JdbcSubstrate.java` — the whole implementation; one file, because the seven methods share connection and CAS plumbing that would be worse split.
- `nessy-substrate-jdbc/src/main/resources/org/jwcarman/nessy/substrate/jdbc/nessy-postgresql.sql` — the DDL users copy.
- `nessy-substrate-jdbc/src/test/java/org/jwcarman/nessy/substrate/jdbc/JdbcSubstrateContractTest.java` — certifies it against testcontainers.

**Modified:**
- `pom.xml` — the reactor `<module>` list, and `dependencyManagement` for the new module.
- `nessy-bom/pom.xml` — so consumers can take the module version-free.

---

### Task 1: `SubstrateContract` — document semantics, certified against `InMemorySubstrate`

**Files:**
- Create: `nessy-testing/src/main/java/org/jwcarman/nessy/testing/SubstrateContract.java`
- Create: `nessy-testing/src/test/java/org/jwcarman/nessy/testing/InMemorySubstrateContractTest.java`

**Interfaces:**
- Consumes: `org.jwcarman.nessy.spi.substrate.Substrate` and its nested `Document`, `Entry`, `Op`; `ConflictException`; `InMemorySubstrate`.
- Produces: `public abstract class SubstrateContract` with `protected abstract Substrate createSubstrate();`. Tasks 2 and 4 extend and add to it.

- [ ] **Step 1: Write the contract's document half**

Create `SubstrateContract.java`. Read `nessy-testing/src/main/java/org/jwcarman/nessy/testing/MemoryContractTest.java` first — match its shape exactly: a `public abstract class`, one `protected abstract` factory, `@Test` methods with prose names, AssertJ assertions, and a license header copied from a neighbouring file.

```java
public abstract class SubstrateContract {

  private static final String KIND = "contract";

  /**
   * A fresh, empty substrate for one test.
   *
   * @return the substrate under test
   */
  protected abstract Substrate createSubstrate();

  @Test
  void readingAnUnknownKeyIsEmpty() {
    assertThat(createSubstrate().read(KIND, "absent")).isEmpty();
  }

  @Test
  void writingAtVersionZeroCreates() {
    Substrate substrate = createSubstrate();

    substrate.write(KIND, "k", "one".getBytes(UTF_8), 0);

    assertThat(substrate.read(KIND, "k"))
        .hasValueSatisfying(
            document -> {
              assertThat(document.payload()).isEqualTo("one".getBytes(UTF_8));
              assertThat(document.version()).isEqualTo(1L);
            });
  }

  @Test
  void writingAtVersionZeroOverAnExistingKeyConflicts() {
    Substrate substrate = createSubstrate();
    substrate.write(KIND, "k", "one".getBytes(UTF_8), 0);

    assertThatThrownBy(() -> substrate.write(KIND, "k", "two".getBytes(UTF_8), 0))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void writingAtTheCurrentVersionSucceedsAndIncrementsByExactlyOne() {
    Substrate substrate = createSubstrate();
    substrate.write(KIND, "k", "one".getBytes(UTF_8), 0);

    substrate.write(KIND, "k", "two".getBytes(UTF_8), 1);

    assertThat(substrate.read(KIND, "k"))
        .hasValueSatisfying(document -> assertThat(document.version()).isEqualTo(2L));
  }

  @Test
  void writingAtAStaleVersionConflicts() {
    Substrate substrate = createSubstrate();
    substrate.write(KIND, "k", "one".getBytes(UTF_8), 0);
    substrate.write(KIND, "k", "two".getBytes(UTF_8), 1);

    assertThatThrownBy(() -> substrate.write(KIND, "k", "three".getBytes(UTF_8), 1))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void deletingAtTheCurrentVersionRemovesTheDocument() {
    Substrate substrate = createSubstrate();
    substrate.write(KIND, "k", "one".getBytes(UTF_8), 0);

    substrate.delete(KIND, "k", 1);

    assertThat(substrate.read(KIND, "k")).isEmpty();
  }

  @Test
  void deletingAtAStaleVersionConflicts() {
    Substrate substrate = createSubstrate();
    substrate.write(KIND, "k", "one".getBytes(UTF_8), 0);
    substrate.write(KIND, "k", "two".getBytes(UTF_8), 1);

    assertThatThrownBy(() -> substrate.delete(KIND, "k", 1))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void aDeletedKeyIsWrittenAgainAtVersionZero() {
    Substrate substrate = createSubstrate();
    substrate.write(KIND, "k", "one".getBytes(UTF_8), 0);
    substrate.delete(KIND, "k", 1);

    substrate.write(KIND, "k", "again".getBytes(UTF_8), 0);

    assertThat(substrate.read(KIND, "k"))
        .hasValueSatisfying(document -> assertThat(document.version()).isEqualTo(1L));
  }

  @Test
  void kindsAreSeparateNamespaces() {
    Substrate substrate = createSubstrate();
    substrate.write("alpha", "k", "a".getBytes(UTF_8), 0);
    substrate.write("beta", "k", "b".getBytes(UTF_8), 0);

    assertThat(substrate.read("alpha", "k"))
        .hasValueSatisfying(document -> assertThat(document.payload()).isEqualTo("a".getBytes(UTF_8)));
    assertThat(substrate.read("beta", "k"))
        .hasValueSatisfying(document -> assertThat(document.payload()).isEqualTo("b".getBytes(UTF_8)));
  }

  @Test
  void theStoreDoesNotAliasTheCallersArrayOnWrite() {
    Substrate substrate = createSubstrate();
    byte[] payload = "original".getBytes(UTF_8);
    substrate.write(KIND, "k", payload, 0);

    payload[0] = 'X';

    assertThat(substrate.read(KIND, "k"))
        .hasValueSatisfying(
            document -> assertThat(document.payload()).isEqualTo("original".getBytes(UTF_8)));
  }

  @Test
  void theStoreDoesNotAliasTheArrayItReturns() {
    Substrate substrate = createSubstrate();
    substrate.write(KIND, "k", "original".getBytes(UTF_8), 0);
    byte[] returned = substrate.read(KIND, "k").orElseThrow().payload();

    returned[0] = 'X';

    assertThat(substrate.read(KIND, "k"))
        .hasValueSatisfying(
            document -> assertThat(document.payload()).isEqualTo("original".getBytes(UTF_8)));
  }
}
```

The two aliasing tests are not ceremony: `storage.md` states *"Implementations must not alias the caller's array — bytes are copied on write and on read, so nothing downstream can mutate stored truth behind the CAS."* A JDBC implementation gets this free from `ResultSet#getBytes`; the in-memory one has to do it deliberately, and these pin it for both.

- [ ] **Step 2: Write the certification of the reference implementation**

Create `InMemorySubstrateContractTest.java`:

```java
class InMemorySubstrateContractTest extends SubstrateContract {

  @Override
  protected Substrate createSubstrate() {
    return new InMemorySubstrate();
  }
}
```

- [ ] **Step 3: Run it**

Run: `./mvnw -q -pl nessy-testing -am test -Dtest=InMemorySubstrateContractTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: all pass. **If any fails, that is a real finding about `InMemorySubstrate`, not a bug in your test** — stop and report it before changing either side. The spec says to expect at least one divergence; this is where it would surface.

- [ ] **Step 4: Verify and commit**

```bash
./mvnw -q clean verify
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add nessy-testing/src
git commit -m "test: a substrate contract, and the reference implementation certified against it"
```

---

### Task 2: `SubstrateContract` — journal, listing, batch atomicity, concurrency

**Files:**
- Modify: `nessy-testing/src/main/java/org/jwcarman/nessy/testing/SubstrateContract.java`

**Interfaces:**
- Consumes: `SubstrateContract` from Task 1.
- Produces: the same class, complete. Task 4 extends it unchanged.

- [ ] **Step 1: Add the journal, listing and batch tests**

Append to `SubstrateContract`:

```java
  @Test
  void journalSequencesStartAtOne() {
    Substrate substrate = createSubstrate();

    substrate.append(KIND, "k", 1, "first".getBytes(UTF_8));

    assertThat(substrate.entries(KIND, "k", 1))
        .singleElement()
        .satisfies(entry -> assertThat(entry.seq()).isEqualTo(1L));
  }

  @Test
  void appendingAtATakenSequenceConflictsRatherThanOverwriting() {
    Substrate substrate = createSubstrate();
    substrate.append(KIND, "k", 1, "first".getBytes(UTF_8));

    assertThatThrownBy(() -> substrate.append(KIND, "k", 1, "second".getBytes(UTF_8)))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void entriesFromASequenceAreInclusiveAndAscending() {
    Substrate substrate = createSubstrate();
    substrate.append(KIND, "k", 1, "one".getBytes(UTF_8));
    substrate.append(KIND, "k", 2, "two".getBytes(UTF_8));
    substrate.append(KIND, "k", 3, "three".getBytes(UTF_8));

    assertThat(substrate.entries(KIND, "k", 2))
        .extracting(Substrate.Entry::seq)
        .containsExactly(2L, 3L);
  }

  @Test
  void entriesBeyondTheEndAreEmpty() {
    Substrate substrate = createSubstrate();
    substrate.append(KIND, "k", 1, "one".getBytes(UTF_8));

    assertThat(substrate.entries(KIND, "k", 2)).isEmpty();
  }

  @Test
  void keysAreAscendingAndScopedToOneKind() {
    Substrate substrate = createSubstrate();
    substrate.write(KIND, "c", "3".getBytes(UTF_8), 0);
    substrate.write(KIND, "a", "1".getBytes(UTF_8), 0);
    substrate.write(KIND, "b", "2".getBytes(UTF_8), 0);
    substrate.write("other", "z", "z".getBytes(UTF_8), 0);

    assertThat(substrate.keys(KIND, 10)).containsExactly("a", "b", "c");
  }

  @Test
  void keysRespectsItsLimit() {
    Substrate substrate = createSubstrate();
    substrate.write(KIND, "a", "1".getBytes(UTF_8), 0);
    substrate.write(KIND, "b", "2".getBytes(UTF_8), 0);
    substrate.write(KIND, "c", "3".getBytes(UTF_8), 0);

    assertThat(substrate.keys(KIND, 2)).containsExactly("a", "b");
  }

  @Test
  void aBatchAppliesAcrossBothShapes() {
    Substrate substrate = createSubstrate();

    substrate.batch(
        List.of(
            new Substrate.Op.WriteDocument(KIND, "k", "doc".getBytes(UTF_8), 0),
            new Substrate.Op.AppendEntry(KIND, "k", 1, "entry".getBytes(UTF_8))));

    assertThat(substrate.read(KIND, "k")).isPresent();
    assertThat(substrate.entries(KIND, "k", 1)).hasSize(1);
  }

  @Test
  void aConflictAnywhereInABatchRollsBackEveryOp() {
    Substrate substrate = createSubstrate();
    substrate.write(KIND, "existing", "already".getBytes(UTF_8), 0);
    List<Substrate.Op> ops =
        List.of(
            new Substrate.Op.WriteDocument(KIND, "fresh", "new".getBytes(UTF_8), 0),
            new Substrate.Op.AppendEntry(KIND, "j", 1, "entry".getBytes(UTF_8)),
            new Substrate.Op.WriteDocument(KIND, "existing", "clobber".getBytes(UTF_8), 0));

    assertThatThrownBy(() -> substrate.batch(ops)).isInstanceOf(ConflictException.class);

    assertThat(substrate.read(KIND, "fresh")).isEmpty();
    assertThat(substrate.entries(KIND, "j", 1)).isEmpty();
    assertThat(substrate.read(KIND, "existing"))
        .hasValueSatisfying(
            document -> assertThat(document.payload()).isEqualTo("already".getBytes(UTF_8)));
  }

  @Test
  void aDeleteInABatchIsRolledBackToo() {
    Substrate substrate = createSubstrate();
    substrate.write(KIND, "doomed", "here".getBytes(UTF_8), 0);
    substrate.write(KIND, "existing", "already".getBytes(UTF_8), 0);
    List<Substrate.Op> ops =
        List.of(
            new Substrate.Op.DeleteDocument(KIND, "doomed", 1),
            new Substrate.Op.WriteDocument(KIND, "existing", "clobber".getBytes(UTF_8), 0));

    assertThatThrownBy(() -> substrate.batch(ops)).isInstanceOf(ConflictException.class);

    assertThat(substrate.read(KIND, "doomed")).isPresent();
  }
```

`aConflictAnywhereInABatchRollsBackEveryOp` is the highest-leverage test in the battery. Nessy's fold puts a state write and an index delete in one batch and relies on them moving together; the third op is the one that conflicts, so a partial application would leave the first two visible.

- [ ] **Step 2: Add the concurrency pair**

```java
  @Test
  void twoWritersAtTheSameVersionProduceExactlyOneWinner() throws Exception {
    Substrate substrate = createSubstrate();
    substrate.write(KIND, "k", "seed".getBytes(UTF_8), 0);
    var conflicts = new AtomicInteger();
    var barrier = new CyclicBarrier(2);
    Runnable writer =
        () -> {
          try {
            barrier.await(5, TimeUnit.SECONDS);
            substrate.write(KIND, "k", "mine".getBytes(UTF_8), 1);
          } catch (ConflictException e) {
            conflicts.incrementAndGet();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        };
    var one = new Thread(writer);
    var two = new Thread(writer);

    one.start();
    two.start();
    one.join(10_000);
    two.join(10_000);

    assertThat(conflicts).hasValue(1);
    assertThat(substrate.read(KIND, "k"))
        .hasValueSatisfying(document -> assertThat(document.version()).isEqualTo(2L));
  }

  @Test
  void twoAppendersAtTheSameSequenceProduceExactlyOneWinner() throws Exception {
    Substrate substrate = createSubstrate();
    var conflicts = new AtomicInteger();
    var barrier = new CyclicBarrier(2);
    Runnable appender =
        () -> {
          try {
            barrier.await(5, TimeUnit.SECONDS);
            substrate.append(KIND, "k", 1, "mine".getBytes(UTF_8));
          } catch (ConflictException e) {
            conflicts.incrementAndGet();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        };
    var one = new Thread(appender);
    var two = new Thread(appender);

    one.start();
    two.start();
    one.join(10_000);
    two.join(10_000);

    assertThat(conflicts).hasValue(1);
    assertThat(substrate.entries(KIND, "k", 1)).hasSize(1);
  }
```

Assertions are on observable contract — *how many conflicted*, *what survived* — never on mechanism. That is what lets one battery certify an in-memory map and a database.

- [ ] **Step 3: Run against the reference**

Run: `./mvnw -q -pl nessy-testing -am test -Dtest=InMemorySubstrateContractTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: all pass. Again — a failure here is a finding about `InMemorySubstrate`. Report it rather than adjusting the assertion.

- [ ] **Step 4: Verify and commit**

```bash
./mvnw -q clean verify
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add nessy-testing/src
git commit -m "test: the contract's journal, listing, batch and concurrency halves"
```

---

### Task 3: The `nessy-substrate-jdbc` module, its DDL, and the document half

**Files:**
- Modify: `pom.xml` (the `<module>` list, and `dependencyManagement`)
- Modify: `nessy-bom/pom.xml`
- Create: `nessy-substrate-jdbc/pom.xml`
- Create: `nessy-substrate-jdbc/src/main/resources/org/jwcarman/nessy/substrate/jdbc/nessy-postgresql.sql`
- Create: `nessy-substrate-jdbc/src/main/java/org/jwcarman/nessy/substrate/jdbc/JdbcSubstrate.java`

**Interfaces:**
- Consumes: `Substrate`, `ConflictException` from `nessy-spi`; `SubstrateContract` from Tasks 1-2.
- Produces: `public final class JdbcSubstrate implements Substrate` with `public JdbcSubstrate(DataSource dataSource)` and `public JdbcSubstrate(DataSource dataSource, Clock clock)`.

- [ ] **Step 1: Create the module**

Add `<module>nessy-substrate-jdbc</module>` to the reactor list in `pom.xml` (the existing list is around lines 57-69 — append after `nessy-tool-mcp`).

Then add it to `nessy-bom/pom.xml` only. **The root `pom.xml` has no `dependencyManagement` entries for Nessy's own modules** — I checked; inter-module versions come from the BOM alone. Follow the existing entries' exact shape:

```xml
<dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-substrate-jdbc</artifactId>
    <version>${project.version}</version>
</dependency>
```

Note that `nessy-bom/pom.xml` is indented with four spaces where the root pom uses two — match the file you are editing, and let `spotless:apply` settle it either way.

`nessy-substrate-jdbc/pom.xml` needs: `nessy-spi` (compile), `org.postgresql:postgresql` at **`provided`** scope (the application supplies its own driver), and at test scope `nessy-testing`, `org.testcontainers:postgresql`, and the `junit-jupiter` aggregator. Versions come from the parent's `dependencyManagement` — `testcontainers.version` 1.21.4 and `postgresql.version` 42.7.13 are already pinned there. Do not inline a version.

- [ ] **Step 2: Write the DDL resource**

`nessy-postgresql.sql`:

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

`key` is a reserved word in some dialects but not PostgreSQL; leave it unquoted to match `storage.md`'s ratified mapping. No secondary indexes — every access path is a primary-key lookup or a prefix scan of one.

- [ ] **Step 3: Implement the document half**

`JdbcSubstrate` acquires a connection per unit of work and closes it, exactly as `JdbcContinuumRepository` does. No ambient-transaction participation.

Structure it around one private helper so every method shares the same transaction discipline:

```java
  private <T> T inTransaction(SqlWork<T> work) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        T result = work.perform(connection);
        connection.commit();
        return result;
      } catch (SQLException | RuntimeException e) {
        connection.rollback();
        throw e;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("substrate operation failed", e);
    }
  }
```

The four document methods:

- `read` — `SELECT payload, version, updated_at FROM nessy_document WHERE kind = ? AND key = ?`, mapping to `new Substrate.Document(rs.getBytes("payload"), rs.getLong("version"), rs.getTimestamp("updated_at").toInstant())`.
- `write` at `expectedVersion == 0` — `INSERT INTO nessy_document (kind, key, payload, version, updated_at) VALUES (?, ?, ?, 1, ?)`. Catch `SQLException` whose `getSQLState()` is `"23505"` (unique violation) and throw `ConflictException`. **Do not** match on the message text.
- `write` at any other version — `UPDATE nessy_document SET payload = ?, version = version + 1, updated_at = ? WHERE kind = ? AND key = ? AND version = ?`; `executeUpdate()` returning 0 is the conflict.
- `delete` — `DELETE FROM nessy_document WHERE kind = ? AND key = ? AND version = ?`; 0 affected rows is the conflict.
- `keys` — `SELECT key FROM nessy_document WHERE kind = ? ORDER BY key LIMIT ?`.

Timestamps come from the injected `Clock`, never SQL `now()` — `InMemorySubstrate` stamps in the JVM and the two must agree. Default the constructor to `Clock.systemUTC()`.

Leave `append`, `entries` and `batch` throwing `UnsupportedOperationException` for now; Task 4 implements them. Say so in a comment naming Task 4.

- [ ] **Step 4: Compile**

Run: `./mvnw -q -pl nessy-substrate-jdbc -am test-compile`
Expected: clean.

- [ ] **Step 5: Verify and commit**

```bash
./mvnw -q clean verify
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add pom.xml nessy-bom/pom.xml nessy-substrate-jdbc
git commit -m "feat: a JDBC substrate, its schema, and the document half"
```

---

### Task 4: The journal, `batch`, and full certification against PostgreSQL

**Files:**
- Modify: `nessy-substrate-jdbc/src/main/java/org/jwcarman/nessy/substrate/jdbc/JdbcSubstrate.java`
- Create: `nessy-substrate-jdbc/src/test/java/org/jwcarman/nessy/substrate/jdbc/JdbcSubstrateContractTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1-3.
- Produces: a fully implemented `JdbcSubstrate` certified by `SubstrateContract`.

- [ ] **Step 1: Write the certification**

```java
@Testcontainers
class JdbcSubstrateContractTest extends SubstrateContract {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17-alpine");

  @Override
  protected Substrate createSubstrate() {
    DataSource dataSource = dataSource();
    applyShippedSchema(dataSource);
    truncate(dataSource);
    return new JdbcSubstrate(dataSource);
  }
}
```

`applyShippedSchema` reads the DDL **from the classpath resource** — `JdbcSubstrate.class.getResourceAsStream("nessy-postgresql.sql")` — and executes it. Do not paste the DDL into the test. The spec's promise is that the file a user copies is the file that is proven, and only executing the shipped resource keeps it.

`createSubstrate()` is called per test and the contract assumes a fresh, empty store, so truncate both tables each time. The container is `static` so one PostgreSQL serves the whole class.

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw -q -pl nessy-substrate-jdbc -am test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: the document tests pass; the journal and batch tests fail with `UnsupportedOperationException`.

If Docker is unavailable in your environment, **stop and report that** rather than skipping the tests or weakening the class — a certification that does not run certifies nothing.

- [ ] **Step 3: Implement the journal**

- `append` — `INSERT INTO nessy_journal (kind, key, seq, payload, appended_at) VALUES (?, ?, ?, ?, ?)`. SQL state `23505` becomes `ConflictException`, same as the document insert.
- `entries` — `SELECT seq, payload, appended_at FROM nessy_journal WHERE kind = ? AND key = ? AND seq >= ? ORDER BY seq`.

- [ ] **Step 4: Implement `batch`**

One transaction, every op applied in list order, any CAS or seq miss throwing `ConflictException` — which the `inTransaction` helper's rollback then makes atomic. Reuse the same statement-building code paths as the single-op methods rather than duplicating the SQL; extract private methods taking a `Connection` and have both the public single-op methods and `batch` call them.

That extraction is the point: duplicated SQL between `write` and `batch` is exactly how the two drift and how `aConflictAnywhereInABatchRollsBackEveryOp` starts passing for the wrong reason.

- [ ] **Step 5: Run the full contract**

Run: `./mvnw -q -pl nessy-substrate-jdbc -am test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: every test in `SubstrateContract` passes against PostgreSQL.

**Any divergence from `InMemorySubstrate` is a finding, not a nuisance.** Report which test, what each implementation does, and which you believe is correct per `storage.md` — do not adjust the contract to accommodate whichever one you built.

- [ ] **Step 6: Verify and commit**

```bash
./mvnw -q clean verify
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add nessy-substrate-jdbc
git commit -m "feat: the journal, batch atomicity, and certification against PostgreSQL"
```

---

## Open questions the implementer must resolve

1. **Task 4 Step 1:** whether this repo has an existing testcontainers usage to copy (the root pom's comment mentions `nessy-jdbc`'s container shape, but that module is gone). If no precedent survives, say so and follow testcontainers' own documented `@Container`/`@Testcontainers` pattern.
2. **Task 4 Step 5:** any behavioural divergence between the two implementations. Expect at least one; the spec says so — and it is the whole reason both are certified.
3. **Any task:** whether Docker is available. If it is not, Task 4's certification cannot run, and a certification that does not run certifies nothing — stop and report rather than skipping or weakening it.
