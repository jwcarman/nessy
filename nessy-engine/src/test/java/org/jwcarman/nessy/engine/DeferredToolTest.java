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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.block.ToolCallBlock;
import org.jwcarman.nessy.api.message.AnswerMessage;
import org.jwcarman.nessy.api.message.ExchangeMessage;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.api.model.ModelResult;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.HouseEvents.HouseEvent;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.testing.TestDatabase;

/**
 * A tool that says "I'll get back to you", and the world getting back.
 *
 * <p>This is the case the old engine could not express at all: a deferred tool became an error
 * handed to the model, because execution ran on a pooled worker with no per-call identity and there
 * was nowhere for a late answer to arrive. The execution actor is that somewhere.
 */
@DisplayName("A tool that defers")
class DeferredToolTest {

  record Job(String what) {}

  private static final AgentType WATCHMAN = AgentType.of("watchman");
  private static final AgentId HOUSE = AgentId.of("house-12");

  private static ActorTestKit testKit;
  private static Harness<HouseEvent> harness;
  private static Replies replies;

  /** What the tool was told to answer on. */
  private static final AtomicReference<ReplyToken> handed = new AtomicReference<>();

  private static Tool<Job> defersForever() {
    return new Tool<>() {
      @Override
      public Class<Job> inputType() {
        return Job.class;
      }

      @Override
      public ObjectNode inputSchema() {
        return JsonNodeFactory.instance.objectNode();
      }

      @Override
      public String name() {
        return "start_job";
      }

      @Override
      public String description() {
        return "kicks off something slow";
      }

      @Override
      public Awaited<ToolResult> execute(ToolCallRequest<Job> call) {
        // Exactly what a real deferring tool does: hand the token to whoever will answer, then say
        // it will be a while.
        handed.set(call.replyToken());
        return Awaited.deferred(Instant.now().plus(1, ChronoUnit.HOURS));
      }
    };
  }

  @BeforeAll
  static void wireEverything() {
    testKit = ClusterOfOne.start();
    ModelProvider models =
        id ->
            new Model() {
              @Override
              public ModelId id() {
                return id;
              }

              @Override
              public org.jwcarman.nessy.spi.model.ModelStream stream(ModelRequest request) {
                boolean answered =
                    request.context().messages().stream()
                        .anyMatch(ExchangeMessage.class::isInstance);
                if (answered) {
                  return Scripts.saying(
                      new ModelResult.Answered(
                          new AnswerMessage(List.of(new TextBlock("all done"))),
                          StopReason.END_TURN,
                          new Usage(1, 1)));
                }
                ObjectNode arguments = JsonNodeFactory.instance.objectNode();
                arguments.put("what", "sweep the hall");
                return Scripts.saying(
                    new ModelResult.Asked(
                        List.of(
                            new ToolCallBlock(
                                new ToolCall(CallId.of("c1"), "start_job", arguments))),
                        new Usage(1, 1)));
              }
            };

    PekkoHarnessFactory factory =
        new PekkoHarnessFactory(
            engine ->
                engine
                    .system(testKit.system())
                    .models(models)
                    .dataSource(TestDatabase.fresh())
                    .maxTokens(4096)
                    .capabilities(java.util.Set.of())
                    .blocking(Runnable::run)
                    .clock(Clock.systemUTC())
                    .replyTokens(ReplyTokens.ephemeral()));
    replies = factory.replies();
    harness =
        factory.createHarness(
            HouseEvent.class,
            config ->
                config
                    .type(WATCHMAN)
                    .systemPrompt("You watch the house.")
                    .model(ModelId.of("scripted"))
                    .renderer(HouseEvents.RENDERER)
                    .tool(defersForever()));
  }

  @AfterAll
  static void stop() {
    testKit.shutdownTestKit();
  }

  @Test
  @DisplayName("the turn waits, then finishes when the world answers")
  void a_deferred_call_is_completed_from_outside() throws Exception {
    List<AgentEvent> heard = new CopyOnWriteArrayList<>();
    harness.subscribe(HOUSE, heard::add);

    harness.observe(HOUSE, new HouseEvent("hall", "dust"));

    // The tool has been handed somewhere to answer, and nothing has finished.
    await().atMost(15, SECONDS).untilAsserted(() -> assertThat(handed.get()).isNotNull());
    assertThat(heard).noneMatch(AgentEvent.TurnEnded.class::isInstance);

    NessyMessage.Ack ack =
        replies
            .answer(handed.get(), ToolResult.ok("job finished"))
            .toCompletableFuture()
            .get(10, java.util.concurrent.TimeUnit.SECONDS);

    assertThat(ack.accepted()).isTrue();
    await()
        .atMost(15, SECONDS)
        .untilAsserted(
            () ->
                assertThat(heard).filteredOn(AgentEvent.TurnEnded.class::isInstance).isNotEmpty());
  }
}
