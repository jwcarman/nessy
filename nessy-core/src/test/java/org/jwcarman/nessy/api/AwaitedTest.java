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

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AwaitedTest {

  @Test
  void ready_carries_its_value() {
    Awaited<String> awaited = Awaited.ready("done");

    String resolved =
        switch (awaited) {
          case Awaited.Ready<String> ready -> ready.value();
          case Awaited.Parked<String> parked -> "parked:" + parked.token().value();
        };

    assertThat(resolved).isEqualTo("done");
  }

  @Test
  void parked_carries_its_token() {
    ParkToken token = new ParkToken("t1");

    Awaited<String> awaited = Awaited.parked(token);

    assertThat(awaited).isEqualTo(new Awaited.Parked<String>(token));
  }

  @Test
  void random_tokens_are_distinct() {
    assertThat(ParkToken.generate()).isNotEqualTo(ParkToken.generate());
  }

  @Test
  void generated_park_tokens_are_time_ordered_uuidv7() {
    assertThat(UUID.fromString(ParkToken.generate().value()).version()).isEqualTo(7);
  }
}
