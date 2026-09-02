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
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
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
import org.jwcarman.nessy.api.HarnessFactory;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.block.ToolCallBlock;
import org.jwcarman.nessy.api.message.AnswerMessage;
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.api.model.ModelResult;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
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
 * A CONVERSATION: one agent, told things one after another, the way a person uses one.
 *
 * <p>Every other test in this suite observes ONCE, to a fresh agent, with the model call running on
 * {@code Runnable::run} — synchronously, inside the actor's own message handling. Both of those
 * choices hide the same defect, and together they hid it completely: a green build said nothing
 * about the second thing anybody types.
 *
 * <p>So this one is deliberately unlike the others in exactly two ways. The model call goes to a
 * REAL executor and TAKES TIME, which is what leaves an agent idle-then-busy with a call in flight;
 * and the next observation follows the previous turn IMMEDIATELY, which is what a piped script does
 * and what a fast typist approximates. That combination lands an observation inside the window
 * between an agent asking to be passivated and the shard telling it to stop.
 */
@DisplayName("An agent held in conversation")
class ConversationTest {

  private static final AgentType WATCHMAN = AgentType.of("watchman");

  /** Long enough that the turn is genuinely in flight when the stop arrives. */
  private static final long MODEL_LATENCY_MILLIS = 120;

  /**
   * Enough turns that the window is hit rather than merely available.
   *
   * <p>The race is timing, so one round proves little either way. Before the fix this reliably
   * stalls within a few rounds; after it, every round completes.
   */
  private static final int ROUNDS = 6;

  /** Fresh call ids: a turn re-driven onto an old id would be answering a different question. */
  private static final AtomicInteger calls = new AtomicInteger();

  private static ActorTestKit testKit;
  private static Harness<HouseEvent> harness;

  @BeforeAll
  static void wire() {
    testKit = ClusterOfOne.start();

    ModelProvider models =
        id ->
            new Model() {
              @Override
              public ModelId id() {
                return id;
              }

              @Override
              public ModelStream stream(ModelRequest request) {
                try {
                  Thread.sleep(MODEL_LATENCY_MILLIS);
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                }
                // A tool on the way in, an answer on the way out — the two-model-call turn the CLI
                // actually runs. A turn that is one call long never leaves anything in flight.
                boolean startOfTurn = request.context().messages().getLast() instanceof UserMessage;
                if (startOfTurn) {
                  return Scripts.saying(
                      new ModelResult.Asked(
                          List.of(
                              new ToolCallBlock(
                                  new ToolCall(
                                      "c" + calls.incrementAndGet(),
                                      "look_up",
                                      JsonNodeFactory.instance.objectNode()))),
                          new Usage(1, 1)));
                }
                return Scripts.saying(
                    new ModelResult.Answered(
                        new AnswerMessage(List.of(new TextBlock("noted"))),
                        StopReason.END_TURN,
                        new Usage(1, 1)));
              }
            };

    HarnessFactory factory =
        new PekkoHarnessFactory(
            engine ->
                engine
                    .system(testKit.system())
                    .models(models)
                    .dataSource(TestDatabase.fresh())
                    .maxTokens(4096)
                    .capabilities(Set.of())
                    // A REAL executor, unlike every other test here: the model call has to outlive
                    // the message that started it for the window to exist at all.
                    .blocking(Executors.newVirtualThreadPerTaskExecutor())
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
                    .tool(lookUp()));
  }

  private static Tool<Query> lookUp() {
    return new Tool<>() {
      @Override
      public Class<Query> inputType() {
        return Query.class;
      }

      @Override
      public ObjectNode inputSchema() {
        return JsonNodeFactory.instance.objectNode();
      }

      @Override
      public String name() {
        return "look_up";
      }

      @Override
      public String description() {
        return "looks something up";
      }

      @Override
      public Awaited<ToolResult> execute(ToolCallRequest<Query> call) {
        Query input = call.input();
        return Awaited.ready(ToolResult.ok("found it"));
      }
    };
  }

  record Query(String text) {}

  @AfterAll
  static void stop() {
    testKit.shutdownTestKit();
  }

  @Test
  @DisplayName("every turn ends, however quickly the next thing arrives")
  void a_run_of_turns_all_finish() {
    AgentId talkative = AgentId.of("house-conversation");
    List<AgentEvent> heard = new CopyOnWriteArrayList<>();

    try (AgentSubscription listening = harness.subscribe(talkative, heard::add)) {
      for (int round = 1; round <= ROUNDS; round++) {
        int expected = round;
        harness.observe(talkative, new HouseEvent("kitchen", "event " + round));
        await()
            .atMost(20, SECONDS)
            .untilAsserted(() -> assertThat(endings(heard)).isEqualTo(expected));
      }
    }

    assertThat(endings(heard)).isEqualTo(ROUNDS);
    // Proof the turns were the two-call kind, not one-shot answers: a turn per round asked for the
    // tool. Without this the test could quietly stop exercising the shape it exists for.
    assertThat(calls.get()).isEqualTo(ROUNDS);
  }

  private static long endings(List<AgentEvent> heard) {
    return heard.stream().filter(AgentEvent.TurnEnded.class::isInstance).count();
  }
}
