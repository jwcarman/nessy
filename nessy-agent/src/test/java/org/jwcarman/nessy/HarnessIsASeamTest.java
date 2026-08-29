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
package org.jwcarman.nessy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.DefaultHarness;
import org.jwcarman.nessy.agent.Harness;

/**
 * {@link Harness} is the seam a second engine plugs into (engine-extraction spec §3). If it ever
 * stops being an interface, {@code nessy-engine} cannot implement it and the whole extraction is
 * blocked — so it is asserted rather than assumed.
 */
class HarnessIsASeamTest {

  @Test
  void the_harness_is_an_interface_so_an_engine_can_implement_it() {
    assertThat(Harness.class).isInterface();
  }

  @Test
  void the_scheduler_backed_engine_is_one_implementation_of_it_not_the_definition_of_it() {
    assertThat(Harness.class).isAssignableFrom(DefaultHarness.class);
    assertThat(DefaultHarness.class).isNotEqualTo(Harness.class);
  }
}
