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
import org.junit.jupiter.api.Timeout;

/**
 * Governed is its own test: the scripted run needs no key and no network, so the offline default
 * build can run it directly. {@code @Timeout} is the backstop behind {@code Governed.await}'s own
 * 30-second bound — this test fails loudly rather than hanging a build if that bound is ever
 * bypassed.
 */
@Timeout(60)
class GovernedTest {

  /**
   * The grant arc (durable-deliveries spec §5a): approving the parked restart dispatches it past
   * the gate directly from the grant's own continuation — no second ask, no re-suspend — so this
   * scripted run observes the turn's completion.
   */
  @Test
  void the_bounced_and_declared_restart_completes_once_approved() throws InterruptedException {
    Governed.Result result = Governed.run();

    assertThat(result.sentinel()).isEqualTo("GOVERNED TURN COMPLETE");
    assertThat(result.bounceMessage()).contains("declare-intent");
    assertThat(result.declaredTarget()).isEqualTo("prod-eu");
  }
}
