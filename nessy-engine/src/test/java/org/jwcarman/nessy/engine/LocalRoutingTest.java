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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.ConfigFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.SpawnProtocol;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.ToolSpec;
import org.jwcarman.nessy.spi.codec.CodecPipeline;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.testing.ScriptedModel;

/**
 * A plain actor system — no cluster, no port, no half-second of cluster formation.
 *
 * <p>The strategy is chosen from the system the caller supplied, so this needs no flag: a local
 * system gets local routing. What it buys is that an agent runs in-process with nothing configured,
 * which is what a CLI or an embedded use wants.
 *
 * <p>Local routing parents agents under the harness, so it needs one harness per agent type. That
 * is the whole guarantee here rather than a weakened one — a local system IS one process.
 */
class LocalRoutingTest {

  private static final ObjectMapper JSON = new ObjectMapper();

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

  @BeforeEach
  void startEngine() {
    system =
        ActorSystem.create(
            SpawnProtocol.create(),
            "local-routing",
            ConfigFactory.parseString(
                "pekko.persistence.state.plugin = \"pekko.persistence.testkit.state\""));
    substrate = new InMemorySubstrate();
    factory = factoryOn(system);
  }

  private PekkoHarnessFactory factoryOn(ActorSystem<SpawnProtocol.Command> on) {
    return new PekkoHarnessFactory(
        on,
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

  @AfterEach
  void stopEngine() {
    system.terminate();
  }

  @Test
  void a_plain_system_is_not_clustered() {
    assertThat(RoutingStrategy.isClustered(system)).isFalse();
  }

  @Test
  void an_agent_runs_with_no_cluster_configured_at_all() {
    Harness<String> harness = factory.create(config -> config.type("local"));

    harness.observe("a1", "the disk is full");

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertThat(new Memories(substrate, 100_000).forAgent("a1").recall().messages())
                    .isNotEmpty());
  }

  @Test
  void a_second_harness_for_one_type_is_refused_because_local_routing_parents_agents() {
    factory.create(config -> config.type("local"));

    assertThatThrownBy(() -> factory.create(config -> config.type("local")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("persistence id");
  }

  @Test
  void the_refusal_holds_across_factories_because_the_reservation_lives_on_the_system() {
    factory.create(config -> config.type("local"));

    assertThatThrownBy(() -> factoryOn(system).create(config -> config.type("local")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void a_stopped_harness_gives_its_type_back() {
    factory.create(config -> config.type("local")).shutdown();

    assertThatCode(() -> factory.create(config -> config.type("local"))).doesNotThrowAnyException();
  }
}
