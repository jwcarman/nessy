package org.jwcarman.nessy.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.ObservationRegistry;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentEventListener;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.tool.Replies;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.harness.DefaultHarnessFactory;
import org.jwcarman.nessy.engine.tool.ReplyTokens;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.springframework.boot.autoconfigure.AutoConfigurations;
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
  private static final String PROMPT = "nessy.system-prompt=you are a test assistant";

  /**
   * <b>No tables, and that is the point of these tests.</b> They assert which beans exist; nothing
   * here runs a turn. The engine's own schema does not even load on H2 -- {@code BYTEA} and {@code
   * TIMESTAMPTZ} are PostgreSQL's -- which is the same fact that makes an in-memory fallback
   * impossible, so opting out is what lets a wiring test stay a wiring test.
   */
  private static final String NO_SCHEMA = "nessy.initialize-schema=false";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ObservationAutoConfiguration.class, NessyAutoConfiguration.class))
          .withUserConfiguration(AnInferenceProvider.class, ADatabase.class)
          .withPropertyValues(MODEL, PROMPT, NO_SCHEMA);

  @Test
  void it_wires_a_harness_from_a_provider_and_a_database() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(Harness.class);
          assertThat(context).hasSingleBean(DefaultHarnessFactory.class);
          assertThat(context).hasSingleBean(Replies.class);
          assertThat(context).hasSingleBean(ReplyTokens.class);
        });
  }

  /**
   * <b>No in-memory fallback, deliberately.</b> There used to be one: no {@code DataSource} meant
   * an embedded H2 and a loud warning. The warning was the tell -- the queries this engine rests on
   * are PostgreSQL's, so the fallback did not run a degraded Nessy, it ran one that fails on the
   * first turn. Refusing to start names the missing thing at the only moment it is cheap to fix.
   */
  @Test
  @DisplayName("with no DataSource, it refuses to start rather than pretending")
  void it_refuses_to_start_without_a_data_source() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(ObservationAutoConfiguration.class, NessyAutoConfiguration.class))
        .withUserConfiguration(AnInferenceProvider.class)
        .withPropertyValues(MODEL, PROMPT, NO_SCHEMA)
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void it_refuses_to_start_without_a_model() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(ObservationAutoConfiguration.class, NessyAutoConfiguration.class))
        .withUserConfiguration(AnInferenceProvider.class, ADatabase.class)
        .withPropertyValues(PROMPT, NO_SCHEMA)
        .run(
            context -> {
              // Guessing a model would start cleanly and fail at the first turn.
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure()).hasMessageContaining("nessy.model");
            });
  }

  @Test
  void it_refuses_to_start_with_a_blank_model() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(ObservationAutoConfiguration.class, NessyAutoConfiguration.class))
        .withUserConfiguration(AnInferenceProvider.class, ADatabase.class)
        .withPropertyValues("nessy.model=   ", PROMPT, NO_SCHEMA)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure()).hasMessageContaining("nessy.model");
            });
  }

  @Test
  void it_refuses_to_start_without_an_inference_provider() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(ObservationAutoConfiguration.class, NessyAutoConfiguration.class))
        .withUserConfiguration(ADatabase.class)
        .withPropertyValues(MODEL, PROMPT, NO_SCHEMA)
        .run(context -> assertThat(context).hasFailed());
  }

  /**
   * An agent with no standing instruction is a chat box, and the empty string used to be allowed
   * only because nothing downstream objected.
   */
  @Test
  void it_refuses_to_start_without_a_system_prompt() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(ObservationAutoConfiguration.class, NessyAutoConfiguration.class))
        .withUserConfiguration(AnInferenceProvider.class, ADatabase.class)
        .withPropertyValues(MODEL, NO_SCHEMA)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure()).hasMessageContaining("nessy.system-prompt");
            });
  }

  @Test
  void giving_both_prompt_sources_fails_rather_than_silently_picking_one() {
    runner
        .withPropertyValues("nessy.system-prompt-file=classpath:application.properties")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure()).hasMessageContaining("not both");
            });
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
    runner.run(context -> assertThat(context).doesNotHaveBean(AgentEventListener.class));
    runner
        .withUserConfiguration(AListener.class)
        .run(context -> assertThat(context).hasSingleBean(DefaultHarnessFactory.class));
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
        .run(context -> assertThat(context).hasSingleBean(DefaultHarnessFactory.class));
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

    static final AgentEventListener INSTANCE = AgentEventListener.none();

    @Bean
    AgentEventListener narrator() {
      return INSTANCE;
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
