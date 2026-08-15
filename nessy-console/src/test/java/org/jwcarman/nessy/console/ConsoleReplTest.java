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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.BufferedReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.spi.plan.Plan;
import org.jwcarman.nessy.spi.plan.PlanStore;
import org.jwcarman.nessy.testing.ScriptedModelProvider;

/**
 * Drives the loop entirely through the package-private reader/writer seam — no console needed —
 * with an {@link Agent} backed by {@link ScriptedModelProvider}, exactly the way {@code ScoutTest}
 * drives {@code Scout#scout} through its own construction seam.
 */
class ConsoleReplTest {

  @AfterEach
  void clear_the_override_seam() {
    Ansi.overrideEnabled(null);
  }

  private static Agent<String> agent_saying(String... replies) {
    ScriptedModelProvider.Builder builder = ScriptedModelProvider.builder();
    for (String reply : replies) {
      builder.text(reply).endTurn();
    }
    Harness harness = Nessy.harness(builder.build()).build();
    return harness.agent().name("repl-test").model("fake-model").systemPrompt("test").build();
  }

  @Nested
  class The_banner {

    @Test
    void prints_once_before_the_first_prompt() {
      Ansi.overrideEnabled(false);
      Agent<String> agent = agent_saying();
      BufferedReader reader = new BufferedReader(new StringReader("exit\n"));
      StringWriter writer = new StringWriter();

      new ConsoleRepl(
              agent,
              "welcome aboard",
              "you> ",
              Set.of("exit", "quit"),
              null,
              new ConsoleRepl.Io(reader, writer))
          .run();

      assertThat(writer).hasToString("welcome aboard\nyou> ");
    }

    @Test
    void is_skipped_entirely_when_blank() {
      Ansi.overrideEnabled(false);
      Agent<String> agent = agent_saying();
      BufferedReader reader = new BufferedReader(new StringReader("exit\n"));
      StringWriter writer = new StringWriter();

      new ConsoleRepl(
              agent, "", "you> ", Set.of("exit", "quit"), null, new ConsoleRepl.Io(reader, writer))
          .run();

      assertThat(writer).hasToString("you> ");
    }
  }

  @Nested
  class The_prompt {

    @Test
    void is_echoed_before_every_single_read_not_just_the_first() {
      Ansi.overrideEnabled(false);
      // Three reads happen here ("hi", "hi again", "exit"), so the prompt — distinguishable from
      // every reply and from the exit word — must appear exactly three times, not merely once.
      Agent<String> agent = agent_saying("first reply", "second reply");
      BufferedReader reader = new BufferedReader(new StringReader("hi\nhi again\nexit\n"));
      StringWriter writer = new StringWriter();

      new ConsoleRepl(
              agent, "", "you> ", Set.of("exit", "quit"), null, new ConsoleRepl.Io(reader, writer))
          .run();

      int promptCount = writer.toString().split("you> ", -1).length - 1;
      assertThat(promptCount).isEqualTo(3);
    }
  }

  @Nested
  class An_exit_word {

    @Test
    void ends_the_loop_without_telling_the_agent() {
      Ansi.overrideEnabled(false);
      Agent<String> agent = agent_saying();
      BufferedReader reader = new BufferedReader(new StringReader("quit\n"));
      StringWriter writer = new StringWriter();

      new ConsoleRepl(
              agent, "", "you> ", Set.of("exit", "quit"), null, new ConsoleRepl.Io(reader, writer))
          .run();

      assertThat(writer).hasToString("you> ");
    }
  }

  @Nested
  class End_of_input {

    @Test
    void ends_the_loop_gracefully_without_telling_the_agent() {
      Ansi.overrideEnabled(false);
      Agent<String> agent = agent_saying();
      // An empty source: BufferedReader#readLine() returns null on the very first read, exactly
      // what a closed pipe or a Ctrl-D at a real terminal looks like.
      BufferedReader reader = new BufferedReader(new StringReader(""));
      StringWriter writer = new StringWriter();

      new ConsoleRepl(
              agent, "", "you> ", Set.of("exit", "quit"), null, new ConsoleRepl.Io(reader, writer))
          .run();

      assertThat(writer).hasToString("you> ");
    }
  }

  @Nested
  class A_blank_line {

    @Test
    void reprompts_without_telling_the_agent() {
      Ansi.overrideEnabled(false);
      Agent<String> agent = agent_saying();
      BufferedReader reader = new BufferedReader(new StringReader("\nexit\n"));
      StringWriter writer = new StringWriter();

      new ConsoleRepl(
              agent, "", "you> ", Set.of("exit", "quit"), null, new ConsoleRepl.Io(reader, writer))
          .run();

      assertThat(writer).hasToString("you> you> ");
    }
  }

  @Nested
  class Every_non_blank_line {

    @Test
    void tells_the_agent_exactly_once_per_line() {
      Ansi.overrideEnabled(false);
      Agent<String> agent = agent_saying("hello once", "hello twice");
      BufferedReader reader = new BufferedReader(new StringReader("hi\nhi again\nexit\n"));
      StringWriter writer = new StringWriter();

      new ConsoleRepl(
              agent, "", "you> ", Set.of("exit", "quit"), null, new ConsoleRepl.Io(reader, writer))
          .run();

      assertThat(writer).hasToString("you> hello once\nyou> hello twice\nyou> ");
    }
  }

  @Nested
  class A_throwing_tell {

    @Test
    void stops_the_spinner_renders_a_red_line_and_the_loop_continues() {
      // Styling enabled on purpose, unlike every other test here: the spinner is a complete no-op
      // while disabled, and this test exists to prove tell()'s finally block actually joins a
      // *real* spinner thread rather than a spinner that never started in the first place.
      Ansi.overrideEnabled(true);
      // A renderer that throws before a single TurnEvent narrates — the real-world shape of a
      // provider/network failure or a broken output stream — is the one scenario where the
      // spinner's own erase-on-first-event handoff never fires; only tell()'s own finally block
      // can save it from spinning forever.
      Harness harness =
          Nessy.harness(ScriptedModelProvider.builder().text("unreached").endTurn().build())
              .build();
      Agent<String> agent =
          harness
              .agent()
              .name("repl-test")
              .model("fake-model")
              .systemPrompt("test")
              .renderer(
                  input -> {
                    throw new IllegalStateException("boom");
                  })
              .build();
      BufferedReader reader = new BufferedReader(new StringReader("hi\nexit\n"));
      StringWriter writer = new StringWriter();

      new ConsoleRepl(
              agent, "", "you> ", Set.of("exit", "quit"), null, new ConsoleRepl.Io(reader, writer))
          .run();

      String output = writer.toString();
      assertThat(output).contains(Ansi.red("! boom"));
      int promptCount = output.split("you> ", -1).length - 1;
      assertThat(promptCount).isEqualTo(2);

      // Spinner#stop() joins its virtual thread before tell() returns, so by now it is already
      // provably dead — but the leak this guards against is exactly a thread that keeps writing a
      // fresh "\r" frame roughly every 80ms forever. Wait comfortably past several frame intervals
      // and confirm the writer received nothing more: the observable proof, not just the internal
      // guarantee, that nothing is still spinning.
      int settledLength = output.length();
      await()
          .pollDelay(Duration.ofMillis(300))
          .atMost(Duration.ofSeconds(2))
          .untilAsserted(() -> assertThat(writer.toString()).hasSize(settledLength));
    }
  }

  @Nested
  class A_renderer_override {

    @Test
    void replaces_the_default_renderer_wholesale() {
      Ansi.overrideEnabled(false);
      Agent<String> agent = agent_saying("hello once");
      BufferedReader reader = new BufferedReader(new StringReader("hi\nexit\n"));
      StringWriter writer = new StringWriter();
      List<TurnEvent> seen = new ArrayList<>();
      TurnObserver custom = seen::add;

      new ConsoleRepl(
              agent,
              "",
              "you> ",
              Set.of("exit", "quit"),
              custom,
              new ConsoleRepl.Io(reader, writer))
          .run();

      assertThat(seen).isNotEmpty();
      // the custom observer writes nothing of its own; only the loop's prompts and the blank
      // line the loop itself prints after every told turn land in the writer.
      assertThat(writer).hasToString("you> \nyou> ");
    }
  }

  @Nested
  class The_exitOn_builder {

    @Test
    void rejects_zero_words_as_a_loop_with_no_way_out() {
      Agent<String> agent = agent_saying();
      ConsoleRepl.Builder builder = ConsoleRepl.of(agent);

      assertThatThrownBy(builder::exitOn)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("at least one exit word");
    }

    @Test
    void deduplicates_repeated_words_instead_of_throwing() {
      Agent<String> agent = agent_saying();
      ConsoleRepl.Builder builder = ConsoleRepl.of(agent);

      // Set.copyOf semantics, not Set.of's throw-on-duplicate: naming the same exit word twice
      // is a caller typo, not a reason to blow up the whole configuration.
      assertThatCode(() -> builder.exitOn("exit", "exit")).doesNotThrowAnyException();
    }
  }

  @Nested
  class The_shared_stdin_seam {

    @Test
    void the_approver_reads_the_same_stream_the_repl_itself_reads() {
      Ansi.overrideEnabled(false);
      ScriptedModelProvider provider =
          ScriptedModelProvider.builder()
              .toolUse("c1", "ping", JsonNodeFactory.instance.objectNode())
              .endWithToolUse()
              .text("pong received")
              .endTurn()
              .build();
      Harness harness = Nessy.harness(provider).build();
      StringWriter writer = new StringWriter();
      // One BufferedReader, one combined script: "hi" is the REPL's own read, "y" is the
      // approver's read mid-turn, "exit" is the REPL's next read afterward — proving a single
      // shared reader serves both consumers in strict order. Two separate BufferedReaders each
      // wrapping the same stdin is exactly the bug this seam fixes: whichever primes its buffer
      // first can swallow bytes (here, "y" or "exit") meant for the other.
      BufferedReader sharedReader = new BufferedReader(new StringReader("hi\ny\nexit\n"));
      ConsoleApprover approver = new ConsoleApprover(sharedReader, writer);
      Agent<String> agent =
          harness
              .agent()
              .name("repl-test")
              .model("fake-model")
              .systemPrompt("test")
              .tools(ToolGrant.grant(new PingTool(), UsagePolicy.requireApproval()))
              .approver(approver)
              .build();

      new ConsoleRepl(
              agent,
              "",
              "you> ",
              Set.of("exit", "quit"),
              null,
              new ConsoleRepl.Io(sharedReader, writer))
          .run();

      String output = writer.toString();
      // The approval went through — proof the approver's "y" read landed, not end-of-stream — and
      // "exit" was still there for the REPL's next read — proof nothing was swallowed — so the
      // loop actually ended rather than blocking on a fourth read that was never scripted.
      assertThat(output).contains("pong received").endsWith("you> ");
    }

    private record PingInput() {}

    private static final class PingTool implements Tool<PingInput> {

      @Override
      public String name() {
        return "ping";
      }

      @Override
      public String description() {
        return "Pings, once approved.";
      }

      @Override
      public Class<PingInput> inputType() {
        return PingInput.class;
      }

      @Override
      public String describe(PingInput input) {
        return "ping";
      }

      @Override
      public Awaited<ToolResult> execute(PingInput input, ToolContext context) {
        return Awaited.ready(ToolResult.ok("pong"));
      }
    }
  }

  @Nested
  class The_plan_checklist {

    @Test
    void a_changed_plan_prints_once_at_the_end_of_the_turn() {
      Ansi.overrideEnabled(false);
      Agent<String> agent = agent_saying("ok");
      Plan plan = new Plan(List.of(new Plan.Task("write tests", Plan.Status.PENDING)));
      ScriptedPlanStore store = ScriptedPlanStore.answering(Optional.of(plan));
      BufferedReader reader = new BufferedReader(new StringReader("hi\nexit\n"));
      StringWriter writer = new StringWriter();

      new ConsoleRepl(
              agent,
              "",
              "you> ",
              Set.of("exit", "quit"),
              null,
              new ConsoleRepl.Io(reader, writer),
              store)
          .run();

      assertThat(writer).hasToString("you> ok\n  [ ] write tests\nyou> ");
    }

    @Test
    void an_unchanged_plan_prints_nothing_on_the_next_turn() {
      Ansi.overrideEnabled(false);
      Agent<String> agent = agent_saying("first", "second");
      Plan plan = new Plan(List.of(new Plan.Task("write tests", Plan.Status.PENDING)));
      ScriptedPlanStore store = ScriptedPlanStore.answering(Optional.of(plan), Optional.of(plan));
      BufferedReader reader = new BufferedReader(new StringReader("hi\nhi again\nexit\n"));
      StringWriter writer = new StringWriter();

      new ConsoleRepl(
              agent,
              "",
              "you> ",
              Set.of("exit", "quit"),
              null,
              new ConsoleRepl.Io(reader, writer),
              store)
          .run();

      int occurrences = writer.toString().split("write tests", -1).length - 1;
      assertThat(occurrences).isEqualTo(1);
    }

    @Test
    void renders_nothing_and_reads_no_store_when_no_store_is_configured() {
      Ansi.overrideEnabled(false);
      Agent<String> agent = agent_saying("ok");
      BufferedReader reader = new BufferedReader(new StringReader("hi\nexit\n"));
      StringWriter writer = new StringWriter();

      new ConsoleRepl(
              agent,
              "",
              "you> ",
              Set.of("exit", "quit"),
              null,
              new ConsoleRepl.Io(reader, writer),
              null)
          .run();

      assertThat(writer).hasToString("you> ok\nyou> ");
    }

    @Test
    void an_absent_or_empty_plan_prints_nothing() {
      Ansi.overrideEnabled(false);
      Agent<String> agent = agent_saying("ok1", "ok2");
      ScriptedPlanStore store =
          ScriptedPlanStore.answering(Optional.empty(), Optional.of(Plan.empty()));
      BufferedReader reader = new BufferedReader(new StringReader("hi\nhi again\nexit\n"));
      StringWriter writer = new StringWriter();

      new ConsoleRepl(
              agent,
              "",
              "you> ",
              Set.of("exit", "quit"),
              null,
              new ConsoleRepl.Io(reader, writer),
              store)
          .run();

      assertThat(writer).hasToString("you> ok1\nyou> ok2\nyou> ");
      assertThat(store.reads()).isEqualTo(2);
    }

    @Test
    void the_final_all_done_state_prints_because_it_differs_from_the_previous_render() {
      Ansi.overrideEnabled(false);
      Agent<String> agent = agent_saying("working", "done");
      Plan inProgress = new Plan(List.of(new Plan.Task("ship it", Plan.Status.IN_PROGRESS)));
      Plan allDone = new Plan(List.of(new Plan.Task("ship it", Plan.Status.DONE)));
      ScriptedPlanStore store =
          ScriptedPlanStore.answering(Optional.of(inProgress), Optional.of(allDone));
      BufferedReader reader = new BufferedReader(new StringReader("hi\nhi again\nexit\n"));
      StringWriter writer = new StringWriter();

      new ConsoleRepl(
              agent,
              "",
              "you> ",
              Set.of("exit", "quit"),
              null,
              new ConsoleRepl.Io(reader, writer),
              store)
          .run();

      assertThat(writer)
          .hasToString("you> working\n  [>] ship it\nyou> done\n  [x] ship it\nyou> ");
    }

    private static final class ScriptedPlanStore implements PlanStore {

      private final List<Optional<Plan>> answers;
      private int index;
      private int reads;

      private ScriptedPlanStore(List<Optional<Plan>> answers) {
        this.answers = answers;
      }

      @SafeVarargs
      private static ScriptedPlanStore answering(Optional<Plan>... answers) {
        return new ScriptedPlanStore(List.of(answers));
      }

      @Override
      public Optional<Plan> find(ConversationId id) {
        reads++;
        Optional<Plan> answer = answers.get(Math.min(index, answers.size() - 1));
        index++;
        return answer;
      }

      @Override
      public void save(ConversationId id, Plan plan) {
        throw new UnsupportedOperationException("ConsoleRepl never writes to the plan store");
      }

      private int reads() {
        return reads;
      }
    }
  }

  @Nested
  class The_plan_builder_verb {

    @Test
    void rejects_a_null_store() {
      Agent<String> agent = agent_saying();
      ConsoleRepl.Builder builder = ConsoleRepl.of(agent);

      assertThatThrownBy(() -> builder.plan(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejects_a_second_call() {
      Agent<String> agent = agent_saying();
      ConsoleRepl.Builder builder = ConsoleRepl.of(agent).plan(PlanStore.inMemory());
      PlanStore second = PlanStore.inMemory();

      assertThatThrownBy(() -> builder.plan(second)).isInstanceOf(IllegalStateException.class);
    }
  }
}
