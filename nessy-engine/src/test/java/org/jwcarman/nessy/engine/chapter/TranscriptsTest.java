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
package org.jwcarman.nessy.engine.chapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.Input;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TranscriptsTest {

  private static final CallId CALL = new CallId("c1");
  private static final String ACTION = "DaysUntil[date=2025-12-21]";
  private static final String RAW_ARGUMENTS = "{\"raw-argument-text\":1}";

  private static Block.ToolCall call() {
    return new Block.ToolCall("c1", "days_until", RAW_ARGUMENTS);
  }

  /** A turn with one round that made one call, ended as given, and then answered. */
  private static Turn turnWith(
      List<Block.ActionRequestContent> request,
      List<ToolOutcome> outcomes,
      Map<CallId, String> results) {
    Exchange exchange = new Exchange(new Seq(2), request, outcomes, Map.of(CALL, ACTION), results);
    return new Turn(
        new TurnId(1),
        new Input(new Seq(1), List.of(new Block.Text("when is it?"))),
        List.of(exchange),
        new TurnResult.Answered(List.of(new Block.Text("soon"))),
        10);
  }

  private static Turn callEndedWith(ToolOutcome outcome, Map<CallId, String> results) {
    return turnWith(List.of(call()), List.of(outcome), results);
  }

  private static String lineOf(String rendered) {
    return rendered
        .lines()
        .filter(line -> line.startsWith("assistant did: "))
        .findFirst()
        .orElseThrow();
  }

  @Nested
  class A_call {

    @Test
    void that_succeeded_shows_its_action_and_its_result_line() {
      Turn turn =
          callEndedWith(
              new ToolOutcome.Succeeded(CALL, List.of(new Block.Text("raw-result-text"))),
              Map.of(CALL, "-285 days"));

      assertThat(lineOf(Transcripts.render(List.of(turn))))
          .isEqualTo("assistant did: " + ACTION + " -- succeeded: -285 days");
    }

    @Test
    void that_succeeded_with_an_empty_result_line_says_only_that_it_succeeded() {
      Turn turn =
          callEndedWith(
              new ToolOutcome.Succeeded(CALL, List.of(new Block.Text("raw-result-text"))),
              Map.of(CALL, ""));

      assertThat(lineOf(Transcripts.render(List.of(turn))))
          .isEqualTo("assistant did: " + ACTION + " -- succeeded");
    }

    @Test
    void that_failed_shows_the_failures_message() {
      Turn turn = callEndedWith(new ToolOutcome.Failed(CALL, "no such date"), Map.of());

      assertThat(lineOf(Transcripts.render(List.of(turn))))
          .isEqualTo("assistant did: " + ACTION + " -- failed: no such date");
    }

    @Test
    void that_was_denied_shows_the_reason() {
      Turn turn = callEndedWith(new ToolOutcome.Denied(CALL, "not today"), Map.of());

      assertThat(lineOf(Transcripts.render(List.of(turn))))
          .isEqualTo("assistant did: " + ACTION + " -- denied: not today");
    }

    @Test
    void with_no_outcome_says_so() {
      Turn turn = turnWith(List.of(call()), List.of(), Map.of());

      assertThat(lineOf(Transcripts.render(List.of(turn))))
          .isEqualTo("assistant did: " + ACTION + " -- no outcome recorded");
    }

    @Test
    void whose_outcome_arrived_out_of_order_is_matched_by_its_id() {
      Block.ToolCall other = new Block.ToolCall("c2", "other", "{}");
      CallId otherId = new CallId("c2");
      Exchange exchange =
          new Exchange(
              new Seq(2),
              List.of(call(), other),
              List.of(new ToolOutcome.Failed(otherId, "broke"), new ToolOutcome.Denied(CALL, "no")),
              Map.of(CALL, ACTION, otherId, "Other[]"),
              Map.of());
      Turn turn =
          new Turn(
              new TurnId(1),
              new Input(new Seq(1), List.of(new Block.Text("go"))),
              List.of(exchange),
              null,
              0);

      assertThat(Transcripts.render(List.of(turn)))
          .contains("assistant did: " + ACTION + " -- denied: no\n")
          .contains("assistant did: Other[] -- failed: broke\n");
    }

    @Test
    void whose_failure_message_is_long_and_has_line_breaks_is_one_cut_line() {
      String message = ("line one\n".repeat(100)) + "the end of it";
      Turn turn = callEndedWith(new ToolOutcome.Failed(CALL, message), Map.of());
      String prefix = "assistant did: " + ACTION + " -- failed: ";

      String rendered = Transcripts.render(List.of(turn));
      String line = lineOf(rendered);

      assertThat(line).startsWith(prefix);
      String shown = line.substring(prefix.length());
      assertThat(shown).hasSizeLessThanOrEqualTo(255).contains("...").doesNotContain("\n");
      assertThat(shown).startsWith("line one").endsWith("the end of it");
    }

    @Test
    void whose_denial_reason_is_long_is_cut_in_its_middle() {
      String reason = "x".repeat(2000);
      Turn turn = callEndedWith(new ToolOutcome.Denied(CALL, reason), Map.of());
      String prefix = "assistant did: " + ACTION + " -- denied: ";

      String shown = lineOf(Transcripts.render(List.of(turn))).substring(prefix.length());

      assertThat(shown).hasSizeLessThanOrEqualTo(255).contains("...");
    }

    @Test
    void shows_neither_its_raw_arguments_nor_its_raw_result() {
      Turn turn =
          callEndedWith(
              new ToolOutcome.Succeeded(CALL, List.of(new Block.Text("raw-result-text"))),
              Map.of(CALL, "-285 days"));

      String rendered = Transcripts.render(List.of(turn));

      assertThat(rendered).isNotBlank();
      assertThat(rendered).doesNotContain("raw-argument-text").doesNotContain("raw-result-text");
    }
  }

  @Nested
  class The_rest_of_the_story {

    @Test
    void commentary_beside_a_call_stays_an_assistant_line() {
      Turn turn =
          turnWith(
              List.of(new Block.Commentary("let me check"), call()),
              List.of(new ToolOutcome.Succeeded(CALL, List.of(new Block.Text("r")))),
              Map.of(CALL, "-285 days"));

      assertThat(Transcripts.render(List.of(turn)))
          .isEqualTo(
              """
              user: when is it?
              assistant: let me check
              assistant did: DaysUntil[date=2025-12-21] -- succeeded: -285 days
              assistant: soon
              """);
    }
  }
}
