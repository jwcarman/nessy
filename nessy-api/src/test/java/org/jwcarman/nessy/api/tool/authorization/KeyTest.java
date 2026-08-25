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
package org.jwcarman.nessy.api.tool.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Value equality is load-bearing (approval-lifecycle spec §1.2): a decoded {@code ApprovalRequest}
 * is read back through freshly constructed keys, so two keys naming the same type and name must be
 * the same key wherever they were built.
 */
class KeyTest {

  @Test
  void twoKeysWithTheSameTypeAndNameAreEqualAndHashAlike() {
    Key<String> one = new Key<>(String.class, "intent.declared");
    Key<String> other = new Key<>(String.class, "intent.declared");

    assertThat(one).isEqualTo(other);
    assertThat(one.hashCode()).isEqualTo(other.hashCode());
  }

  @Test
  void twoKeysDifferingOnlyInNameAreNotEqual() {
    Key<String> one = new Key<>(String.class, "intent.declared");
    Key<String> other = new Key<>(String.class, "intent.observed");

    assertThat(one).isNotEqualTo(other);
  }
}
