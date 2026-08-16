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
package org.jwcarman.nessy.model.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Conversation;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;

/**
 * Exercises {@link OpenAiModelProvider} against the real OpenAI API.
 *
 * <p>Every test starts with {@code assumeTrue} on {@code OPENAI_API_KEY} so the default, keyless
 * build (which excludes the {@code live} tag entirely — see the root {@code pom.xml}'s {@code
 * nessy.excludedGroups}) never depends on network access, and a stray {@code -Dtest=...} run
 * without the key skips cleanly instead of failing. This is the intended tinkering entry point for
 * this module: point {@code OPENAI_API_KEY} at a real key and run it directly.
 */
@Tag("live")
class OpenAiLiveTest {

  // OpenAI's current cheapest general-purpose chat model as of this writing; matches the model
  // used to smoke-test this module's request/stream translation during development.
  private static final String MODEL = "gpt-4o-mini";

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
    public Awaited<ToolResult> execute(Add input, ToolContext context) {
      return Awaited.ready(ToolResult.ok(String.valueOf(input.left() + input.right())));
    }
  }

  @Test
  void a_real_conversation_answers() {
    assumeTrue(System.getenv("OPENAI_API_KEY") != null, "OPENAI_API_KEY not set");

    Agent<String> agent =
        Nessy.harness(h -> h.provider(OpenAiModelProvider.builder().fromEnv().build()))
            .agent(a -> a.name("openai-live").model(MODEL).maxTokens(64));

    TextObserver observer = new TextObserver();
    RunOutcome outcome = agent.converse().tell("Reply with exactly: pong", observer);

    assertThat(observer.text()).contains("pong");
    assertThat(outcome.state().usage().inputTokens()).isGreaterThan(0);
  }

  @Test
  void a_real_tool_call_round_trips() {
    assumeTrue(System.getenv("OPENAI_API_KEY") != null, "OPENAI_API_KEY not set");

    Agent<String> agent =
        Nessy.harness(h -> h.provider(OpenAiModelProvider.builder().fromEnv().build()))
            .agent(
                a ->
                    a.name("openai-live")
                        .model(MODEL)
                        .maxTokens(256)
                        .tools(ToolGrant.grant(new AddTool(), UsagePolicy.allow())));

    Conversation<String> conversation = agent.converse();
    TextObserver observer = new TextObserver();
    conversation.tell("What is 2+2? Use the add tool to compute it.", observer);

    assertThat(observer.text()).contains("4");
    boolean hasToolResult =
        agent.contextFor(conversation.conversationId()).messages().stream()
            .flatMap(message -> message.content().stream())
            .anyMatch(ToolResultBlock.class::isInstance);
    assertThat(hasToolResult).isTrue();
  }

  /**
   * Template for pointing this provider at any OpenAI-compatible endpoint instead of OpenAI itself
   * — an OpenRouter account, or a local Ollama server. Swap {@code baseUrl}, {@code apiKey}, and
   * {@code MODEL} for the target endpoint's values and enable manually; this is not run as part of
   * the {@code live} suite because it depends on infrastructure outside OpenAI's own API.
   */
  @Test
  @Disabled("manual: point at a local or OpenRouter endpoint")
  void a_real_conversation_answers_through_an_openai_compatible_endpoint() {
    Agent<String> agent =
        Nessy.harness(
                h ->
                    h.provider(
                        OpenAiModelProvider.builder()
                            .apiKey(System.getenv("OPENROUTER_API_KEY"))
                            .baseUrl("https://openrouter.ai/api/v1")
                            .build()))
            .agent(a -> a.name("openai-live").model("openai/gpt-4o-mini").maxTokens(64));

    TextObserver observer = new TextObserver();
    agent.converse().tell("Reply with exactly: pong", observer);

    assertThat(observer.text()).contains("pong");
  }
}
