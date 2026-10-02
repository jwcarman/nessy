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
package org.jwcarman.nessy.engine.harness.direct;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.ChapterPolicy;
import org.jwcarman.nessy.api.ContextConfig;
import org.jwcarman.nessy.api.Summarizer;

@DisplayName("The direct door refuses chapter settings that cannot work")
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ChapterSettingsValidationTest {

  private static ContextConfig context() {
    return new DefaultDirectHarnessConfig<String>(
            new AgentType("settings"), ObservationRegistry.NOOP)
        .inference();
  }

  @Test
  void a_chapter_of_no_turns_is_refused() {
    ContextConfig context = context();

    assertThatThrownBy(() -> context.maxChapterLength(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one turn");
  }

  @Test
  void a_zero_lease_time_is_refused() {
    ContextConfig context = context();

    assertThatThrownBy(() -> context.chapterLeaseTtl(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be positive");
  }

  @Test
  void a_negative_lease_time_is_refused() {
    ContextConfig context = context();

    assertThatThrownBy(() -> context.chapterLeaseTtl(Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be positive");
  }

  @Test
  void a_null_lease_time_is_refused() {
    ContextConfig context = context();

    assertThatThrownBy(() -> context.chapterLeaseTtl(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("ttl must not be null");
  }

  @Test
  void a_null_policy_is_refused() {
    ContextConfig context = context();
    ChapterPolicy none = null;

    assertThatThrownBy(() -> context.chapterPolicy(none))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("policy must not be null");
  }

  @Test
  void a_null_summariser_is_refused() {
    ContextConfig context = context();
    Summarizer none = null;

    assertThatThrownBy(() -> context.summarizer(none))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("summarizer must not be null");
  }
}
