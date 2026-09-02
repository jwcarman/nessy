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
package org.jwcarman.nessy.memory.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.memory.Memory;
import org.jwcarman.nessy.api.message.AmbientMessage;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.ContextMessage;
import org.jwcarman.nessy.api.message.HistoryMessage;
import org.jwcarman.nessy.api.message.UserMessage;

@DisplayName("A memory that recalls through stages")
class PipelineMemoryTest {

  private static final AgentId AGENT = AgentId.of("a");

  /** A memory that is a list. No mocking library, and nothing here needs one. */
  private static final class Remembering implements Memory {

    private final List<HistoryMessage> told = new ArrayList<>();

    @Override
    public Context recall(AgentId agentId) {
      return Context.of(List.copyOf(told));
    }

    @Override
    public void remember(AgentId agentId, HistoryMessage message) {
      told.add(message);
    }

    @Override
    public void forget(AgentId agentId) {
      told.clear();
    }
  }

  private static ContextTransformer adding(String text) {
    return (agentId, context) ->
        Context.of(
            java.util.stream.Stream.concat(
                    context.messages().stream(),
                    java.util.stream.Stream.<ContextMessage>of(
                        new AmbientMessage("test", List.of(new TextBlock(text)))))
                .toList());
  }

  @Test
  void recalls_through_the_stages_in_order() {
    Memory memory =
        MemoryPipeline.of(new Remembering(), p -> p.stage(adding("first")).stage(adding("second")));
    memory.remember(AGENT, UserMessage.of("hello"));

    Context context = memory.recall(AGENT);

    assertThat(context.messages()).hasSize(3);
    assertThat(context.messages().get(1))
        .isEqualTo(new AmbientMessage("test", List.of(new TextBlock("first"))));
    assertThat(context.messages().get(2))
        .isEqualTo(new AmbientMessage("test", List.of(new TextBlock("second"))));
  }

  @Test
  @DisplayName("what a stage adds is never remembered")
  void stage_output_does_not_reach_the_bootstrap() {
    Remembering bootstrap = new Remembering();
    Memory memory = MemoryPipeline.of(bootstrap, p -> p.stage(adding("background")));
    memory.remember(AGENT, UserMessage.of("hello"));

    memory.recall(AGENT);
    memory.recall(AGENT);

    // Two recalls, and the record still holds exactly what happened -- no accumulation, no drift.
    assertThat(bootstrap.recall(AGENT).messages()).containsExactly(UserMessage.of("hello"));
  }

  @Test
  @DisplayName("no stages is the bootstrap, unchanged")
  void an_empty_pipeline_changes_nothing() {
    Remembering bootstrap = new Remembering();
    Memory memory = MemoryPipeline.of(bootstrap, p -> {});
    memory.remember(AGENT, UserMessage.of("hello"));

    assertThat(memory.recall(AGENT)).isEqualTo(bootstrap.recall(AGENT));
  }

  /**
   * A stage that throws fails the turn rather than being papered over: the durable machinery
   * retries it later, and the model never sees a context the stage did not bless.
   */
  @Test
  void a_stage_that_throws_propagates() {
    Memory memory =
        MemoryPipeline.of(
            new Remembering(),
            p ->
                p.stage(
                    (agentId, context) -> {
                      throw new IllegalStateException("the notebook is unreachable");
                    }));

    assertThatThrownBy(() -> memory.recall(AGENT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("notebook is unreachable");
  }

  @Test
  void remembering_goes_straight_through() {
    Remembering bootstrap = new Remembering();
    Memory memory = MemoryPipeline.of(bootstrap, p -> p.stage(adding("background")));

    memory.remember(AGENT, UserMessage.of("said"));

    assertThat(bootstrap.recall(AGENT).messages()).containsExactly(UserMessage.of("said"));
  }

  /**
   * A stage assembles context at recall time and owns nothing it could forget -- what an agent
   * actually said lives in the bootstrap, so {@code forget} must reach it and nothing else.
   */
  @Test
  @DisplayName("forgetting reaches the bootstrap, not the stages")
  void forgetting_delegates_to_the_bootstrap() {
    Remembering bootstrap = new Remembering();
    Memory memory = MemoryPipeline.of(bootstrap, p -> p.stage(adding("background")));
    memory.remember(AGENT, UserMessage.of("said"));

    memory.forget(AGENT);

    assertThat(bootstrap.recall(AGENT).messages()).isEmpty();
  }
}
