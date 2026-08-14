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
package org.jwcarman.nessy.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.model.anthropic.AnthropicModelProvider;
import org.jwcarman.nessy.model.openai.OpenAiModelProvider;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.testing.ScriptedModelProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProviderAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  AnthropicProviderAutoConfiguration.class, OpenAiProviderAutoConfiguration.class));

  @Test
  void anthropic_on_the_classpath_with_a_key_yields_a_provider() {
    runner
        .withPropertyValues("nessy.anthropic.api-key=test-key")
        .run(context -> assertThat(context).hasSingleBean(ModelProvider.class));
  }

  @Test
  void openai_keyed_alone_yields_an_openai_provider_even_with_both_jars_present() {
    runner
        .withPropertyValues("nessy.openai.api-key=test-key")
        .run(
            context -> {
              assertThat(context).hasSingleBean(ModelProvider.class);
              assertThat(context)
                  .getBean(ModelProvider.class)
                  .isInstanceOf(OpenAiModelProvider.class);
            });
  }

  @Test
  void anthropic_absent_means_no_anthropic_bean() {
    runner
        .withClassLoader(new FilteredClassLoader(AnthropicModelProvider.class))
        .withPropertyValues("nessy.openai.api-key=test-key")
        .run(
            context -> {
              assertThat(context).hasSingleBean(ModelProvider.class);
              assertThat(context)
                  .getBean(ModelProvider.class)
                  .isInstanceOf(OpenAiModelProvider.class);
            });
  }

  @Test
  void a_user_declared_provider_bean_always_wins() {
    var scripted = ScriptedModelProvider.builder().text("hi").endTurn().build();
    runner
        .withPropertyValues("nessy.anthropic.api-key=test-key")
        .withBean("mine", ModelProvider.class, () -> scripted)
        .run(
            context ->
                assertThat(context.getBean(ModelProvider.class)).isSameAs(context.getBean("mine")));
  }

  @Test
  void both_provider_jars_without_a_choice_fail_fast_naming_the_property() {
    runner
        .withPropertyValues("nessy.anthropic.api-key=k", "nessy.openai.api-key=k")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseMessage(
                      "two model-provider modules"
                          + " are on the classpath; set nessy.provider=anthropic|openai");
            });
  }

  @Test
  void a_user_declared_harness_bean_stops_either_provider_from_ever_being_built() {
    Harness harness =
        Nessy.harness(ScriptedModelProvider.builder().text("hi").endTurn().build()).build();
    runner
        .withBean("mine", Harness.class, () -> harness)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(ModelProvider.class);
            });
  }

  @Test
  void nessy_provider_property_selects_between_two_present_jars() {
    runner
        .withPropertyValues(
            "nessy.provider=openai", "nessy.anthropic.api-key=k", "nessy.openai.api-key=k")
        .run(
            context ->
                assertThat(context.getBean(ModelProvider.class))
                    .isInstanceOf(OpenAiModelProvider.class));
  }

  @Test
  void an_unrecognized_provider_value_fails_fast_naming_the_property_with_both_jars_present() {
    runner
        .withPropertyValues("nessy.provider=anthorpic")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseMessage(
                      "nessy.provider=anthorpic is not a recognized value; expected anthropic or"
                          + " openai");
            });
  }

  @Test
  void an_unrecognized_provider_value_fails_fast_with_only_the_anthropic_jar_present() {
    runner
        .withClassLoader(new FilteredClassLoader(OpenAiModelProvider.class))
        .withPropertyValues("nessy.provider=anthorpic")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseMessage(
                      "nessy.provider=anthorpic is not a recognized value; expected anthropic or"
                          + " openai");
            });
  }

  @Test
  void an_unrecognized_provider_value_fails_fast_with_only_the_openai_jar_present() {
    runner
        .withClassLoader(new FilteredClassLoader(AnthropicModelProvider.class))
        .withPropertyValues("nessy.provider=anthorpic")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseMessage(
                      "nessy.provider=anthorpic is not a recognized value; expected anthropic or"
                          + " openai");
            });
  }
}
