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
package org.jwcarman.nessy.examples.watchman;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The token metric, without a container or a Boot context in the way.
 *
 * <p>{@code TelemetryWiringTest} proves the handler is REGISTERED on the registry the harness was
 * given; this proves what it does once something reaches it. Different questions, and only one of
 * them needs Docker.
 */
class TokenUsageHandlerTest {

  private final MeterRegistry meters = new SimpleMeterRegistry();
  private final ObservationRegistry observations = ObservationRegistry.create();

  TokenUsageHandlerTest() {
    observations.observationConfig().observationHandler(new TokenUsageHandler(meters));
  }

  /**
   * The recorded total for one {@code gen_ai.token.type}, or empty if that series was never
   * created.
   */
  private Optional<Double> totalFor(String tokenType) {
    return meters.find("gen_ai.client.token.usage").summaries().stream()
        .filter(summary -> tokenType.equals(summary.getId().getTag("gen_ai.token.type")))
        .map(summary -> summary.totalAmount())
        .findFirst();
  }

  private void observe(String... keyValues) {
    Observation observation =
        Observation.createNotStarted("gen_ai.client.operation.duration", observations)
            .lowCardinalityKeyValue("gen_ai.provider.name", "anthropic")
            .lowCardinalityKeyValue("gen_ai.request.model", "claude-test")
            .lowCardinalityKeyValue("error.type", "none");
    for (int i = 0; i < keyValues.length; i += 2) {
      observation = observation.highCardinalityKeyValue(keyValues[i], keyValues[i + 1]);
    }
    observation.start().stop();
  }

  @Nested
  class The_two_counts_every_chat_carries {

    @Test
    void become_input_and_output_series() {
      observe(
          "gen_ai.usage.input_tokens", "120",
          "gen_ai.usage.output_tokens", "34");

      assertThat(totalFor("input")).contains(120.0d);
      assertThat(totalFor("output")).contains(34.0d);
    }

    @Test
    void a_failed_chat_records_nothing_at_all() {
      Observation.createNotStarted("gen_ai.client.operation.duration", observations)
          .lowCardinalityKeyValue("error.type", "timeout")
          .start()
          .stop();

      assertThat(meters.find("gen_ai.client.token.usage").summaries()).isEmpty();
    }
  }

  /**
   * The soak's cache question (final review, finding #6). {@code application.yml} asks for {@code
   * prompt-caching} precisely because a round resends the same system prompt every thirty minutes
   * forever — and before this, "did the cache work?" could only be answered by opening individual
   * spans in Tempo, never on a dashboard and never as a trend.
   */
  @Nested
  class The_cache_counts_a_caching_provider_adds {

    @Test
    void become_their_own_series_under_the_same_metric() {
      observe(
          "gen_ai.usage.input_tokens", "1200",
          "gen_ai.usage.output_tokens", "40",
          "gen_ai.usage.cache_read.input_tokens", "1000",
          "gen_ai.usage.cache_write.input_tokens", "150");

      assertThat(totalFor("cache_read")).contains(1000.0d);
      assertThat(totalFor("cache_write")).contains(150.0d);
    }

    /**
     * The property that makes these numbers readable, pinned so nobody "fixes" it later: semconv
     * says both cache counts SHOULD already be included in {@code gen_ai.usage.input_tokens}, so
     * these series are a SUBSET of {@code input}, not additions to it. Summing every {@code
     * gen_ai.token.type} therefore double-counts, which is worth knowing before building a spend
     * panel.
     */
    @Test
    void are_a_subset_of_input_rather_than_an_addition_to_it() {
      observe(
          "gen_ai.usage.input_tokens", "1200",
          "gen_ai.usage.output_tokens", "40",
          "gen_ai.usage.cache_read.input_tokens", "1000");

      assertThat(totalFor("input")).contains(1200.0d);
      assertThat(totalFor("cache_read")).contains(1000.0d);
      // Not 1200 + 1000: the cached tokens are already inside the input count.
      assertThat(totalFor("input").orElseThrow())
          .isGreaterThanOrEqualTo(totalFor("cache_read").orElseThrow());
    }

    @Test
    void are_absent_entirely_when_the_provider_reported_no_cache_usage() {
      observe(
          "gen_ai.usage.input_tokens", "120",
          "gen_ai.usage.output_tokens", "34");

      assertThat(totalFor("input")).isPresent();
      assertThat(totalFor("cache_read")).isEmpty();
      assertThat(totalFor("cache_write")).isEmpty();
    }

    /** A provider that reports one half and not the other is not a reason to record neither. */
    @Test
    void a_read_without_a_write_is_still_recorded() {
      observe(
          "gen_ai.usage.input_tokens", "1200",
          "gen_ai.usage.output_tokens", "40",
          "gen_ai.usage.cache_read.input_tokens", "1000");

      assertThat(totalFor("cache_read")).contains(1000.0d);
      assertThat(totalFor("cache_write")).isEmpty();
    }
  }
}
