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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.AgentConfigurationException;
import org.jwcarman.nessy.Conversation;
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.TerminationPolicy;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.InputRenderer;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.Role;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.memory.Memory;

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

    Agent<String> agent =
        Nessy.harness(provider)
            .build()
            .agent()
            .name("facade")
            .model("fake-model")
            .tools(ToolGrant.grant(new AddTool(), UsagePolicy.allow()))
            .build();
    TextObserver observer = new TextObserver();
    RunOutcome outcome = agent.converse().tell("what is 2+2?", observer);

    assertThat(observer.text()).isEqualTo("The answer is 4.");
    assertThat(RunOutcomes.failed(outcome)).isFalse();
  }

  @Test
  void conversations_carry_their_session_across_tells() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder()
            .text("Hello!")
            .endTurn()
            .text("Still here.")
            .endTurn()
            .build();
    Agent<String> agent =
        Nessy.harness(provider).build().agent().name("facade").model("fake-model").build();

    Conversation<String> chat = agent.converse();
    chat.tell("hi");
    TextObserver observer = new TextObserver();
    chat.tell("you there?", observer);

    assertThat(observer.text()).isEqualTo("Still here.");
    assertThat(provider.requests()).hasSize(2);
  }

  /**
   * The grant line is the security statement: {@code ToolGrant.grant(tool, policy)} declares
   * capability and authority together. The README's "The harness" section mirrors this two-builder
   * chain verbatim.
   */
  @Test
  void a_grant_line_declares_capability_and_authority_together() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder()
            .toolUse("c1", "add", addArgs(2, 2))
            .endWithToolUse()
            .text("The answer is 4.")
            .endTurn()
            .build();
    Harness harness = Nessy.harness(provider).build();
    Agent<String> agent =
        harness
            .agent()
            .name("facade")
            .model("fake-model")
            .tools(ToolGrant.grant(new AddTool(), UsagePolicy.allow()))
            // The approver denies everything, but it must never be asked: the reply below
            // proves the sum actually ran (via the tool) rather than being silently denied.
            .approver(Approver.denyAll("would fail if ever asked"))
            .build();
    TextObserver observer = new TextObserver();

    agent.converse().tell("what is 2+2?", observer);

    assertThat(observer.text()).isEqualTo("The answer is 4.");
  }

  @Test
  void contextFor_shows_exactly_what_a_call_would_see() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder()
            .toolUse("c1", "add", addArgs(2, 2))
            .endWithToolUse()
            .text("4")
            .endTurn()
            .text("Sure, still here.")
            .endTurn()
            .build();
    Agent<String> agent =
        Nessy.harness(provider)
            .build()
            .agent()
            .name("facade")
            .model("fake-model")
            .tools(ToolGrant.grant(new AddTool(), UsagePolicy.allow()))
            .build();

    Conversation<String> chat = agent.converse();
    chat.tell("what is 2+2?"); // a tool round trip: two model calls inside this one tell
    ConversationId conversationId = chat.conversationId();

    Context preview = agent.contextFor(conversationId);

    chat.tell("anything else?"); // the subsequent tell

    List<Message> expected = new ArrayList<>(preview.messages());
    expected.add(Message.user("anything else?"));
    assertThat(provider.requests().getLast().context().messages()).isEqualTo(expected);
  }

  @Test
  void contextFor_rejects_an_unknown_conversation() {
    ScriptedModelProvider provider = ScriptedModelProvider.builder().text("Hi").endTurn().build();
    Agent<String> agent =
        Nessy.harness(provider).build().agent().name("facade").model("fake-model").build();

    ConversationId unknownConversationId = ConversationId.generate();

    assertThatThrownBy(() -> agent.contextFor(unknownConversationId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown conversation");
  }

  @Test
  void a_declared_context_window_is_wired_through_the_builder() {
    ScriptedModelProvider provider = ScriptedModelProvider.builder().text("Hi").endTurn().build();

    Agent<String> agent =
        Nessy.harness(provider)
            .build()
            .agent()
            .name("facade")
            .model("fake-model")
            .maxTokens(4_000)
            .contextWindow(32_000)
            .build();

    assertThat(agent).isNotNull();
  }

  /**
   * A {@link Memory} whose {@code recall} always returns the same seeded {@link Context},
   * regardless of what it has been told, and which records every message {@code remember} tells it
   * — proving the two halves of the wiring separately: a builder that silently ignored {@code
   * .memory(m)} would still pass an assertion that only checked the reply text, so this fake makes
   * both the recall path and the telling path independently observable.
   */
  private static final class SeededMemory implements Memory {

    private final Context seeded;
    private final List<Message> told = new ArrayList<>();

    SeededMemory(Context seeded) {
      this.seeded = seeded;
    }

    List<Message> told() {
      return told;
    }

    @Override
    public void remember(ConversationId id, Message message) {
      told.add(message);
    }

    @Override
    public Context recall(ConversationId id) {
      return seeded;
    }
  }

  /**
   * A custom {@link Memory} replaces the {@code TranscriptMemory} floor entirely — the content
   * jurisdiction the loop's own {@code ModelCallExecutor} consults on every send. A builder that
   * ignored {@code .memory(m)} would still fall back to a working {@code TranscriptMemory}, so this
   * test proves both halves of the wiring rather than only that the agent still answers: the seeded
   * marker message reaches the wire request (recall wiring), and both the rendered user message and
   * the settled assistant reply reach the custom memory's own {@code remember} (telling wiring).
   */
  @Test
  void a_custom_memory_is_wired_through_the_builder() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder().text("Hi there.").endTurn().build();
    Message marker = Message.user("seeded-by-custom-memory");
    SeededMemory memory = new SeededMemory(Context.of(List.of(marker)));

    Agent<String> agent =
        Nessy.harness(provider)
            .build()
            .agent()
            .name("facade")
            .model("fake-model")
            .memory(memory)
            .build();

    agent.converse().tell("hi");

    assertThat(provider.requests().getFirst().context().messages()).contains(marker);
    assertThat(memory.told()).isNotEmpty();
    assertThat(memory.told()).contains(Message.user("hi"));
    assertThat(memory.told())
        .anySatisfy(message -> assertThat(message.role()).isEqualTo(Role.ASSISTANT));
  }

  @Test
  void a_declared_listener_sees_every_conversation_the_agent_runs() {
    ScriptedModelProvider provider = ScriptedModelProvider.builder().text("Hi").endTurn().build();
    RecordingSubscriber recorder = new RecordingSubscriber();
    Agent<String> agent =
        Nessy.harness(provider)
            .build()
            .agent()
            .name("facade")
            .model("fake-model")
            .listen(Object.class, recorder)
            .build();

    agent.converse().tell("hello");

    assertThat(recorder.ofType(ConversationEvent.class)).isNotEmpty();
  }

  @Test
  void a_missing_model_is_rejected_at_build_time() {
    ScriptedModelProvider provider = ScriptedModelProvider.builder().text("Hi").endTurn().build();
    var builder = Nessy.harness(provider).build().agent().name("facade");

    assertThatThrownBy(builder::build)
        .isInstanceOf(AgentConfigurationException.class)
        .hasMessageContaining("model");
  }

  @Test
  void a_null_memory_is_rejected() {
    ScriptedModelProvider provider = ScriptedModelProvider.builder().text("Hi").endTurn().build();
    var builder = Nessy.harness(provider).build().agent().name("facade").model("fake-model");

    assertThatThrownBy(() -> builder.memory(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("memory");
  }

  @Test
  void assistant_text_excludes_thinking_prose() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder()
            .thinking("Let me think.")
            .text("The answer is 4.")
            .endTurn()
            .build();
    Agent<String> agent =
        Nessy.harness(provider).build().agent().name("facade").model("fake-model").build();
    TextObserver observer = new TextObserver();

    agent.converse().tell("what is 2+2?", observer);

    assertThat(observer.text()).isEqualTo("The answer is 4.");
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
    Agent<String> agent =
        Nessy.harness(provider).build().agent().name("facade").model("fake-model").build();

    Conversation<String> first = agent.converse();
    first.tell("hi");
    ConversationId conversationId = first.conversationId();

    TextObserver observer = new TextObserver();
    agent.conversation(conversationId).tell("you there?", observer);

    assertThat(observer.text()).isEqualTo("Still here.");
    assertThat(agent.contextFor(conversationId).messages()).hasSize(4);
  }

  @Test
  void failure_reason_surfaces_through_the_outcome() {
    ScriptedModelProvider provider = ScriptedModelProvider.builder().text("Hi").endTurn().build();
    Agent<String> agent =
        Nessy.harness(provider)
            .build()
            .agent()
            .name("facade")
            .model("fake-model")
            .termination(TerminationPolicy.maxModelCalls(1))
            .build();
    Conversation<String> chat = agent.converse();
    chat.tell("hi");

    // Turn 1 already reached the ceiling, so this tell halts on agentTold before the loop
    // would ask the model for a second turn: the scripted provider is never called again, and
    // the second script entry (if any) would simply go unconsumed.
    RunOutcome second = chat.tell("still there?");

    assertThat(RunOutcomes.failed(second)).isTrue();
    assertThat(RunOutcomes.failureReason(second)).isPresent();
    assertThat(RunOutcomes.failureReason(second).orElseThrow()).contains("model calls");
  }

  /**
   * The plan's facade proof for design §17: every listening level fires for one {@code tell}, in
   * the pinned delivery order (conversation-local first, then the frozen chain — harness seed
   * before the agent's own), and every event each level sees carries the {@code conversationId} of
   * the conversation that produced it.
   */
  @Test
  void everything_centers_on_a_conversation() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder().text("The answer is 4.").endTurn().build();
    List<String> order = new ArrayList<>();
    List<ConversationEvent> seenByHarness = new ArrayList<>();
    List<ConversationEvent> seenByAgent = new ArrayList<>();
    List<ConversationEvent> seenByConversation = new ArrayList<>();

    Harness harness =
        Nessy.harness(provider)
            .listen(
                ConversationEvent.class,
                event -> {
                  order.add("harness");
                  seenByHarness.add(event);
                })
            .build();
    Agent<String> agent =
        harness
            .agent()
            .name("facade")
            .model("fake-model")
            .listen(
                ConversationEvent.class,
                event -> {
                  order.add("agent");
                  seenByAgent.add(event);
                })
            .build();
    Conversation<String> chat = agent.converse();
    chat.events()
        .subscribe(
            ConversationEvent.class,
            event -> {
              order.add("conversation");
              seenByConversation.add(event);
            });

    chat.tell("what is 2+2?");

    assertThat(seenByHarness).isNotEmpty();
    assertThat(seenByAgent).isNotEmpty();
    assertThat(seenByConversation).isNotEmpty();
    // Pinned delivery order (design §17): this conversation's dynamic subscriber first, then the
    // frozen chain — the harness's declaration, then the agent's own.
    assertThat(order.subList(0, 3)).containsExactly("conversation", "harness", "agent");
    assertThat(seenByHarness)
        .allSatisfy(event -> assertThat(event.conversationId()).isEqualTo(chat.conversationId()));
    assertThat(seenByAgent)
        .allSatisfy(event -> assertThat(event.conversationId()).isEqualTo(chat.conversationId()));
    assertThat(seenByConversation)
        .allSatisfy(event -> assertThat(event.conversationId()).isEqualTo(chat.conversationId()));
  }

  /**
   * The sealed-switch renderer is the recommended idiom for a typed vocabulary — an exhaustive
   * {@code switch} over the vocabulary's own variants, one arm per shape of thing an application
   * may tell the agent. The README's "Typed agents" section mirrors this shape.
   */
  @Nested
  class Typed_front_door {

    sealed interface SupportInput permits Question, Escalation {}

    record Question(String text) implements SupportInput {}

    record Escalation(String orderId, String reason) implements SupportInput {}

    static final InputRenderer<SupportInput> SUPPORT_RENDERER =
        input ->
            switch (input) {
              case Question(String text) -> List.of(new TextBlock(text));
              case Escalation(String orderId, String reason) ->
                  List.of(new TextBlock("Escalate order " + orderId + ": " + reason));
            };

    @Test
    void a_typed_agent_speaks_its_vocabulary() {
      ScriptedModelProvider provider =
          ScriptedModelProvider.builder().text("On it.").endTurn().build();
      Harness harness = Nessy.harness(provider).build();
      Agent<SupportInput> support =
          harness
              .agent(SupportInput.class)
              .name("facade")
              .model("fake-model")
              .renderer(SUPPORT_RENDERER)
              .build();
      TextObserver observer = new TextObserver();

      support.converse().tell(new Escalation("o-1", "damaged in transit"), observer);

      assertThat(observer.text()).isEqualTo("On it.");
      assertThat(provider.requests().getFirst().context().messages().getFirst().content())
          .containsExactly(new TextBlock("Escalate order o-1: damaged in transit"));
    }

    @Test
    void a_string_agent_tells_plain_text() {
      // Wire-bytes proof: a String agent's tell produces exactly the one TextBlock send(String)
      // always produced — typing the front door changes nothing about what a String agent puts
      // on the wire.
      ScriptedModelProvider provider = ScriptedModelProvider.builder().text("Hi").endTurn().build();
      Agent<String> agent =
          Nessy.harness(provider).build().agent().name("facade").model("fake-model").build();

      agent.converse().tell("what is 2+2?");

      assertThat(provider.requests().getFirst().context().messages())
          .containsExactly(Message.user("what is 2+2?"));
    }

    record Ping(String note) {}

    @Test
    void the_default_json_renderer_tags_and_serializes() {
      // No explicit .renderer(...): a typed vocabulary defaults to the tagged-JSON renderer over
      // the harness's own mapper.
      ScriptedModelProvider provider =
          ScriptedModelProvider.builder().text("ack").endTurn().build();
      Harness harness = Nessy.harness(provider).build();
      Agent<Ping> agent = harness.agent(Ping.class).name("facade").model("fake-model").build();

      agent.converse().tell(new Ping("hello"));

      TextBlock block =
          (TextBlock)
              provider.requests().getFirst().context().messages().getFirst().content().getFirst();
      assertThat(block.text()).startsWith("[ping]\n");
      assertThat(block.text()).contains("\"note\":\"hello\"");
    }

    @Test
    void a_broken_renderer_fails_at_the_front_door() {
      ScriptedModelProvider provider =
          ScriptedModelProvider.builder().text("never reached").endTurn().build();
      ConversationStore store = ConversationStore.inMemory();
      Harness harness = Nessy.harness(provider).store(store).build();
      InputRenderer<String> throwing =
          input -> {
            throw new IllegalStateException("renderer blew up");
          };
      Agent<String> agent =
          harness.agent(String.class).name("facade").model("fake-model").renderer(throwing).build();
      Conversation<String> chat = agent.converse();

      assertThatThrownBy(() -> chat.tell("hi")).isInstanceOf(IllegalStateException.class);

      assertThat(store.load(chat.conversationId())).isEmpty();
      assertThat(provider.requests()).isEmpty();
    }

    @Test
    void a_renderer_that_produces_no_blocks_also_fails_at_the_front_door() {
      ScriptedModelProvider provider =
          ScriptedModelProvider.builder().text("never reached").endTurn().build();
      ConversationStore store = ConversationStore.inMemory();
      Harness harness = Nessy.harness(provider).store(store).build();
      InputRenderer<String> empty = input -> List.of();
      Agent<String> agent =
          harness.agent(String.class).name("facade").model("fake-model").renderer(empty).build();
      Conversation<String> chat = agent.converse();

      assertThatThrownBy(() -> chat.tell("hi")).isInstanceOf(IllegalArgumentException.class);

      assertThat(store.load(chat.conversationId())).isEmpty();
      assertThat(provider.requests()).isEmpty();
    }
  }
}
