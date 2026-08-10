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
package org.jwcarman.nessy.api.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.ToolCall;

class ContextTest {

  private static ToolCall call(String id) {
    return new ToolCall(id, "read_file", JsonNodeFactory.instance.objectNode());
  }

  private static Message assistantText(String text) {
    return Message.assistant(List.of(new TextBlock(text)));
  }

  @Nested
  class Validity {

    @Test
    void a_plain_conversation_is_valid() {
      Context context = Context.of(List.of(Message.user("hi"), assistantText("hello")));

      assertThat(context.messages()).hasSize(2);
    }

    @Test
    void a_completed_tool_exchange_is_valid() {
      Message assistant =
          Message.assistant(List.of(new ToolUseBlock(call("c1")), new ToolUseBlock(call("c2"))));
      Message results =
          Message.toolResults(
              List.of(
                  new ToolResultBlock("c1", "ok", false), new ToolResultBlock("c2", "ok", false)));

      Context context = Context.of(List.of(assistant, results));

      assertThat(context.messages()).containsExactly(assistant, results);
    }

    @Test
    void an_unanswered_tool_use_is_rejected() {
      Message assistant = Message.assistant(List.of(new ToolUseBlock(call("c1"))));

      assertThatThrownBy(() -> Context.of(List.of(assistant)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("c1");
    }

    @Test
    void a_partial_results_message_is_rejected() {
      Message assistant =
          Message.assistant(List.of(new ToolUseBlock(call("c1")), new ToolUseBlock(call("c2"))));
      Message results = Message.toolResults(List.of(new ToolResultBlock("c1", "ok", false)));

      assertThatThrownBy(() -> Context.of(List.of(assistant, results)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("c2");
    }

    @Test
    void a_result_for_an_unknown_id_is_rejected() {
      Message assistant = Message.assistant(List.of(new ToolUseBlock(call("c1"))));
      Message results =
          Message.toolResults(
              List.of(
                  new ToolResultBlock("c1", "ok", false),
                  new ToolResultBlock("unknown", "ok", false)));

      assertThatThrownBy(() -> Context.of(List.of(assistant, results)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unknown");
    }

    @Test
    void a_result_outside_an_answering_message_is_rejected() {
      Message results = Message.toolResults(List.of(new ToolResultBlock("c1", "ok", false)));

      assertThatThrownBy(() -> Context.of(List.of(Message.user("hi"), results)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("c1");
    }

    @Test
    void an_interleaved_message_breaks_the_pair() {
      Message assistant = Message.assistant(List.of(new ToolUseBlock(call("c1"))));
      Message interleaved = Message.user("wait, one more thing");
      Message results = Message.toolResults(List.of(new ToolResultBlock("c1", "ok", false)));

      assertThatThrownBy(() -> Context.of(List.of(assistant, interleaved, results)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("c1");
    }

    @Test
    void an_empty_context_is_valid() {
      Context context = Context.of(List.of());

      assertThat(context.messages()).isEmpty();
    }
  }

  @Nested
  class The_pair_safe_cut {

    @Test
    void the_cut_lands_exactly_on_the_keep_recent_boundary_when_it_qualifies_there() {
      // 10 plain-text messages; keepRecentMessages=6 puts the limit at index 4 (u3), which
      // qualifies on the very first check — no walk-down needed.
      Context context =
          Context.of(
              List.of(
                  Message.user("u1"), assistantText("a1"),
                  Message.user("u2"), assistantText("a2"),
                  Message.user("u3"), assistantText("a3"),
                  Message.user("u4"), assistantText("a4"),
                  Message.user("u5"), assistantText("a5")));

      assertThat(context.pairSafeCut(6)).isEqualTo(4);
    }

    @Test
    void the_cut_walks_down_past_a_tool_exchange() {
      // 8 messages; keepRecentMessages=3 puts the naive limit at index 5, the tool-result
      // message. That and the tool_use message above it are not genuine user turns, so the
      // walk continues down to index 2 (u2), the nearest genuine user turn.
      Message toolUse = Message.assistant(List.of(new ToolUseBlock(call("c1"))));
      Message results = Message.toolResults(List.of(new ToolResultBlock("c1", "ok", false)));
      Context context =
          Context.of(
              List.of(
                  Message.user("u1"),
                  assistantText("a1"),
                  Message.user("u2"),
                  assistantText("a2"),
                  toolUse,
                  results,
                  Message.user("u3"),
                  assistantText("a3")));

      assertThat(context.pairSafeCut(3)).isEqualTo(2);
    }

    @Test
    void zero_when_nothing_qualifies() {
      Message toolUse = Message.assistant(List.of(new ToolUseBlock(call("c1"))));
      Message results = Message.toolResults(List.of(new ToolResultBlock("c1", "ok", false)));
      Context context = Context.of(List.of(toolUse, results));

      assertThat(context.pairSafeCut(0)).isZero();
    }

    @Test
    void keep_recent_messages_of_zero_clamps_to_size_minus_one() {
      // Without the clamp, the naive limit (size - 0 = 2) would index past the end. Clamped to
      // size - 1 = 1, which is the genuine user turn at index 1.
      Context context = Context.of(List.of(assistantText("a0"), Message.user("u1")));

      assertThat(context.pairSafeCut(0)).isEqualTo(1);
    }
  }

  @Nested
  class Head {

    @Test
    void head_returns_the_prefix_before_cut() {
      Message first = Message.user("u1");
      Message second = assistantText("a1");
      Context context = Context.of(List.of(first, second, Message.user("u2")));

      Context head = context.head(context.pairSafeCut(1));

      assertThat(head.messages()).containsExactly(first, second);
    }
  }

  private static Message toolUse(String callId) {
    return Message.assistant(List.of(new ToolUseBlock(call(callId))));
  }

  @Nested
  class Drop {

    @Test
    void dropping_either_half_of_an_exchange_takes_the_whole_exchange() {
      Message before = Message.user("before");
      Message assistant = toolUse("c1");
      Message results = Message.toolResults(List.of(new ToolResultBlock("c1", "ok", false)));
      Message after = Message.user("after");
      Context context = Context.of(List.of(before, assistant, results, after));

      Context resultsMatched = context.drop(message -> message == results);
      Context toolUseMatched = context.drop(message -> message == assistant);

      assertThat(resultsMatched.messages()).containsExactly(before, after);
      assertThat(toolUseMatched.messages()).containsExactly(before, after);
    }

    @Test
    void plain_messages_drop_individually() {
      Message keep1 = Message.user("keep 1");
      Message drop1 = Message.user("drop me");
      Message keep2 = assistantText("keep 2");
      Context context = Context.of(List.of(keep1, drop1, keep2));

      Context dropped = context.drop(message -> message == drop1);

      assertThat(dropped.messages()).containsExactly(keep1, keep2);
    }

    @Test
    void dropping_nothing_returns_an_equal_context() {
      Context context = Context.of(List.of(Message.user("hi"), assistantText("hello")));

      Context dropped = context.drop(message -> false);

      assertThat(dropped).isEqualTo(context);
    }

    @Test
    void consecutive_exchanges_do_not_cross_contaminate() {
      // Pins the i += 2 step: matching only the first exchange's results message must not spill
      // over into the second exchange.
      Message assistant1 = toolUse("c1");
      Message results1 = Message.toolResults(List.of(new ToolResultBlock("c1", "ok", false)));
      Message assistant2 = toolUse("c2");
      Message results2 = Message.toolResults(List.of(new ToolResultBlock("c2", "ok", false)));
      Context context = Context.of(List.of(assistant1, results1, assistant2, results2));

      Context dropped = context.drop(message -> message == results1);

      assertThat(dropped.messages()).containsExactly(assistant2, results2);
    }

    @Test
    void a_plain_message_between_exchanges_drops_alone() {
      Message assistant1 = toolUse("c1");
      Message results1 = Message.toolResults(List.of(new ToolResultBlock("c1", "ok", false)));
      Message plain = Message.user("in between");
      Message assistant2 = toolUse("c2");
      Message results2 = Message.toolResults(List.of(new ToolResultBlock("c2", "ok", false)));
      Context context = Context.of(List.of(assistant1, results1, plain, assistant2, results2));

      Context dropped = context.drop(message -> message == plain);

      assertThat(dropped.messages()).containsExactly(assistant1, results1, assistant2, results2);
    }

    @Test
    void a_null_predicate_is_rejected() {
      Context context = Context.of(List.of(Message.user("hi")));

      assertThatThrownBy(() -> context.drop(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("predicate must not be null");
    }
  }

  @Nested
  class Map {

    @Test
    void a_rewrite_that_breaks_pairing_throws_naming_the_orphan() {
      Message assistant = toolUse("c1");
      Message results = Message.toolResults(List.of(new ToolResultBlock("c1", "ok", false)));
      Context context = Context.of(List.of(assistant, results));
      Function<Message, Message> renameCallId =
          message -> message == assistant ? toolUse("c1-renamed") : message;

      assertThatThrownBy(() -> context.map(renameCallId))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("c1-renamed");
    }

    @Test
    void an_identity_rewrite_preserves_everything() {
      Context context =
          Context.of(List.of(Message.user("hi"), assistantText("hello"), Message.user("bye")));

      Context rewritten = context.map(Function.identity());

      assertThat(rewritten).isEqualTo(context);
    }

    @Test
    void a_null_rewriter_is_rejected() {
      Context context = Context.of(List.of(Message.user("hi")));

      assertThatThrownBy(() -> context.map(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("rewriter must not be null");
    }

    @Test
    void a_rewriter_returning_null_is_rejected() {
      Context context = Context.of(List.of(Message.user("hi")));

      assertThatThrownBy(() -> context.map(message -> null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("rewriter must not return a null message");
    }
  }

  @Nested
  class Enrich {

    @Test
    void enrich_appends_exactly_one_user_message() {
      Context context = Context.of(List.of(Message.user("hi")));

      Context enriched = context.enrich(new TextBlock("a remembered fact"));

      assertThat(enriched.messages()).hasSize(2);
      Message appended = enriched.messages().getLast();
      assertThat(appended.role()).isEqualTo(Role.USER);
      assertThat(appended.content()).containsExactly(new TextBlock("a remembered fact"));
    }

    @Test
    void an_empty_enrichment_is_rejected() {
      Context context = Context.of(List.of(Message.user("hi")));

      assertThatThrownBy(() -> context.enrich(List.of()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("blocks must not be empty");
    }

    @Test
    void a_null_varargs_array_is_rejected() {
      Context context = Context.of(List.of(Message.user("hi")));

      assertThatThrownBy(() -> context.enrich((ContentBlock[]) null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("blocks must not be null");
    }

    @Test
    void a_null_block_list_is_rejected() {
      Context context = Context.of(List.of(Message.user("hi")));

      assertThatThrownBy(() -> context.enrich((List<ContentBlock>) null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("blocks must not be null");
    }

    @Test
    void enrich_defensively_copies_the_block_list() {
      Context context = Context.of(List.of(Message.user("hi")));
      List<ContentBlock> mutable = new ArrayList<>();
      mutable.add(new TextBlock("original"));

      Context enriched = context.enrich(mutable);
      mutable.add(new TextBlock("added after the fact"));
      mutable.set(0, new TextBlock("mutated after the fact"));

      assertThat(enriched.messages().getLast().content())
          .containsExactly(new TextBlock("original"));
    }
  }

  @Nested
  class Eliding_tool_results {

    @Test
    void old_tool_results_are_elided_but_their_ids_and_pairing_survive() {
      Message assistant1 =
          Message.assistant(
              List.of(new ToolUseBlock(call("call-1")), new ToolUseBlock(call("call-2"))));
      Message old_result =
          Message.toolResults(
              List.of(
                  new ToolResultBlock("call-1", "forty-two", false),
                  new ToolResultBlock("call-2", "boom", true)));
      Message middle = Message.user("in between");
      Message assistant2 = toolUse("call-3");
      Message recent_result =
          Message.toolResults(List.of(new ToolResultBlock("call-3", "still fresh", false)));
      Context context =
          Context.of(List.of(assistant1, old_result, middle, assistant2, recent_result));

      Context shaped = context.elideToolResults(1);

      List<Message> expected =
          List.of(
              assistant1,
              Message.toolResults(
                  List.of(
                      new ToolResultBlock("call-1", "[elided]", false),
                      new ToolResultBlock("call-2", "[elided]", true))),
              middle,
              assistant2,
              recent_result);
      assertThat(shaped.messages()).containsExactlyElementsOf(expected);
    }

    @Test
    void recent_messages_are_verbatim() {
      Message assistant_call1 = toolUse("call-1");
      Message old_result =
          Message.toolResults(List.of(new ToolResultBlock("call-1", "old", false)));
      Message recent_assistant = toolUse("call-2");
      Message recent_result =
          Message.toolResults(List.of(new ToolResultBlock("call-2", "recent", false)));
      Context context =
          Context.of(List.of(assistant_call1, old_result, recent_assistant, recent_result));

      Context shaped = context.elideToolResults(2);

      assertThat(shaped.messages().get(2)).isSameAs(recent_assistant);
      assertThat(shaped.messages().get(3)).isSameAs(recent_result);
    }

    @Test
    void non_tool_blocks_are_never_touched() {
      TextBlock untouched_text = new TextBlock("keep me exactly as I am");
      Message assistant1 = toolUse("call-1");
      Message old_mixed =
          Message.user(List.of(untouched_text, new ToolResultBlock("call-1", "gone", false)));
      Message recent = Message.user("recent");
      Context context = Context.of(List.of(assistant1, old_mixed, recent));

      Context shaped = context.elideToolResults(1);

      ContentBlock preserved = shaped.messages().get(1).content().get(0);
      assertThat(preserved).isSameAs(untouched_text);
    }

    @Test
    void keep_zero_elides_everything_and_keep_huge_elides_nothing() {
      Message assistant1 = toolUse("call-1");
      Message first = Message.toolResults(List.of(new ToolResultBlock("call-1", "one", false)));
      Message assistant2 = toolUse("call-2");
      Message second = Message.toolResults(List.of(new ToolResultBlock("call-2", "two", false)));
      Context context = Context.of(List.of(assistant1, first, assistant2, second));

      Context elides_everything = context.elideToolResults(0);
      Context elides_nothing = context.elideToolResults(100);

      assertThat(elides_everything.messages())
          .containsExactly(
              assistant1,
              Message.toolResults(List.of(new ToolResultBlock("call-1", "[elided]", false))),
              assistant2,
              Message.toolResults(List.of(new ToolResultBlock("call-2", "[elided]", false))));
      assertThat(elides_nothing.messages()).containsExactly(assistant1, first, assistant2, second);
      assertThat(elides_nothing.messages().get(1)).isSameAs(first);
      assertThat(elides_nothing.messages().get(3)).isSameAs(second);
    }

    @Test
    void keep_recent_messages_must_not_be_negative() {
      Context context = Context.of(List.of(Message.user("hi")));

      assertThatThrownBy(() -> context.elideToolResults(-1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("keepRecentMessages must be at least 0");
    }
  }

  @Nested
  class Keep_recent {

    @Test
    void the_window_lands_on_a_pair_safe_boundary() {
      // Same shape as The_pair_safe_cut#the_cut_walks_down_past_a_tool_exchange: keepRecent(3)
      // would naively land inside the tool exchange, so the boundary walks down to u2 (index 2),
      // leaving a tail of 6 messages — longer than the 3 requested.
      Message toolUse = toolUse("c1");
      Message results = Message.toolResults(List.of(new ToolResultBlock("c1", "ok", false)));
      Message u1 = Message.user("u1");
      Message a1 = assistantText("a1");
      Message u2 = Message.user("u2");
      Message a2 = assistantText("a2");
      Message u3 = Message.user("u3");
      Message a3 = assistantText("a3");
      Context context = Context.of(List.of(u1, a1, u2, a2, toolUse, results, u3, a3));

      Context kept = context.keepRecent(3);

      assertThat(kept.messages()).containsExactly(u2, a2, toolUse, results, u3, a3);
      assertThat(kept.messages()).hasSizeGreaterThan(3);
    }

    @Test
    void no_safe_boundary_returns_the_context_unchanged() {
      Message toolUse = toolUse("c1");
      Message results = Message.toolResults(List.of(new ToolResultBlock("c1", "ok", false)));
      Context context = Context.of(List.of(toolUse, results));

      Context kept = context.keepRecent(0);

      assertThat(kept).isSameAs(context);
    }

    @Test
    void a_short_context_is_untouched() {
      Context context = Context.of(List.of(Message.user("hi"), assistantText("hello")));

      Context kept = context.keepRecent(10);

      assertThat(kept).isSameAs(context);
    }

    @Test
    void a_negative_n_is_rejected() {
      Context context = Context.of(List.of(Message.user("hi")));

      assertThatThrownBy(() -> context.keepRecent(-1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("n must be at least 0");
    }
  }

  @Nested
  class Limit_tokens {

    @Test
    void dropping_boundaries_until_the_budget_fits() {
      // Heuristic estimator: characters / 4, floored at 1 token/message. Every message here is
      // 16 characters of text, so each costs exactly 4 tokens; six messages cost 24 total. Budget
      // 10 forces a drop: the only pair-safe boundary at or before the naive limit is u3 (index
      // 4), so the head [u1,a1,u2,a2] (16 tokens) goes, leaving [u3,a3] at 8 tokens — under
      // budget, and the loop stops because 8 <= 10.
      Message u1 = Message.user("uuuuuuuuuuuuuuuu");
      Message a1 = assistantText("aaaaaaaaaaaaaaaa");
      Message u2 = Message.user("uuuuuuuuuuuuuuuu");
      Message a2 = assistantText("aaaaaaaaaaaaaaaa");
      Message u3 = Message.user("uuuuuuuuuuuuuuuu");
      Message a3 = assistantText("aaaaaaaaaaaaaaaa");
      Context context = Context.of(List.of(u1, a1, u2, a2, u3, a3));
      TokenEstimator estimator = TokenEstimator.heuristic();

      Context limited = context.limitTokens(10, estimator);

      assertThat(limited.messages()).containsExactly(u3, a3);
      assertThat(limited.tokens(estimator)).isEqualTo(8);
    }

    @Test
    void over_budget_with_no_safe_cut_is_returned_honestly() {
      Message assistant = toolUse("c1");
      Message results =
          Message.toolResults(
              List.of(new ToolResultBlock("c1", "a fairly long tool result", false)));
      Context context = Context.of(List.of(assistant, results));
      TokenEstimator estimator = TokenEstimator.heuristic();

      Context limited = context.limitTokens(1, estimator);

      assertThat(limited).isSameAs(context);
      assertThat(limited.tokens(estimator)).isGreaterThan(1);
    }

    @Test
    void a_budget_below_one_is_rejected() {
      Context context = Context.of(List.of(Message.user("hi")));

      assertThatThrownBy(() -> context.limitTokens(0, TokenEstimator.heuristic()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("budget must be at least 1");
    }

    @Test
    void a_null_estimator_is_rejected() {
      Context context = Context.of(List.of(Message.user("hi")));

      assertThatThrownBy(() -> context.limitTokens(10, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("estimator must not be null");
    }

    @Test
    void every_message_is_estimated_at_most_twice() {
      // Same shape as dropping_boundaries_until_the_budget_fits: the single real cut lands at
      // index 4, dropping [u1,a1,u2,a2]. Linear behavior pinned by call count: the initial sum
      // estimates all 6 messages once, then the subtracted prefix re-estimates only the 4 dropped
      // messages — 10 calls total, never the O(n^2) blowup of re-summing the whole remainder every
      // iteration.
      Message u1 = Message.user("uuuuuuuuuuuuuuuu");
      Message a1 = assistantText("aaaaaaaaaaaaaaaa");
      Message u2 = Message.user("uuuuuuuuuuuuuuuu");
      Message a2 = assistantText("aaaaaaaaaaaaaaaa");
      Message u3 = Message.user("uuuuuuuuuuuuuuuu");
      Message a3 = assistantText("aaaaaaaaaaaaaaaa");
      Context context = Context.of(List.of(u1, a1, u2, a2, u3, a3));
      int[] calls = {0};
      TokenEstimator counting =
          message -> {
            calls[0]++;
            return TokenEstimator.heuristic().estimate(message);
          };

      Context limited = context.limitTokens(10, counting);

      assertThat(limited.messages()).containsExactly(u3, a3);
      assertThat(calls[0]).isEqualTo(10);
    }
  }

  @Nested
  class Tokens {

    @Test
    void the_sum_of_the_parts() {
      Context context = Context.of(List.of(Message.user("1234"), assistantText("12345678")));

      long tokens = context.tokens(TokenEstimator.heuristic());

      assertThat(tokens).isEqualTo(1 + 2);
    }

    @Test
    void a_null_estimator_is_rejected() {
      Context context = Context.of(List.of(Message.user("hi")));

      assertThatThrownBy(() -> context.tokens(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("estimator must not be null");
    }
  }
}
