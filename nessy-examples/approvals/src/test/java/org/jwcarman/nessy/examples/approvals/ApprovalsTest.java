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
package org.jwcarman.nessy.examples.approvals;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Approvals is its own test: the scripted arc needs no key and no console input, so the offline
 * default build can run it directly. {@code @Timeout} is the backstop behind {@code
 * Approvals.await}'s own 30-second bound — this test fails loudly rather than hanging a build if
 * that bound is ever bypassed.
 */
@Timeout(60)
class ApprovalsTest {

  /**
   * The answer arc (approval-lifecycle spec §5 — see {@link Approvals#runScripted()}'s javadoc):
   * approving the parked computation folds one answer, and the fold emits the run, with no second
   * ask, so the scripted arc observes a completed restart.
   */
  @Test
  void the_scripted_restart_parks_then_completes_once_approved() throws InterruptedException {
    String line = Approvals.runScripted();

    assertThat(line).isEqualTo("restarted prod-eu: Restarted prod-eu.");
  }
}
