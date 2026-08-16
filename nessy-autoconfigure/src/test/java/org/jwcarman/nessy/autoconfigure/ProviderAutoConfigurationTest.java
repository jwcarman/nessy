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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.model.anthropic.AnthropicModelProvider;
import org.jwcarman.nessy.model.gemini.GeminiModelProvider;
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
                      "nessy.provider=anthorpic is not a recognized value; expected anthropic,"
                          + " openai, gemini, or bedrock");
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
                      "nessy.provider=anthorpic is not a recognized value; expected anthropic,"
                          + " openai, gemini, or bedrock");
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
                      "nessy.provider=anthorpic is not a recognized value; expected anthropic,"
                          + " openai, gemini, or bedrock");
            });
  }

  /**
   * The three-way cases the provider-expansion design adds: {@link GeminiProviderAutoConfiguration}
   * registered alongside its two siblings, and every new ambiguity combination Gemini's presence
   * makes possible.
   */
  @Nested
  class Three_provider_scenarios {

    private final ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AnthropicProviderAutoConfiguration.class,
                    OpenAiProviderAutoConfiguration.class,
                    GeminiProviderAutoConfiguration.class));

    @Test
    void gemini_keyed_alone_yields_a_gemini_provider_even_with_all_three_jars_present() {
      runner
          .withPropertyValues("nessy.gemini.api-key=test-key")
          .run(
              context -> {
                assertThat(context).hasSingleBean(ModelProvider.class);
                assertThat(context)
                    .getBean(ModelProvider.class)
                    .isInstanceOf(GeminiModelProvider.class);
              });
    }

    @Test
    void nessy_provider_property_selects_gemini_among_three_present_jars() {
      runner
          .withPropertyValues(
              "nessy.provider=gemini",
              "nessy.anthropic.api-key=k",
              "nessy.openai.api-key=k",
              "nessy.gemini.api-key=k")
          .run(
              context ->
                  assertThat(context.getBean(ModelProvider.class))
                      .isInstanceOf(GeminiModelProvider.class));
    }

    @Test
    void gemini_absent_means_no_gemini_bean() {
      runner
          .withClassLoader(new FilteredClassLoader(GeminiModelProvider.class))
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
    void a_user_declared_harness_bean_stops_gemini_from_ever_being_built_too() {
      Harness harness =
          Nessy.harness(ScriptedModelProvider.builder().text("hi").endTurn().build()).build();
      runner
          .withBean("mine", Harness.class, () -> harness)
          .withPropertyValues("nessy.gemini.api-key=test-key")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(ModelProvider.class);
              });
    }

    @Test
    void anthropic_and_gemini_ambiguous_with_openai_absent_fails_fast_naming_both() {
      runner
          .withClassLoader(new FilteredClassLoader(OpenAiModelProvider.class))
          .withPropertyValues("nessy.anthropic.api-key=k", "nessy.gemini.api-key=k")
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseMessage(
                        "two model-provider modules are on the classpath; set"
                            + " nessy.provider=anthropic|gemini");
              });
    }

    @Test
    void openai_and_gemini_ambiguous_with_anthropic_absent_fails_fast_naming_both() {
      runner
          .withClassLoader(new FilteredClassLoader(AnthropicModelProvider.class))
          .withPropertyValues("nessy.openai.api-key=k", "nessy.gemini.api-key=k")
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseMessage(
                        "two model-provider modules are on the classpath; set"
                            + " nessy.provider=openai|gemini");
              });
    }

    @Test
    void all_three_keyed_fails_fast_naming_all_three() {
      runner
          .withPropertyValues(
              "nessy.anthropic.api-key=k", "nessy.openai.api-key=k", "nessy.gemini.api-key=k")
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseMessage(
                        "three model-provider modules are on the classpath; set"
                            + " nessy.provider=anthropic|openai|gemini");
              });
    }

    @Test
    void nessy_provider_property_still_resolves_a_three_way_ambiguity() {
      runner
          .withPropertyValues(
              "nessy.provider=gemini",
              "nessy.anthropic.api-key=k",
              "nessy.openai.api-key=k",
              "nessy.gemini.api-key=k")
          .run(
              context ->
                  assertThat(context.getBean(ModelProvider.class))
                      .isInstanceOf(GeminiModelProvider.class));
    }

    @Test
    void an_unrecognized_provider_value_fails_fast_with_only_the_gemini_jar_present() {
      runner
          .withClassLoader(
              new FilteredClassLoader(AnthropicModelProvider.class, OpenAiModelProvider.class))
          .withPropertyValues("nessy.provider=anthorpic")
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseMessage(
                        "nessy.provider=anthorpic is not a recognized value; expected anthropic,"
                            + " openai, gemini, or bedrock");
              });
    }

    @Test
    void gemini_is_a_recognized_provider_value_not_an_invalid_one() {
      // Guards against a regression where InvalidProviderCondition's "recognized" list forgets
      // gemini: if it did, this would fail fast instead of building a GeminiModelProvider.
      runner
          .withPropertyValues("nessy.provider=gemini", "nessy.gemini.api-key=k")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(ModelProvider.class))
                    .isInstanceOf(GeminiModelProvider.class);
              });
    }
  }

  /**
   * The four-way cases {@link BedrockProviderAutoConfiguration} adds: explicit-selection-only, no
   * key of its own, never counted by {@link
   * AnthropicProviderAutoConfiguration.AmbiguousProviderCondition}. Every test here that reaches
   * {@link BedrockProviderAutoConfiguration#bedrockModelProvider()} itself is guarded by {@code
   * assumeTrue} exactly like {@code BedrockModelProviderTest}'s own {@code fromEnv()} tests: that
   * bean calls {@code BedrockModelProvider.builder().fromEnv().build()}, which reads the real
   * process environment for {@code AWS_REGION}/{@code AWS_DEFAULT_REGION} and fails without either
   * — so what's proven offline is that the explicit choice reaches that call at all (never a
   * different provider, never silently ignored), not the build's own success.
   */
  @Nested
  class Four_provider_scenarios {

    private final ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AnthropicProviderAutoConfiguration.class,
                    OpenAiProviderAutoConfiguration.class,
                    GeminiProviderAutoConfiguration.class,
                    BedrockProviderAutoConfiguration.class));

    @Test
    void explicit_bedrock_choice_reaches_the_provider_builder_ahead_of_every_keyed_jar() {
      assumeTrue(System.getenv("AWS_REGION") == null, "AWS_REGION is set in this shell");
      assumeTrue(
          System.getenv("AWS_DEFAULT_REGION") == null, "AWS_DEFAULT_REGION is set in this shell");
      runner
          .withPropertyValues(
              "nessy.provider=bedrock",
              "nessy.anthropic.api-key=k",
              "nessy.openai.api-key=k",
              "nessy.gemini.api-key=k")
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseMessage(
                        "AWS_REGION (or AWS_DEFAULT_REGION) environment variable is not set; call"
                            + " region(...) or fromEnv(), or provide a preconfigured client via"
                            + " client(...)");
              });
    }

    @Test
    void bedrock_is_never_selected_by_classpath_presence_alone() {
      // No nessy.provider set at all: unlike Anthropic/OpenAI/Gemini's own "sole module present"
      // fallback, Bedrock's own selection condition has no such arm (design §4) — so with none of
      // the four keyed, no ModelProvider bean is built here, the same as if this whole
      // configuration set were absent.
      runner.run(
          context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ModelProvider.class);
          });
    }

    @Test
    void an_unrecognized_provider_value_fails_fast_with_only_the_bedrock_jar_present() {
      runner
          .withClassLoader(
              new FilteredClassLoader(
                  AnthropicModelProvider.class,
                  OpenAiModelProvider.class,
                  GeminiModelProvider.class))
          .withPropertyValues("nessy.provider=anthorpic")
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseMessage(
                        "nessy.provider=anthorpic is not a recognized value; expected anthropic,"
                            + " openai, gemini, or bedrock");
              });
    }

    @Test
    void bedrock_is_a_recognized_provider_value_not_an_invalid_one() {
      // Guards against a regression where InvalidProviderCondition's "recognized" list forgets
      // bedrock: if it did, this would fail fast naming an unrecognized value instead of reaching
      // BedrockModelProvider.Builder#fromEnv()'s own missing-region failure.
      assumeTrue(System.getenv("AWS_REGION") == null, "AWS_REGION is set in this shell");
      assumeTrue(
          System.getenv("AWS_DEFAULT_REGION") == null, "AWS_DEFAULT_REGION is set in this shell");
      runner
          .withPropertyValues("nessy.provider=bedrock")
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("AWS_REGION")
                    .hasMessageNotContaining("is not a recognized value");
              });
    }
  }
}
