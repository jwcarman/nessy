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
 * {@link ToolCallAddress#digest()} digests, opaquely (computation-identity spec §1, §2):
 * deterministic over the identity tuple, distinct between tuples, and carrying no colon-delimited
 * structure a caller could parse back apart.
 */
class ToolCallAddressTest {

  private static final Pattern LOWERCASE_HEX = Pattern.compile("[0-9a-f]+");

  @Test
  void theSameTupleDerivesTheSameIndexKeyEveryTime() {
    var address = new ToolCallAddress("ops", "prod-1", "r7", "c42");
    assertThat(address.digest())
        .isEqualTo(new ToolCallAddress("ops", "prod-1", "r7", "c42").digest());
  }

  @Test
  void distinctTuplesDeriveDistinctIndexKeys() {
    var address = new ToolCallAddress("ops", "prod-1", "r7", "c42");
    var other = new ToolCallAddress("ops", "prod-1", "r7", "c43");
    assertThat(address.digest()).isNotEqualTo(other.digest());
  }

  /**
   * Per-coordinate sensitivity, isolated (computation-identity spec §2): each of the four fields
   * must move the digest on its own, with the other three held fixed against a common baseline —
   * distinct from {@link #fieldsWithEmbeddedDelimitersDoNotCollide()} below, which deliberately
   * varies two fields at once to pin a different property. {@code digest()} is now load-bearing for
   * the dispatch index: a digest that silently dropped one field (e.g. {@code responseId}, closing
   * "the provider-uniqueness hole" the class javadoc calls out) would collide two distinct calls
   * into one dispatch entry, and a redrive of one call would absorb against the other's in-flight
   * computation.
   */
  @Test
  void varyingAgentTypeAloneChangesTheIndexKey() {
    var baseline = new ToolCallAddress("ops", "prod-1", "r7", "c42");
    var variant = new ToolCallAddress("billing", "prod-1", "r7", "c42");
    assertThat(baseline.digest()).isNotEqualTo(variant.digest());
  }

  @Test
  void varyingAgentIdAloneChangesTheIndexKey() {
    var baseline = new ToolCallAddress("ops", "prod-1", "r7", "c42");
    var variant = new ToolCallAddress("ops", "prod-2", "r7", "c42");
    assertThat(baseline.digest()).isNotEqualTo(variant.digest());
  }

  @Test
  void varyingResponseIdAloneChangesTheIndexKey() {
    var baseline = new ToolCallAddress("ops", "prod-1", "r7", "c42");
    var variant = new ToolCallAddress("ops", "prod-1", "r8", "c42");
    assertThat(baseline.digest()).isNotEqualTo(variant.digest());
  }

  @Test
  void varyingCallIdAloneChangesTheIndexKey() {
    var baseline = new ToolCallAddress("ops", "prod-1", "r7", "c42");
    var variant = new ToolCallAddress("ops", "prod-1", "r7", "c43");
    assertThat(baseline.digest()).isNotEqualTo(variant.digest());
  }

  /**
   * Pins the length-prefix property the class javadoc claims: a naive delimiter-join (plain
   * concatenation with a separator, no length prefix) would collide these two tuples — {@code "a:b"
   * + "c"} and {@code "a" + "b:c"} render identically once joined — so this only stays distinct
   * because each field's own byte length is digested ahead of it.
   */
  @Test
  void fieldsWithEmbeddedDelimitersDoNotCollide() {
    var first = new ToolCallAddress("a:b", "c", "r", "x");
    var second = new ToolCallAddress("a", "b:c", "r", "x");
    assertThat(first.digest()).isNotEqualTo(second.digest());
  }

  @Test
  void theIndexKeyCarriesNoColonDelimitedFormat() {
    var address = new ToolCallAddress("ops", "prod-1", "r7", "c42");
    assertThat(address.digest()).doesNotContain(":");
  }

  @Test
  void theIndexKeyIsLowercaseHex() {
    var address = new ToolCallAddress("ops", "prod-1", "r7", "c42");
    assertThat(address.digest()).matches(LOWERCASE_HEX);
  }

  @Test
  void blankCoordinatesAreRefused() {
    assertThatThrownBy(() -> new ToolCallAddress(" ", "a", "r", "c"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aBlankAgentIdIsRefused() {
    assertThatThrownBy(() -> new ToolCallAddress("ops", " ", "r", "c"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aBlankResponseIdIsRefused() {
    assertThatThrownBy(() -> new ToolCallAddress("ops", "a", " ", "c"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aBlankCallIdIsRefused() {
    assertThatThrownBy(() -> new ToolCallAddress("ops", "a", "r", " "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aNullCoordinateThrowsANullPointerExceptionLikeEverySiblingType() {
    assertThatThrownBy(() -> new ToolCallAddress(null, "a", "r", "c"))
        .isInstanceOf(NullPointerException.class);
  }
}
