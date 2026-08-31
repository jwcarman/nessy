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
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.block.AssistantContentBlock;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.block.ToolCallBlock;
import org.jwcarman.nessy.api.memory.Memory;
import org.jwcarman.nessy.api.message.AnswerMessage;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.api.model.ModelResult;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolBinding;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolDescriber;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.HouseEvents.HouseEvent;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

/**
 * A turn that uses a tool: ask, approve, run, answer, ask again.
 *
 * <p>The exchange is the thing under test. A model reply naming a call and the message answering it
 * are one fact, and the transcript must never hold the first without the second — so this asserts
 * the finished shape rather than the steps.
 */
@DisplayName("A turn that calls a tool")
class ToolCallTest {

  record Query(String text) {}

  private static final AgentType WATCHMAN = AgentType.of("watchman");
  private static final EntityTypeKey<NessyMessage> KEY =
      EntityTypeKey.create(NessyMessage.class, WATCHMAN.name());

  private static ActorTestKit testKit;
  private static Memory memory;

  /** Everything the engine said out loud, in order. */
  private static final List<AgentEvent> narrated = new CopyOnWriteArrayList<>();

  private static Tool<Query> lookUp(String answer) {
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
      public Awaited<ToolResult> execute(Query input, ToolContext context) {
        return Awaited.ready(ToolResult.ok(answer + " for " + input.text()));
      }
    };
  }

  /**
   * Asks for a tool until it has been answered, then talks.
   *
   * <p>Decided from the CONTEXT rather than a call counter: a counter is per-model state, and one
   * model shared by several tests scripts them in whatever order the runner picks.
   */
  private static Model asksThenAnswers() {
    return new Model() {
      @Override
      public ModelId id() {
        return ModelId.of("scripted");
      }

      @Override
      public org.jwcarman.nessy.spi.model.ModelStream stream(ModelRequest request) {
        boolean toolAlreadyAnswered =
            request.context().messages().stream().anyMatch(ToolResultMessage.class::isInstance);
        if (!toolAlreadyAnswered) {
          ObjectNode arguments = JsonNodeFactory.instance.objectNode();
          arguments.put("text", "the kitchen");
          return Scripts.saying(
              new ModelResult.Answered(
                  new AnswerMessage(
                      List.of(
                          (AssistantContentBlock)
                              new ToolCallBlock(new ToolCall("c1", "look_up", arguments)))),
                  StopReason.TOOL_USE,
                  new Usage(1, 1)));
        }
        String heard = request.context().messages().size() + " messages seen";
        return Scripts.saying(
            new ModelResult.Answered(
                new AnswerMessage(List.of(new TextBlock(heard))),
                StopReason.END_TURN,
                new Usage(1, 1)));
      }
    };
  }

  private static void start(Approver approver) {
    testKit = ClusterOfOne.start();
    memory = new Transcripts(new InMemorySubstrate(Clock.systemUTC()), WATCHMAN);
    StateTypes.of(testKit.system()).register(WATCHMAN, HouseEvent.class);

    ToolBinding<Query> binding =
        new ToolBinding<>(lookUp("found it"), approver, ToolDescriber.byToString());
    TurnActor.Dependencies turnDeps =
        new TurnActor.Dependencies(
            WATCHMAN,
            memory,
            asksThenAnswers(),
            "you watch the house",
            1024,
            new ToolBindings(List.of(binding), EngineMapper.INSTANCE),
            java.util.Set.<org.jwcarman.nessy.spi.model.Capability>of(),
            Narrator.to(narrated::add),
            new Claims(new InMemorySubstrate(Clock.systemUTC())),
            ReplyTokens.ephemeral(),
            Runnable::run,
            Traces.noop());

    Turns turns =
        (agentId, turnId, input, agent, carried) ->
            TurnActor.create(turnDeps, agentId, turnId, input, agent, java.util.Map.of());

    AgentActor.Dependencies<HouseEvent> deps =
        new AgentActor.Dependencies<>(
            WATCHMAN,
            HouseEvents.CODEC,
            HouseEvents.KEEP_ALL,
            HouseEvents.RENDERER,
            turns,
            Clock.systemUTC(),
            Traces.noop());

    ClusterSharding.get(testKit.system())
        .init(
            Entity.of(
                    KEY,
                    context ->
                        AgentActor.create(
                            deps, AgentId.of(context.getEntityId()), context.getShard()))
                .withStopMessage(new NessyMessage.Stop(Map.of())));
  }

  @AfterAll
  static void stop() {
    if (testKit != null) {
      testKit.shutdownTestKit();
    }
  }

  private static void observe(String agentId, HouseEvent event) {
    ClusterSharding.get(testKit.system())
        .entityRefFor(KEY, agentId)
        .tell(new NessyMessage.Observe(HouseEvents.CODEC.encode(event), Map.of()));
  }

  @BeforeAll
  static void startApproving() {
    start(Approver.always());
  }

  @Test
  @DisplayName("the exchange is remembered whole: call and answer together")
  void a_tool_call_and_its_answer_reach_the_transcript_as_a_pair() {
    observe("house-12", new HouseEvent("kitchen", "door opened"));

    await()
        .atMost(15, SECONDS)
        .untilAsserted(
            () -> {
              Context context = memory.recall(AgentId.of("house-12"));
              assertThat(context.messages()).hasSize(4);
              assertThat(context.messages().get(1)).isInstanceOf(AnswerMessage.class);
              assertThat(context.messages().get(2)).isInstanceOf(ToolResultMessage.class);
              ToolResultMessage results = (ToolResultMessage) context.messages().get(2);
              assertThat(results.blocks()).hasSize(1);
              assertThat(results.blocks().getFirst().toolUseId()).isEqualTo("c1");
              assertThat(results.blocks().getFirst().isError()).isFalse();
            });
  }

  @Test
  @DisplayName("the engine narrates the whole story of the call")
  void narration_tells_the_turn_and_the_call() {
    narrated.clear();
    observe("house-99", new HouseEvent("porch", "bell"));

    await()
        .atMost(15, SECONDS)
        .untilAsserted(
            () -> {
              assertThat(narrated).isNotEmpty();
              assertThat(narrated).last().isInstanceOf(AgentEvent.TurnEnded.class);
              assertThat(narrated)
                  .extracting(event -> event.getClass().getSimpleName())
                  .containsSubsequence(
                      "TurnStarted",
                      "Answered",
                      "ToolCallRequested",
                      "ApprovalDecided",
                      "ToolCallCompleted",
                      "Answered",
                      "TurnEnded");
            });
  }

  @Test
  void every_narrated_event_carries_a_time_ordered_id() {
    assertThat(narrated).isNotEmpty();
    List<String> ids = narrated.stream().map(AgentEvent::id).toList();

    assertThat(ids).doesNotHaveDuplicates();
    assertThat(ids).isSorted();
  }

  @Test
  void the_tool_actually_ran_and_its_answer_is_what_came_back() {
    observe("house-13", new HouseEvent("hall", "motion"));

    await()
        .atMost(15, SECONDS)
        .untilAsserted(
            () -> {
              Context context = memory.recall(AgentId.of("house-13"));
              assertThat(context.messages()).hasSize(4);
              ToolResultMessage results = (ToolResultMessage) context.messages().get(2);
              assertThat(results.blocks().getFirst().content())
                  .containsExactly(new TextBlock("found it for the kitchen"));
            });
  }
}
