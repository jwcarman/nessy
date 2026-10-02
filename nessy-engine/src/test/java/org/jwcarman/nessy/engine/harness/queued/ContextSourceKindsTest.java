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
package org.jwcarman.nessy.engine.harness.queued;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.AmbientSource;
import org.jwcarman.nessy.api.Memory;
import org.jwcarman.nessy.api.MemorySource;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.State;
import org.jwcarman.nessy.api.StateSource;
import org.jwcarman.nessy.engine.schema.VictoolsJsonSchemaGenerator;
import org.jwcarman.nessy.inference.InferenceOptions;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("The queued door refuses two sources of a kind within one stratum")
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ContextSourceKindsTest {

  private static final MemorySource MEMORY = MemorySource.constant(Memory.text("notes", "m"));
  private static final StateSource STATE = StateSource.constant(State.text("notes", "s"));
  private static final AmbientSource AMBIENT = AmbientSource.constant(Ambient.text("notes", "a"));

  private static DefaultQueuedHarnessConfig<String> config() {
    return new DefaultQueuedHarnessConfig<>(
        new AgentType("kinds"),
        new TypeRef<String>() {},
        new DefaultQueuedHarnessConfig.Defaults(ProviderId.of("test"), InferenceOptions.of("m")),
        JsonMapper.builder().build(),
        new VictoolsJsonSchemaGenerator(),
        ObservationRegistry.NOOP);
  }

  @Nested
  @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
  class Within_a_stratum {

    @Test
    void two_memory_sources_of_one_kind_are_refused_when_the_second_is_added() {
      DefaultQueuedHarnessConfig<String> config = config().memory(MEMORY);

      assertThatThrownBy(() -> config.memory(MEMORY))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("two memory sources offer the kind 'notes'");
    }

    @Test
    void two_state_sources_of_one_kind_are_refused_when_the_second_is_added() {
      DefaultQueuedHarnessConfig<String> config = config().state(STATE);

      assertThatThrownBy(() -> config.state(STATE))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("two state sources offer the kind 'notes'");
    }

    @Test
    void two_ambient_sources_of_one_kind_are_refused_when_the_second_is_added() {
      DefaultQueuedHarnessConfig<String> config = config().ambient(AMBIENT);

      assertThatThrownBy(() -> config.ambient(AMBIENT))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("two ambient sources offer the kind 'notes'");
    }
  }

  @Nested
  @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
  class Across_strata {

    @Test
    void the_same_kind_in_three_strata_is_accepted() {
      DefaultQueuedHarnessConfig<String> config = config();

      assertThatCode(() -> config.memory(MEMORY).state(STATE).ambient(AMBIENT))
          .doesNotThrowAnyException();
    }
  }
}
