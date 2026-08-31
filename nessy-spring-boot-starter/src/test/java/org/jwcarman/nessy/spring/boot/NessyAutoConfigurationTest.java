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
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.PekkoHarnessFactory;
import org.jwcarman.nessy.engine.Replies;
import org.jwcarman.nessy.engine.ReplyTokens;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
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
  void it_falls_back_to_an_in_memory_substrate_when_there_is_no_data_source() {
    runner.run(
        context ->
            assertThat(context.getBean(Substrate.class)).isInstanceOf(InMemorySubstrate.class));
  }

  @Test
  void an_application_substrate_wins() {
    runner
        .withUserConfiguration(AnApplicationSubstrate.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(Substrate.class);
              assertThat(context.getBean(Substrate.class))
                  .isSameAs(context.getBean(AnApplicationSubstrate.class).substrate);
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
  static class AnApplicationSubstrate {

    private final Substrate substrate = new InMemorySubstrate(Clock.systemUTC());

    @Bean
    Substrate mine() {
      return substrate;
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
        public Awaited<ToolResult> execute(ObjectNode input, ToolContext context) {
          return Awaited.ready(ToolResult.ok("nothing happened"));
        }
      };
    }
  }
}
