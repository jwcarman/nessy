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
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.Reply;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CompactionPolicy;
import org.jwcarman.nessy.api.CompactionStrategy;
import org.jwcarman.nessy.api.CompactionTrigger;
import org.jwcarman.nessy.api.Event;
import org.jwcarman.nessy.api.Message;
import org.jwcarman.nessy.api.RedactedThinkingBlock;
import org.jwcarman.nessy.api.Role;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.SessionId;
import org.jwcarman.nessy.api.SessionState;
import org.jwcarman.nessy.api.SessionStatus;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.TextBlock;
import org.jwcarman.nessy.api.ThinkingBlock;
import org.jwcarman.nessy.api.ToolResult;
import org.jwcarman.nessy.api.ToolResultBlock;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.event.CompactionFailed;
import org.jwcarman.nessy.api.event.SessionEvent;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.spi.context.ContextBuilder;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.jwcarman.nessy.spi.session.InMemoryTranscriptStore;
import org.jwcarman.nessy.spi.session.TranscriptEntry;
import org.jwcarman.nessy.spi.session.TranscriptStore;

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
  class A_grant_line_per_agent {

    /**
     * {@code AddTool.requiresApproval()} is {@code true}, so its derived default grant would defer
     * every agent to the approver. One harness, two agents, two grant lines for the same tool: the
     * free agent's explicit {@link UsagePolicy#allow()} skips the approver outright, while the
     * gated agent keeps the derived default and hits an approver that denies. The grant, not the
     * tool, is what decided each agent's authority.
     */
    @Test
    void the_grant_line_is_the_security_statement() {
      Harness harness = Nessy.harness().build();

      ScriptedModelProvider freeProvider =
          ScriptedModelProvider.builder()
              .toolUse("c1", "add", addArgs(2, 2))
              .endWithToolUse()
              .text("The answer is 4.")
              .endTurn()
              .build();
      Agent freeAgent =
          harness
              .agent()
              .provider(freeProvider)
              .model("fake-model")
              .tools(ToolGrant.grant(new AddTool()).with(UsagePolicy.allow()))
              // If the approver were consulted at all, this would deny the call and the
              // reply would carry an error instead of "The answer is 4." — proving allow()
              // really does skip it.
              .approver(Approver.denyAll("would fail if ever asked"))
              .build();

      ScriptedModelProvider gatedProvider =
          ScriptedModelProvider.builder()
              .toolUse("c1", "add", addArgs(2, 2))
              .endWithToolUse()
              .text("Understood.")
              .endTurn()
              .build();
      Agent gatedAgent =
          harness
              .agent()
              .provider(gatedProvider)
              .model("fake-model")
              .tools(new AddTool())
              .approver(Approver.denyAll("not on this agent"))
              .build();

      Reply freeReply = freeAgent.converse().send("what is 2+2?");
      Reply gatedReply = gatedAgent.converse().send("what is 2+2?");

      assertThat(freeReply.failed()).isFalse();
      assertThat(freeReply.text()).isEqualTo("The answer is 4.");

      assertThat(gatedReply.failed()).isFalse();
      ToolResultBlock deniedBlock =
          (ToolResultBlock) gatedReply.state().messages().get(2).content().getFirst();
      assertThat(deniedBlock.isError()).isTrue();
      assertThat(deniedBlock.content()).contains("not on this agent");
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
              .compaction(
                  new CompactionPolicy(CompactionTrigger.atTokens(100_000), 0, 256, "Summarize."))
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
              .compaction(
                  new CompactionPolicy(CompactionTrigger.atTokens(100_000), 0, 256, "Summarize."))
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
  class A_custom_summarizer {

    /**
     * {@code .summarizer(...)} replaces only the thing that calls a model inside the default,
     * summarizing strategy — the policy still decides when to trigger and what to keep. Proven two
     * ways: the resulting summary is exactly what the double was scripted to return, and the
     * provider only ever sees the two ordinary conversational calls — never a third, summarization
     * call — because {@link ScriptedSummarizer} intercepted that call entirely.
     */
    @Test
    void the_summarizer_override_replaces_the_providers_summarization_call() {
      ScriptedModelProvider provider =
          ScriptedModelProvider.builder()
              .text("First answer.")
              .endTurn(new Usage(150_000, 20, 0))
              .text("Second answer.")
              .endTurn()
              .build();
      ScriptedSummarizer summarizer =
          ScriptedSummarizer.builder()
              .summary("Summary of earlier turns.", new Usage(500, 10, 0))
              .build();
      Agent agent =
          Nessy.agent()
              .provider(provider)
              .model("fake-model")
              .compaction(
                  new CompactionPolicy(CompactionTrigger.atTokens(100_000), 0, 256, "Summarize."))
              .summarizer(summarizer)
              .build();

      var conversation = agent.converse();
      conversation.send("first question");
      Reply secondReply = conversation.send("second question");

      assertThat(secondReply.failed()).isFalse();
      assertThat(secondReply.text()).isEqualTo("Second answer.");
      assertThat(secondReply.state().generation()).isEqualTo(1);
      Message summaryMessage = secondReply.state().messages().getFirst();
      assertThat(((TextBlock) summaryMessage.content().getFirst()).text())
          .contains("Summary of earlier turns.");
      assertThat(summarizer.heads()).hasSize(1);
      assertThat(provider.requests()).hasSize(2);
    }
  }

  @Nested
  class An_explicit_strategy {

    /**
     * {@code .compaction(CompactionStrategy)} replaces the strategy outright, even when a {@code
     * CompactionPolicy} was set first — the policy-tuned default is never assembled at all. Proven
     * by using a trivial, no-LLM strategy whose shape (keep only the newest message) is nothing
     * like what the policy's summarizing default would have produced (a prefixed summary message
     * plus a verbatim tail): if the policy default had won instead, this would need a third,
     * summarization call from the provider, which the two-turn script below doesn't have — the run
     * would fail with "script exhausted" rather than succeed with a one-message transcript.
     */
    @Test
    void an_explicit_strategy_wins_over_a_policy_set_earlier() {
      ScriptedModelProvider provider =
          ScriptedModelProvider.builder()
              .text("First answer.")
              .endTurn(new Usage(150_000, 20, 0))
              .text("Second answer.")
              .endTurn()
              .build();
      CompactionStrategy keepOnlyTheNewestMessage =
          new CompactionStrategy() {
            @Override
            public boolean requiresCompaction(SessionState state) {
              return state.lastInputTokens() >= 1;
            }

            @Override
            public Result compact(List<Message> workingSet) {
              List<Message> tail = workingSet.subList(workingSet.size() - 1, workingSet.size());
              return new Result(tail, Usage.zero());
            }
          };
      Agent agent =
          Nessy.agent()
              .provider(provider)
              .model("fake-model")
              .compaction(
                  new CompactionPolicy(CompactionTrigger.atTokens(100_000), 10, 256, "Summarize."))
              .compaction(keepOnlyTheNewestMessage)
              .build();

      var conversation = agent.converse();
      conversation.send("first question");
      Reply secondReply = conversation.send("second question");

      assertThat(secondReply.failed()).isFalse();
      assertThat(secondReply.text()).isEqualTo("Second answer.");
      assertThat(secondReply.state().generation()).isEqualTo(1);
      // Only the strategy's kept tail message plus the fresh assistant reply survive — no
      // summary prefix, unlike the policy default's shape (compare A_compacting_conversation).
      assertThat(secondReply.state().messages())
          .containsExactly(
              Message.user("second question"),
              Message.assistant(List.of(new TextBlock("Second answer."))));
      assertThat(provider.requests()).hasSize(2);
      // No summarizer means no spend: the ledger holds only the two conversational turns'
      // usage — first turn's 150_000/20 plus the second, unscripted-usage answer's zero.
      assertThat(secondReply.state().usage()).isEqualTo(new Usage(150_000, 20, 0));
    }
  }

  @Nested
  class A_journaled_transcript {

    /**
     * Same arithmetic as {@link A_compacting_conversation}: a {@code CompactionPolicy} with {@code
     * keepRecentMessages = 0} compacts {@code [user1, asst1]} into a summary once send 2's usage
     * crosses the trigger. The journal, wired via {@code .transcript(...)}, is compaction-blind by
     * construction — it appends every message the instant it is born, before anything downstream
     * can remove it from the working set. So after compaction shrinks {@code reply.state()} to
     * {@code [summary, user2, asst2]} (three messages), the journal still holds all five births in
     * birth order: the two originals compaction discarded, the second question, the summary, and
     * the final answer.
     */
    @Test
    void the_journal_keeps_what_compaction_removes() {
      ScriptedModelProvider provider =
          ScriptedModelProvider.builder()
              .text("First answer.")
              .endTurn(new Usage(150_000, 20, 0))
              .text("Summary of earlier turns.")
              .endTurn()
              .text("Second answer.")
              .endTurn()
              .build();
      InMemoryTranscriptStore journal = TranscriptStore.inMemory();
      Agent agent =
          Nessy.agent()
              .provider(provider)
              .model("fake-model")
              .compaction(
                  new CompactionPolicy(CompactionTrigger.atTokens(100_000), 0, 256, "Summarize."))
              .transcript(journal)
              .build();

      var conversation = agent.converse();
      conversation.send("first question");
      Reply secondReply = conversation.send("second question");
      SessionId sessionId = conversation.sessionId();

      assertThat(secondReply.failed()).isFalse();
      assertThat(secondReply.state().generation()).isEqualTo(1);
      assertThat(secondReply.state().messages()).hasSize(3);

      List<TranscriptEntry> entries = journal.entries(sessionId);
      assertThat(entries).hasSize(5);
      Message originalUser1 = Message.user("first question");
      Message originalAssistant1 = Message.assistant(List.of(new TextBlock("First answer.")));
      assertThat(entries.get(0).message()).isEqualTo(originalUser1);
      assertThat(entries.get(1).message()).isEqualTo(originalAssistant1);
      assertThat(entries.get(2).message()).isEqualTo(Message.user("second question"));
      String summaryText = ((TextBlock) entries.get(3).message().content().getFirst()).text();
      assertThat(summaryText).contains("Summary of earlier turns.");
      assertThat(entries.get(4).message())
          .isEqualTo(Message.assistant(List.of(new TextBlock("Second answer."))));

      // The working set no longer holds the two originals compaction removed — only the journal
      // does.
      assertThat(secondReply.state().messages()).doesNotContain(originalUser1, originalAssistant1);
    }
  }

  @Nested
  class A_ledger_that_bills_the_strategy {

    /**
     * The strategy's spend is not a side channel — it is billed to the same ledger every
     * conversational turn's usage lands in. {@link ScriptedSummarizer} pins the summarizer's spend
     * to a value no conversational turn in this script produces (500 input / 10 output tokens), so
     * {@code reply.state().usage()} landing on turn 1's usage plus exactly that spend proves the
     * bill was added rather than merely that compaction didn't error.
     */
    @Test
    void the_ledger_counts_the_strategys_spend() {
      ScriptedModelProvider provider =
          ScriptedModelProvider.builder()
              .text("First answer.")
              .endTurn(new Usage(150_000, 20, 0))
              .text("Second answer.")
              .endTurn()
              .build();
      ScriptedSummarizer summarizer =
          ScriptedSummarizer.builder()
              .summary("Summary of earlier turns.", new Usage(500, 10, 0))
              .build();
      Agent agent =
          Nessy.agent()
              .provider(provider)
              .model("fake-model")
              .compaction(
                  new CompactionPolicy(CompactionTrigger.atTokens(100_000), 0, 256, "Summarize."))
              .summarizer(summarizer)
              .build();

      var conversation = agent.converse();
      conversation.send("first question");
      Reply secondReply = conversation.send("second question");

      assertThat(secondReply.failed()).isFalse();
      assertThat(secondReply.state().generation()).isEqualTo(1);
      assertThat(secondReply.state().usage()).isEqualTo(new Usage(150_500, 30, 0));
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

      Message wireToolResults = provider.requests().getLast().context().messages().get(2);
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
