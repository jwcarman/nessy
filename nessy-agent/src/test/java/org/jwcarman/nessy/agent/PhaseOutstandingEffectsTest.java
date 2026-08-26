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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;

class PhaseOutstandingEffectsTest {

  private static final ToolCall CALL_A =
      new ToolCall("a", "lookup", JsonNodeFactory.instance.objectNode());
  private static final ToolCall CALL_B =
      new ToolCall("b", "restart", JsonNodeFactory.instance.objectNode());
  private static final Message TURN =
      Message.assistant(
          List.<ContentBlock>of(new ToolUseBlock(CALL_A, "sig-a"), new ToolUseBlock(CALL_B, null)));

  @Test
  void idleHasNothingOutstanding() {
    assertThat(new AgentPhase.Idle().outstandingEffects()).isEmpty();
  }

  @Test
  void awaitingModelReDerivesItsBareModelCall() {
    assertThat(new AgentPhase.AwaitingModel().outstandingEffects())
        .containsExactly(new Effect.CallModel());
  }

  @Test
  void awaitingToolsReDerivesOnlyTheUnsettledCallsWithFullArguments() {
    var phase =
        new AgentPhase.AwaitingTools(
            TURN,
            Map.of(
                "a",
                new ToolCallState.Finished(new ToolResultBlock("a", "42", false)),
                "b",
                new ToolCallState.Pending()),
            ModelResponseId.of("response-1"));
    assertThat(phase.outstandingEffects()).containsExactly(new Effect.SeekApproval(CALL_B));
  }

  @Test
  void awaitingToolsReDerivesInTheAssistantTurnsOwnOrder() {
    var phase =
        new AgentPhase.AwaitingTools(
            TURN,
            Map.of("b", new ToolCallState.Pending(), "a", new ToolCallState.Pending()),
            ModelResponseId.of("response-1"));
    assertThat(phase.outstandingEffects())
        .containsExactly(new Effect.SeekApproval(CALL_A), new Effect.SeekApproval(CALL_B));
  }
}
