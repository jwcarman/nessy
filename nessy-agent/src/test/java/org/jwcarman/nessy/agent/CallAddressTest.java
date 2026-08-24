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
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@link CallAddress#indexKey()} digests, opaquely (computation-identity spec §1, §2):
 * deterministic over the identity tuple, distinct between tuples, and carrying no colon-delimited
 * structure a caller could parse back apart.
 */
class CallAddressTest {

  private static final Pattern LOWERCASE_HEX = Pattern.compile("[0-9a-f]+");

  @Test
  void theSameTupleDerivesTheSameIndexKeyEveryTime() {
    var address = new CallAddress("ops", "prod-1", "r7", "c42");
    assertThat(address.indexKey())
        .isEqualTo(new CallAddress("ops", "prod-1", "r7", "c42").indexKey());
  }

  @Test
  void distinctTuplesDeriveDistinctIndexKeys() {
    var address = new CallAddress("ops", "prod-1", "r7", "c42");
    var other = new CallAddress("ops", "prod-1", "r7", "c43");
    assertThat(address.indexKey()).isNotEqualTo(other.indexKey());
  }

  /**
   * Pins the length-prefix property the class javadoc claims: a naive delimiter-join (plain
   * concatenation with a separator, no length prefix) would collide these two tuples — {@code "a:b"
   * + "c"} and {@code "a" + "b:c"} render identically once joined — so this only stays distinct
   * because each field's own byte length is digested ahead of it.
   */
  @Test
  void fieldsWithEmbeddedDelimitersDoNotCollide() {
    var first = new CallAddress("a:b", "c", "r", "x");
    var second = new CallAddress("a", "b:c", "r", "x");
    assertThat(first.indexKey()).isNotEqualTo(second.indexKey());
  }

  @Test
  void theIndexKeyCarriesNoColonDelimitedFormat() {
    var address = new CallAddress("ops", "prod-1", "r7", "c42");
    assertThat(address.indexKey()).doesNotContain(":");
  }

  @Test
  void theIndexKeyIsLowercaseHex() {
    var address = new CallAddress("ops", "prod-1", "r7", "c42");
    assertThat(address.indexKey()).matches(LOWERCASE_HEX);
  }

  @Test
  void blankCoordinatesAreRefused() {
    assertThatThrownBy(() -> new CallAddress(" ", "a", "r", "c"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aBlankAgentIdIsRefused() {
    assertThatThrownBy(() -> new CallAddress("ops", " ", "r", "c"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aBlankResponseIdIsRefused() {
    assertThatThrownBy(() -> new CallAddress("ops", "a", " ", "c"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aBlankCallIdIsRefused() {
    assertThatThrownBy(() -> new CallAddress("ops", "a", "r", " "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aNullCoordinateThrowsANullPointerExceptionLikeEverySiblingType() {
    assertThatThrownBy(() -> new CallAddress(null, "a", "r", "c"))
        .isInstanceOf(NullPointerException.class);
  }
}
