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
package org.jwcarman.nessy.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A loop on a virtual thread, where a scheduler used to be.
 *
 * <p>The property that matters is that a THROWING sweep does not kill the loop. A scheduled task
 * that dies silently takes every future sweep with it, and the symptom -- deadlines that stop
 * firing, hours later -- looks like anything except a swallowed exception.
 */
@DisplayName("The sweep loop")
class SweepsTest {

  @Test
  @DisplayName("the work runs repeatedly until closed")
  void it_keeps_sweeping() throws Exception {
    CountDownLatch swept = new CountDownLatch(3);
    try (Sweeps sweeps =
        new Sweeps(
            () -> {
              swept.countDown();
              return Duration.ofMillis(10);
            })) {
      sweeps.start();

      assertThat(swept.await(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("a sweep that throws does not stop the ones after it")
  void a_throwing_sweep_does_not_kill_the_loop() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    CountDownLatch thrice = new CountDownLatch(3);
    java.util.function.Supplier<Duration> exploding =
        () -> {
          attempts.incrementAndGet();
          thrice.countDown();
          throw new IllegalStateException("boom");
        };

    try (Sweeps sweeps = new Sweeps(exploding)) {
      sweeps.start();

      assertThat(thrice.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(attempts.get()).isGreaterThanOrEqualTo(3);
    }
  }

  @Test
  @DisplayName("starting twice starts one loop")
  void start_is_idempotent() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    try (Sweeps sweeps =
        new Sweeps(
            () -> {
              runs.incrementAndGet();
              return Duration.ofSeconds(30);
            })) {
      sweeps.start();
      sweeps.start();

      TimeUnit.MILLISECONDS.sleep(200);

      assertThat(runs.get()).isEqualTo(1);
    }
  }
}
