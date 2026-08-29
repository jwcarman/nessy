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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.TextBlock;

/** The factory door, exercised without an actor system: what a customizer can and cannot say. */
class HarnessFactoryTest {

  /**
   * Runs the customizer against a fresh config and builds nothing — the door is what is under test,
   * not what it eventually spawns.
   *
   * <p>A test reads the config back by <b>capturing it in its own customizer</b> rather than asking
   * the factory for it afterwards. That keeps the observation type on the customizer's own
   * signature, so nothing here has to cast a wildcard back to {@code HarnessConfig<String>}.
   */
  private static final class ConfigOnlyFactory implements HarnessFactory {
    @Override
    public <O> Harness<O> create(Class<O> observationType, HarnessCustomizer<O> customizer) {
      customizer.customize(new HarnessConfig<>());
      return null;
    }
  }

  private static HarnessConfig<String> configFrom(HarnessCustomizer<String> customizer) {
    AtomicReference<HarnessConfig<String>> seen = new AtomicReference<>();
    new ConfigOnlyFactory()
        .create(
            config -> {
              customizer.customize(config);
              seen.set(config);
            });
    return seen.get();
  }

  @Nested
  class The_text_default {

    @Test
    void renders_a_string_observation_without_the_caller_saying_how() {
      HarnessConfig<String> config = configFrom(unused -> {});

      List<ContentBlock> rendered = config.renderer().render("the disk is full");

      assertThat(rendered).containsExactly(new TextBlock("the disk is full"));
    }

    @Test
    void is_applied_before_the_customizer_so_a_caller_can_still_override_it() {
      HarnessConfig<String> config =
          configFrom(c -> c.renderer(text -> List.of(new TextBlock("SHOUTED: " + text))));

      List<ContentBlock> rendered = config.renderer().render("hello");

      assertThat(rendered).containsExactly(new TextBlock("SHOUTED: hello"));
    }
  }

  @Nested
  class The_config {

    @Test
    void carries_defaults_a_caller_never_has_to_state() {
      HarnessConfig<String> config = configFrom(unused -> {});

      assertThat(config.type().name()).isEqualTo("agent");
      assertThat(config.modelName()).isEmpty();
      assertThat(config.approvalTerm()).isEqualTo(Duration.ofDays(3));
      assertThat(config.backlogCapacity()).isEqualTo(1024);
    }

    @Test
    void takes_a_model_by_name_rather_than_a_model() {
      HarnessConfig<String> config = configFrom(c -> c.modelName("qwen3-coder-30b"));

      assertThat(config.modelName()).contains("qwen3-coder-30b");
    }

    @Test
    void coalesces_nothing_until_told_to() {
      HarnessConfig<String> config = configFrom(unused -> {});

      assertThat(config.coalescer()).isNotNull();
    }

    @Test
    void refuses_a_backlog_that_can_hold_nothing() {
      HarnessConfig<String> config = new HarnessConfig<>();

      assertThatThrownBy(() -> config.backlogCapacity(0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("at least 1");
    }
  }
}
