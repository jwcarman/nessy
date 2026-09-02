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
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.block.ToolCallBlock;
import org.jwcarman.nessy.api.message.AnswerMessage;
import org.jwcarman.nessy.api.message.ExchangeMessage;
import org.jwcarman.nessy.api.model.ModelResult;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.engine.HouseEvents.HouseEvent;

/**
 * A call for a tool this agent was never given.
 *
 * <p>{@code AgentLogic} decides to ask an approver for every call the model made without ever
 * checking whether a binding exists — that check belongs to {@code Instructions}, which is the last
 * place that knows what this agent can actually do. This is the path nothing before it drove: a
 * model naming a tool with no binding at all, rather than one whose binding denies or fails.
 */
@DisplayName("A call for a tool nobody bound")
class UnknownToolTest {

  private static final AgentType WATCHMAN = AgentType.of("toolless");
  private static final EntityTypeKey<NessyMessage> KEY =
      EntityTypeKey.create(NessyMessage.class, WATCHMAN.name());

  private static ActorTestKit testKit;
  private static Engines.Parts parts;

  @BeforeAll
  static void start() {
    testKit = ClusterOfOne.start();
    parts =
        Engines.of(
            testKit.system(),
            WATCHMAN,
            Engines.saying(
                List.of(
                    new ModelResult.Asked(
                        List.of(
                            new ToolCallBlock(
                                new ToolCall(
                                    CallId.of("c1"),
                                    "unlock_the_front_door",
                                    EngineMapper.INSTANCE.createObjectNode()))),
                        Usage.unreported()),
                    new ModelResult.Answered(
                        new AnswerMessage(List.of(new TextBlock("understood"))),
                        StopReason.END_TURN,
                        Usage.unreported()))),
            List.of() /* no bindings at all */);

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
  @DisplayName("the model is told no such tool exists, and the call was never made")
  void an_unbound_tool_name_fails_the_call_without_asking_anyone() {
    AgentId agentId = AgentId.of("house-toolless");
    parts.backlog().offer(agentId, new HouseEvent("porch", "bell"));
    ClusterSharding.get(testKit.system())
        .entityRefFor(KEY, agentId.value())
        .tell(new NessyMessage.BacklogUpdated(Map.of()));

    await()
        .atMost(15, SECONDS)
        .untilAsserted(
            () -> {
              List<ExchangeMessage> exchanges =
                  parts.remembered().of(agentId).stream()
                      .filter(ExchangeMessage.class::isInstance)
                      .map(ExchangeMessage.class::cast)
                      .toList();
              assertThat(exchanges).hasSize(1);
              assertThat(exchanges.getFirst().results().getFirst().isError()).isTrue();
              String text =
                  exchanges.getFirst().results().getFirst().content().stream()
                      .filter(TextBlock.class::isInstance)
                      .map(TextBlock.class::cast)
                      .map(TextBlock::text)
                      .reduce("", String::concat);
              assertThat(text)
                  .contains("no such tool")
                  .contains("unlock_the_front_door")
                  .contains("the call was not made");
            });
  }
}
