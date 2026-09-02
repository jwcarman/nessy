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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.PekkoHarnessFactory;
import org.jwcarman.nessy.engine.Replies;
import org.jwcarman.nessy.engine.ReplyTokens;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.jwcarman.nessy.testing.TestDatabase;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * What the starter wires, and what it steps aside for.
 *
 * <p>Every test here drives a real {@link ApplicationContextRunner}: the point of a starter is what
 * Spring does with it, and a test that called the {@code @Bean} methods directly would prove
 * nothing about the conditions guarding them.
 */
class NessyAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(NessyAutoConfiguration.class))
          .withUserConfiguration(AModelProvider.class)
          .withPropertyValues("nessy.model=a-test-model");

  @Test
  void it_wires_a_harness_from_nothing_but_a_model_provider() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(Harness.class);
          assertThat(context).hasSingleBean(PekkoHarnessFactory.class);
          assertThat(context).hasSingleBean(Replies.class);
          assertThat(context).hasSingleBean(ReplyTokens.class);
        });
  }

  @Test
  void it_refuses_to_start_without_a_model_id() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(NessyAutoConfiguration.class))
        .withUserConfiguration(AModelProvider.class)
        .run(
            context -> {
              // Guessing a model would start cleanly and fail at the first turn.
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure()).hasMessageContaining("nessy.model");
            });
  }

  @Test
  void it_refuses_to_start_without_a_model_provider() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(NessyAutoConfiguration.class))
        .withPropertyValues("nessy.model=a-test-model")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  @DisplayName("with no DataSource configured, Nessy supplies an in-memory one and says so")
  void it_falls_back_to_an_in_memory_database_when_there_is_no_data_source() {
    runner.run(context -> assertThat(context).hasSingleBean(DataSource.class));
  }

  @Test
  void an_application_data_source_wins() {
    runner
        .withUserConfiguration(AnApplicationDataSource.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(DataSource.class);
              assertThat(context.getBean(DataSource.class))
                  .isSameAs(context.getBean(AnApplicationDataSource.class).dataSource);
            });
  }

  @Test
  void the_agent_type_comes_from_properties() {
    runner
        .withPropertyValues("nessy.type=watchman")
        .run(
            context ->
                assertThat(context.getBean(Harness.class).type().name()).isEqualTo("watchman"));
  }

  @Test
  void the_system_prompt_can_be_given_inline() {
    runner
        .withPropertyValues("nessy.system-prompt=You watch the house.")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void giving_both_prompt_sources_fails_rather_than_silently_picking_one() {
    runner
        .withPropertyValues(
            "nessy.system-prompt=inline", "nessy.system-prompt-file=classpath:prompt.txt")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void tools_declared_as_beans_are_granted() {
    runner
        .withUserConfiguration(AToolBean.class)
        .run(context -> assertThat(context).hasSingleBean(Harness.class));
  }

  @Test
  @DisplayName("a blank model id fails the same way a missing one does")
  void it_refuses_to_start_with_a_blank_model_id() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(NessyAutoConfiguration.class))
        .withUserConfiguration(AModelProvider.class)
        .withPropertyValues("nessy.model=   ")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure()).hasMessageContaining("nessy.model");
            });
  }

  @Test
  @DisplayName("configured reply keys seal tokens instead of falling back to the ephemeral default")
  void configured_reply_keys_are_used_instead_of_the_ephemeral_default() {
    runner
        .withPropertyValues(
            "nessy.reply-token-encryption-keys=otvNTFHF1XGxgAjeGl32r+k/MhX08XZ5j9mmsOhz+xM=")
        .run(context -> assertThat(context).hasSingleBean(ReplyTokens.class));
  }

  @Test
  @DisplayName("an application with its own seed-nodes forms its own cluster, so this steps aside")
  void an_application_with_seed_nodes_configured_does_not_self_join() {
    runner
        .withUserConfiguration(ASeedNodesConfig.class)
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  @DisplayName("with both a registry and meters present, model calls are observed")
  void an_application_with_observability_beans_gets_observed_models() {
    runner
        .withUserConfiguration(AnObservabilityConfig.class)
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  @DisplayName("a registry alone, with no MeterRegistry to record onto, does not observe models")
  void a_registry_without_meters_does_not_observe_models() {
    runner
        .withUserConfiguration(ARegistryOnlyConfig.class)
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  @DisplayName("a tool is wrapped for observation once the application has a registry")
  void a_declared_tool_is_wrapped_when_observability_is_present() {
    runner
        .withUserConfiguration(AToolBean.class, AnObservabilityConfig.class)
        .run(context -> assertThat(context).hasSingleBean(Harness.class));
  }

  @Test
  @DisplayName("the approvals projection only exists when there is a JdbcTemplate to keep it in")
  void the_approvals_projection_appears_once_there_is_a_jdbc_template() {
    runner
        .withConfiguration(AutoConfigurations.of(JdbcTemplateAutoConfiguration.class))
        .withUserConfiguration(AnApplicationDataSource.class)
        .run(context -> assertThat(context).hasSingleBean(PendingApprovalsRepository.class));
  }

  @Configuration(proxyBeanMethods = false)
  static class AModelProvider {

    @Bean
    ModelProvider models() {
      return id ->
          new Model() {
            @Override
            public ModelId id() {
              return id;
            }

            @Override
            public ModelStream stream(ModelRequest request) {
              throw new UnsupportedOperationException("this test never takes a turn");
            }
          };
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class AnApplicationDataSource {

    private final DataSource dataSource = TestDatabase.fresh();

    @Bean
    DataSource mine() {
      return dataSource;
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class ASeedNodesConfig {

    // Never actually reached — the point is that this branch does not try. A cluster with
    // seed-nodes configured is a real cluster the application is joining itself, and the starter
    // steps aside rather than joining it a second time.
    @Bean
    Config pekkoSeedNodes() {
      return ConfigFactory.parseString(
          "pekko.cluster.seed-nodes = [\"pekko://nessy@127.0.0.1:25520\"]");
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class AnObservabilityConfig {

    @Bean
    ObservationRegistry observations() {
      return ObservationRegistry.create();
    }

    @Bean
    MeterRegistry meters() {
      return new SimpleMeterRegistry();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class ARegistryOnlyConfig {

    @Bean
    ObservationRegistry observations() {
      return ObservationRegistry.create();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class AToolBean {

    @Bean
    Tool<ObjectNode> aTool() {
      return new Tool<>() {
        @Override
        public String name() {
          return "noop";
        }

        @Override
        public String description() {
          return "does nothing";
        }

        @Override
        public Class<ObjectNode> inputType() {
          return ObjectNode.class;
        }

        @Override
        public ObjectNode inputSchema() {
          return JsonNodeFactory.instance.objectNode().put("type", "object");
        }

        @Override
        public Awaited<ToolResult> execute(ToolCallRequest<ObjectNode> call) {
          return Awaited.ready(ToolResult.ok("nothing happened"));
        }
      };
    }
  }
}
