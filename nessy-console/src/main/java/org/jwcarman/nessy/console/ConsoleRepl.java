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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Conversation;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.spi.plan.Plan;
import org.jwcarman.nessy.spi.plan.PlanStore;

/**
 * The loop every REPL example hand-rolled three times over: read a line, tell the agent, render
 * deltas, prompt again (design §3). One conversation per {@link #run(Agent, ReplCustomizer)} — the
 * exact shape {@code AnthropicChat}, {@code OpenAiChat}, and {@code Scout} shared before this
 * module existed.
 *
 * <pre>{@code
 * ConsoleRepl.run(
 *     agent,
 *     r -> r.banner("scout — ask about any public GitHub repo")
 *         .prompt("you> ")
 *         .exitOn("exit", "quit"));
 * }</pre>
 *
 * <p>Every decision the loop makes — banner, prompt, exit words, blank-line reprompt, the spinner's
 * erase-on-first-event handoff to the renderer — lives here, against a plain {@link
 * BufferedReader}/{@link Writer} pair, so it is exercised headless in this module's own tests with
 * no real console anywhere in the picture. {@link ReplConfig#run()} is the only place a real
 * console enters: a thin wrap of {@link System#in} and {@link System#out}.
 */
public final class ConsoleRepl {

  private final Conversation<String> conversation;
  private final String banner;
  private final String prompt;
  private final Set<String> exitWords;
  private final TurnObserver renderer;
  private final BufferedReader reader;
  private final Writer writer;
  private final PlanStore planStore;
  private final String farewell;
  private Plan lastRenderedPlan;

  /** The console seam as one value: where lines come from and where output goes (S107). */
  record Io(BufferedReader reader, Writer writer) {
    Io {
      Objects.requireNonNull(reader, "reader must not be null");
      Objects.requireNonNull(writer, "writer must not be null");
    }
  }

  /**
   * The loop's fixed decor as one value: banner, prompt, exit words, and farewell (S107, alongside
   * {@link Io}). {@code farewell} is nullable — {@code null} means no farewell line (see {@link
   * Builder#farewell}).
   */
  record Chrome(String banner, String prompt, Set<String> exitWords, String farewell) {
    Chrome {
      Objects.requireNonNull(banner, "banner must not be null");
      Objects.requireNonNull(prompt, "prompt must not be null");
      exitWords = Set.copyOf(Objects.requireNonNull(exitWords, "exitWords must not be null"));
    }
  }

  ConsoleRepl(Agent<String> agent, Chrome chrome, TurnObserver renderer, Io io) {
    this(agent, chrome, renderer, io, null);
  }

  ConsoleRepl(
      Agent<String> agent, Chrome chrome, TurnObserver renderer, Io io, PlanStore planStore) {
    Objects.requireNonNull(agent, "agent must not be null");
    Objects.requireNonNull(chrome, "chrome must not be null");
    Objects.requireNonNull(io, "io must not be null");
    this.writer = io.writer();
    this.conversation = agent.converse();
    this.banner = chrome.banner();
    this.prompt = chrome.prompt();
    this.exitWords = chrome.exitWords();
    this.renderer = renderer != null ? renderer : ConsoleRenderer.observer(this.writer);
    this.reader = io.reader();
    this.planStore = planStore;
    this.farewell = chrome.farewell();
  }

  /**
   * Prints the banner (if any), then loops: prompt, read, exit or tell, until end of input — an
   * exit word or {@code null} (EOF) both print the farewell (if any, see {@link Builder#farewell})
   * immediately, before returning.
   */
  public void run() {
    printBanner();
    while (true) {
      write(prompt);
      String line = readLine();
      if (line == null || exitWords.contains(line.trim())) {
        printFarewell();
        return;
      }
      if (line.isBlank()) {
        continue;
      }
      tell(line);
    }
  }

  private void printBanner() {
    if (!banner.isBlank()) {
      write(Ansi.bold(banner) + "\n");
    }
  }

  private void printFarewell() {
    if (farewell != null) {
      write(Ansi.dim(farewell) + "\n");
    }
  }

  /**
   * Tells {@code line} to the conversation, guaranteeing the spinner stops no matter what happens —
   * a raw {@link RuntimeException} out of {@link Conversation#tell} (a provider/network failure, or
   * the renderer itself throwing — see {@link Conversation#tell}'s own contract) happens before
   * {@link #spinnerErasing} ever sees an event, so nothing but a {@code finally} block can be
   * trusted to stop it.
   *
   * <p>The honest behavior once the spinner is safely stopped: render one red error line, the same
   * shape {@link ConsoleRenderer} already gives a {@code TurnEnded} {@code FAILED} ending, and let
   * the loop reprompt — render-and-continue, not crash-the-REPL. A single bad turn (a flaky network
   * call, say) should not cost the rest of the session.
   */
  private void tell(String line) {
    Spinner spinner = new Spinner(writer);
    spinner.start();
    try {
      conversation.tell(line, spinnerErasing(spinner));
    } catch (RuntimeException e) {
      String reason = Objects.requireNonNullElse(e.getMessage(), e.getClass().getName());
      write("\n" + Ansi.red("! " + reason) + "\n");
    } finally {
      spinner.stop();
    }
    write("\n");
    renderPlanIfChanged();
  }

  /**
   * The end-of-turn half of design §9's plan checklist: when a {@link PlanStore} was granted (see
   * {@link Builder#plan}), reads the current plan for this REPL's own conversation and prints the
   * checklist only when it is present, non-empty, and different from the last one printed — quiet
   * turns, and turns where the plan didn't change, print nothing. No store granted means no read at
   * all.
   */
  private void renderPlanIfChanged() {
    if (planStore == null) {
      return;
    }
    planStore
        .find(conversation.conversationId())
        .filter(plan -> !plan.isEmpty())
        .filter(plan -> !plan.equals(lastRenderedPlan))
        .ifPresent(
            plan -> {
              ConsoleRenderer.checklist(writer, plan);
              lastRenderedPlan = plan;
            });
  }

  /**
   * Wraps {@link #renderer} so the spinner is erased the instant the first {@code TurnEvent}
   * arrives — the design's "stops on first event" rule — rather than waiting for the whole turn to
   * finish; every event, including the first, still reaches the renderer.
   */
  private TurnObserver spinnerErasing(Spinner spinner) {
    AtomicBoolean erased = new AtomicBoolean();
    return event -> {
      if (erased.compareAndSet(false, true)) {
        spinner.stop();
      }
      renderer.on(event);
    };
  }

  private void write(String text) {
    try {
      writer.write(text);
      writer.flush();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private String readLine() {
    try {
      return reader.readLine();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Runs the loop against a real console: {@code customizer} fills in a live {@link ReplConfig},
   * then this factory hands it the real {@link System#in}/{@link System#out} pair and runs it. No
   * public {@code build()} survives here; the factory is the only place a {@link ReplConfig} ever
   * turns into a running loop (design of record 2026-08-16 §1).
   */
  public static void run(Agent<String> agent, ReplCustomizer customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    ReplConfig config = new ReplConfig(agent);
    customizer.customize(config);
    config.run();
  }
}
