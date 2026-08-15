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
import static org.awaitility.Awaitility.await;

import java.io.BufferedReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;
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
              agent, "welcome aboard", "you> ", Set.of("exit", "quit"), null, reader, writer)
          .run();

      assertThat(writer.toString()).isEqualTo("welcome aboard\nyou> ");
    }

    @Test
    void is_skipped_entirely_when_blank() {
      Ansi.overrideEnabled(false);
      Agent<String> agent = agent_saying();
      BufferedReader reader = new BufferedReader(new StringReader("exit\n"));
      StringWriter writer = new StringWriter();

      new ConsoleRepl(agent, "", "you> ", Set.of("exit", "quit"), null, reader, writer).run();

      assertThat(writer.toString()).isEqualTo("you> ");
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

      new ConsoleRepl(agent, "", "you> ", Set.of("exit", "quit"), null, reader, writer).run();

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

      new ConsoleRepl(agent, "", "you> ", Set.of("exit", "quit"), null, reader, writer).run();

      assertThat(writer.toString()).isEqualTo("you> ");
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

      new ConsoleRepl(agent, "", "you> ", Set.of("exit", "quit"), null, reader, writer).run();

      assertThat(writer.toString()).isEqualTo("you> you> ");
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

      new ConsoleRepl(agent, "", "you> ", Set.of("exit", "quit"), null, reader, writer).run();

      assertThat(writer.toString()).isEqualTo("you> hello once\nyou> hello twice\nyou> ");
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

      new ConsoleRepl(agent, "", "you> ", Set.of("exit", "quit"), null, reader, writer).run();

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

      new ConsoleRepl(agent, "", "you> ", Set.of("exit", "quit"), custom, reader, writer).run();

      assertThat(seen).isNotEmpty();
      // the custom observer writes nothing of its own; only the loop's prompts and the blank
      // line the loop itself prints after every told turn land in the writer.
      assertThat(writer.toString()).isEqualTo("you> \nyou> ");
    }
  }
}
