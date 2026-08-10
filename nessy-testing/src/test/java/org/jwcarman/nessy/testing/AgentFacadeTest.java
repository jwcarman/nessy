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
import org.jwcarman.nessy.Conversation;
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.Reply;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Event;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.compaction.CompactionPolicy;
import org.jwcarman.nessy.api.compaction.CompactionStrategy;
import org.jwcarman.nessy.api.compaction.CompactionTrigger;
import org.jwcarman.nessy.api.event.SessionEvent;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.InputRenderer;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.session.SessionId;
import org.jwcarman.nessy.api.session.SessionState;
import org.jwcarman.nessy.api.session.TerminationPolicy;
import org.jwcarman.nessy.api.session.Usage;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.spi.context.ContextEnricher;
import org.jwcarman.nessy.spi.context.Projection;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.session.InMemoryTranscriptStore;
import org.jwcarman.nessy.spi.session.SessionStore;
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

    Agent<String> agent =
        Nessy.agent().provider(provider).model("fake-model").tools(new AddTool()).build();
    Reply reply = agent.converse().tell("what is 2+2?");

    assertThat(reply.text()).isEqualTo("The answer is 4.");
    assertThat(reply.failed()).isFalse();
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
    Agent<String> agent = Nessy.agent().provider(provider).model("fake-model").build();

    Conversation<String> chat = agent.converse();
    chat.tell("hi");
    Reply second = chat.tell("you there?");

    assertThat(second.text()).isEqualTo("Still here.");
    assertThat(second.state().messages()).hasSize(4);
  }

  @Test
  void the_engine_consults_the_context_pipeline() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder()
            .text("Hello!")
            .endTurn()
            .text("Still here.")
            .endTurn()
            .build();
    // A marking projection: drops the oldest message so the assertions below can tell the
    // projected request apart from the untouched state the reducer kept.
    Projection droppingOldest =
        context -> Context.of(context.messages().subList(1, context.messages().size()));
    Agent<String> agent =
        Nessy.agent()
            .provider(provider)
            .model("fake-model")
            .context(pipeline -> pipeline.project(droppingOldest))
            .build();

    Conversation<String> chat = agent.converse();
    chat.tell("hi");
    Reply second = chat.tell("you there?");

    List<ModelRequest> requests = provider.requests();
    assertThat(requests).hasSize(2);
    assertThat(requests.get(0).context().messages()).isEmpty();
    assertThat(requests.get(1).context().messages()).hasSize(2);
    assertThat(second.state().messages()).hasSize(4);
  }

  /**
   * The grant line is the security statement: {@code ToolGrant.grant(tool).with(policy)} declares
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
    Harness harness = Nessy.harness().provider(provider).build();
    Agent<String> agent =
        harness
            .agent()
            .model("fake-model")
            .tools(ToolGrant.grant(new AddTool()).with(UsagePolicy.allow()))
            // The approver denies everything, but it must never be asked: the reply below
            // proves the sum actually ran (via the tool) rather than being silently denied.
            .approver(Approver.denyAll("would fail if ever asked"))
            .build();

    Reply reply = agent.converse().tell("what is 2+2?");

    assertThat(reply.text()).isEqualTo("The answer is 4.");
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
    Message fact = Message.user("remembered fact");
    ContextEnricher enricher = state -> List.of(fact);
    // keepRecentMessages is large enough that nothing in this short transcript is ever old
    // enough to elide: the point of this test is that contextFor consults the same
    // ContextPipeline the engine does, not eliding's own cut-point behavior.
    Agent<String> agent =
        Nessy.agent()
            .provider(provider)
            .model("fake-model")
            .tools(new AddTool())
            .context(pipeline -> pipeline.project(ctx -> ctx.elideToolResults(50)).enrich(enricher))
            .build();

    Conversation<String> chat = agent.converse();
    chat.tell("what is 2+2?"); // a tool round trip: two model calls inside this one tell
    SessionId sessionId = chat.sessionId();

    Context preview = agent.contextFor(sessionId);

    chat.tell("anything else?"); // the subsequent tell

    List<Message> expected = new ArrayList<>(preview.messages());
    expected.add(Message.user("anything else?"));
    assertThat(provider.requests().getLast().context().messages()).isEqualTo(expected);
    assertThat(preview.messages()).contains(fact);
  }

  @Test
  void contextFor_rejects_an_unknown_session() {
    ScriptedModelProvider provider = ScriptedModelProvider.builder().text("Hi").endTurn().build();
    Agent<String> agent = Nessy.agent().provider(provider).model("fake-model").build();

    assertThatThrownBy(() -> agent.contextFor(SessionId.generate()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown session");
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

    Agent<String> agent =
        Nessy.agent().provider(provider).model("fake-model").compaction(policy).build();

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
    Agent<String> derivedAgent =
        Nessy.agent()
            .provider(derivedProvider)
            .model("fake-model")
            .maxTokens(10_000)
            .contextWindow(110_000)
            .build();
    Conversation<String> derivedConversation = derivedAgent.converse();
    for (int i = 1; i <= 6; i++) {
      derivedConversation.tell("u" + i);
    }

    Reply seventhReply = derivedConversation.tell("u7");

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
    Agent<String> explicitAgent =
        Nessy.agent()
            .provider(explicitProvider)
            .model("fake-model")
            .maxTokens(10_000)
            .contextWindow(110_000)
            .compaction(CompactionPolicy.disabled())
            .build();
    Conversation<String> explicitConversation = explicitAgent.converse();
    explicitConversation.tell("first question");

    Reply explicitReply = explicitConversation.tell("second question");

    assertThat(explicitReply.failed()).isFalse();
    assertThat(explicitReply.state().generation()).isZero();
  }

  @Test
  void compaction_can_be_disabled_on_the_builder() {
    ScriptedModelProvider provider = ScriptedModelProvider.builder().text("Hi").endTurn().build();

    Agent<String> agent =
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

    Agent<String> agent =
        Nessy.agent().provider(provider).model("fake-model").compaction(myStrategy).build();

    assertThat(agent).isNotNull();
  }

  @Test
  void a_declared_context_window_is_wired_through_the_builder() {
    ScriptedModelProvider provider = ScriptedModelProvider.builder().text("Hi").endTurn().build();

    Agent<String> agent =
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
    Agent<String> agent =
        Nessy.agent().provider(provider).model("fake-model").transcript(journal).build();

    assertThat(agent).isNotNull();
  }

  @Test
  void elideToolResults_is_wired_through_the_builder() {
    ScriptedModelProvider provider = ScriptedModelProvider.builder().text("Hi").endTurn().build();

    Agent<String> agent =
        Nessy.agent()
            .provider(provider)
            .model("fake-model")
            .context(pipeline -> pipeline.project(ctx -> ctx.elideToolResults(2)))
            .build();

    assertThat(agent).isNotNull();
  }

  @Test
  void the_hub_is_reachable_from_the_agent() {
    ScriptedModelProvider provider = ScriptedModelProvider.builder().text("Hi").endTurn().build();
    Agent<String> agent = Nessy.agent().provider(provider).model("fake-model").build();
    RecordingSubscriber recorder = new RecordingSubscriber();
    recorder.attachTo(agent.events());

    agent.converse().tell("hello");

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
    Agent<String> agent = Nessy.agent().provider(provider).model("fake-model").build();

    Reply reply = agent.converse().tell("what is 2+2?");

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
    Agent<String> agent = Nessy.agent().provider(provider).model("fake-model").build();

    Conversation<String> first = agent.converse();
    first.tell("hi");
    SessionId sessionId = first.sessionId();

    Reply second = agent.resume(sessionId).tell("you there?");

    assertThat(second.state().messages()).hasSize(4);
  }

  @Test
  void failure_reason_surfaces_through_reply() {
    ScriptedModelProvider provider = ScriptedModelProvider.builder().text("Hi").endTurn().build();
    Agent<String> agent =
        Nessy.agent()
            .provider(provider)
            .model("fake-model")
            .termination(TerminationPolicy.maxTurns(1))
            .build();
    Conversation<String> chat = agent.converse();
    chat.tell("hi");

    // Turn 1 already reached the ceiling, so this tell halts on userSaid before the
    // reducer would ask the model for a second turn: the scripted provider is never called
    // again, and the second script entry (if any) would simply go unconsumed.
    Reply second = chat.tell("still there?");

    assertThat(second.failed()).isTrue();
    assertThat(second.failureReason()).isPresent();
    assertThat(second.failureReason().orElseThrow()).contains("turn");
  }

  @Test
  void a_tell_tap_sees_this_conversations_events_in_order() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder().text("The answer is 4.").endTurn().build();
    Agent<String> agent = Nessy.agent().provider(provider).model("fake-model").build();
    List<Event> tapped = new ArrayList<>();

    agent.converse().tell("what is 2+2?", tapped::add);

    assertThat(tapped)
        .filteredOn(Event.TextDelta.class::isInstance)
        .isNotEmpty()
        .allSatisfy(event -> assertThat(((Event.TextDelta) event).text()).isNotEmpty());
    assertThat(tapped.getLast()).isInstanceOf(Event.ModelTurnEnded.class);
  }

  @Test
  void a_tell_tap_never_sees_another_conversations_events() {
    // A foreign SessionEvent is emitted mid-turn — while A's tap is still subscribed — rather than
    // by a second, later tell. A synchronous hub delivers events the instant they're emitted, so a
    // foreign event published after A's turn ends would never reach a tap that closes when tell
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
    Agent<String> agent =
        Nessy.agent()
            .provider(provider)
            .model("fake-model")
            .tools(new EmitForeignEventTool(foreignSessionId))
            .build();
    List<Event> tapped = new ArrayList<>();

    agent.converse().tell("hi", tapped::add);

    assertThat(tapped)
        .filteredOn(Event.UserSaid.class::isInstance)
        .extracting(event -> textOf((Event.UserSaid) event))
        .noneMatch(text -> text.contains("foreign"));
  }

  /**
   * A tap is just another hub subscriber, so the synchronous spine's veto-by-throw (design §9.1)
   * applies to it exactly as it does to any other subscriber: a throwing tap propagates and aborts
   * the {@code tell}, rather than being contained.
   */
  @Test
  void a_throwing_tap_propagates_and_aborts_the_tell() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder().text("The answer is 4.").endTurn().build();
    Agent<String> agent = Nessy.agent().provider(provider).model("fake-model").build();

    assertThatThrownBy(
            () ->
                agent
                    .converse()
                    .tell(
                        "what is 2+2?",
                        event -> {
                          throw new RuntimeException("tap blew up");
                        }))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("tap blew up");
  }

  @Test
  void the_tap_is_closed_after_tell() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder().text("Hi").endTurn().text("Hi again").endTurn().build();
    Agent<String> agent = Nessy.agent().provider(provider).model("fake-model").build();
    List<Event> tapped = new ArrayList<>();
    Conversation<String> chat = agent.converse();

    chat.tell("hi", tapped::add);
    int sizeAfterTappedTell = tapped.size();
    chat.tell("still there?");

    assertThat(tapped).hasSize(sizeAfterTappedTell);
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
              case Question question -> List.of(new TextBlock(question.text()));
              case Escalation escalation ->
                  List.of(
                      new TextBlock(
                          "Escalate order " + escalation.orderId() + ": " + escalation.reason()));
            };

    @Test
    void a_typed_agent_speaks_its_vocabulary() {
      ScriptedModelProvider provider =
          ScriptedModelProvider.builder().text("On it.").endTurn().build();
      Harness harness = Nessy.harness().provider(provider).build();
      Agent<SupportInput> support =
          harness.agent(SupportInput.class).model("fake-model").renderer(SUPPORT_RENDERER).build();

      Reply reply = support.converse().tell(new Escalation("o-1", "damaged in transit"));

      assertThat(reply.text()).isEqualTo("On it.");
      assertThat(provider.requests().getFirst().context().messages().getFirst().content())
          .containsExactly(new TextBlock("Escalate order o-1: damaged in transit"));
    }

    @Test
    void a_string_agent_tells_plain_text() {
      // Wire-bytes proof: a String agent's tell produces exactly the one TextBlock send(String)
      // always produced — typing the front door changes nothing about what a String agent puts
      // on the wire.
      ScriptedModelProvider provider = ScriptedModelProvider.builder().text("Hi").endTurn().build();
      Agent<String> agent = Nessy.agent().provider(provider).model("fake-model").build();

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
      Harness harness = Nessy.harness().provider(provider).build();
      Agent<Ping> agent = harness.agent(Ping.class).model("fake-model").build();

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
      SessionStore store = SessionStore.inMemory();
      Harness harness = Nessy.harness().provider(provider).store(store).build();
      InputRenderer<String> throwing =
          input -> {
            throw new IllegalStateException("renderer blew up");
          };
      Agent<String> agent =
          harness.agent(String.class).model("fake-model").renderer(throwing).build();
      Conversation<String> chat = agent.converse();

      assertThatThrownBy(() -> chat.tell("hi")).isInstanceOf(IllegalStateException.class);

      assertThat(store.load(chat.sessionId())).isEmpty();
      assertThat(provider.requests()).isEmpty();
    }

    @Test
    void a_renderer_that_produces_no_blocks_also_fails_at_the_front_door() {
      ScriptedModelProvider provider =
          ScriptedModelProvider.builder().text("never reached").endTurn().build();
      SessionStore store = SessionStore.inMemory();
      Harness harness = Nessy.harness().provider(provider).store(store).build();
      InputRenderer<String> empty = input -> List.of();
      Agent<String> agent = harness.agent(String.class).model("fake-model").renderer(empty).build();
      Conversation<String> chat = agent.converse();

      assertThatThrownBy(() -> chat.tell("hi")).isInstanceOf(IllegalArgumentException.class);

      assertThat(store.load(chat.sessionId())).isEmpty();
      assertThat(provider.requests()).isEmpty();
    }
  }
}
