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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.Reply;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Event;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.SessionId;
import org.jwcarman.nessy.api.SessionStatus;
import org.jwcarman.nessy.api.TextBlock;
import org.jwcarman.nessy.api.ThinkingBlock;
import org.jwcarman.nessy.api.ToolResult;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.event.SessionEvent;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.spi.model.Capability;

class EndToEndTest {

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
      return true;
    }

    @Override
    public String describe(Add input) {
      return "add(" + input.left() + ", " + input.right() + ")";
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
  void a_full_tool_calling_conversation_runs_end_to_end() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder()
            .text("Let me add those.")
            .toolUse("c1", "add", addArgs(2, 2))
            .endWithToolUse()
            .text("The answer is 4.")
            .endTurn()
            .build();
    RecordingSubscriber subscriber = new RecordingSubscriber();

    Agent agent =
        Nessy.agent()
            .provider(provider)
            .model("fake-model")
            .systemPrompt("be helpful")
            .tools(new AddTool())
            .build();
    subscriber.attachTo(agent.events());

    Reply reply = agent.converse().send("what is 2+2?");

    assertThat(reply.failed()).isFalse();
    assertThat(reply.state().status()).isEqualTo(SessionStatus.COMPLETE);
    assertThat(reply.state().messages()).hasSize(4);
    assertThat(reply.text()).isEqualTo("The answer is 4.");
    assertThat(subscriber.ofType(SessionEvent.class)).isNotEmpty();
  }

  @Test
  void the_tool_schema_reaches_the_model() {
    ScriptedModelProvider provider = ScriptedModelProvider.builder().text("Hi").endTurn().build();
    Agent agent = Nessy.agent().provider(provider).model("fake-model").tools(new AddTool()).build();

    agent.engine().run(new SessionId("s1"), Event.UserSaid.of("hello"));

    assertThat(provider.requests().getFirst().tools()).hasSize(1);
    assertThat(provider.requests().getFirst().tools().getFirst().name()).isEqualTo("add");
    assertThat(
            provider
                .requests()
                .getFirst()
                .tools()
                .getFirst()
                .inputSchema()
                .get("properties")
                .has("left"))
        .isTrue();
  }

  @Test
  void requested_capabilities_reach_the_provider() {
    ScriptedModelProvider provider = ScriptedModelProvider.builder().text("Hi").endTurn().build();
    Agent agent =
        Nessy.agent()
            .provider(provider)
            .model("fake-model")
            .capabilities(Set.of(Capability.PROMPT_CACHING))
            .build();

    agent.engine().run(new SessionId("s1"), Event.UserSaid.of("hello"));

    assertThat(provider.requests().getFirst().requested())
        .containsExactly(Capability.PROMPT_CACHING);
  }

  @Test
  void usage_accumulates_from_the_model_into_the_final_state() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder().text("hi").endTurn(new Usage(10, 5)).build();
    Agent agent = Nessy.agent().provider(provider).model("fake-model").build();

    RunOutcome outcome = agent.engine().run(new SessionId("s1"), Event.UserSaid.of("hi"));

    RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
    assertThat(completed.state().usage()).isEqualTo(new Usage(10, 5));
    assertThat(completed.state().turns()).isEqualTo(1);
  }

  @Test
  void thinking_chunks_settle_into_a_thinking_block_before_the_answer() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder().thinking("Let me think.").text("Answer.").endTurn().build();
    Agent agent = Nessy.agent().provider(provider).model("fake-model").build();

    RunOutcome outcome = agent.engine().run(new SessionId("s1"), Event.UserSaid.of("hi"));

    RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
    assertThat(completed.state().messages().getLast().content())
        .containsExactly(new ThinkingBlock("Let me think.", ""), new TextBlock("Answer."));
  }
}
