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

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.ComputationCallback;

class AwaitedTest {

  @Test
  void ready_carries_its_value() {
    Awaited<String> awaited = Awaited.ready("done");

    String resolved =
        switch (awaited) {
          case Awaited.Ready<String>(String value) -> value;
          case Awaited.Deferred<String> _ -> "deferred";
        };

    assertThat(resolved).isEqualTo("done");
  }

  @Test
  void deferred_carries_the_callback_and_the_term_and_nothing_else() {
    ComputationCallback callback = (id, deadline) -> {};

    Awaited<String> awaited = Awaited.deferred(callback, Duration.ofDays(30));

    assertThat(awaited).isEqualTo(new Awaited.Deferred<String>(callback, Duration.ofDays(30)));
  }

  @Test
  void a_deferral_without_a_callback_is_refused() {
    var term = Duration.ofDays(30);

    assertThatThrownBy(() -> new Awaited.Deferred<String>(null, term))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void a_deferral_without_a_term_is_refused() {
    ComputationCallback callback = (id, deadline) -> {};

    assertThatThrownBy(() -> new Awaited.Deferred<String>(callback, null))
        .isInstanceOf(NullPointerException.class);
  }
}
