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
package org.jwcarman.nessy.model.bedrock;

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
import software.amazon.awssdk.regions.Region;

/**
 * Exercises {@link BedrockModelProvider} against real Amazon Bedrock.
 *
 * <p>Every test starts with {@code assumeTrue} on the AWS default-credentials-chain resolving, so
 * the default, keyless build (which excludes the {@code live} tag entirely — see the root {@code
 * pom.xml}'s {@code nessy.excludedGroups}) never depends on network access. This is the intended
 * tinkering entry point for this module: configure AWS credentials for an account with Bedrock
 * model access (and {@code AWS_REGION}/{@code AWS_DEFAULT_REGION}, or override {@link #REGION}
 * below) and run it directly.
 *
 * <p><b>Honesty note (provider-expansion design §2):</b> as of this module's creation, these tests
 * have <em>not</em> been executed against real Bedrock — no AWS credentials with Bedrock access
 * were available in the environment this module was built in. The request/response mapping is
 * grounded in the AWS SDK for Java v2's own source (see {@code BedrockRequests}/{@code
 * BedrockStream}'s javadoc and the task report), and is covered by the offline mapping tests, but
 * the live round-trip below — including the async-to-blocking bridge in {@code
 * BedrockModelProvider.Builder} — is unvalidated until someone runs it against a real account.
 *
 * <p>{@link #MODEL} is the {@code us} cross-region inference profile id for Claude Haiku 4.5,
 * confirmed 2026-08-16 against Anthropic's own Amazon Bedrock documentation (the "API model IDs"
 * table on the legacy/ARN-versioned Bedrock integration page, which is the page this model's
 * cross-region-inference-profile-shaped id still lives on): base model id {@code
 * anthropic.claude-haiku-4-5-20251001-v1:0}, {@code us} cross-region inference profile supported,
 * giving {@code us.anthropic.claude-haiku-4-5-20251001-v1:0} per the documented {@code
 * <region-prefix>.<base-model-id>} inference-profile-id rule. Docs-verified, not live-verified —
 * see the honesty note above.
 */
@Tag("live")
class BedrockLiveTest {

  // The us cross-region inference profile id for Claude Haiku 4.5 — see the class javadoc for
  // how this string was confirmed. Override REGION below if the target account's Bedrock model
  // access lives outside a region this profile routes through.
  private static final String MODEL = "us.anthropic.claude-haiku-4-5-20251001-v1:0";
  private static final Region REGION = Region.US_EAST_1;

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

  private static void assumeCredentialsPresent() {
    // Amazon Bedrock live suites don't have a single documented "is a key set" env var the way
    // the API-key providers do — the SDK's default credentials provider chain resolves from many
    // possible sources (env vars, shared config/credentials files, container/instance metadata).
    // AWS_ACCESS_KEY_ID is the common denominator for a shell-configured run; a CI runner using a
    // profile or instance role instead should set it explicitly to opt this suite in.
    assumeTrue(System.getenv("AWS_ACCESS_KEY_ID") != null, "AWS_ACCESS_KEY_ID is not set");
  }

  @Test
  void a_real_conversation_answers() {
    assumeCredentialsPresent();

    Agent<String> agent =
        Nessy.harness(BedrockModelProvider.builder().region(REGION).build())
            .build()
            .agent()
            .name("bedrock-live")
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
    assumeCredentialsPresent();

    Agent<String> agent =
        Nessy.harness(BedrockModelProvider.builder().region(REGION).build())
            .build()
            .agent()
            .name("bedrock-live")
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
