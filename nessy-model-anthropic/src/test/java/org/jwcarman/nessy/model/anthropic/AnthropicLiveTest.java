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
package org.jwcarman.nessy.model.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.Reply;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.Role;
import org.jwcarman.nessy.api.message.ThinkingBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.model.Capability;

/**
 * Exercises {@link AnthropicModelProvider} against the real Anthropic API.
 *
 * <p>Every test starts with {@code assumeTrue} on {@code ANTHROPIC_API_KEY} so the default, keyless
 * build (which excludes the {@code live} tag entirely — see the root {@code pom.xml}'s {@code
 * nessy.excludedGroups}) never depends on network access, and a stray {@code -Dtest=...} run
 * without the key skips cleanly instead of failing. This is the intended tinkering entry point for
 * this module: point {@code ANTHROPIC_API_KEY} at a real key and run it directly.
 */
@Tag("live")
class AnthropicLiveTest {

  private static final String MODEL = "claude-haiku-4-5-20251001";

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

  @Test
  void a_real_conversation_answers() {
    assumeTrue(System.getenv("ANTHROPIC_API_KEY") != null, "ANTHROPIC_API_KEY not set");

    Agent<String> agent =
        Nessy.agent()
            .provider(AnthropicModelProvider.builder().fromEnv().build())
            .model(MODEL)
            .maxTokens(64)
            .build();

    Reply reply = agent.converse().tell("Reply with exactly: pong");

    assertThat(reply.text()).contains("pong");
    assertThat(reply.state().usage().inputTokens()).isGreaterThan(0);
  }

  @Test
  void a_real_tool_call_round_trips() {
    assumeTrue(System.getenv("ANTHROPIC_API_KEY") != null, "ANTHROPIC_API_KEY not set");

    Agent<String> agent =
        Nessy.agent()
            .provider(AnthropicModelProvider.builder().fromEnv().build())
            .model(MODEL)
            .maxTokens(256)
            .tools(new AddTool())
            .build();

    Reply reply = agent.converse().tell("What is 2+2? Use the add tool to compute it.");

    assertThat(reply.text()).contains("4");
    boolean hasToolResult =
        reply.state().messages().stream()
            .flatMap(message -> message.content().stream())
            .anyMatch(ToolResultBlock.class::isInstance);
    assertThat(hasToolResult).isTrue();
  }

  @Test
  void real_thinking_round_trips_with_a_signature() {
    assumeTrue(System.getenv("ANTHROPIC_API_KEY") != null, "ANTHROPIC_API_KEY not set");

    // Extended thinking requires headroom above its budget: a small budget keeps this cheap
    // while still leaving room for maxTokens to exceed it, per AnthropicRequests' contract.
    Agent<String> agent =
        Nessy.agent()
            .provider(AnthropicModelProvider.builder().fromEnv().thinkingBudget(1024).build())
            .model(MODEL)
            .maxTokens(2048)
            .capabilities(Set.of(Capability.THINKING))
            .build();

    Reply reply = agent.converse().tell("What is 2+2? Think it through briefly.");

    Message lastAssistantMessage =
        reply.state().messages().stream()
            .filter(message -> message.role() == Role.ASSISTANT)
            .reduce((first, second) -> second)
            .orElseThrow(() -> new AssertionError("no assistant message in the final state"));

    boolean hasSignedThinking =
        lastAssistantMessage.content().stream()
            .filter(ThinkingBlock.class::isInstance)
            .map(ThinkingBlock.class::cast)
            .anyMatch(thinking -> !thinking.signature().isEmpty());
    assertThat(hasSignedThinking).isTrue();
  }
}
