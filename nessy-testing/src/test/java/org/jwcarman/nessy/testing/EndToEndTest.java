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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.Reply;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CompactionPolicy;
import org.jwcarman.nessy.api.Event;
import org.jwcarman.nessy.api.Message;
import org.jwcarman.nessy.api.RedactedThinkingBlock;
import org.jwcarman.nessy.api.Role;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.SessionId;
import org.jwcarman.nessy.api.SessionStatus;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.TextBlock;
import org.jwcarman.nessy.api.ThinkingBlock;
import org.jwcarman.nessy.api.ToolResult;
import org.jwcarman.nessy.api.ToolResultBlock;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.event.CompactionFailed;
import org.jwcarman.nessy.api.event.SessionEvent;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.spi.ContextBuilder;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

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
        ScriptedModelProvider.builder().text("hi").endTurn(new Usage(10, 5, 0)).build();
    Agent agent = Nessy.agent().provider(provider).model("fake-model").build();

    RunOutcome outcome = agent.engine().run(new SessionId("s1"), Event.UserSaid.of("hi"));

    RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
    assertThat(completed.state().usage()).isEqualTo(new Usage(10, 5, 0));
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

  @Test
  void thinking_signatures_round_trip_through_the_final_state() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder()
            .thinking("Let me think.")
            .thinkingSigned("sig-abc")
            .text("The answer is 4.")
            .endTurn()
            .build();
    Agent agent = Nessy.agent().provider(provider).model("fake-model").build();

    Reply reply = agent.converse().send("what is 2+2?");

    assertThat(reply.state().messages().getLast().content())
        .containsExactly(
            new ThinkingBlock("Let me think.", "sig-abc"), new TextBlock("The answer is 4."));
  }

  @Test
  void redacted_thinking_round_trips_through_the_final_state() {
    ScriptedModelProvider provider =
        ScriptedModelProvider.builder()
            .redactedThinking("opaque-bytes")
            .text("Answer.")
            .endTurn()
            .build();
    Agent agent = Nessy.agent().provider(provider).model("fake-model").build();

    Reply reply = agent.converse().send("hi");

    assertThat(reply.state().messages().getLast().content())
        .containsExactly(new RedactedThinkingBlock("opaque-bytes"), new TextBlock("Answer."));
  }

  /**
   * A summarizer that throws on its second call (the compaction call), used only by {@link
   * A_failed_compaction}. This mirrors {@code InProcessEngineCompactionTest}'s {@code
   * FailingCompactProvider}: a hand-rolled fake, not a mock, so the "no mocking library" house rule
   * holds.
   */
  private static final class FailingSummarizerProvider implements ModelProvider {

    private final Deque<List<ModelEvent>> turns = new ArrayDeque<>();
    private int calls;

    FailingSummarizerProvider(List<List<ModelEvent>> scripted) {
      turns.addAll(scripted);
    }

    @Override
    public ModelStream stream(ModelRequest request) {
      calls++;
      if (calls == 2) {
        throw new IllegalStateException("summarizer exploded");
      }
      Iterator<ModelEvent> events = turns.removeFirst().iterator();
      return new ModelStream() {
        @Override
        public Iterator<ModelEvent> iterator() {
          return events;
        }

        @Override
        public void close() {}
      };
    }

    @Override
    public Set<Capability> capabilities() {
      return Set.of();
    }
  }

  @Nested
  class A_compacting_conversation {

    /**
     * Arithmetic: a {@code CompactionPolicy} with {@code keepRecentMessages = 0} and the default
     * trigger is what {@code InProcessEngineCompactionTest} uses, because the default policy's
     * {@code keepRecentMessages} of 10 would leave a short transcript with no safe cut (cut == 0,
     * compaction silently skipped). {@code Reducer.userSaid} appends the new user message
     * <em>before</em> deciding whether to compact, so by the time send 2's "second question" is
     * appended, the transcript is {@code [user1, asst1, user2]} (size 3). {@code pairSafeCut} then
     * computes {@code limit = min(3 - 0, 3 - 1) = 2}, and {@code messages.get(2)} is {@code user2}
     * — a genuine user-text turn — so {@code cut = 2}. {@code [user1, asst1]} collapses into the
     * summary, {@code [user2]} survives as the tail, and the model is then called with the
     * rewritten context to answer send 2. A third send, with {@code lastInputTokens} reset to 0 by
     * compaction, proceeds without triggering compaction again.
     */
    @Test
    void a_long_conversation_compacts_and_keeps_answering() {
      ScriptedModelProvider provider =
          ScriptedModelProvider.builder()
              .text("First answer.")
              .endTurn(new Usage(150_000, 20, 0))
              .text("Summary of earlier turns.")
              .endTurn()
              .text("Second answer.")
              .endTurn()
              .text("Third answer.")
              .endTurn()
              .build();
      Agent agent =
          Nessy.agent()
              .provider(provider)
              .model("fake-model")
              .compaction(new CompactionPolicy(100_000, 0, 256, "Summarize."))
              .build();

      var conversation = agent.converse();
      conversation.send("first question");
      Reply secondReply = conversation.send("second question");

      assertThat(secondReply.failed()).isFalse();
      assertThat(secondReply.text()).isEqualTo("Second answer.");
      assertThat(secondReply.state().generation()).isEqualTo(1);
      Message summaryMessage = secondReply.state().messages().getFirst();
      assertThat(summaryMessage.role()).isEqualTo(Role.USER);
      assertThat(((TextBlock) summaryMessage.content().getFirst()).text())
          .contains("Summary of earlier turns.");

      Reply thirdReply = conversation.send("third question");

      assertThat(thirdReply.failed()).isFalse();
      assertThat(thirdReply.text()).isEqualTo("Third answer.");
    }
  }

  @Nested
  class A_failed_compaction {

    @Test
    void is_invisible_to_the_user_but_visible_on_the_hub() {
      FailingSummarizerProvider provider =
          new FailingSummarizerProvider(
              List.of(
                  List.of(
                      new ModelEvent.TextChunk("First answer."),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(150_000, 20, 0))),
                  List.of(
                      new ModelEvent.TextChunk("Second answer."),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()))));
      RecordingSubscriber subscriber = new RecordingSubscriber();
      Agent agent =
          Nessy.agent()
              .provider(provider)
              .model("fake-model")
              .compaction(new CompactionPolicy(100_000, 0, 256, "Summarize."))
              .build();
      subscriber.attachTo(agent.events());

      var conversation = agent.converse();
      conversation.send("first question");
      Reply reply = conversation.send("second question");

      assertThat(reply.failed()).isFalse();
      assertThat(reply.text()).isEqualTo("Second answer.");
      assertThat(reply.state().generation()).isZero();
      List<CompactionFailed> failures = subscriber.ofType(CompactionFailed.class);
      assertThat(failures).hasSize(1);
      assertThat(failures.getFirst().reason()).contains("summarizer exploded");
    }
  }

  @Nested
  class An_eliding_context_builder {

    /**
     * Arithmetic: after send 1, the settled transcript is {@code [user1, asst1(tool_use), user2
     * (tool_results), asst2]} (size 4, matching {@code
     * a_full_tool_calling_conversation_runs_end_to_end}). {@code Reducer.userSaid} appends send 2's
     * user message before the model is called, so the state projected for send 2 has 5 messages.
     * With {@code elidingToolResults(2)}, {@code firstRecentIndex = max(0, 5 - 2) = 3}: index 2
     * (the tool-results message) falls before that window and is elided on the wire, while indices
     * 3 and 4 (asst2, user3) stay verbatim. {@code SessionState} itself is never touched — elision
     * is a per-request projection.
     */
    @Test
    void shrinks_what_the_model_sees_not_what_the_state_keeps() {
      ScriptedModelProvider provider =
          ScriptedModelProvider.builder()
              .text("Let me add those.")
              .toolUse("c1", "add", addArgs(2, 2))
              .endWithToolUse()
              .text("The answer is 4.")
              .endTurn()
              .text("And what about 3+3?")
              .endTurn()
              .build();
      Agent agent =
          Nessy.agent()
              .provider(provider)
              .model("fake-model")
              .tools(new AddTool())
              .contextBuilder(ContextBuilder.elidingToolResults(2))
              .build();

      var conversation = agent.converse();
      Reply firstReply = conversation.send("what is 2+2?");
      Reply secondReply = conversation.send("thanks, what else?");

      assertThat(secondReply.failed()).isFalse();

      Message wireToolResults = provider.requests().getLast().messages().get(2);
      ToolResultBlock elidedBlock = (ToolResultBlock) wireToolResults.content().getFirst();
      assertThat(elidedBlock.content()).isEqualTo("[elided]");
      assertThat(elidedBlock.toolUseId()).isEqualTo("c1");

      Message realToolResults = secondReply.state().messages().get(2);
      ToolResultBlock realBlock = (ToolResultBlock) realToolResults.content().getFirst();
      assertThat(realBlock.content()).isEqualTo("4");
      assertThat(firstReply.state().messages().get(2)).isEqualTo(realToolResults);
    }
  }
}
