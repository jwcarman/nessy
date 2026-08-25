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
}
