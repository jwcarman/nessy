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
package org.jwcarman.nessy.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Conversation;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.Reply;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.SessionId;
import org.jwcarman.nessy.api.TerminationPolicy;
import org.jwcarman.nessy.api.ToolResult;
import org.jwcarman.nessy.api.event.SessionEvent;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;

class AgentFacadeTest {

  record Add(int left, int right) {}

  static final class AddTool implements Tool<Add> {
    @Override
    public String name() {
      return "add";
    }

    @Override
    public String description() {
      return "Adds two integers";
    }

    @Override
    public Class<Add> inputType() {
      return Add.class;
    }

    @Override
    public boolean requiresApproval() {
      return false;
    }

    @Override
    public Awaited<ToolResult> execute(Add input, ToolContext context) {
      return Awaited.ready(ToolResult.ok(String.valueOf(input.left() + input.right())));
    }
  }

  private static ObjectNode addArgs(int left, int right) {
    ObjectNode args = JsonNodeFactory.instance.objectNode();
    args.put("left", left);
    args.put("right", right);
    return args;
  }

  @Test
  void the_five_minute_path_is_five_lines() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder()
            .toolUse("c1", "add", addArgs(2, 2))
            .endWithToolUse()
            .text("The answer is 4.")
            .endTurn()
            .build();

    Agent agent = Nessy.agent().provider(provider).model("fake-model").tools(new AddTool()).build();
    Reply reply = agent.converse().send("what is 2+2?");

    assertThat(reply.text()).isEqualTo("The answer is 4.");
    assertThat(reply.failed()).isFalse();
  }

  @Test
  void conversations_carry_their_session_across_sends() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder()
            .text("Hello!")
            .endTurn()
            .text("Still here.")
            .endTurn()
            .build();
    Agent agent = Nessy.agent().provider(provider).model("fake-model").build();

    Conversation chat = agent.converse();
    chat.send("hi");
    Reply second = chat.send("you there?");

    assertThat(second.text()).isEqualTo("Still here.");
    assertThat(second.state().messages()).hasSize(4);
  }

  @Test
  void the_hub_is_reachable_from_the_agent() {
    ScriptedModelProvider provider = ScriptedModelProvider.builder().text("Hi").endTurn().build();
    Agent agent = Nessy.agent().provider(provider).model("fake-model").build();
    RecordingSubscriber recorder = new RecordingSubscriber();
    recorder.attachTo(agent.events());

    agent.converse().send("hello");

    assertThat(recorder.ofType(SessionEvent.class)).isNotEmpty();
  }

  @Test
  void a_missing_provider_is_rejected_at_build_time() {
    assertThatThrownBy(() -> Nessy.agent().model("fake-model").build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("provider");
  }

  @Test
  void a_missing_model_is_rejected_at_build_time() {
    ScriptedModelProvider provider = ScriptedModelProvider.builder().text("Hi").endTurn().build();

    assertThatThrownBy(() -> Nessy.agent().provider(provider).build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("model");
  }

  @Test
  void reply_text_excludes_thinking_prose() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder()
            .thinking("Let me think.")
            .text("The answer is 4.")
            .endTurn()
            .build();
    Agent agent = Nessy.agent().provider(provider).model("fake-model").build();

    Reply reply = agent.converse().send("what is 2+2?");

    assertThat(reply.text()).isEqualTo("The answer is 4.");
  }

  @Test
  void a_conversation_resumes_by_session_id_with_its_history() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder()
            .text("Hello!")
            .endTurn()
            .text("Still here.")
            .endTurn()
            .build();
    Agent agent = Nessy.agent().provider(provider).model("fake-model").build();

    Conversation first = agent.converse();
    first.send("hi");
    SessionId sessionId = first.sessionId();

    Reply second = agent.resume(sessionId).send("you there?");

    assertThat(second.state().messages()).hasSize(4);
  }

  @Test
  void failure_reason_surfaces_through_reply() {
    ScriptedModelProvider provider = ScriptedModelProvider.builder().text("Hi").endTurn().build();
    Agent agent =
        Nessy.agent()
            .provider(provider)
            .model("fake-model")
            .termination(TerminationPolicy.maxTurns(1))
            .build();
    Conversation chat = agent.converse();
    chat.send("hi");

    // Turn 1 already reached the ceiling, so this send halts on userSaid before the
    // reducer would ask the model for a second turn: the scripted provider is never called
    // again, and the second script entry (if any) would simply go unconsumed.
    Reply second = chat.send("still there?");

    assertThat(second.failed()).isTrue();
    assertThat(second.failureReason()).isPresent();
    assertThat(second.failureReason().orElseThrow()).contains("turn");
  }
}
