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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentSubscription;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.block.ToolCallBlock;
import org.jwcarman.nessy.api.message.AnswerMessage;
import org.jwcarman.nessy.api.message.ExchangeMessage;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.api.model.ModelResult;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.HouseEvents.HouseEvent;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.jwcarman.nessy.testing.TestDatabase;

/**
 * A deadline that fires because a row said so, not because an actor was holding a timer.
 *
 * <p>This is the property the whole reminder mechanism exists for, and it is asserted rather than
 * waited for: the sweep is a call and its clock is a parameter, so "three days have passed" is one
 * line rather than a test that sleeps.
 */
@DisplayName("A parked approval whose deadline passes")
class ReminderExpiryTest {

  private static final AgentType WATCHMAN = AgentType.of("watchman");
  private static final AgentId HOUSE = AgentId.of("house-12");
  private static final Instant PARKED_AT = Instant.parse("2026-09-01T12:00:00Z");
  private static final Duration TERM = Duration.ofDays(3);

  private static ActorTestKit testKit;
  private static Harness<HouseEvent> harness;
  private static DataSource database;
  private static Reminders reminders;

  @BeforeAll
  static void wire() {
    testKit = ClusterOfOne.start();
    database = TestDatabase.fresh();
    reminders = new Reminders(database);

    ModelProvider models =
        id ->
            new Model() {
              @Override
              public ModelId id() {
                return id;
              }

              @Override
              public ModelStream stream(ModelRequest request) {
                boolean settled =
                    request.context().messages().stream()
                        .anyMatch(ExchangeMessage.class::isInstance);
                if (settled) {
                  return Scripts.saying(
                      new ModelResult.Answered(
                          new AnswerMessage(List.of(new TextBlock("understood"))),
                          StopReason.END_TURN,
                          new Usage(1, 1)));
                }
                return Scripts.saying(
                    new ModelResult.Asked(
                        List.of(
                            new ToolCallBlock(
                                new ToolCall(
                                    "c1", "send_email", JsonNodeFactory.instance.objectNode()))),
                        new Usage(1, 1)));
              }
            };

    PekkoHarnessFactory factory =
        new PekkoHarnessFactory(
            engine ->
                engine
                    .system(testKit.system())
                    .models(models)
                    .dataSource(database)
                    .maxTokens(4096)
                    .capabilities(Set.of())
                    .blocking(Runnable::run)
                    .clock(Clock.systemUTC())
                    .replyTokens(ReplyTokens.ephemeral()));

    harness =
        factory.createHarness(
            HouseEvent.class,
            config ->
                config
                    .type(WATCHMAN)
                    .systemPrompt("You watch the house.")
                    .model(ModelId.of("scripted"))
                    .renderer(HouseEvents.RENDERER)
                    // Parks on a person for three days and is never answered — the case a timer in
                    // a resident actor was the only thing holding.
                    .tool(sendEmail(), binding -> binding.approver(parksForThreeDays())));
  }

  @AfterAll
  static void stop() {
    testKit.shutdownTestKit();
  }

  private static Approver parksForThreeDays() {
    return request -> Awaited.deferred(PARKED_AT.plus(TERM));
  }

  private static Tool<ObjectNode> sendEmail() {
    return new Tool<>() {
      @Override
      public Class<ObjectNode> inputType() {
        return ObjectNode.class;
      }

      @Override
      public ObjectNode inputSchema() {
        return JsonNodeFactory.instance.objectNode();
      }

      @Override
      public String name() {
        return "send_email";
      }

      @Override
      public String description() {
        return "sends an email";
      }

      @Override
      public Awaited<ToolResult> execute(ToolCallRequest<ObjectNode> call) {
        ObjectNode input = call.input();
        return Awaited.ready(ToolResult.ok("sent"));
      }
    };
  }

  @Test
  @DisplayName("the deadline is written down, then fires through the sweep and ends the turn")
  void a_parked_call_expires_without_anything_holding_a_timer() {
    List<AgentEvent> heard = new CopyOnWriteArrayList<>();
    String key = ReminderSweep.keyFor(WATCHMAN.name(), HOUSE.value(), "c1");

    try (AgentSubscription listening = harness.subscribe(HOUSE, heard::add)) {
      harness.observe(HOUSE, new HouseEvent("kitchen", "door opened"));

      // The deadline is a ROW, and it names the term the approver asked for.
      await().atMost(15, SECONDS).untilAsserted(() -> assertThat(reminders.find(key)).isPresent());
      assertThat(reminders.find(key).orElseThrow().expiresAt()).isEqualTo(PARKED_AT.plus(TERM));

      // Three days later, without waiting three days: the sweep's clock is a parameter.
      ReminderSweep sweep =
          new ReminderSweep(
              reminders,
              Clock.fixed(PARKED_AT.plus(TERM).plusSeconds(1), java.time.ZoneOffset.UTC),
              // Delivered to the agent's LOGICAL address, exactly as production will: an
              // EntityRef reaches a passivated agent by reactivating it, which is the property the
              // whole mechanism rests on.
              (where, expired) ->
                  ClusterSharding.get(testKit.system())
                      .entityRefFor(
                          EntityTypeKey.create(NessyMessage.class, where.agentType()),
                          where.agentId())
                      .tell(expired));

      assertThat(sweep.sweep()).isEqualTo(1);

      await()
          .atMost(15, SECONDS)
          .untilAsserted(
              () ->
                  assertThat(heard)
                      .filteredOn(AgentEvent.TurnEnded.class::isInstance)
                      .isNotEmpty());
    }
  }
}
