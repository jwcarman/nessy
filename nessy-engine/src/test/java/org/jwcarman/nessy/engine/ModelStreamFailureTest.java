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

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.Map;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.Entity;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnResult;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.engine.HouseEvents.HouseEvent;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * A model call that never even starts streaming.
 *
 * <p>A provider client can throw before it produces a single event — a bad request, a closed
 * connection pool, a serialization bug in the request builder. {@code Instructions.callModel} runs
 * the whole call, request-building included, on the blocking executor precisely so a synchronous
 * throw there is caught the same way a failure mid-stream would be. This is the throw-before-any-
 * event case; nothing before this test drove it.
 */
@DisplayName("A model provider that throws before it streams anything")
class ModelStreamFailureTest {

  private static final AgentType WATCHMAN = AgentType.of("brokenmodel");
  private static final EntityTypeKey<NessyMessage> KEY =
      EntityTypeKey.create(NessyMessage.class, WATCHMAN.name());

  private static ActorTestKit testKit;
  private static Engines.Parts parts;

  private static Model throwing() {
    return new Model() {
      @Override
      public ModelId id() {
        return ModelId.of("broken");
      }

      @Override
      public ModelStream stream(ModelRequest request) {
        throw new IllegalStateException("connection pool exhausted");
      }
    };
  }

  @BeforeAll
  static void start() {
    testKit = ClusterOfOne.start();
    parts = Engines.of(testKit.system(), WATCHMAN, throwing());
    ClusterSharding.get(testKit.system())
        .init(
            Entity.of(
                    KEY,
                    context ->
                        AgentActor.create(
                            new AgentActor.Dependencies(
                                WATCHMAN, parts.instructions(), Traces.noop()),
                            AgentId.of(context.getEntityId()),
                            context.getShard()))
                .withStopMessage(new NessyMessage.Stop(Map.of())));
  }

  @AfterAll
  static void stop() {
    testKit.shutdownTestKit();
  }

  @Test
  @DisplayName("the turn closes as failed, carrying the provider's own message")
  void the_turn_reports_the_thrown_message_as_its_failure_reason() {
    AgentId agentId = AgentId.of("house-brokenmodel");
    parts.backlog().offer(agentId, new HouseEvent("porch", "bell"));
    ClusterSharding.get(testKit.system())
        .entityRefFor(KEY, agentId.value())
        .tell(new NessyMessage.BacklogUpdated(Map.of()));

    await()
        .atMost(15, SECONDS)
        .untilAsserted(
            () -> {
              List<AgentEvent.TurnEnded> ended =
                  parts.narrated().of(agentId).stream()
                      .filter(AgentEvent.TurnEnded.class::isInstance)
                      .map(AgentEvent.TurnEnded.class::cast)
                      .toList();
              assertThat(ended).hasSize(1);
              assertThat(ended.getFirst().outcome()).isInstanceOf(TurnResult.Failed.class);
              TurnResult.Failed failed = (TurnResult.Failed) ended.getFirst().outcome();
              assertThat(failed.reason()).isEqualTo("connection pool exhausted");
            });
  }
}
