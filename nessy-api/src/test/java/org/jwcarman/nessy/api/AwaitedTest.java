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
package org.jwcarman.nessy.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("The outcome of something that might have to wait")
class AwaitedTest {

  @Nested
  @DisplayName("deferred")
  class Deferred {

    @Test
    @DisplayName("the factory wraps the lease's expiry")
    void the_factory_wraps_the_expiry() {
      Instant expiresAt = Instant.parse("2026-08-29T00:00:00Z");

      Awaited<String> awaited = Awaited.deferred(expiresAt);

      assertThat(awaited).isInstanceOf(Awaited.Deferred.class);
      assertThat(((Awaited.Deferred<String>) awaited).expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    @DisplayName("refuses a null expiry, since the engine has nothing to release the wait against")
    void refuses_a_null_expiry() {
      assertThatThrownBy(() -> new Awaited.Deferred<String>(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("expiresAt must not be null");
    }
  }

  @Nested
  @DisplayName("ready")
  class Ready {

    @Test
    @DisplayName("the factory wraps the answer in hand")
    void the_factory_wraps_the_result() {
      Awaited<String> awaited = Awaited.ready("done");

      assertThat(awaited).isEqualTo(new Awaited.Ready<>("done"));
    }
  }
}
