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
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Conversation;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.Reply;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CompactionPolicy;
import org.jwcarman.nessy.api.CompactionStrategy;
import org.jwcarman.nessy.api.CompactionTrigger;
import org.jwcarman.nessy.api.Context;
import org.jwcarman.nessy.api.Event;
import org.jwcarman.nessy.api.SessionId;
import org.jwcarman.nessy.api.SessionState;
import org.jwcarman.nessy.api.TerminationPolicy;
import org.jwcarman.nessy.api.TextBlock;
import org.jwcarman.nessy.api.ToolResult;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.event.SessionEvent;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.spi.context.ContextBuilder;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.session.InMemoryTranscriptStore;
import org.jwcarman.nessy.spi.session.TranscriptStore;

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

  /** Test-only: while running, emits a {@link SessionEvent} for a session that is not its own. */
  record NoArgs() {}

  static final class EmitForeignEventTool implements Tool<NoArgs> {

    private final SessionId foreignSessionId;

    EmitForeignEventTool(SessionId foreignSessionId) {
      this.foreignSessionId = foreignSessionId;
    }

    @Override
    public String name() {
      return "emit-foreign";
    }

    @Override
    public String description() {
      return "Test-only: emits a SessionEvent belonging to a different session.";
    }

    @Override
    public Class<NoArgs> inputType() {
      return NoArgs.class;
    }

    @Override
    public boolean requiresApproval() {
      return false;
    }

    @Override
    public Awaited<ToolResult> execute(NoArgs input, ToolContext context) {
      context
          .events()
          .emit(
              new SessionEvent(
                  foreignSessionId,
                  Event.UserSaid.of("foreign"),
                  SessionState.newSession(foreignSessionId)));
      return Awaited.ready(ToolResult.ok("emitted"));
    }
  }

  private static String textOf(Event.UserSaid userSaid) {
    return userSaid.content().stream()
        .filter(TextBlock.class::isInstance)
        .map(TextBlock.class::cast)
        .map(TextBlock::text)
        .findFirst()
        .orElse("");
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
  void the_engine_consults_the_context_builder() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder()
            .text("Hello!")
            .endTurn()
            .text("Still here.")
            .endTurn()
            .build();
    // A marking projection: drops the oldest message so the assertions below can tell the
    // projected request apart from the untouched state the reducer kept.
    ContextBuilder droppingOldest =
        state -> Context.of(state.messages().subList(1, state.messages().size()));
    Agent agent =
        Nessy.agent().provider(provider).model("fake-model").contextBuilder(droppingOldest).build();

    Conversation chat = agent.converse();
    chat.send("hi");
    Reply second = chat.send("you there?");

    List<ModelRequest> requests = provider.requests();
    assertThat(requests).hasSize(2);
    assertThat(requests.get(0).context().messages()).isEmpty();
    assertThat(requests.get(1).context().messages()).hasSize(2);
    assertThat(second.state().messages()).hasSize(4);
  }

  @Test
  void a_custom_compaction_policy_is_wired_through_the_builder() {
    ScriptedModelProvider provider = ScriptedModelProvider.builder().text("Hi").endTurn().build();
    CompactionPolicy policy =
        new CompactionPolicy(
            CompactionTrigger.atTokens(50_000), // trigger at 50k measured input tokens
            20, // keep the last 20 messages verbatim
            1_024, // cap the summary reply at 1024 tokens
            "Summarize the conversation so far, focusing on open TODOs.");

    Agent agent = Nessy.agent().provider(provider).model("fake-model").compaction(policy).build();

    assertThat(agent).isNotNull();
  }

  @Test
  void a_declared_window_derives_the_trigger() {
    // window 110_000, maxTokens 10_000 -> forWindow trigger at 0.8 * (110_000 - 10_000) = 80_000.
    // keepRecentMessages stays the defaults' 10, so a safe cut needs at least eleven settled
    // messages before the trigger can find one: six plain user/assistant pairs (twelve messages)
    // plus the seventh user message the reducer appends before deciding.
    ScriptedModelProvider derivedProvider =
        ScriptedModelProvider.builder()
            .text("a1")
            .endTurn()
            .text("a2")
            .endTurn()
            .text("a3")
            .endTurn()
            .text("a4")
            .endTurn()
            .text("a5")
            .endTurn()
            .text("a6")
            .endTurn(new Usage(80_000, 10, 0))
            .text("Summary of earlier turns.")
            .endTurn()
            .text("a7")
            .endTurn()
            .build();
    Agent derivedAgent =
        Nessy.agent()
            .provider(derivedProvider)
            .model("fake-model")
            .maxTokens(10_000)
            .contextWindow(110_000)
            .build();
    Conversation derivedConversation = derivedAgent.converse();
    for (int i = 1; i <= 6; i++) {
      derivedConversation.send("u" + i);
    }

    Reply seventhReply = derivedConversation.send("u7");

    assertThat(seventhReply.failed()).isFalse();
    assertThat(seventhReply.state().generation()).isEqualTo(1);

    // The same declared window, but an explicit compaction policy always wins: compaction
    // never fires even though the same usage crosses the derived threshold.
    ScriptedModelProvider explicitProvider =
        ScriptedModelProvider.builder()
            .text("First answer.")
            .endTurn(new Usage(80_000, 10, 0))
            .text("Second answer.")
            .endTurn()
            .build();
    Agent explicitAgent =
        Nessy.agent()
            .provider(explicitProvider)
            .model("fake-model")
            .maxTokens(10_000)
            .contextWindow(110_000)
            .compaction(CompactionPolicy.disabled())
            .build();
    Conversation explicitConversation = explicitAgent.converse();
    explicitConversation.send("first question");

    Reply explicitReply = explicitConversation.send("second question");

    assertThat(explicitReply.failed()).isFalse();
    assertThat(explicitReply.state().generation()).isZero();
  }

  @Test
  void compaction_can_be_disabled_on_the_builder() {
    ScriptedModelProvider provider = ScriptedModelProvider.builder().text("Hi").endTurn().build();

    Agent agent =
        Nessy.agent()
            .provider(provider)
            .model("fake-model")
            .compaction(CompactionPolicy.disabled())
            .build();

    assertThat(agent).isNotNull();
  }

  @Test
  void a_custom_compaction_strategy_is_wired_through_the_builder() {
    ScriptedModelProvider provider = ScriptedModelProvider.builder().text("Hi").endTurn().build();
    CompactionStrategy myStrategy = CompactionStrategy.disabled();

    Agent agent =
        Nessy.agent().provider(provider).model("fake-model").compaction(myStrategy).build();

    assertThat(agent).isNotNull();
  }

  @Test
  void a_declared_context_window_is_wired_through_the_builder() {
    ScriptedModelProvider provider = ScriptedModelProvider.builder().text("Hi").endTurn().build();

    Agent agent =
        Nessy.agent()
            .provider(provider)
            .model("fake-model")
            .maxTokens(4_000)
            .contextWindow(32_000) // trigger derives to ~0.8 × (32_000 − 4_000)
            .build();

    assertThat(agent).isNotNull();
  }

  @Test
  void a_transcript_store_is_wired_through_the_builder() {
    ScriptedModelProvider provider = ScriptedModelProvider.builder().text("Hi").endTurn().build();

    InMemoryTranscriptStore journal = TranscriptStore.inMemory();
    Agent agent = Nessy.agent().provider(provider).model("fake-model").transcript(journal).build();

    assertThat(agent).isNotNull();
  }

  @Test
  void elidingToolResults_is_wired_through_the_builder() {
    ScriptedModelProvider provider = ScriptedModelProvider.builder().text("Hi").endTurn().build();

    Agent agent =
        Nessy.agent()
            .provider(provider)
            .model("fake-model")
            .contextBuilder(ContextBuilder.elidingToolResults(2))
            .build();

    assertThat(agent).isNotNull();
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
  void a_null_compaction_policy_is_rejected_at_build_time() {
    ScriptedModelProvider provider = ScriptedModelProvider.builder().text("Hi").endTurn().build();

    assertThatThrownBy(
            () ->
                Nessy.agent()
                    .provider(provider)
                    .model("fake-model")
                    .compaction((CompactionPolicy) null)
                    .build())
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("compaction");
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

  @Test
  void a_send_tap_sees_this_conversations_events_in_order() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder().text("The answer is 4.").endTurn().build();
    Agent agent = Nessy.agent().provider(provider).model("fake-model").build();
    List<Event> tapped = new ArrayList<>();

    agent.converse().send("what is 2+2?", tapped::add);

    assertThat(tapped)
        .filteredOn(Event.TextDelta.class::isInstance)
        .isNotEmpty()
        .allSatisfy(event -> assertThat(((Event.TextDelta) event).text()).isNotEmpty());
    assertThat(tapped.getLast()).isInstanceOf(Event.ModelTurnEnded.class);
  }

  @Test
  void a_send_tap_never_sees_another_conversations_events() {
    // A foreign SessionEvent is emitted mid-turn — while A's tap is still subscribed — rather than
    // by a second, later send. A synchronous hub delivers events the instant they're emitted, so a
    // foreign event published after A's turn ends would never reach a tap that closes when send
    // returns; the only way to prove the sessionId filter (rather than timing) is what protects the
    // tap is to have the foreign event arrive while the subscription is demonstrably still live.
    SessionId foreignSessionId = SessionId.generate();
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder()
            .toolUse("c1", "emit-foreign", JsonNodeFactory.instance.objectNode())
            .endWithToolUse()
            .text("done")
            .endTurn()
            .build();
    Agent agent =
        Nessy.agent()
            .provider(provider)
            .model("fake-model")
            .tools(new EmitForeignEventTool(foreignSessionId))
            .build();
    List<Event> tapped = new ArrayList<>();

    agent.converse().send("hi", tapped::add);

    assertThat(tapped)
        .filteredOn(Event.UserSaid.class::isInstance)
        .extracting(event -> textOf((Event.UserSaid) event))
        .noneMatch(text -> text.contains("foreign"));
  }

  @Test
  void a_throwing_tap_does_not_abort_the_send() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder().text("The answer is 4.").endTurn().build();
    Agent agent = Nessy.agent().provider(provider).model("fake-model").build();

    Reply reply =
        agent
            .converse()
            .send(
                "what is 2+2?",
                event -> {
                  throw new RuntimeException("tap blew up");
                });

    assertThat(reply.failed()).isFalse();
    assertThat(reply.text()).isEqualTo("The answer is 4.");
  }

  @Test
  void the_tap_is_closed_after_send() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder().text("Hi").endTurn().text("Hi again").endTurn().build();
    Agent agent = Nessy.agent().provider(provider).model("fake-model").build();
    List<Event> tapped = new ArrayList<>();
    Conversation chat = agent.converse();

    chat.send("hi", tapped::add);
    int sizeAfterTappedSend = tapped.size();
    chat.send("still there?");

    assertThat(tapped).hasSize(sizeAfterTappedSend);
  }
}
