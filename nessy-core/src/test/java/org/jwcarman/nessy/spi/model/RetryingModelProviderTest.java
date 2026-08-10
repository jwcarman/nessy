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
package org.jwcarman.nessy.spi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;

class RetryingModelProviderTest {

  static final class FlakyProvider implements ModelProvider {
    int calls;
    final int failuresBeforeSuccess;
    final RuntimeException failure;

    FlakyProvider(int failuresBeforeSuccess, RuntimeException failure) {
      this.failuresBeforeSuccess = failuresBeforeSuccess;
      this.failure = failure;
    }

    @Override
    public ModelStream stream(ModelRequest request) {
      calls++;
      if (calls <= failuresBeforeSuccess) {
        throw failure;
      }
      return new ModelStream() {
        @Override
        public Iterator<ModelEvent> iterator() {
          return List.<ModelEvent>of().iterator();
        }

        @Override
        public void close() {
          // fake stream holds no resources to release
        }
      };
    }

    @Override
    public Set<Capability> capabilities() {
      return Set.of(Capability.THINKING);
    }
  }

  static final class RecordingSleeper implements Sleeper {
    final List<Duration> slept = new ArrayList<>();

    @Override
    public void sleep(Duration duration) {
      slept.add(duration);
    }
  }

  private static ModelRequest request() {
    return new ModelRequest(
        Context.of(List.of(Message.user("hi"))), "sys", "m", 100, List.of(), Set.of(), null);
  }

  @Nested
  class Retrying {

    @Test
    void retries_retryable_failures_with_exponential_backoff() {
      FlakyProvider flaky = new FlakyProvider(2, new IllegalStateException("429"));
      RecordingSleeper sleeper = new RecordingSleeper();
      ModelProvider provider =
          new RetryingModelProvider(flaky, RetryPolicy.defaults(), e -> true, sleeper);

      provider.stream(request()).close();

      assertThat(flaky.calls).isEqualTo(3);
      assertThat(sleeper.slept).containsExactly(Duration.ofMillis(500), Duration.ofMillis(1000));
    }

    @Test
    void gives_up_after_max_attempts_and_rethrows_the_last_failure() {
      FlakyProvider flaky = new FlakyProvider(99, new IllegalStateException("still 429"));
      ModelProvider provider =
          new RetryingModelProvider(
              flaky, RetryPolicy.defaults(), e -> true, new RecordingSleeper());

      ModelRequest modelRequest = request();

      assertThatThrownBy(() -> provider.stream(modelRequest))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("still 429");
      assertThat(flaky.calls).isEqualTo(3);
    }

    @Test
    void non_retryable_failures_are_rethrown_immediately() {
      FlakyProvider flaky = new FlakyProvider(99, new IllegalArgumentException("bad request"));
      RecordingSleeper sleeper = new RecordingSleeper();
      ModelProvider provider =
          new RetryingModelProvider(flaky, RetryPolicy.defaults(), e -> false, sleeper);

      ModelRequest modelRequest = request();

      assertThatThrownBy(() -> provider.stream(modelRequest))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(flaky.calls).isEqualTo(1);
      assertThat(sleeper.slept).isEmpty();
    }
  }

  @Test
  void capabilities_pass_through_untouched() {
    ModelProvider provider =
        RetryingModelProvider.wrap(
            new FlakyProvider(0, new IllegalStateException("unused")),
            RetryPolicy.defaults(),
            e -> true);

    assertThat(provider.capabilities()).containsExactly(Capability.THINKING);
  }

  @Test
  void degenerate_policies_are_rejected() {
    Duration oneMilli = Duration.ofMillis(1);

    assertThatThrownBy(() -> new RetryPolicy(0, oneMilli, 2.0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RetryPolicy(3, Duration.ZERO, 2.0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RetryPolicy(3, oneMilli, 0.5))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
