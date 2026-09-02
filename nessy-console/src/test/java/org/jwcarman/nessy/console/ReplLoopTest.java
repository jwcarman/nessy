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
package org.jwcarman.nessy.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnResult;
import org.jwcarman.nessy.api.model.Usage;

@DisplayName("A terminal conversation")
class ReplLoopTest {

  private static final AgentId AGENT = AgentId.of("cli");

  private static AgentEvent said(String text) {
    return new AgentEvent.TextDelta("e", text);
  }

  private static AgentEvent ended() {
    return endedWith(new TurnResult.Completed());
  }

  private static AgentEvent endedWith(TurnResult outcome) {
    return new AgentEvent.TurnEnded("e", outcome, Usage.unreported());
  }

  private static void run(FakeHarness harness, FakeConsole console, ReplConfig config) {
    new ReplLoop(harness, AGENT, config, console).run();
  }

  private static ReplConfig config() {
    return new ReplConfig();
  }

  @Test
  void what_is_typed_reaches_the_agent() {
    FakeHarness harness = new FakeHarness(List.of(said("hi"), ended()));
    FakeConsole console = new FakeConsole("hello there", "quit");

    run(harness, console, config());

    assertThat(harness.observed()).containsExactly("hello there");
  }

  @Test
  void what_the_agent_says_is_printed_as_it_arrives() {
    FakeHarness harness = new FakeHarness(List.of(said("Hel"), said("lo."), ended()));
    FakeConsole console = new FakeConsole("hi", "quit");

    run(harness, console, config());

    assertThat(console.written()).contains("Hello.");
  }

  /**
   * The one thing that makes this a REPL rather than a fire-and-forget: a person is not asked to
   * type over a reply still being written. A turn that never ends would hang here, which is why the
   * fake always ends one.
   */
  @Test
  @DisplayName("the next prompt waits for the turn to end")
  void the_prompt_does_not_return_until_the_turn_ends() {
    FakeHarness harness =
        new FakeHarness(List.of(said("first"), ended()), List.of(said("second"), ended()));
    FakeConsole console = new FakeConsole("one", "two", "quit");

    run(harness, console, config());

    assertThat(harness.observed()).containsExactly("one", "two");
    assertThat(console.written().indexOf("first")).isLessThan(console.written().indexOf("second"));
  }

  @Test
  @DisplayName("it starts listening before it says anything")
  void it_subscribes_so_the_first_answer_is_not_missed() {
    FakeHarness harness = new FakeHarness(List.of(said("hi"), ended()));

    run(harness, new FakeConsole("quit"), config());

    assertThat(harness.wasListenedTo()).isTrue();
  }

  /**
   * An unclosed subscription leaves a routing entry behind, and an engine narrating into a REPL
   * that has left is how a clean exit turns into a warning about dropped messages.
   */
  @Test
  @DisplayName("and stops listening on the way out")
  void it_closes_its_subscription() {
    FakeHarness harness = new FakeHarness(List.of(said("hi"), ended()));

    run(harness, new FakeConsole("quit"), config());

    assertThat(harness.isListenedTo()).isFalse();
  }

  @Nested
  @DisplayName("leaving")
  class Leaving {

    /**
     * A leave word that is merely ALMOST right is worse than none: it reaches the model, which says
     * a warm goodbye, and the person is exactly where they were. Every form someone might
     * reasonably type has to work.
     */
    @Test
    @DisplayName("every form of leaving works, slash or not, in any case")
    void the_obvious_ways_to_say_it_all_work() {
      for (String word : List.of("quit", "exit", "/quit", "/exit", "EXIT", "  /Quit  ")) {
        FakeHarness harness = new FakeHarness(List.of(said("hi"), ended()));

        run(harness, new FakeConsole(word), config());

        assertThat(harness.observed()).as("'%s' should have left", word).isEmpty();
      }
    }

    @Test
    void an_exit_word_ends_the_loop_without_reaching_the_agent() {
      FakeHarness harness = new FakeHarness();

      run(harness, new FakeConsole("quit", "this is never read"), config());

      assertThat(harness.observed()).isEmpty();
    }

    @Test
    @DisplayName("end of input ends it too, whatever the exit words say")
    void end_of_input_ends_the_loop() {
      FakeHarness harness = new FakeHarness();

      run(harness, new FakeConsole(), config().exitOn("stop"));

      assertThat(harness.observed()).isEmpty();
    }

    @Test
    @DisplayName("a configured word is matched the same forgiving way")
    void a_configured_word_ignores_case_too() {
      FakeHarness harness = new FakeHarness(List.of(said("hi"), ended()));

      run(harness, new FakeConsole("STOP"), config().exitOn("stop"));

      assertThat(harness.observed()).isEmpty();
    }

    @Test
    void a_configured_word_replaces_the_defaults() {
      FakeHarness harness = new FakeHarness(List.of(said("hi"), ended()));
      FakeConsole console = new FakeConsole("stop");

      run(harness, console, config().exitOn("stop"));

      assertThat(harness.observed()).isEmpty();
    }

    @Test
    @DisplayName("a default word stops being one once others are named")
    void quit_is_just_a_line_when_the_exit_words_are_replaced() {
      FakeHarness harness = new FakeHarness(List.of(said("hi"), ended()));
      FakeConsole console = new FakeConsole("quit", "stop");

      run(harness, console, config().exitOn("stop"));

      assertThat(harness.observed()).containsExactly("quit");
    }

    @Test
    void the_farewell_is_printed_on_the_way_out() {
      FakeConsole console = new FakeConsole("quit");

      run(new FakeHarness(), console, config().farewell("bye."));

      // Last, because it is the last thing a person sees.
      assertThat(console.written()).endsWith("bye." + System.lineSeparator());
    }

    @Test
    @DisplayName("an unset farewell prints nothing")
    void is_absent_by_default() {
      FakeConsole console = new FakeConsole("quit");

      run(new FakeHarness(), console, config());

      assertThat(console.written()).doesNotContain("bye");
    }
  }

  /**
   * The loop waits on a {@link java.util.concurrent.BlockingQueue}, and a blocking wait is exactly
   * the kind of call that must not swallow an interrupt: something outside the loop (a shutdown, a
   * test harness) may need the thread back. {@link java.util.concurrent.ArrayBlockingQueue#poll}
   * checks the interrupt flag before it ever blocks, so setting it first makes this deterministic —
   * no five-minute wait for {@code PATIENCE} required.
   */
  @Test
  @DisplayName("an interrupt while waiting for the turn is put back on the thread, not lost")
  void an_interrupt_while_waiting_for_the_turn_is_restored_on_the_thread() {
    // No TurnEnded is ever narrated, so awaitTurn() has nothing to poll but the interrupt itself.
    FakeHarness harness = new FakeHarness(List.of());
    FakeConsole console = new FakeConsole("hello", "quit");
    Thread.currentThread().interrupt();

    try {
      run(harness, console, config());

      assertThat(Thread.currentThread().isInterrupted())
          .as("the catch block re-interrupts rather than swallowing the signal")
          .isTrue();
    } finally {
      // Clears the flag so this test's interrupt does not leak into whichever test runs next on
      // the same surefire-forked thread.
      Thread.interrupted();
    }
  }

  @Nested
  class Blank_lines {

    @Test
    @DisplayName("a blank line prompts again rather than asking the model about nothing")
    void a_blank_line_is_not_an_observation() {
      FakeHarness harness = new FakeHarness(List.of(said("hi"), ended()));
      FakeConsole console = new FakeConsole("", "   ", "something", "quit");

      run(harness, console, config());

      assertThat(harness.observed()).containsExactly("something");
    }
  }

  @Nested
  class The_banner {

    @Test
    void is_printed_once_before_the_first_prompt() {
      FakeConsole console = new FakeConsole("quit");

      run(new FakeHarness(), console, config().banner("nessy chat"));

      assertThat(console.written().indexOf("nessy chat"))
          .isLessThan(console.written().indexOf("> "));
    }

    @Test
    @DisplayName("an unset banner prints nothing at all")
    void is_absent_by_default() {
      FakeConsole console = new FakeConsole("quit");

      run(new FakeHarness(), console, config());

      assertThat(console.written().strip()).isEqualTo(">");
    }
  }

  @Test
  @DisplayName("a tool call says so, because a silent pause looks like a hang")
  void tool_calls_are_announced() {
    FakeHarness harness =
        new FakeHarness(
            List.of(
                new AgentEvent.ToolCallRequested(
                    "e", CallId.of("c1"), "days_until", "counting days"),
                new AgentEvent.ToolCallCompleted(
                    "e",
                    CallId.of("c1"),
                    "days_until",
                    org.jwcarman.nessy.api.tool.ToolResult.ok("116 days")),
                ended()));
    FakeConsole console = new FakeConsole("when is christmas", "quit");

    run(harness, console, config());

    assertThat(console.written()).contains("calling days_until").contains("days_until answered");
  }

  /**
   * What a turn that produced no answer looks like.
   *
   * <p>Observed in a real session: the model ended a turn saying nothing, the REPL printed an empty
   * line, and the person typed "no answer?" — because a silent turn, a refused one and a failed one
   * were all indistinguishable from a hang. TurnResult carries the answer; nothing was reading it.
   */
  @Nested
  @DisplayName("when a turn ends without an answer")
  class Reporting {

    @Test
    void a_silent_completion_says_so_rather_than_printing_nothing() {
      FakeHarness harness = new FakeHarness(List.of(ended()));
      FakeConsole console = new FakeConsole("hello", "/exit");

      new ReplLoop(harness, AGENT, config(), console).run();

      assertThat(console.written()).contains("ended the turn without saying anything");
    }

    @Test
    void a_refusal_names_its_category_and_explanation() {
      FakeHarness harness =
          new FakeHarness(List.of(endedWith(new TurnResult.Refused("safety", "not that"))));
      FakeConsole console = new FakeConsole("hello", "/exit");

      new ReplLoop(harness, AGENT, config(), console).run();

      assertThat(console.written()).contains("refused (safety): not that");
    }

    /** A rate limit, a timeout, a context overflow — all of these used to print nothing at all. */
    @Test
    void a_failure_names_its_reason() {
      FakeHarness harness =
          new FakeHarness(List.of(endedWith(new TurnResult.Failed("rate limited"))));
      FakeConsole console = new FakeConsole("hello", "/exit");

      new ReplLoop(harness, AGENT, config(), console).run();

      assertThat(console.written()).contains("failed: rate limited");
    }

    /**
     * Said even though text WAS streamed: a half-written answer that then hit the ceiling is the
     * case most likely to be read as a complete one.
     */
    @Test
    void a_truncated_answer_says_it_was_cut_off_even_though_it_spoke() {
      FakeHarness harness =
          new FakeHarness(List.of(said("as I was say"), endedWith(new TurnResult.Truncated())));
      FakeConsole console = new FakeConsole("hello", "/exit");

      new ReplLoop(harness, AGENT, config(), console).run();

      assertThat(console.written()).contains("as I was say").contains("cut off");
    }

    @Test
    @DisplayName("a turn that answered normally says nothing extra")
    void a_completed_turn_that_spoke_is_left_alone() {
      FakeHarness harness = new FakeHarness(List.of(said("hi"), ended()));
      FakeConsole console = new FakeConsole("hello", "/exit");

      new ReplLoop(harness, AGENT, config(), console).run();

      assertThat(console.written()).contains("hi").doesNotContain("without saying anything");
    }
  }
}
