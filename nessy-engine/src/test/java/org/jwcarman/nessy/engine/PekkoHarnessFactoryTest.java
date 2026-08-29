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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.ConfigFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executors;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.SpawnProtocol;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.ToolSpec;
import org.jwcarman.nessy.spi.codec.CodecPipeline;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.testing.ScriptedModel;

/**
 * A whole agent, standing up on nothing but an actor system and an in-memory store.
 *
 * <p>This is what the factory is FOR: no Spring, no HTTP, no Postgres, no guardian of our own —
 * just a system somebody handed us. If an agent cannot run under these conditions, the harness has
 * not actually separated the engine from the application.
 */
class PekkoHarnessFactoryTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** A catalog with nothing in it — this test is about the turn, not the tools. */
  private static final AgentTools NO_TOOLS =
      new AgentTools() {
        @Override
        public List<ToolSpec> specs() {
          return List.of();
        }

        @Override
        public boolean needsApproval(String tool) {
          return false;
        }

        @Override
        public String action(String tool, String argumentsJson) {
          return tool;
        }

        @Override
        public String run(String tool, String argumentsJson) {
          return ToolResult.error("no tools here").text();
        }

        @Override
        public JsonNode argumentsOf(String argumentsJson) {
          return JSON.createObjectNode();
        }
      };

  private ActorSystem<SpawnProtocol.Command> system;
  private InMemorySubstrate substrate;
  private PekkoHarnessFactory factory;

  private static ModelProvider providerOf(Model model) {
    return new ModelProvider() {
      @Override
      public Model model(String id) {
        return model;
      }

      @Override
      public String name() {
        return "scripted";
      }
    };
  }

  @BeforeEach
  void startEngine() {
    // A SpawnProtocol guardian, because Pekko refuses top-level spawns from outside a system with
    // a custom user guardian — the same constraint that stops a harness being the guardian itself.
    system =
        ActorSystem.create(
            SpawnProtocol.create(),
            "engine-test",
            ConfigFactory.parseString(
                // AgentActor is a DurableStateBehavior; without a store it dies at creation and the
                // observation lands in dead letters.
                "pekko.persistence.state.plugin = \"pekko.persistence.testkit.state\""));
    substrate = new InMemorySubstrate();
    factory =
        new PekkoHarnessFactory(
            system,
            substrate,
            providerOf(ScriptedModel.script(s -> s.text("nothing to report").endTurn())),
            "scripted",
            NO_TOOLS,
            new SimpleMeterRegistry(),
            MicrometerTracing.noop(),
            Clock.systemUTC(),
            Executors.newSingleThreadExecutor(),
            CodecPipeline.none());
  }

  @AfterEach
  void stopEngine() {
    system.terminate();
  }

  @Nested
  class An_agent {

    @Test
    void ingests_an_observation_and_remembers_the_turn_it_produced() {
      Harness<String> harness = factory.create(config -> config.type("test"));

      harness.observe("agent-1", "the disk is full");

      Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(20))
          .untilAsserted(
              () ->
                  assertThat(
                          new Memories(substrate, 100_000).forAgent("agent-1").recall().messages())
                      .isNotEmpty());
    }

    @Test
    void carries_the_type_its_config_named() {
      Harness<String> harness = factory.create(config -> config.type("watchman"));

      assertThat(harness.type().name()).isEqualTo("watchman");
    }
  }

  @Nested
  class The_factory {

    @Test
    void refuses_a_max_tokens_that_cannot_fit_the_model_it_resolved() {
      assertThatThrownBy(() -> factory.create(config -> config.maxTokens(500_000)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("context window");
    }

    @Test
    void says_plainly_that_typed_observations_are_not_supported_yet() {
      assertThatThrownBy(() -> factory.create(Integer.class, config -> {}))
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessageContaining("String observations only");
    }

    @Test
    void leaves_the_borrowed_actor_system_running_after_shutdown() {
      Harness<String> harness = factory.create(config -> config.type("test"));

      harness.shutdown();

      assertThat(system.whenTerminated().isCompleted()).isFalse();
    }
  }
}
