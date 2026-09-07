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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.block.ToolCallBlock;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.api.model.ModelResult;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.api.tool.ActionRenderer;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolBinding;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.HouseEvents.HouseEvent;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * F2: the stall sweep against an agent that is busy but perfectly alive.
 *
 * <p>The sweep exists to replace obligations that were LOST -- a node that died between committing
 * a decision and its rows being run. It reads "busy and not touched lately" off {@code nessy_agent}
 * alone, and a busy agent's {@code last_touched_at} never moves (recovery folds to an IDENTICAL
 * state, so {@code Transition} skips the save), so an agent legitimately working longer than the
 * stall window stayed in the sweep's result set forever and was recovered on EVERY pass. For a
 * running tool call that meant {@code resume()} emitting a fresh {@code RunTool} row every thirty
 * seconds against a tool that had already been invoked -- the exact re-invocation {@code
 * EffectWorker} refuses to do for a throw, arriving by another road.
 *
 * <p>Driven by calling {@link AgentRuntime#recover} directly rather than by waiting on the real
 * thirty-second timer: the sweep IS a loop around that call, and a test that waited for it would be
 * a ninety-second test measuring {@code Sweeps} rather than recovery.
 */
@DisplayName("The stall sweep and a busy agent")
class StallSweepTest {

  private static final AgentType WATCHMAN = AgentType.of("watchman");
  private static final CallId CALL = CallId.of("c1");

  record Query(String text) {}

  /** How many times the tool was actually entered. The whole measurement. */
  private final AtomicInteger invocations = new AtomicInteger();

  /** Never counted down: the call stays Running for the life of the test, as a slow tool does. */
  private final CountDownLatch neverAnswers = new CountDownLatch(1);

  private Engines.Parts parts;

  @BeforeEach
  void start() {
    ToolBinding<Query> binding =
        new ToolBinding<>(blocking(), Approver.always(), ActionRenderer.byToString());
    parts = Engines.of(WATCHMAN, alwaysAsks(), List.of(binding));
  }

  @AfterEach
  void stop() {
    neverAnswers.countDown();
    parts.close();
  }

  @Test
  @DisplayName(
      "an agent busy with a running tool call is not recovered at all -- the tool it is already"
          + " running is invoked exactly once, however many sweeps pass over it")
  void a_busy_agent_is_never_recovered_while_its_obligations_are_outstanding() {
    AgentId agent = AgentId.of("house-busy");
    Engines.observe(parts, agent, new HouseEvent("kitchen", "door opened"));
    await().atMost(15, SECONDS).untilAsserted(() -> assertThat(invocations).hasValue(1));
    assertThat(outstandingRows(agent)).isEqualTo(1);

    for (int sweep = 0; sweep < 3; sweep++) {
      parts.runtime().recover(agent);
      settle();
    }

    assertThat(invocations)
        .as(
            "the tool was already running and nothing was lost, so recovery had nothing to"
                + " replace -- a second invocation is a side effect nobody asked for")
        .hasValue(1);
    assertThat(outstandingRows(agent))
        .as("no sweep added an obligation beside the one the agent already owed")
        .isEqualTo(1);
  }

  @Test
  @DisplayName(
      "an agent whose obligations were lost out from under it IS recovered -- the case the sweep"
          + " exists for")
  void an_agent_that_really_lost_its_obligations_is_recovered() {
    AgentId agent = AgentId.of("house-lost");
    Engines.observe(parts, agent, new HouseEvent("kitchen", "door opened"));
    await().atMost(15, SECONDS).untilAsserted(() -> assertThat(invocations).hasValue(1));

    // What a node dying between commit and run cannot be made to do on demand, spelled as the
    // state it leaves behind: an agent whose state says busy and whose rows are simply gone.
    deleteRows(agent);
    assertThat(outstandingRows(agent)).isZero();

    parts.runtime().recover(agent);

    await()
        .atMost(15, SECONDS)
        .untilAsserted(
            () ->
                assertThat(invocations)
                    .as("the lost RunTool obligation was re-emitted and run")
                    .hasValue(2));
  }

  /** Long enough for a recovery's own effects, had it emitted any, to be polled and run. */
  private void settle() {
    try {
      Thread.sleep(300L);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private int outstandingRows(AgentId agent) {
    Integer count =
        JdbcClient.create(parts.dataSource())
            .sql("SELECT COUNT(*) FROM nessy_effect WHERE agent_type = ? AND agent_id = ?")
            .param(WATCHMAN.name())
            .param(agent.value())
            .query(Integer.class)
            .single();
    return count == null ? 0 : count;
  }

  private void deleteRows(AgentId agent) {
    JdbcClient.create(parts.dataSource())
        .sql("DELETE FROM nessy_effect WHERE agent_type = ? AND agent_id = ?")
        .param(WATCHMAN.name())
        .param(agent.value())
        .update();
  }

  /** A tool that is entered, counted, and never finishes -- a ten-minute call, in a test. */
  private Tool<Query> blocking() {
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
        return "slow_tool";
      }

      @Override
      public String description() {
        return "takes a very long time";
      }

      @Override
      public Awaited<ToolResult> execute(ToolCallRequest<Query> call) {
        invocations.incrementAndGet();
        try {
          neverAnswers.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return Awaited.ready(ToolResult.ok("finally"));
      }
    };
  }

  /** A model that asks for the slow tool, every time it is asked anything. */
  private static Model alwaysAsks() {
    return new Model() {
      @Override
      public ModelId id() {
        return ModelId.of("scripted");
      }

      @Override
      public ModelStream stream(ModelRequest request) {
        ObjectNode arguments = JsonNodeFactory.instance.objectNode();
        arguments.put("text", "the kitchen");
        return Scripts.saying(
            new ModelResult.Asked(
                List.of(new ToolCallBlock(new ToolCall(CALL, "slow_tool", arguments))),
                new Usage(1, 1)));
      }
    };
  }
}
