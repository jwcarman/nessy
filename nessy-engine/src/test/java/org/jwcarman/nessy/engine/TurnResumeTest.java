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
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.block.AssistantContentBlock;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.block.ToolCallBlock;
import org.jwcarman.nessy.api.memory.Memory;
import org.jwcarman.nessy.api.message.AssistantMessage;
import org.jwcarman.nessy.api.message.ToolResultMessage;
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.api.model.ModelResult;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolBinding;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolDescriber;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

/**
 * A turn that died while its tools were working, picked back up.
 *
 * <p>The property under test is the reason claims exist at all: a tool that already answered MUST
 * NOT run again. Without the claimed asking message, recovery means asking the model afresh, which
 * hands back new call ids, which makes every stored answer unreachable — and every tool runs a
 * second time. For a tool that charges a card or sends a message, that is not merely wasteful.
 *
 * <p>Two tools, deliberately: one answers immediately, the other defers and never comes back. That
 * leaves the turn in a stable half-answered state rather than a racy one, so killing it is
 * deterministic.
 */
@DisplayName("A turn resumed mid-tools")
class TurnResumeTest {

  record Job(String what) {}

  private static final AgentType WATCHMAN = AgentType.of("watchman");
  private static final AgentId HOUSE = AgentId.of("house-12");
  private static final String TURN = "turn-under-test";

  private static ActorTestKit testKit;
  private static final AtomicInteger quickRuns = new AtomicInteger();
  private static final AtomicInteger slowRuns = new AtomicInteger();

  private static Memory memory;
  private static Claims claims;
  private static TurnActor.Dependencies deps;

  private static Tool<Job> quick() {
    return tool("quick", input -> Awaited.ready(ToolResult.ok("done")), quickRuns);
  }

  private static Tool<Job> neverAnswers() {
    return tool(
        "slow", input -> Awaited.deferred(Instant.now().plus(1, ChronoUnit.HOURS)), slowRuns);
  }

  private static Tool<Job> tool(
      String name, java.util.function.Function<Job, Awaited<ToolResult>> body, AtomicInteger runs) {
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
        return name;
      }

      @Override
      public String description() {
        return name;
      }

      @Override
      public Awaited<ToolResult> execute(Job input, ReplyToken replyTo) {
        runs.incrementAndGet();
        return body.apply(input);
      }
    };
  }

  /** Asks for both tools once, then would talk — but this turn never gets that far. */
  private static Model asksForBoth() {
    return new Model() {
      @Override
      public ModelId id() {
        return ModelId.of("scripted");
      }

      @Override
      public org.jwcarman.nessy.spi.model.ModelStream stream(ModelRequest request) {
        boolean answered =
            request.context().messages().stream().anyMatch(ToolResultMessage.class::isInstance);
        if (answered) {
          return Scripts.saying(
              new ModelResult.Replied(
                  new AssistantMessage(List.of(new TextBlock("all done"))),
                  StopReason.END_TURN,
                  new Usage(1, 1)));
        }
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.put("what", "something");
        return Scripts.saying(
            new ModelResult.Replied(
                new AssistantMessage(
                    List.of(
                        (AssistantContentBlock)
                            new ToolCallBlock(new ToolCall("c1", "quick", arguments)),
                        (AssistantContentBlock)
                            new ToolCallBlock(new ToolCall("c2", "slow", arguments)))),
                StopReason.TOOL_USE,
                new Usage(1, 1)));
      }
    };
  }

  @BeforeAll
  static void wire() {
    testKit = ClusterOfOne.start();
    InMemorySubstrate substrate = new InMemorySubstrate(Clock.systemUTC());
    memory = new Transcripts(substrate, WATCHMAN);
    claims = new Claims(substrate);
    deps =
        new TurnActor.Dependencies(
            WATCHMAN,
            memory,
            asksForBoth(),
            "you watch the house",
            1024,
            new ToolBindings(
                List.of(
                    new ToolBinding<>(
                        quick(),
                        org.jwcarman.nessy.api.tool.Approver.always(),
                        ToolDescriber.byToString()),
                    new ToolBinding<>(
                        neverAnswers(),
                        org.jwcarman.nessy.api.tool.Approver.always(),
                        ToolDescriber.byToString())),
                EngineMapper.INSTANCE),
            java.util.Set.<org.jwcarman.nessy.spi.model.Capability>of(),
            Narrator.silent(),
            claims,
            ReplyTokens.ephemeral(),
            Runnable::run);
  }

  @AfterAll
  static void stop() {
    testKit.shutdownTestKit();
  }

  @Test
  @DisplayName("a tool that already answered does not run a second time")
  void resuming_re_runs_only_what_is_still_outstanding() {
    TestProbe<NessyMessage> agent = testKit.createTestProbe();

    ActorRef<TurnActor.Command> first =
        testKit.spawn(TurnActor.create(deps, HOUSE, TURN, UserMessage.of("go"), agent.ref()));

    // The quick tool answers and is claimed; the slow one parks. Stable, not racy.
    await()
        .atMost(15, SECONDS)
        .untilAsserted(
            () -> {
              assertThat(quickRuns).hasValue(1);
              assertThat(slowRuns).hasValue(1);
              assertThat(claims.get(HOUSE, TURN, "result-c1")).isPresent();
              assertThat(claims.get(HOUSE, TURN, "result-c2")).isEmpty();
            });

    // The process dies with the turn half answered.
    testKit.stop(first);

    testKit.spawn(TurnActor.create(deps, HOUSE, TURN, UserMessage.of("go"), agent.ref()));

    await()
        .atMost(15, SECONDS)
        .untilAsserted(
            () -> {
              // Only the outstanding one ran again. This is the whole point.
              assertThat(slowRuns).hasValue(2);
              assertThat(quickRuns).hasValue(1);
            });
  }

  @Test
  void the_input_is_not_remembered_twice_by_a_resumed_turn() {
    assertThat(memory.recall(HOUSE).lines())
        .filteredOn(line -> line.role().equals("user"))
        .hasSize(1);
  }
}
