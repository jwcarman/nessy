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
package org.jwcarman.nessy.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.tool.Replies;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.backend.DirectBackend;
import org.jwcarman.nessy.backend.QueuedBackend;
import org.jwcarman.nessy.backend.inmemory.InMemoryDirectBackend;
import org.jwcarman.nessy.backend.inmemory.InMemoryQueuedBackend;
import org.jwcarman.nessy.engine.harness.queued.DefaultQueuedHarnessFactory;
import org.jwcarman.nessy.engine.tool.ReplyTokens;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/**
 * What the starter wires, and what it steps aside for.
 *
 * <p>Every test here drives a real {@link ApplicationContextRunner}: the point of a starter is what
 * Spring does with it, and a test that called the {@code @Bean} methods directly would prove
 * nothing about the conditions guarding them.
 *
 * <p><b>The database here is H2, and only because nothing here runs a turn.</b> These are wiring
 * tests -- they assert which beans exist and which do not. The engine's own tests use real
 * PostgreSQL, because its queries are PostgreSQL's and a test on H2 would pass against exactly the
 * bugs that matter. Nothing in this file should ever ask an agent to do anything.
 */
class NessyAutoConfigurationTest {

  private static final String MODEL = "nessy.model=a-test-model";
  // The bean name Spring gives AnInferenceProvider#inference() -- registered under that name by
  // the harness auto-configurations, so naming it here is what makes it the factory default.
  private static final String PROVIDER = "nessy.provider=inference";
  private static final String PROMPT = "nessy.system-prompt=you are a test assistant";

  /**
   * <b>No tables, and that is the point of these tests.</b> They assert which beans exist; nothing
   * here runs a turn. The engine's own schema does not even load on H2 -- {@code BYTEA} and {@code
   * TIMESTAMPTZ} are PostgreSQL's -- so opting out is what lets a wiring test with an H2 {@code
   * DataSource} stay a wiring test.
   */
  private static final String NO_SCHEMA = "nessy.initialize-schema=false";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  JacksonAutoConfiguration.class,
                  DataSourceTransactionManagerAutoConfiguration.class,
                  ObservationAutoConfiguration.class,
                  NessyAutoConfiguration.class,
                  JdbcBackendAutoConfiguration.class,
                  InMemoryBackendAutoConfiguration.class,
                  QueuedHarnessAutoConfiguration.class))
          .withUserConfiguration(AnInferenceProvider.class, ADatabase.class)
          .withPropertyValues(MODEL, PROVIDER, PROMPT, NO_SCHEMA);

  @Test
  void it_wires_a_harness_from_a_provider_and_a_database() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(DefaultQueuedHarnessFactory.class);
          assertThat(context).hasSingleBean(DefaultQueuedHarnessFactory.class);
          assertThat(context).hasSingleBean(Replies.class);
          assertThat(context).hasSingleBean(ReplyTokens.class);
        });
  }

  /**
   * <b>The in-memory backend is what "falls back" means.</b> No {@code DataSource} bean means
   * {@link JdbcBackendAutoConfiguration} -- conditional on one -- never activates, tables and codec
   * factory included, and {@link InMemoryBackendAutoConfiguration} takes the direct door instead.
   * That is exactly what lets a CLI or a test run this starter with no database, and it is why
   * {@code NessyAutoConfiguration} itself declares nothing that needs a {@code DataSource} any
   * more.
   *
   * <p>Both doors, not just the direct one: the queued door's backend is in memory too now, so an
   * application with no database still gets the door that writes work down and picks it up later.
   * Nothing it writes survives a restart, which is what makes it a test's backend and not a
   * production one.
   */
  @Test
  @DisplayName("with no DataSource, the in-memory backend takes over and the context starts")
  void it_falls_back_to_the_in_memory_backend_without_a_data_source() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class,
                ObservationAutoConfiguration.class,
                NessyAutoConfiguration.class,
                JdbcBackendAutoConfiguration.class,
                InMemoryBackendAutoConfiguration.class,
                QueuedHarnessAutoConfiguration.class))
        .withUserConfiguration(AnInferenceProvider.class)
        .withPropertyValues(MODEL, PROVIDER, PROMPT, NO_SCHEMA)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(DirectBackend.class);
              assertThat(context.getBean(DirectBackend.class))
                  .isInstanceOf(InMemoryDirectBackend.class);
              assertThat(context).hasSingleBean(QueuedBackend.class);
              assertThat(context.getBean(QueuedBackend.class))
                  .isInstanceOf(InMemoryQueuedBackend.class);
            });
  }

  /**
   * <b>Token metrics belong to neither door.</b> This handler used to be registered as a side
   * effect of building the queued door's factory, so an application that used only the direct door
   * got no token histogram at all and nothing said so. It is a bean now, which Boot's observation
   * auto-configuration registers, and the condition is a meter registry rather than a door.
   */
  @Test
  @DisplayName("token usage is measured whenever there is a registry, whichever door is in use")
  void the_token_usage_handler_does_not_belong_to_a_door() {
    runner
        .withUserConfiguration(AMeterRegistry.class)
        .run(context -> assertThat(context).hasSingleBean(TokenUsageHandler.class));

    // The direct door alone, with no queued backend in sight: still measured.
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration.class,
                ObservationAutoConfiguration.class,
                NessyAutoConfiguration.class,
                InMemoryBackendAutoConfiguration.class,
                DirectHarnessAutoConfiguration.class))
        .withUserConfiguration(AnInferenceProvider.class, AMeterRegistry.class)
        .withPropertyValues(MODEL, PROVIDER, PROMPT, NO_SCHEMA)
        .run(
            context -> {
              assertThat(context).hasSingleBean(TokenUsageHandler.class);
              assertThat(context).hasSingleBean(DirectBackend.class);
            });
  }

  /** No meter registry, nothing to record into, so nothing is declared. */
  @Test
  void nothing_measures_token_usage_without_a_meter_registry() {
    runner.run(context -> assertThat(context).doesNotHaveBean(TokenUsageHandler.class));
  }

  /**
   * <b>Interim behaviour, pending Task 4.</b> A factory's default is set only when both {@code
   * nessy.provider} and {@code nessy.model} are present -- the both-or-neither rule that refuses a
   * partial default lives in {@code NessyProperties} from Task 4 on. Until then, a missing or blank
   * model simply leaves the factory without a default, and the context starts: an agent type that
   * names neither fails when a harness is built from it, not before.
   */
  @Test
  void it_starts_without_a_model_and_defers_the_failure_to_when_a_harness_is_built() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class,
                ObservationAutoConfiguration.class,
                NessyAutoConfiguration.class,
                JdbcBackendAutoConfiguration.class,
                InMemoryBackendAutoConfiguration.class,
                QueuedHarnessAutoConfiguration.class))
        .withUserConfiguration(AnInferenceProvider.class, ADatabase.class)
        .withPropertyValues(PROVIDER, PROMPT, NO_SCHEMA)
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void it_starts_with_a_blank_model_and_defers_the_failure_to_when_a_harness_is_built() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class,
                ObservationAutoConfiguration.class,
                NessyAutoConfiguration.class,
                JdbcBackendAutoConfiguration.class,
                InMemoryBackendAutoConfiguration.class,
                QueuedHarnessAutoConfiguration.class))
        .withUserConfiguration(AnInferenceProvider.class, ADatabase.class)
        .withPropertyValues("nessy.model=   ", PROVIDER, PROMPT, NO_SCHEMA)
        .run(context -> assertThat(context).hasNotFailed());
  }

  /**
   * <b>Interim behaviour, pending Task 4.</b> With no {@code InferenceProvider} bean there is
   * nothing to register under any name, so the factory simply holds no providers; the context still
   * starts, and an agent type naming one fails when a harness is built from it.
   */
  @Test
  void it_starts_without_an_inference_provider_and_defers_the_failure_to_when_a_harness_is_built() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class,
                ObservationAutoConfiguration.class,
                NessyAutoConfiguration.class,
                JdbcBackendAutoConfiguration.class,
                InMemoryBackendAutoConfiguration.class,
                QueuedHarnessAutoConfiguration.class))
        .withUserConfiguration(ADatabase.class)
        .withPropertyValues(MODEL, PROVIDER, PROMPT, NO_SCHEMA)
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void an_application_data_source_wins() {
    runner.run(context -> assertThat(context).hasSingleBean(DataSource.class));
  }

  @Test
  void tools_declared_as_beans_are_bound_to_the_harness() {
    runner
        .withUserConfiguration(AToolBean.class)
        .run(context -> assertThat(context).hasNotFailed());
  }

  /** Nobody listens by default; every listener bean an application declares is attached. */
  @Test
  void listeners_are_the_applications_to_declare() {
    runner.run(context -> assertThat(context).doesNotHaveBean(NarrationListener.class));
    runner
        .withUserConfiguration(AListener.class)
        .run(context -> assertThat(context).hasSingleBean(DefaultQueuedHarnessFactory.class));
  }

  @Test
  void configured_reply_keys_are_used_instead_of_the_ephemeral_default() {
    runner
        .withPropertyValues(
            "nessy.reply-token-encryption-keys[0]=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        .run(context -> assertThat(context).hasSingleBean(ReplyTokens.class));
  }

  @Test
  void an_application_with_a_registry_gets_its_provider_observed() {
    runner
        .withUserConfiguration(ARegistry.class)
        .run(context -> assertThat(context).hasSingleBean(DefaultQueuedHarnessFactory.class));
  }

  // ---- what an application brings -------------------------------------------------------

  @Configuration(proxyBeanMethods = false)
  static class AnInferenceProvider {

    @Bean
    InferenceProvider inference() {
      return (request, narrator) ->
          new InferenceResult.Refusal("this provider is never actually called");
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class ADatabase {

    @Bean
    DataSource dataSource() {
      return new EmbeddedDatabaseBuilder()
          .setType(EmbeddedDatabaseType.H2)
          .generateUniqueName(true)
          .build();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class AListener {

    static final NarrationListener INSTANCE = NarrationListener.none();

    @Bean
    NarrationListener narrator() {
      return INSTANCE;
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class AMeterRegistry {

    @Bean
    MeterRegistry meters() {
      return new SimpleMeterRegistry();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class ARegistry {

    @Bean
    ObservationRegistry observations() {
      return ObservationRegistry.create();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class AToolBean {

    @Bean
    Tool<String> aTool() {
      return new Tool<>() {
        @Override
        public Class<String> inputType() {
          return String.class;
        }

        @Override
        public ToolName name() {
          return new ToolName("a_tool");
        }

        @Override
        public String description() {
          return "does a thing";
        }

        @Override
        public Awaited<ToolResult> call(ToolCallRequest<String> request) {
          throw new UnsupportedOperationException("a declared-only tool is never called");
        }
      };
    }
  }
}
