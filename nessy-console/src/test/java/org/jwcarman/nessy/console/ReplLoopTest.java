package org.jwcarman.nessy.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolName;

@DisplayName("A terminal conversation")
class ReplLoopTest {

  private static final AgentId AGENT = new AgentId(UUID.randomUUID());

  private static AgentEvent said(String text) {
    return new AgentEvent.ContentDelta(text);
  }

  /** A streaming provider has already said everything; the answer just closes the turn. */
  private static AgentEvent ended() {
    return new AgentEvent.Answered("(already streamed)");
  }

  private static void run(FakeHarness harness, FakeConsole console, ReplConfig config) {
    ConsoleNarration narration = new ConsoleNarration(AGENT, console);
    harness.narrateTo(narration);
    new ReplLoop(harness, AGENT, config, console, narration).run();
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
    assertThat(console.written()).contains("Hello.").doesNotContain("already streamed");
  }

  /** A provider that does not stream says everything at once, and it must still be shown. */
  @Test
  void an_answer_that_was_not_streamed_is_printed_whole() {
    FakeHarness harness = new FakeHarness(List.of(new AgentEvent.Answered("all at once")));
    FakeConsole console = new FakeConsole("hi", "quit");
    run(harness, console, config());
    assertThat(console.written()).contains("all at once");
  }

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

  /** One narrator hears every agent; this terminal only prints its own. */
  @Test
  void another_agents_events_are_not_printed() {
    ConsoleNarration narration = new ConsoleNarration(AGENT, new FakeConsole());
    FakeConsole console = new FakeConsole();
    ConsoleNarration mine = new ConsoleNarration(AGENT, console);
    mine.on(new AgentType("chat"), new AgentId(UUID.randomUUID()), said("not for you"));
    assertThat(console.written()).isEmpty();
    assertThat(narration.spoke()).isFalse();
  }

  @Nested
  @DisplayName("leaving")
  class Leaving {

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
    @DisplayName("a default word stops being one once others are named")
    void quit_is_just_a_line_when_the_exit_words_are_replaced() {
      FakeHarness harness = new FakeHarness(List.of(said("hi"), ended()));
      run(harness, new FakeConsole("quit", "stop"), config().exitOn("stop"));
      assertThat(harness.observed()).containsExactly("quit");
    }

    @Test
    void the_farewell_is_printed_on_the_way_out() {
      FakeConsole console = new FakeConsole("quit");
      run(new FakeHarness(), console, config().farewell("bye."));
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

  @Test
  @DisplayName("an interrupt while waiting for the turn is put back on the thread, not lost")
  void an_interrupt_while_waiting_for_the_turn_is_restored_on_the_thread() {
    // Nothing ends the turn, so awaitTurn() has nothing to poll but the interrupt itself.
    FakeHarness harness = new FakeHarness(List.of());
    FakeConsole console = new FakeConsole("hello", "quit");
    Thread.currentThread().interrupt();
    try {
      run(harness, console, config());
      assertThat(Thread.currentThread().isInterrupted())
          .as("the catch block re-interrupts rather than swallowing the signal")
          .isTrue();
    } finally {
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
                new AgentEvent.ActionsRequested(List.of(new ToolName("days_until"))),
                new AgentEvent.CallFinished(new CallId("c1")),
                ended()));
    FakeConsole console = new FakeConsole("when is christmas", "quit");
    run(harness, console, config());
    assertThat(console.written()).contains("calling days_until").contains("c1 answered");
  }

  @Nested
  @DisplayName("when a turn ends without an answer")
  class Reporting {

    @Test
    void a_silent_completion_says_so_rather_than_printing_nothing() {
      FakeHarness harness = new FakeHarness(List.of(new AgentEvent.Answered("   ")));
      FakeConsole console = new FakeConsole("hello", "/exit");
      run(harness, console, config());
      assertThat(console.written()).contains("ended the turn without saying anything");
    }

    @Test
    void a_refusal_is_reported() {
      FakeHarness harness = new FakeHarness(List.of(new AgentEvent.TurnRefused()));
      FakeConsole console = new FakeConsole("hello", "/exit");
      run(harness, console, config());
      assertThat(console.written()).contains("refused");
    }

    @Test
    void a_failure_is_reported() {
      FakeHarness harness = new FakeHarness(List.of(new AgentEvent.TurnFailed()));
      FakeConsole console = new FakeConsole("hello", "/exit");
      run(harness, console, config());
      assertThat(console.written()).contains("failed");
    }

    @Test
    @DisplayName("a turn that answered normally says nothing extra")
    void a_completed_turn_that_spoke_is_left_alone() {
      FakeHarness harness = new FakeHarness(List.of(said("hi"), ended()));
      FakeConsole console = new FakeConsole("hello", "/exit");
      run(harness, console, config());
      assertThat(console.written()).contains("hi").doesNotContain("without saying anything");
    }
  }
}
