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

import io.micrometer.observation.ObservationRegistry;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.HarnessBuilder;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Pins the shipped cadence (spec §6): a plain {@code @SpringBootTest} with no cadence override, so
 * {@code @Scheduled} registers against application.yaml's real {@code 0 * * * * *}. A bad shipped
 * cron string fails at context refresh with a {@code BeanCreationException} — this test would catch
 * that regression, which no other test in this module would, since {@code NightWatchmanSmokeTest}
 * disables the cron with Spring's own sentinel.
 */
@SpringBootTest
class NightWatchmanSchedulingTest {

  @Autowired private Watchman watchman;

  @Test
  void the_context_boots_with_the_shipped_cadence() {
    // A null here (or a failed context refresh before we even get this far) means the shipped
    // cron string in application.yaml no longer parses as a valid 6-field Spring cron.
    assertThat(watchman).isNotNull();
  }

  /**
   * Self-contained harness over its own scripted provider, in-memory end to end; wins over the
   * starter's own by {@code @ConditionalOnMissingBean(Harness.class)}, which also keeps the real
   * Anthropic provider from ever being constructed — no key, no network.
   */
  @TestConfiguration
  static class WatchmanTestConfig {

    @Bean
    Harness harness(ObjectProvider<ObservationRegistry> observations) {
      HarnessBuilder builder = Nessy.harness(new AllQuietProvider());
      observations.ifAvailable(builder::observations);
      return builder.build();
    }
  }

  /**
   * Every call answers "All quiet." — a round that happens to fire during the test run is harmless,
   * and nothing here asserts call counts.
   */
  private static final class AllQuietProvider implements ModelProvider {

    @Override
    public Set<Capability> capabilities() {
      return Set.of();
    }

    @Override
    public ModelStream stream(ModelRequest request) {
      List<ModelEvent> turn =
          List.of(
              new ModelEvent.TextChunk("All quiet."),
              new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()));
      Iterator<ModelEvent> events = turn.iterator();
      return new ModelStream() {
        @Override
        public Iterator<ModelEvent> iterator() {
          return events;
        }

        @Override
        public void close() {
          // scripted stream holds no resources to release
        }
      };
    }
  }
}
