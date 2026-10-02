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
package org.jwcarman.nessy.engine.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CacheWatchTest {

  private static final AgentType TYPE = new AgentType("support");
  private static final AgentId AGENT = AgentId.random();

  private final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();
  private final ObservationRegistry registry = ObservationRegistry.create();
  private final CacheWatch watch = new CacheWatch(registry);

  CacheWatchTest() {
    registry
        .observationConfig()
        .observationHandler(
            new ObservationHandler<>() {
              @Override
              public void onStop(Observation.Context context) {
                stopped.add(context);
              }

              @Override
              public boolean supportsContext(Observation.Context context) {
                return true;
              }
            });
  }

  private static Usage reading(int cached) {
    return Usage.of("model", 10, 10).withCacheRead(cached);
  }

  private static Usage silent() {
    return Usage.of("model", 10, 10);
  }

  private void saw(AgentId agent, long turn, Usage usage) {
    watch.saw(TYPE, agent, TurnId.of(turn), usage);
  }

  @Nested
  class Inside_one_turn {

    @Test
    void a_fall_is_reported_once_with_the_agent_type_as_a_low_cardinality_key() {
      saw(AGENT, 1, reading(100));
      saw(AGENT, 1, reading(40));

      assertThat(stopped).hasSize(1);
      Observation.Context fell = stopped.getFirst();
      assertThat(fell.getName()).isEqualTo("nessy.cache.read.fell");
      assertThat(fell.getLowCardinalityKeyValues())
          .anyMatch(
              kv -> kv.getKey().equals("gen_ai.agent.name") && kv.getValue().equals("support"));
    }

    @Test
    void a_rise_is_not_reported() {
      saw(AGENT, 1, reading(40));
      saw(AGENT, 1, reading(100));

      assertThat(stopped).isEmpty();
    }

    @Test
    void an_unreported_count_is_ignored_and_does_not_replace_the_remembered_one() {
      saw(AGENT, 1, reading(100));
      saw(AGENT, 1, silent());
      assertThat(stopped).isEmpty();

      saw(AGENT, 1, reading(50));

      assertThat(stopped).hasSize(1);
    }
  }

  @Nested
  class Across_turns_and_agents {

    @Test
    void a_new_turn_reading_less_than_the_last_turn_is_not_reported() {
      saw(AGENT, 1, reading(100));
      saw(AGENT, 2, reading(10));

      assertThat(stopped).isEmpty();
    }

    @Test
    void a_new_turn_remembers_its_own_first_count() {
      saw(AGENT, 1, reading(100));
      saw(AGENT, 2, reading(10));
      saw(AGENT, 2, reading(5));

      assertThat(stopped).hasSize(1);
    }

    @Test
    void two_agents_do_not_affect_each_other() {
      AgentId other = AgentId.random();
      saw(AGENT, 1, reading(100));
      saw(other, 1, reading(10));
      saw(other, 1, reading(20));

      assertThat(stopped).isEmpty();
    }
  }

  @Nested
  class Memory_of_agents {

    @Test
    void is_bounded_so_the_least_recently_used_agent_is_forgotten() {
      saw(AGENT, 1, reading(100));
      for (int i = 0; i < 10_000; i++) {
        saw(AgentId.random(), 1, reading(1));
      }

      saw(AGENT, 1, reading(50));

      assertThat(stopped).isEmpty();
    }

    @Test
    void keeps_an_agent_that_is_still_in_use() {
      saw(AGENT, 1, reading(100));
      for (int i = 0; i < 9_999; i++) {
        saw(AgentId.random(), 1, reading(1));
      }

      saw(AGENT, 1, reading(50));

      assertThat(stopped).hasSize(1);
    }
  }
}
