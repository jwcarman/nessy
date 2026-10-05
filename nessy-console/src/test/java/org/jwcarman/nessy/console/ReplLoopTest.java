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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.AskOutcome;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.TurnStats;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;

@DisplayName("A terminal conversation")
class ReplLoopTest {

  /** Stands in for a tally nothing here is measuring. */
  static final TurnStats ANY_STATS = TurnStats.opened(Instant.EPOCH);

  private static final AgentId AGENT = new AgentId(UUID.randomUUID());
  private static final IdempotencyKey KEY =
      IdempotencyKey.of(UUID.fromString("01999999-0000-7000-8000-000000000001"));

  private static Narration said(String text) {
    return new Narration.ContentDelta(text);
  }

  /** A streaming provider has already said everything; the answer just closes the turn. */
  private static Narration ended() {
    return new Narration.Answered(new TurnId(1), false, Usage.unreported());
  }

  private static void run(FakeHarness harness, FakeConsole console, ReplConfig config) {
    run(harness, console, config, Optional.of("openai"));
  }

  private static void run(
      FakeHarness harness, FakeConsole console, ReplConfig config, Optional<String> provider) {
    ConsoleNarration narration = new ConsoleNarration(AGENT, console);
    harness.narrateTo(narration);
    new ReplLoop(
            harness,
            AGENT,
            config,
            console,
            narration,
            new ReplLoop.Diagnostics(provider, "a-model", 4096))
        .run();
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

  /**
   * A provider that does not stream says everything at once, and it must still be shown.
   *
   * <p>From the value ask returned, not from the narration: narration says a turn was answered
   * without carrying the answer, so the terminal prints what it was handed.
   */
  @Test
  void an_answer_that_was_not_streamed_is_printed_whole() {
    FakeHarness harness =
        new FakeHarness(List.of(new Narration.Answered(new TurnId(1), false, Usage.unreported())))
            .answering(new AskOutcome.Answered<>("all at once", ANY_STATS));
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
    mine.on(
        Envelopes.of(new AgentType("chat"), new AgentId(UUID.randomUUID()), said("not for you")));
    assertThat(console.written()).isEmpty();
    assertThat(narration.spoke()).isFalse();
  }

  @Nested
  @DisplayName("which conversation it is")
  class WhichConversationItIs {

    private String resumeLine(AgentId id) {
      return "conversation %s: resume with --nessy.console.agent=%s"
          .formatted(id.value(), id.value());
    }

    @Test
    void the_conversation_is_named_at_startup_with_how_to_resume_it() {
      FakeConsole console = new FakeConsole("quit");
      run(new FakeHarness(), console, config());
      assertThat(console.written()).contains(resumeLine(AGENT));
    }

    @Test
    void a_conversation_kept_in_memory_is_named_without_advice_to_resume_it() {
      ReplConfig config = config();
      config.keptInMemory();
      FakeConsole console = new FakeConsole("quit");
      run(new FakeHarness(), console, config);
      assertThat(console.written())
          .contains(
              "conversation %s (kept in memory; ends with this process)".formatted(AGENT.value()))
          .doesNotContain("resume with");
    }

    @Test
    void clear_on_an_in_memory_conversation_does_not_promise_a_resume_either() {
      ReplConfig config = config();
      config.keptInMemory();
      FakeHarness harness = new FakeHarness();
      FakeConsole console = new FakeConsole("/clear", "after", "quit");
      run(harness, console, config);
      assertThat(console.written()).contains("kept in memory").doesNotContain("resume with");
    }

    @Test
    void clear_switches_the_id_the_next_question_is_put_to() {
      FakeHarness harness = new FakeHarness();
      FakeConsole console = new FakeConsole("before", "/clear", "after", "quit");
      run(harness, console, config());
      assertThat(harness.askedOf()).hasSize(2);
      assertThat(harness.askedOf().get(0)).isEqualTo(AGENT);
      assertThat(harness.askedOf().get(1)).isNotEqualTo(AGENT);
    }

    @Test
    void clear_says_the_new_id_and_how_to_resume_it() {
      FakeHarness harness = new FakeHarness();
      FakeConsole console = new FakeConsole("/clear", "after", "quit");
      run(harness, console, config());
      AgentId fresh = harness.askedOf().get(0);
      assertThat(console.written()).contains(resumeLine(fresh));
    }

    @Test
    void clear_leaves_the_old_conversation_running_so_it_can_be_resumed() {
      FakeHarness harness = new FakeHarness();
      run(harness, new FakeConsole("/clear", "quit"), config());
      assertThat(harness.terminated()).isEmpty();
    }

    @Test
    void what_the_new_conversation_says_is_still_printed() {
      FakeHarness harness = new FakeHarness(List.of(said("hello again"), ended()));
      FakeConsole console = new FakeConsole("/clear", "hi", "quit");
      run(harness, console, config());
      assertThat(console.written()).contains("hello again");
    }

    @Test
    void config_reports_the_current_conversation_after_clear() {
      FakeHarness harness = new FakeHarness();
      FakeConsole console = new FakeConsole("/clear", "ask", "/config", "quit");
      run(harness, console, config());
      assertThat(console.written()).contains("chat / " + harness.askedOf().get(0).value());
    }
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
    void a_blank_line_is_not_an_input() {
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
      // The only thing said before the first prompt is which conversation this is.
      List<String> spoken =
          console
              .written()
              .lines()
              .filter(line -> !line.startsWith("conversation "))
              .map(String::strip)
              .filter(line -> !line.isEmpty())
              .toList();
      assertThat(spoken).containsExactly(">");
    }
  }

  @Test
  @DisplayName("a tool call says so, because a silent pause looks like a hang")
  void tool_calls_are_announced() {
    FakeHarness harness =
        new FakeHarness(
            List.of(
                new Narration.ActionsRequested(
                    new TurnId(1),
                    List.of(
                        new Narration.ActionsRequested.Call(
                            new CallId("c1"), KEY, new ToolName("days_until"), "days_until")),
                    Usage.unreported()),
                new Narration.CallFinished(new CallId("c1"), KEY),
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
      FakeHarness harness =
          new FakeHarness(List.of(new Narration.Answered(new TurnId(1), false, Usage.unreported())))
              .answering(new AskOutcome.Answered<>("", ANY_STATS));
      FakeConsole console = new FakeConsole("hello", "/exit");
      run(harness, console, config());
      assertThat(console.written()).contains("ended the turn without saying anything");
    }

    @Test
    void a_refusal_is_reported() {
      FakeHarness harness =
          new FakeHarness(List.of()).answering(new AskOutcome.Refused<>("self-harm", ANY_STATS));
      FakeConsole console = new FakeConsole("hello", "/exit");
      run(harness, console, config());
      assertThat(console.written()).contains("refused");
    }

    @Test
    void a_terminated_conversation_is_reported_as_terminated() {
      FakeHarness harness = new FakeHarness(List.of()).answering(new AskOutcome.Terminated<>());
      FakeConsole console = new FakeConsole("hello", "/exit");
      run(harness, console, config());
      assertThat(console.written()).contains("that conversation has been terminated");
    }

    @Test
    void a_failure_is_reported() {
      FakeHarness harness =
          new FakeHarness(List.of())
              .answering(new AskOutcome.Failed<>("the model was unreachable", ANY_STATS));
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

  @Test
  @DisplayName("says what is actually configured, which the 404 from the wrong vendor does not")
  void the_diagnostic_says_what_is_wired_up() {
    FakeHarness harness = new FakeHarness();
    FakeConsole console = new FakeConsole("/config", "/exit");

    run(harness, console, config().tool(namedTool("days_until")));

    assertThat(console.written()).contains("openai").contains("a-model").contains("days_until");
    assertThat(harness.observed()).as("asking what is configured is not a turn").isEmpty();
  }

  @Test
  @DisplayName(
      "when this Repl did not choose the provider itself, /config says nothing about one rather"
          + " than claiming one")
  void the_diagnostic_omits_the_provider_it_never_chose() {
    FakeHarness harness = new FakeHarness();
    FakeConsole console = new FakeConsole("/config", "/exit");

    run(harness, console, config(), Optional.empty());

    assertThat(console.written()).contains("a-model").doesNotContain("provider");
  }

  /** Just enough of a tool to have a name worth reporting. */
  private static Tool<Void> namedTool(String name) {
    return new Tool<Void>() {
      @Override
      public Class<Void> inputType() {
        return Void.class;
      }

      @Override
      public ToolName name() {
        return new ToolName(name);
      }

      @Override
      public String description() {
        return "does something";
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<Void> request) {
        return Awaited.ready(ToolResult.ok(new Block.Text("done")));
      }
    };
  }
}
