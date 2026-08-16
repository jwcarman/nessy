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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Conversation;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.RedactedThinkingBlock;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ThinkingBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;
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

    Agent<String> agent =
        Nessy.harness(provider)
            .build()
            .agent()
            .name("end-to-end")
            .model("fake-model")
            .systemPrompt("be helpful")
            .tools(ToolGrant.grant(new AddTool(), UsagePolicy.allow()))
            .listen(Object.class, subscriber)
            .build();
    TextObserver observer = new TextObserver();

    RunOutcome outcome = agent.converse().tell("what is 2+2?", observer);

    assertThat(RunOutcomes.failed(outcome)).isFalse();
    assertThat(outcome.state().status()).isEqualTo(ConversationStatus.COMPLETE);
    // Both scripted model turns narrate through the same observer inside this one tell: the
    // tool-announcing prose from the first turn, then the settled answer from the second.
    assertThat(observer.text()).isEqualTo("Let me add those.The answer is 4.");
    assertThat(subscriber.ofType(ConversationEvent.class)).isNotEmpty();
  }

  @Test
  void the_tool_schema_reaches_the_model() {
    ScriptedModelProvider provider = ScriptedModelProvider.builder().text("Hi").endTurn().build();
    Agent<String> agent =
        Nessy.harness(provider)
            .build()
            .agent()
            .name("end-to-end")
            .model("fake-model")
            .tools(ToolGrant.grant(new AddTool(), UsagePolicy.allow()))
            .build();

    agent.converse().tell("hello");

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
    Agent<String> agent =
        Nessy.harness(provider)
            .build()
            .agent()
            .name("end-to-end")
            .model("fake-model")
            .capabilities(Set.of(Capability.PROMPT_CACHING))
            .build();

    agent.converse().tell("hello");

    assertThat(provider.requests().getFirst().requested())
        .containsExactly(Capability.PROMPT_CACHING);
  }

  @Test
  void usage_accumulates_from_the_model_into_the_final_state() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder().text("hi").endTurn(new Usage(10, 5, 0)).build();
    Agent<String> agent =
        Nessy.harness(provider).build().agent().name("end-to-end").model("fake-model").build();

    RunOutcome outcome = agent.converse().tell("hi");

    assertThat(outcome.state().usage()).isEqualTo(new Usage(10, 5, 0));
    assertThat(outcome.state().modelCalls()).isEqualTo(1);
  }

  @Test
  void thinking_chunks_settle_into_a_thinking_block_before_the_answer() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder().thinking("Let me think.").text("Answer.").endTurn().build();
    Agent<String> agent =
        Nessy.harness(provider).build().agent().name("end-to-end").model("fake-model").build();
    Conversation<String> conversation = agent.converse();

    conversation.tell("hi");

    assertThat(agent.contextFor(conversation.conversationId()).messages().getLast().content())
        .containsExactly(new ThinkingBlock("Let me think.", ""), new TextBlock("Answer."));
  }

  @Test
  void thinking_signatures_round_trip_through_memory() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder()
            .thinking("Let me think.")
            .thinkingSigned("sig-abc")
            .text("The answer is 4.")
            .endTurn()
            .build();
    Agent<String> agent =
        Nessy.harness(provider).build().agent().name("end-to-end").model("fake-model").build();
    Conversation<String> conversation = agent.converse();

    conversation.tell("what is 2+2?");

    assertThat(agent.contextFor(conversation.conversationId()).messages().getLast().content())
        .containsExactly(
            new ThinkingBlock("Let me think.", "sig-abc"), new TextBlock("The answer is 4."));
  }

  /**
   * Spec §5's reason {@code toolUseSigned} exists: drive a signed tool call through a real turn —
   * fold, store, and the rebuilt context handed to the model on the next request — and confirm the
   * signature is still attached at the far end, not just at the scripted source. A future hydrator
   * that rebuilds an assistant message via {@code new ToolUseBlock(call)} (dropping the signature)
   * would fail this test.
   */
  @Test
  void a_signed_tool_call_carries_its_signature_all_the_way_into_the_next_request() {
    ToolCall call = new ToolCall("c1", "add", addArgs(2, 2));
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder()
            .toolUseSigned("c1", "add", addArgs(2, 2), "sig-123")
            .endWithToolUse()
            .text("The answer is 4.")
            .endTurn()
            .build();
    Agent<String> agent =
        Nessy.harness(provider)
            .build()
            .agent()
            .name("end-to-end")
            .model("fake-model")
            .tools(ToolGrant.grant(new AddTool(), UsagePolicy.allow()))
            .build();

    agent.converse().tell("what is 2+2?");

    assertThat(provider.requests()).hasSizeGreaterThanOrEqualTo(2);
    assertThat(provider.requests().get(1).context().messages())
        .flatExtracting(Message::content)
        .contains(new ToolUseBlock(call, "sig-123"));
  }

  @Test
  void redacted_thinking_round_trips_through_memory() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder()
            .redactedThinking("opaque-bytes")
            .text("Answer.")
            .endTurn()
            .build();
    Agent<String> agent =
        Nessy.harness(provider).build().agent().name("end-to-end").model("fake-model").build();
    Conversation<String> conversation = agent.converse();

    conversation.tell("hi");

    assertThat(agent.contextFor(conversation.conversationId()).messages().getLast().content())
        .containsExactly(new RedactedThinkingBlock("opaque-bytes"), new TextBlock("Answer."));
  }

  @Nested
  class A_grant_line_per_agent {

    /**
     * A tool carries no authority of its own — only its grant does. Two harnesses (provider is
     * harness-owned, never an agent override — design §17's razor), two grant lines for the same
     * {@code AddTool}: the free agent's explicit {@link UsagePolicy#allow()} skips the approver
     * outright, while the gated agent's explicit {@link UsagePolicy#requireApproval()} hits an
     * approver that denies. The grant, not the tool, is what decided each agent's authority.
     */
    @Test
    void the_grant_line_is_the_security_statement() {
      ScriptedModelProvider freeProvider =
          ScriptedModelProvider.builder()
              .toolUse("c1", "add", addArgs(2, 2))
              .endWithToolUse()
              .text("The answer is 4.")
              .endTurn()
              .build();
      Agent<String> freeAgent =
          Nessy.harness(freeProvider)
              .build()
              .agent()
              .name("end-to-end")
              .model("fake-model")
              .tools(ToolGrant.grant(new AddTool(), UsagePolicy.allow()))
              // The approver denies everything, but it must never be asked: the tool-result
              // assertion below checks the sum actually ran rather than being denied, which
              // "The answer is 4." alone would not prove — that text comes from the script
              // either way, denied or not.
              .approver(Approver.denyAll("would fail if ever asked"))
              .build();

      ScriptedModelProvider gatedProvider =
          ScriptedModelProvider.builder()
              .toolUse("c1", "add", addArgs(2, 2))
              .endWithToolUse()
              .text("Understood.")
              .endTurn()
              .build();
      Agent<String> gatedAgent =
          Nessy.harness(gatedProvider)
              .build()
              .agent()
              .name("end-to-end")
              .model("fake-model")
              .tools(ToolGrant.grant(new AddTool(), UsagePolicy.requireApproval()))
              .approver(Approver.denyAll("not on this agent"))
              .build();

      Conversation<String> freeConversation = freeAgent.converse();
      TextObserver freeObserver = new TextObserver();
      freeConversation.tell("what is 2+2?", freeObserver);
      Conversation<String> gatedConversation = gatedAgent.converse();
      gatedConversation.tell("what is 2+2?");

      assertThat(freeObserver.text()).isEqualTo("The answer is 4.");
      ToolResultBlock freeBlock =
          (ToolResultBlock)
              contextOf(freeAgent, freeConversation).messages().get(2).content().getFirst();
      assertThat(freeBlock).isEqualTo(new ToolResultBlock("c1", "4", false));

      ToolResultBlock deniedBlock =
          (ToolResultBlock)
              contextOf(gatedAgent, gatedConversation).messages().get(2).content().getFirst();
      assertThat(deniedBlock.isError()).isTrue();
      assertThat(deniedBlock.content()).contains("not on this agent");
    }

    private static Context contextOf(Agent<String> agent, Conversation<String> conversation) {
      return agent.contextFor(conversation.conversationId());
    }
  }

  @Nested
  class An_observed_turn {

    /**
     * A {@link TurnObserver} handed to {@code tell} sees the whole segment live — text, thinking,
     * homework requested — in {@code TurnEvent} order.
     */
    @Test
    void an_observer_sees_this_conversations_turn_in_order() {
      ScriptedModelProvider provider =
          ScriptedModelProvider.builder().text("The answer is 4.").endTurn().build();
      Agent<String> agent =
          Nessy.harness(provider).build().agent().name("end-to-end").model("fake-model").build();
      List<TurnEvent> observed = new ArrayList<>();

      agent.converse().tell("what is 2+2?", observed::add);

      assertThat(observed)
          .filteredOn(TurnEvent.TextDelta.class::isInstance)
          .isNotEmpty()
          .allSatisfy(event -> assertThat(((TurnEvent.TextDelta) event).text()).isNotEmpty());
    }
  }
}
