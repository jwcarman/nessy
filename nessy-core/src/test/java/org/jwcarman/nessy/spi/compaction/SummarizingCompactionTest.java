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
package org.jwcarman.nessy.spi.compaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.CompactionPolicy;
import org.jwcarman.nessy.api.CompactionStrategy;
import org.jwcarman.nessy.api.CompactionTrigger;
import org.jwcarman.nessy.api.Context;
import org.jwcarman.nessy.api.Message;
import org.jwcarman.nessy.api.SessionId;
import org.jwcarman.nessy.api.SessionState;
import org.jwcarman.nessy.api.TextBlock;
import org.jwcarman.nessy.api.ToolCall;
import org.jwcarman.nessy.api.ToolResultBlock;
import org.jwcarman.nessy.api.ToolUseBlock;
import org.jwcarman.nessy.api.Usage;

/**
 * The default {@link CompactionStrategy}, pure over a scripted {@link Summarizer}: how the working
 * set gets cut, what the strategy hands the summarizer, and how the summary is spliced back in.
 * {@code SummarizingCompaction} itself is package-private, reached only through {@link
 * CompactionStrategies#summarizing}.
 */
class SummarizingCompactionTest {

  private static CompactionPolicy policy(int keepRecentMessages) {
    return new CompactionPolicy(
        CompactionTrigger.atTokens(1), keepRecentMessages, 512, "Summarize.");
  }

  /** Six user/assistant text pairs — twelve messages, every even index a genuine user turn. */
  private static List<Message> sixPairs() {
    List<Message> messages = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      messages.add(Message.user("u" + i));
      messages.add(Message.assistant(List.of(new TextBlock("a" + i))));
    }
    return messages;
  }

  /** One assistant tool_use answered by its result: no genuine user turn anywhere in it. */
  private static List<Message> toolOnlyExchange() {
    ToolCall call = new ToolCall("c1", "read_file", JsonNodeFactory.instance.objectNode());
    Message assistant = Message.assistant(List.of(new ToolUseBlock(call)));
    Message result = Message.user(List.of(new ToolResultBlock(call.id(), "contents", false)));
    return List.of(assistant, result);
  }

  /** Records every head it is handed and replays scripted summaries in order. */
  private static final class RecordingSummarizer implements Summarizer {

    private final Deque<Summary> script;
    private final List<Context> heads = new ArrayList<>();

    RecordingSummarizer(Summary... script) {
      this.script = new ArrayDeque<>(List.of(script));
    }

    @Override
    public Summary summarize(Context head, CompactionPolicy policy) {
      heads.add(head);
      return script.removeFirst();
    }

    List<Context> heads() {
      return List.copyOf(heads);
    }
  }

  @Nested
  class Cutting_and_splicing {

    @Test
    void the_head_is_summarized_and_the_tail_survives_verbatim() {
      List<Message> workingSet = sixPairs();
      Usage spend = new Usage(1_500, 75, 0);
      RecordingSummarizer summarizer =
          new RecordingSummarizer(new Summarizer.Summary("the gist", spend));
      CompactionStrategy strategy = CompactionStrategies.summarizing(policy(4), summarizer);

      CompactionStrategy.Result result = strategy.compact(workingSet);

      List<Message> tail = workingSet.subList(8, workingSet.size());
      List<Message> expected = new ArrayList<>();
      expected.add(Message.user("[Conversation summary — earlier turns compacted]\nthe gist"));
      expected.addAll(tail);
      assertThat(result.workingSet()).isEqualTo(expected);
      assertThat(result.spend()).isEqualTo(spend);
      assertThat(summarizer.heads()).containsExactly(Context.of(workingSet.subList(0, 8)));
    }

    @Test
    void the_summary_prefix_is_the_strategys_business() {
      List<Message> workingSet = sixPairs();
      RecordingSummarizer summarizer =
          new RecordingSummarizer(new Summarizer.Summary("the gist", Usage.zero()));
      CompactionStrategy strategy = CompactionStrategies.summarizing(policy(4), summarizer);

      CompactionStrategy.Result result = strategy.compact(workingSet);

      String text = ((TextBlock) result.workingSet().getFirst().content().getFirst()).text();
      assertThat(text).startsWith("[Conversation summary — earlier turns compacted]\n");
    }
  }

  @Nested
  class No_safe_cut {

    @Test
    void no_safe_cut_returns_the_working_set_unchanged() {
      List<Message> workingSet = toolOnlyExchange();
      RecordingSummarizer summarizer = new RecordingSummarizer();
      CompactionStrategy strategy = CompactionStrategies.summarizing(policy(0), summarizer);

      CompactionStrategy.Result result = strategy.compact(workingSet);

      assertThat(result.workingSet()).isEqualTo(workingSet);
      assertThat(result.spend()).isEqualTo(Usage.zero());
      assertThat(summarizer.heads()).isEmpty();
    }
  }

  @Nested
  class Consulting_the_trigger {

    @Test
    void requires_compaction_delegates_to_the_policys_trigger() {
      RecordingSummarizer summarizer = new RecordingSummarizer();
      CompactionStrategy neverTriggers =
          CompactionStrategies.summarizing(CompactionPolicy.disabled(), summarizer);

      assertThat(
              neverTriggers.requiresCompaction(
                  SessionState.newSession(new SessionId("s1")).withLastInputTokens(Long.MAX_VALUE)))
          .isFalse();
    }
  }
}
