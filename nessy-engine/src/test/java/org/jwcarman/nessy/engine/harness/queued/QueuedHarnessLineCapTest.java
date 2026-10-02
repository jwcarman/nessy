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
package org.jwcarman.nessy.engine.harness.queued;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolConfig;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.inference.InferenceResult;

/**
 * The queued door's own wiring applies the cap to the two lines a tool call leaves in the stream,
 * whatever the application's stringifiers wrote.
 */
@Tag("container")
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class QueuedHarnessLineCapTest {

  private static final AgentType TYPE = new AgentType("capped");
  private static final Duration PATIENCE = Duration.ofSeconds(30);

  private final EngineFixture engine =
      new EngineFixture(
          (request, narrator) -> {
            if (request.context().activeTurn().exchanges().isEmpty()) {
              return new InferenceResult.Actions(
                  List.of(new Block.ToolCall("c1", "lookup", "{\"q\":\"x\"}")),
                  Usage.unreported("a-model"));
            }
            return new InferenceResult.Answer(
                List.of(new Block.Text("done")), Usage.unreported("a-model"));
          });

  record Query(String q) {}

  private static Tool<Query> lookup() {
    return new Tool<>() {
      @Override
      public Class<Query> inputType() {
        return Query.class;
      }

      @Override
      public ToolName name() {
        return new ToolName("lookup");
      }

      @Override
      public String description() {
        return "looks a thing up";
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<Query> request) {
        return Awaited.ready(ToolResult.ok(new Block.Text("1412 metres")));
      }
    };
  }

  @AfterEach
  void stopEngine() {
    engine.close();
  }

  @Test
  void the_harness_cuts_a_named_stringifier_at_the_cap() {
    AgentId agent = AgentId.random();
    QueuedHarness<String> harness =
        engine
            .harnesses()
            .create(
                TYPE,
                String.class,
                c ->
                    c.systemPrompt("You are terse.")
                        .tool(
                            lookup(),
                            t -> t.action(x -> "a".repeat(3000)).result(x -> "r".repeat(3000)))
                        .effects(e -> e.pollInterval(Duration.ofMillis(100))));

    harness.tell(agent, "look it up");
    await()
        .atMost(PATIENCE)
        .untilAsserted(
            () ->
                assertThat(engine.history().forAgent(TYPE, agent).completedAfter(Optional.empty()))
                    .hasSize(1));

    List<AgentEvent> story = engine.story(TYPE, agent);
    assertThat(story)
        .filteredOn(AgentEvent.ActionsRequested.class::isInstance)
        .map(AgentEvent.ActionsRequested.class::cast)
        .singleElement()
        .satisfies(
            requested ->
                assertThat(requested.actions())
                    .singleElement()
                    .asInstanceOf(InstanceOfAssertFactories.type(ActionRequest.ToolCall.class))
                    .extracting(ActionRequest.ToolCall::action)
                    .asString()
                    .hasSize(ToolConfig.LINE_CAP));
    assertThat(story)
        .filteredOn(AgentEvent.ToolSucceeded.class::isInstance)
        .map(AgentEvent.ToolSucceeded.class::cast)
        .singleElement()
        .satisfies(succeeded -> assertThat(succeeded.rendered()).hasSize(ToolConfig.LINE_CAP));
  }
}
