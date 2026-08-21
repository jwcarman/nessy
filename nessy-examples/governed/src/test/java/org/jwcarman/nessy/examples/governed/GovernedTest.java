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
package org.jwcarman.nessy.examples.governed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Governed is its own test: the scripted run needs no key and no network, so the offline default
 * build can run it directly.
 */
class GovernedTest {

  @Test
  void the_bounced_declared_and_approved_restart_completes_the_turn() throws InterruptedException {
    String line = Governed.run();

    assertThat(line).isEqualTo("GOVERNED TURN COMPLETE");
  }
}
