/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.nessy.testing;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.substrate.ConflictException;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * The runnable conformance harness every {@link Substrate} implementation owes (jdbc-substrate spec
 * §6): CAS discipline on the document store — version-zero create, exact-version-match update,
 * stale-version conflict, delete-then-recreate, delete-of-absent-as-no-op — kind namespacing, and
 * non-aliasing of the caller's payload array on write. A third-party {@code Substrate} extends this
 * class directly and implements {@link #createSubstrate()} — the one abstract member — to run every
 * test here against its own instance, exactly as {@code InMemorySubstrateContractTest} does against
 * the reference implementation.
 *
 * <p>Public and main-scope, on purpose (mirrors {@link MemoryContractTest}'s own rationale): a
 * conformance suite that only test-scoped code could see would be unusable by a {@code Substrate}
 * implementation living outside this repository. Pulls in {@code junit-jupiter-api} and {@code
 * assertj-core} directly, never the {@code junit-jupiter} aggregator, so depending on this class
 * never drags a test engine onto a caller's own main classpath (this module's {@code pom.xml}
 * already notes the convention).
 */
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

    assertThatThrownBy(() -> substrate.delete(KIND, "k", 1)).isInstanceOf(ConflictException.class);
  }

  @Test
  void deletingAnAbsentKeyAtVersionZeroIsANoOp() {
    Substrate substrate = createSubstrate();

    substrate.delete(KIND, "never-written", 0);

    assertThat(substrate.read(KIND, "never-written")).isEmpty();
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
        .hasValueSatisfying(
            document -> assertThat(document.payload()).isEqualTo("a".getBytes(UTF_8)));
    assertThat(substrate.read("beta", "k"))
        .hasValueSatisfying(
            document -> assertThat(document.payload()).isEqualTo("b".getBytes(UTF_8)));
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
  void keysOrderMatchesStringCompareToNotADictionaryCollation() {
    Substrate substrate = createSubstrate();
    substrate.write(KIND, "B", "1".getBytes(UTF_8), 0);
    substrate.write(KIND, "a", "2".getBytes(UTF_8), 0);
    substrate.write(KIND, "a-b", "3".getBytes(UTF_8), 0);
    substrate.write(KIND, "ab", "4".getBytes(UTF_8), 0);

    // "a", "a-b", "ab", "B" is dictionary order (a glibc-collated database's default). Ascending
    // lexicographic order — Substrate#keys's own promise, and how String.compareTo orders these
    // four — is "B", "a", "a-b", "ab": every uppercase letter's code point precedes every
    // lowercase one, and "-" (0x2D) precedes "b" (0x62) so "a-b" sorts before "ab". A key set of
    // "a"/"b"/"c" alone can't tell these two orderings apart.
    assertThat(substrate.keys(KIND, 10)).containsExactly("B", "a", "a-b", "ab");
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
}
