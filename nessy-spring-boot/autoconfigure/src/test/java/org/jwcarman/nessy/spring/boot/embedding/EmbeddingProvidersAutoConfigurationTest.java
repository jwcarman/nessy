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
package org.jwcarman.nessy.spring.boot.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.embedding.Embedder;
import org.jwcarman.nessy.api.embedding.EmbedderConfig;
import org.jwcarman.nessy.api.embedding.EmbedderFactory;
import org.jwcarman.nessy.api.embedding.Embedding;
import org.jwcarman.nessy.embedding.EmbeddingOptions;
import org.jwcarman.nessy.embedding.EmbeddingProvider;
import org.jwcarman.nessy.embedding.openai.OpenAiEmbeddingProvider;
import org.jwcarman.nessy.engine.observability.ObservedEmbedder;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.spring.boot.NessyProperties;
import org.jwcarman.nessy.spring.boot.inference.InferenceProvidersAutoConfiguration;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * What {@code nessy.embedders.*} -- a vendor key, a prefixed key, an explicit {@code enabled}, a
 * custom id -- registers as {@code EmbeddingProvider} beans, what the factory's default is, and
 * what fails, re-run as {@code ApplicationContextRunner} rows against the named-embedders design
 * record's §11a. Every outcome is decided by something written down.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("The embedders, and what lights them")
class EmbeddingProvidersAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(EmbeddingProvidersAutoConfiguration.class))
          .withBean(ObservationRegistry.class, ObservationRegistry::create);

  private static Map<String, EmbeddingProvider> providers(AssertableApplicationContext context) {
    return context.getBeansOfType(EmbeddingProvider.class);
  }

  private static ResolvedEmbedder resolved(AssertableApplicationContext context, String id) {
    return context.getBean(ResolvedEmbedders.class).embedders().stream()
        .filter(embedder -> embedder.id().equals(id))
        .findFirst()
        .orElseThrow();
  }

  private static EmbedderFactory factory(AssertableApplicationContext context) {
    return context.getBean(EmbedderFactory.class);
  }

  private static SystemEnvironmentPropertySource environment(Map<String, Object> variables) {
    return new SystemEnvironmentPropertySource(
        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables);
  }

  // ---- what lights --------------------------------------------------------------------------

  @Test
  void nothing_configured_registers_nothing_and_the_factory_says_so() {
    runner.run(
        context -> {
          assertThat(providers(context)).isEmpty();
          EmbedderFactory embedders = factory(context);
          Customizer<EmbedderConfig> defaults = c -> {};
          assertThatThrownBy(() -> embedders.create(defaults))
              .isInstanceOf(IllegalStateException.class)
              .hasMessage(
                  "an embedder names no provider and the factory has no default; registered: []");
        });
  }

  @Test
  void an_openai_key_registers_one_embedder_whose_bean_carries_the_suffix() {
    runner
        .withPropertyValues("openai.api-key=sk-test")
        .run(
            context -> {
              assertThat(providers(context)).containsOnlyKeys("openaiEmbeddings");
              assertThat(providers(context).get("openaiEmbeddings").vendor()).isEqualTo("openai");
              assertThat(resolved(context, "openai").wire()).isEqualTo(EmbeddingWire.OPENAI);
            });
  }

  /** §6b: every application with OPENAI_API_KEY and no embedding jar reads one line, once. */
  @Test
  void a_key_whose_embedding_adapter_is_absent_is_skipped_with_one_line(CapturedOutput output) {
    Logger registrar = (Logger) LoggerFactory.getLogger(EmbedderRegistrar.class);
    registrar.setLevel(Level.INFO);
    try {
      runner
          .withClassLoader(new FilteredClassLoader(OpenAiEmbeddingProvider.class))
          .withPropertyValues("openai.api-key=sk-test")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(providers(context)).isEmpty();
              });
      assertThat(output)
          .contains(
              "NESSY EMBEDDING: openai is configured but nessy-embedding-openai is not on the"
                  + " classpath; skipped");
    } finally {
      registrar.setLevel(null);
    }
  }

  @Test
  void three_keys_register_three_embedders_and_none_is_chosen() {
    runner
        .withPropertyValues(
            "openai.api-key=sk-test", "gemini.api-key=g-test", "voyage.api-key=v-test")
        .run(
            context -> {
              assertThat(providers(context))
                  .containsOnlyKeys("openaiEmbeddings", "geminiEmbeddings", "voyageEmbeddings");
              EmbedderFactory embedders = factory(context);
              Customizer<EmbedderConfig> defaults = c -> {};
              assertThatThrownBy(() -> embedders.create(defaults))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessage(
                      "an embedder names no provider and the factory has no default; registered:"
                          + " [openai, gemini, voyage]");
            });
  }

  /** The row that used to make Voyage win: both register, and neither is chosen. */
  @Test
  void openai_and_voyage_keys_register_two() {
    runner
        .withPropertyValues("openai.api-key=sk-test", "voyage.api-key=v-test")
        .run(
            context ->
                assertThat(providers(context))
                    .containsOnlyKeys("openaiEmbeddings", "voyageEmbeddings"));
  }

  @Test
  void both_gemini_keys_register_one_gemini_embedder() {
    runner
        .withPropertyValues("gemini.api-key=g-test", "google.api-key=o-test")
        .run(context -> assertThat(providers(context)).containsOnlyKeys("geminiEmbeddings"));
  }

  @Test
  void the_prefixed_form_of_a_key_registers_the_preset() {
    runner
        .withPropertyValues("nessy.embedders.voyage.api-key=v-test")
        .run(context -> assertThat(providers(context)).containsOnlyKeys("voyageEmbeddings"));
  }

  @Test
  void the_environment_variable_form_of_a_prefixed_key_registers_the_preset() {
    runner
        .withInitializer(
            context ->
                context
                    .getEnvironment()
                    .getPropertySources()
                    .addFirst(environment(Map.of("NESSY_EMBEDDERS_VOYAGE_APIKEY", "v-test"))))
        .run(context -> assertThat(providers(context)).containsOnlyKeys("voyageEmbeddings"));
  }

  /** §6b: Voyage's key has its conventional name, the variable the adapter's fromEnv reads. */
  @Test
  void voyage_s_conventional_environment_variable_registers_voyage() {
    runner
        .withInitializer(
            context ->
                context
                    .getEnvironment()
                    .getPropertySources()
                    .addFirst(environment(Map.of("VOYAGE_API_KEY", "v-test"))))
        .run(context -> assertThat(providers(context)).containsOnlyKeys("voyageEmbeddings"));
  }

  /** No model named anywhere, and nothing fails: a key registers; a store names the model. */
  @Test
  void the_openai_base_url_moves_the_endpoint_and_no_model_is_required() {
    runner
        .withPropertyValues("openai.api-key=lm-studio", "openai.base-url=http://localhost:1234/v1")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(resolved(context, "openai").baseUrl())
                  .isEqualTo("http://localhost:1234/v1");
            });
  }

  /** §6c made checkable: lmstudio is not an embedding preset. */
  @Test
  void lmstudio_is_not_a_preset_and_turning_it_on_fails_naming_the_wire() {
    runner
        .withPropertyValues("nessy.embedders.lmstudio.enabled=true")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining(
                      "nessy.embedders.lmstudio.wire is required: lmstudio is not a preset");
            });
  }

  @Test
  void a_custom_embedder_at_a_local_server_registers_with_its_own_vendor() {
    runner
        .withPropertyValues(
            "nessy.embedders.local.wire=openai",
            "nessy.embedders.local.base-url=http://localhost:1234/v1",
            "nessy.embedders.local.api-key=lm-studio",
            "nessy.embedders.local.vendor=lmstudio")
        .run(
            context -> {
              assertThat(providers(context)).containsOnlyKeys("localEmbeddings");
              Embedder embedder =
                  factory(context)
                      .create(
                          c -> c.provider("local").model("text-embedding-nomic-embed-text-v1.5"));
              assertThat(embedder.vendor()).isEqualTo("lmstudio");
            });
  }

  @Test
  void a_custom_embedder_s_vendor_defaults_to_the_wire_s() {
    runner
        .withPropertyValues(
            "nessy.embedders.mine.wire=openai",
            "nessy.embedders.mine.base-url=https://g/v1",
            "nessy.embedders.mine.api-key=k")
        .run(
            context -> {
              assertThat(providers(context)).containsOnlyKeys("mineEmbeddings");
              assertThat(providers(context).get("mineEmbeddings").vendor()).isEqualTo("openai");
            });
  }

  /** §6h left this binder fact open; this pins it. */
  @Test
  void a_blank_enabled_binds_as_unset() {
    runner
        .withPropertyValues("gemini.api-key=g-test", "nessy.embedders.gemini.enabled=")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(providers(context)).containsOnlyKeys("geminiEmbeddings");
            });
  }

  @Test
  void a_gemini_key_set_for_chat_can_leave_embeddings_off() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                InferenceProvidersAutoConfiguration.class,
                EmbeddingProvidersAutoConfiguration.class))
        .withBean(ObservationRegistry.class, ObservationRegistry::create)
        .withPropertyValues("gemini.api-key=g-test", "nessy.embedders.gemini.enabled=false")
        .run(
            context -> {
              assertThat(context.getBeansOfType(InferenceProvider.class))
                  .containsOnlyKeys("gemini");
              assertThat(providers(context)).isEmpty();
            });
  }

  /** §6f: one key, two registries, one bean namespace, and no collision. */
  @Test
  void one_openai_key_lights_an_inference_provider_and_an_embedder_side_by_side() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                InferenceProvidersAutoConfiguration.class,
                EmbeddingProvidersAutoConfiguration.class))
        .withBean(ObservationRegistry.class, ObservationRegistry::create)
        .withPropertyValues("openai.api-key=sk-test")
        .run(
            context -> {
              assertThat(context.getBeansOfType(InferenceProvider.class))
                  .containsOnlyKeys("openai");
              assertThat(providers(context)).containsOnlyKeys("openaiEmbeddings");
            });
  }

  @Test
  void a_custom_embedder_with_no_wire_fails_naming_it() {
    runner
        .withPropertyValues("nessy.embedders.mine.base-url=https://g/v1")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("nessy.embedders.mine.wire is required");
            });
  }

  /** Plan ruling 15: Boot's failure analyzer prints the allowed values at a real startup. */
  @Test
  void a_bogus_wire_value_fails_naming_the_property() {
    runner
        .withPropertyValues(
            "nessy.embedders.mine.wire=bogus",
            "nessy.embedders.mine.base-url=https://g/v1",
            "nessy.embedders.mine.api-key=k")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("nessy.embedders.mine.wire");
            });
  }

  /** §4c made checkable: the OpenAI embeddings wire is openai, not openai-embeddings. */
  @Test
  void the_wire_value_openai_embeddings_does_not_exist() {
    runner
        .withPropertyValues(
            "nessy.embedders.mine.wire=openai-embeddings",
            "nessy.embedders.mine.base-url=https://g/v1",
            "nessy.embedders.mine.api-key=k")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("nessy.embedders.mine.wire");
            });
  }

  // ---- application beans ---------------------------------------------------------------------

  @Test
  void an_application_bean_joins_under_its_bean_name_beside_a_preset() {
    runner
        .withUserConfiguration(AScriptedEmbedder.class)
        .withPropertyValues("openai.api-key=sk-test")
        .run(
            context -> {
              assertThat(providers(context)).containsOnlyKeys("scripted", "openaiEmbeddings");
              factory(context).create(c -> c.provider("scripted").model("m")).embedDocument("x");
              assertThat(AScriptedEmbedder.INSTANCE.asked.get().modelName()).isEqualTo("m");
            });
  }

  @Test
  void an_application_bean_named_like_a_preset_s_bean_fails_naming_both() {
    runner
        .withUserConfiguration(AnOpenAiEmbeddingsBean.class)
        .withPropertyValues("openai.api-key=sk-test")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining(
                      "a bean named 'openaiEmbeddings' and the openai embedder would both be"
                          + " registered as 'openaiEmbeddings'");
            });
  }

  /** Review Focus 2: two registrations under one registry id is a failure, never a choice. */
  @Test
  void an_application_bean_named_like_a_lit_preset_s_id_fails_naming_the_id() {
    runner
        .withUserConfiguration(AVoyageBean.class)
        .withPropertyValues("voyage.api-key=v-test")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("embedding provider 'voyage' is already registered");
            });
  }

  @Test
  void an_application_embedder_factory_backs_the_starter_s_off_and_the_presets_stay() {
    EmbedderFactory ours =
        customizer -> {
          throw new UnsupportedOperationException("ours makes nothing");
        };
    runner
        .withBean(EmbedderFactory.class, () -> ours)
        .withPropertyValues("openai.api-key=sk-test")
        .run(
            context -> {
              assertThat(context).hasSingleBean(EmbedderFactory.class);
              assertThat(context.getBean(EmbedderFactory.class)).isSameAs(ours);
              assertThat(providers(context)).containsOnlyKeys("openaiEmbeddings");
            });
  }

  @Test
  void an_application_bean_whose_name_breaks_the_provider_id_rule_fails_naming_the_bean() {
    String longName = "x".repeat(65);
    runner
        .withBean(longName, EmbeddingProvider.class, Scripted::new)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("the EmbeddingProvider bean '" + longName + "'");
            });
  }

  /** One bean namespace, two registries: an inference provider may not squat an embedder's bean. */
  @Test
  void an_inference_provider_named_like_an_embedder_s_bean_fails_naming_the_bean() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                InferenceProvidersAutoConfiguration.class,
                EmbeddingProvidersAutoConfiguration.class))
        .withBean(ObservationRegistry.class, ObservationRegistry::create)
        .withPropertyValues(
            "nessy.providers.fooEmbeddings.wire=openai-chat",
            "nessy.providers.fooEmbeddings.base-url=https://g/v1",
            "nessy.providers.fooEmbeddings.api-key=k",
            "nessy.embedders.foo.wire=openai",
            "nessy.embedders.foo.base-url=https://g/v1",
            "nessy.embedders.foo.api-key=k")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure()).hasStackTraceContaining("'fooEmbeddings'");
            });
  }

  // ---- the default ---------------------------------------------------------------------------

  @Test
  void the_pair_names_the_default_a_store_that_says_nothing_gets() {
    runner
        .withPropertyValues(
            "voyage.api-key=v-test", "nessy.embedder=voyage", "nessy.embedding-model=voyage-3.5")
        .run(
            context -> {
              Embedder embedder = factory(context).create(c -> {});
              assertThat(embedder.model()).isEqualTo("voyage-3.5");
              assertThat(embedder.vendor()).isEqualTo("voyage");
            });
  }

  @Test
  void an_embedder_alone_fails_naming_the_pair() {
    runner
        .withPropertyValues("voyage.api-key=v-test", "nessy.embedder=voyage")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("nessy.embedder and nessy.embedding-model are a pair");
            });
  }

  @Test
  void an_embedding_model_alone_fails_naming_the_pair() {
    runner
        .withPropertyValues("voyage.api-key=v-test", "nessy.embedding-model=voyage-3.5")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("nessy.embedder and nessy.embedding-model are a pair");
            });
  }

  /** Review Focus 3: chat-web's own shape, with neither variable set. */
  @Test
  void a_blank_pair_is_no_default() {
    runner
        .withPropertyValues("openai.api-key=sk-test", "nessy.embedder=", "nessy.embedding-model=")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(NessyProperties.class).embedder()).isNull();
            });
  }

  /** Review Focus 3. */
  @Test
  void a_blank_embedder_beside_a_model_fails_naming_the_pair() {
    runner
        .withPropertyValues("nessy.embedder=", "nessy.embedding-model=voyage-3.5")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("nessy.embedder and nessy.embedding-model are a pair");
            });
  }

  /** Plan ruling 5: at startup, not at the first store that asks. */
  @Test
  void a_default_naming_an_unregistered_embedder_fails_at_startup_listing_what_is() {
    runner
        .withPropertyValues(
            "openai.api-key=sk-test", "nessy.embedder=cohere", "nessy.embedding-model=embed-v4")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining(
                      "an embedder names provider 'cohere', which is not registered; registered:"
                          + " [openai]");
            });
  }

  @Test
  void the_default_width_is_the_width_a_default_embedder_asks_for() {
    runner
        .withPropertyValues(
            "voyage.api-key=v-test",
            "nessy.embedder=voyage",
            "nessy.embedding-model=voyage-3.5",
            "nessy.embedding-dimension=256")
        .run(context -> assertThat(factory(context).create(c -> {}).dimension()).isEqualTo(256));
  }

  @Test
  void a_width_without_the_pair_fails() {
    runner
        .withPropertyValues("voyage.api-key=v-test", "nessy.embedding-dimension=256")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("nessy.embedding-dimension");
            });
  }

  @Test
  void a_zero_width_beside_the_pair_fails_naming_the_property() {
    runner
        .withPropertyValues(
            "voyage.api-key=v-test",
            "nessy.embedder=voyage",
            "nessy.embedding-model=voyage-3.5",
            "nessy.embedding-dimension=0")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("nessy.embedding-dimension must be positive: 0");
            });
  }

  // ---- vendor properties ---------------------------------------------------------------------

  /** §7a: a dotted key under properties binds as one entry, as it does for inference. */
  @Test
  void a_dotted_property_binds_as_one_entry() {
    runner
        .withPropertyValues(
            "voyage.api-key=v-test", "nessy.embedders.voyage.properties.voyage.truncation=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(resolved(context, "voyage").properties())
                  .containsExactly(Map.entry("voyage.truncation", "false"));
            });
  }

  @Test
  void another_adapter_s_property_on_an_embedder_fails_at_startup_naming_the_prefix() {
    runner
        .withPropertyValues(
            "voyage.api-key=v-test", "nessy.embedders.voyage.properties.openai.user=x")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("'openai.user'")
                  .hasStackTraceContaining("'voyage.'");
            });
  }

  // ---- observed ------------------------------------------------------------------------------

  @Test
  void with_a_registry_every_embedder_is_observed() {
    runner
        .withPropertyValues("openai.api-key=sk-test")
        .run(
            context ->
                assertThat(
                        factory(context)
                            .create(c -> c.provider("openai").model("text-embedding-3-small")))
                    .isInstanceOf(ObservedEmbedder.class));
  }

  @Test
  void without_one_every_embedder_is_still_observed() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(EmbeddingProvidersAutoConfiguration.class))
        .withPropertyValues("openai.api-key=sk-test")
        .run(
            context ->
                assertThat(
                        factory(context)
                            .create(c -> c.provider("openai").model("text-embedding-3-small")))
                    .isInstanceOf(ObservedEmbedder.class));
  }

  // ---- fixtures ------------------------------------------------------------------------------

  /** Answers two-wide vectors and remembers what it was asked. */
  static final class Scripted implements EmbeddingProvider {
    final AtomicReference<EmbeddingOptions> asked = new AtomicReference<>();

    @Override
    public List<Embedding> embedDocuments(List<String> texts, EmbeddingOptions options) {
      asked.set(options);
      return texts.stream()
          .map(text -> new Embedding(options.modelName(), new float[] {1f, 0f}))
          .toList();
    }

    @Override
    public Embedding embedQuery(String query, EmbeddingOptions options) {
      asked.set(options);
      return new Embedding(options.modelName(), new float[] {1f, 0f});
    }

    @Override
    public String vendor() {
      return "scripted";
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class AScriptedEmbedder {

    static final Scripted INSTANCE = new Scripted();

    @Bean
    EmbeddingProvider scripted() {
      return INSTANCE;
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class AnOpenAiEmbeddingsBean {

    @Bean
    EmbeddingProvider openaiEmbeddings() {
      return new Scripted();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class AVoyageBean {

    @Bean
    EmbeddingProvider voyage() {
      return new Scripted();
    }
  }
}
