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

import org.junit.jupiter.api.Test;

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
  void deferred_is_a_marker() {
    Awaited<String> awaited = Awaited.deferred();
    assertThat(awaited).isEqualTo(new Awaited.Deferred<String>());
  }
}
