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
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;

class AwaitingToolsPhaseTest {

  private static final ToolCall CALL_A =
      new ToolCall("a", "lookup", JsonNodeFactory.instance.objectNode());
  private static final ToolCall CALL_B =
      new ToolCall("b", "restart", JsonNodeFactory.instance.objectNode());
  private static final Message TURN =
      Message.assistant(
          List.<ContentBlock>of(new ToolUseBlock(CALL_A, "sig-a"), new ToolUseBlock(CALL_B, null)));

  private static AgentEvent.ToolFinished returned(ToolCall call, String content) {
    return new AgentEvent.ToolFinished(call, new ToolOutcome.Returned(ToolResult.ok(content)));
  }

  @Test
  void aPartialResultShrinksPendingAndCommitsNothing() {
    var phase = new Phase.AwaitingTools(TURN, Set.of("a", "b"), List.of());
    var t = phase.handle(returned(CALL_A, "42"));
    assertThat(t.commit()).isEmpty();
    assertThat(t.effects()).isEmpty();
    assertThat(t.next())
        .isEqualTo(
            new Phase.AwaitingTools(
                TURN, Set.of("b"), List.of(new ToolResultBlock("a", "42", false))));
  }

  @Test
  void theLastResultCommitsTheWholeUnitAndCallsTheModel() {
    var gathered = List.of(new ToolResultBlock("a", "42", false));
    var phase = new Phase.AwaitingTools(TURN, Set.of("b"), gathered);
    var t = phase.handle(returned(CALL_B, "ok"));
    assertThat(t.next()).isEqualTo(new Phase.AwaitingModel());
    assertThat(t.commit())
        .containsExactly(
            TURN,
            Message.toolResults(
                List.of(
                    new ToolResultBlock("a", "42", false), new ToolResultBlock("b", "ok", false))));
    assertThat(t.effects()).containsExactly(new Effect.CallModel());
  }

  @Test
  void aFailedToolRendersInBandAsAnErrorResult() {
    var phase = new Phase.AwaitingTools(TURN, Set.of("a", "b"), List.of());
    var failed =
        new AgentEvent.ToolFinished(CALL_A, new ToolOutcome.Failed(new ToolError("timed out")));
    var t = phase.handle(failed);
    assertThat(t.next())
        .isEqualTo(
            new Phase.AwaitingTools(
                TURN, Set.of("b"), List.of(new ToolResultBlock("a", "timed out", true))));
  }

  @Test
  void aDuplicateDeliveryOfASettledCallIsIgnored() {
    var phase =
        new Phase.AwaitingTools(TURN, Set.of("b"), List.of(new ToolResultBlock("a", "42", false)));
    assertThat(phase.handle(returned(CALL_A, "42-again")).isIgnored()).isTrue();
  }

  @Test
  void aStrayModelCompletionIsIgnored() {
    var phase = new Phase.AwaitingTools(TURN, Set.of("a", "b"), List.of());
    var event = new AgentEvent.ModelFinished(new ModelOutcome.Failed("late duplicate"));
    assertThat(phase.handle(event).isIgnored()).isTrue();
  }

  @Test
  void anObservationReachingThisPhaseIsAProgrammingError() {
    var phase = new Phase.AwaitingTools(TURN, Set.of("a"), List.of());
    var event = new AgentEvent.Observed(List.of(new TextBlock("hi")));
    assertThatThrownBy(() -> phase.handle(event)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void awaitingNothingIsNotAPhase() {
    Set<String> empty = Set.of();
    List<ToolResultBlock> none = List.of();
    assertThatThrownBy(() -> new Phase.AwaitingTools(TURN, empty, none))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
