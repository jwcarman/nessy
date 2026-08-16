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
package org.jwcarman.nessy.model.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
 * Exercises {@link GeminiModelProvider} against the real Gemini Developer API.
 *
 * <p>Every test starts with {@code assumeTrue} on {@code GEMINI_API_KEY}/{@code GOOGLE_API_KEY} so
 * the default, keyless build (which excludes the {@code live} tag entirely — see the root {@code
 * pom.xml}'s {@code nessy.excludedGroups}) never depends on network access, and a stray {@code
 * -Dtest=...} run without a key skips cleanly instead of failing. This is the intended tinkering
 * entry point for this module: point {@code GEMINI_API_KEY} at a real key and run it directly.
 *
 * <p><b>Honesty note (provider-expansion design §2):</b> as of this module's creation, these tests
 * have <em>not</em> been executed against a real key — no {@code GEMINI_API_KEY}/{@code
 * GOOGLE_API_KEY} was available in the environment this module was built in. The request/response
 * mapping is grounded in the SDK's own source and documented examples (see {@code
 * GeminiRequests}/{@code GeminiStream}'s javadoc and the task report), and is covered by the
 * offline mapping tests, but the live round-trip below is unvalidated until someone runs it with a
 * real key.
 */
@Tag("live")
class GeminiLiveTest {

  // Google's current cheapest general-purpose Gemini model as of this writing.
  private static final String MODEL = "gemini-3.6-flash";

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

  private static void assumeKeyPresent() {
    assumeTrue(
        System.getenv("GEMINI_API_KEY") != null || System.getenv("GOOGLE_API_KEY") != null,
        "neither GEMINI_API_KEY nor GOOGLE_API_KEY is set");
  }

  @Test
  void a_real_conversation_answers() {
    assumeKeyPresent();

    Agent<String> agent =
        Nessy.harness(GeminiModelProvider.builder().fromEnv().build())
            .build()
            .agent()
            .name("gemini-live")
            .model(MODEL)
            .maxTokens(64)
            .build();

    TextObserver observer = new TextObserver();
    RunOutcome outcome = agent.converse().tell("Reply with exactly: pong", observer);

    assertThat(observer.text()).contains("pong");
    assertThat(outcome.state().usage().inputTokens()).isGreaterThan(0);
  }

  @Test
  void a_real_tool_call_round_trips() {
    assumeKeyPresent();

    Agent<String> agent =
        Nessy.harness(GeminiModelProvider.builder().fromEnv().build())
            .build()
            .agent()
            .name("gemini-live")
            .model(MODEL)
            .maxTokens(256)
            .tools(ToolGrant.grant(new AddTool(), UsagePolicy.allow()))
            .build();

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
}
