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

import com.fasterxml.jackson.annotation.JsonProperty;
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
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.block.ToolCallBlock;
import org.jwcarman.nessy.api.message.AnswerMessage;
import org.jwcarman.nessy.api.message.ExchangeMessage;
import org.jwcarman.nessy.api.model.ModelResult;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolBinding;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolDescriber;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.HouseEvents.HouseEvent;

/**
 * What the model is told when a person says no.
 *
 * <p>A denial is a RESULT, not a missing one. The logic marks the call completed and cannot write
 * anything itself, so unless whoever produced the denial claims it, the exchange reaches the
 * transcript saying "no result was recorded" — which reads to the model as a broken tool rather
 * than a person refusing. Measured in a browser before this test existed: the agent apologised for
 * an error it had not had.
 */
@DisplayName("A call a person refused")
class DeniedCallTest {

  private static final AgentType WATCHMAN = AgentType.of("denier");
  private static final EntityTypeKey<NessyMessage> KEY =
      EntityTypeKey.create(NessyMessage.class, WATCHMAN.name());

  private static ActorTestKit testKit;
  private static Engines.Parts parts;

  record Letter(@JsonProperty("to") String to) {}

  @BeforeAll
  static void start() {
    testKit = ClusterOfOne.start();
    Tool<Letter> send =
        new Tool<>() {
          @Override
          public Class<Letter> inputType() {
            return Letter.class;
          }

          @Override
          public com.fasterxml.jackson.databind.node.ObjectNode inputSchema() {
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
          }

          @Override
          public String name() {
            return "send_letter";
          }

          @Override
          public String description() {
            return "sends a letter";
          }

          @Override
          public Awaited<ToolResult> execute(ToolCallRequest<Letter> call) {
            Letter input = call.input();
            throw new AssertionError("a denied call must never run");
          }
        };
    ToolBinding<Letter> binding =
        new ToolBinding<>(
            send,
            request -> Awaited.ready(ApprovalResult.denied("not this time")),
            ToolDescriber.byToString());

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
                                    "c1",
                                    "send_letter",
                                    EngineMapper.INSTANCE.valueToTree(
                                        new Letter("james@example.com"))))),
                        Usage.unreported()),
                    new ModelResult.Answered(
                        new AnswerMessage(List.of(new TextBlock("understood"))),
                        StopReason.END_TURN,
                        Usage.unreported()))),
            List.of(binding));

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
  @DisplayName("the model is told it was denied, not that the result went missing")
  void a_denial_reaches_the_transcript_as_the_calls_result() {
    AgentId agentId = AgentId.of("house-denied");
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
              assertThat(ToolResults.text(exchanges.getFirst()))
                  .contains("denied")
                  .contains("not this time")
                  .doesNotContain("no result was recorded");
            });
  }

  /** Reading one call's answer back out of an exchange. */
  private static final class ToolResults {

    private ToolResults() {}

    static String text(ExchangeMessage exchange) {
      return exchange.results().getFirst().content().stream()
          .filter(TextBlock.class::isInstance)
          .map(TextBlock.class::cast)
          .map(TextBlock::text)
          .reduce("", String::concat);
    }
  }
}
