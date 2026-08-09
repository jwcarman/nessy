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
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Event;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.SessionId;
import org.jwcarman.nessy.api.SessionStatus;
import org.jwcarman.nessy.api.ToolResult;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.event.EventHub;
import org.jwcarman.nessy.api.event.SessionEvent;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.spi.ExecutionEngine;
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
  void aFullToolCallingConversationRunsEndToEnd() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder()
            .text("Let me add those.")
            .toolUse("c1", "add", addArgs(2, 2))
            .endWithToolUse()
            .text("The answer is 4.")
            .endTurn()
            .build();
    EventHub hub = EventHub.synchronous();
    RecordingSubscriber subscriber = new RecordingSubscriber();
    subscriber.attachTo(hub);

    ExecutionEngine engine =
        Nessy.builder()
            .provider(provider)
            .model("fake-model")
            .systemPrompt("be helpful")
            .tools(ToolRegistry.of(new AddTool()))
            .approver(Approver.allowAll())
            .events(hub)
            .build();

    RunOutcome outcome = engine.run(new SessionId("s1"), new Event.UserSaid("what is 2+2?"));

    RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
    assertThat(completed.state().status()).isEqualTo(SessionStatus.COMPLETE);
    assertThat(completed.state().messages()).hasSize(4);
    assertThat(subscriber.ofType(SessionEvent.class)).isNotEmpty();
  }

  @Test
  void theToolSchemaReachesTheModel() {
    ScriptedModelProvider provider = ScriptedModelProvider.builder().text("Hi").endTurn().build();

    Nessy.builder()
        .provider(provider)
        .model("fake-model")
        .tools(ToolRegistry.of(new AddTool()))
        .build()
        .run(new SessionId("s1"), new Event.UserSaid("hello"));

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
  void requestedCapabilitiesReachTheProvider() {
    ScriptedModelProvider provider = ScriptedModelProvider.builder().text("Hi").endTurn().build();

    Nessy.builder()
        .provider(provider)
        .model("fake-model")
        .capabilities(Set.of(Capability.PROMPT_CACHING))
        .build()
        .run(new SessionId("s1"), new Event.UserSaid("hello"));

    assertThat(provider.requests().getFirst().requested())
        .containsExactly(Capability.PROMPT_CACHING);
  }

  @Test
  void aMissingModelIsRejectedAtBuildTime() {
    assertThatThrownBy(() -> Nessy.builder().model("fake-model").build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("model");
  }
}
