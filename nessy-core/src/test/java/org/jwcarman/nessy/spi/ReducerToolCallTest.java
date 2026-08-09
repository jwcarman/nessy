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
package org.jwcarman.nessy.spi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Event;
import org.jwcarman.nessy.api.Message;
import org.jwcarman.nessy.api.SessionId;
import org.jwcarman.nessy.api.SessionState;
import org.jwcarman.nessy.api.SessionStatus;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.TextBlock;
import org.jwcarman.nessy.api.ToolCall;
import org.jwcarman.nessy.api.ToolUseBlock;
import org.jwcarman.nessy.api.Usage;

class ReducerToolCallTest {

  private final Reducer reducer = Reducer.defaults();
  private final SessionState initial = SessionState.newSession(new SessionId("s1"));

  private static ToolCall call(String id, String name) {
    ObjectNode args = JsonNodeFactory.instance.objectNode();
    args.put("path", "pom.xml");
    return new ToolCall(id, name, args);
  }

  @Test
  void a_requested_call_is_recorded_as_a_block_and_as_pending_work() {
    ToolCall toolCall = call("c1", "read_file");

    Step step = reducer.reduce(initial, new Event.ToolCallRequested(toolCall));

    assertThat(step.state().pendingBlocks()).containsExactly(new ToolUseBlock(toolCall));
    assertThat(step.state().pendingCalls()).containsExactly(toolCall);
    assertThat(step.effects()).isEmpty();
  }

  @Test
  void turn_end_with_calls_asks_for_approval_of_the_first() {
    ToolCall first = call("c1", "read_file");
    ToolCall second = call("c2", "grep");

    SessionState state = reducer.reduce(initial, new Event.TextDelta("Let me look.")).state();
    state = reducer.reduce(state, new Event.ToolCallRequested(first)).state();
    state = reducer.reduce(state, new Event.ToolCallRequested(second)).state();

    Step step = reducer.reduce(state, new Event.ModelTurnEnded(StopReason.TOOL_USE, Usage.zero()));

    assertThat(step.state().status()).isEqualTo(SessionStatus.AWAITING_APPROVAL);
    assertThat(step.effects()).containsExactly(new Effect.RequestApproval(first));
  }

  @Test
  void turn_end_settles_text_and_tool_use_blocks_into_one_assistant_message() {
    ToolCall toolCall = call("c1", "read_file");

    SessionState state = reducer.reduce(initial, new Event.TextDelta("Looking.")).state();
    state = reducer.reduce(state, new Event.ToolCallRequested(toolCall)).state();

    Step step = reducer.reduce(state, new Event.ModelTurnEnded(StopReason.TOOL_USE, Usage.zero()));

    assertThat(step.state().messages())
        .containsExactly(
            Message.assistant(List.of(new TextBlock("Looking."), new ToolUseBlock(toolCall))));
    assertThat(step.state().pendingBlocks()).isEmpty();
    assertThat(step.state().pendingCalls()).containsExactly(toolCall);
  }
}
