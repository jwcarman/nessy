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
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
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
 * deltas, prompt again (design §3). One conversation per {@link #run()} — the exact shape {@code
 * AnthropicChat}, {@code OpenAiChat}, and {@code Scout} shared before this module existed.
 *
 * <pre>{@code
 * ConsoleRepl.of(agent)
 *     .banner("scout — ask about any public GitHub repo")
 *     .prompt("you> ")
 *     .exitOn("exit", "quit")
 *     .run();
 * }</pre>
 *
 * <p>Every decision the loop makes — banner, prompt, exit words, blank-line reprompt, the spinner's
 * erase-on-first-event handoff to the renderer — lives here, against a plain {@link
 * BufferedReader}/{@link Writer} pair, so it is exercised headless in this module's own tests with
 * no real console anywhere in the picture. {@link Builder#run()} is the only place a real console
 * enters: a thin wrap of {@link System#in} and {@link System#out}.
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

  ConsoleRepl(
      Agent<String> agent,
      String banner,
      String prompt,
      Set<String> exitWords,
      TurnObserver renderer,
      Io io) {
    this(agent, banner, prompt, exitWords, renderer, io, null, null);
  }

  ConsoleRepl(
      Agent<String> agent,
      String banner,
      String prompt,
      Set<String> exitWords,
      TurnObserver renderer,
      Io io,
      PlanStore planStore) {
    this(agent, banner, prompt, exitWords, renderer, io, planStore, null);
  }

  ConsoleRepl(
      Agent<String> agent,
      String banner,
      String prompt,
      Set<String> exitWords,
      TurnObserver renderer,
      Io io,
      PlanStore planStore,
      String farewell) {
    Objects.requireNonNull(agent, "agent must not be null");
    Objects.requireNonNull(io, "io must not be null");
    this.writer = io.writer();
    this.conversation = agent.converse();
    this.banner = Objects.requireNonNull(banner, "banner must not be null");
    this.prompt = Objects.requireNonNull(prompt, "prompt must not be null");
    this.exitWords = Set.copyOf(Objects.requireNonNull(exitWords, "exitWords must not be null"));
    this.renderer = renderer != null ? renderer : ConsoleRenderer.observer(this.writer);
    this.reader = io.reader();
    this.planStore = planStore;
    this.farewell = farewell;
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

  /** Starts a {@link Builder} for {@code agent}. */
  public static Builder of(Agent<String> agent) {
    return new Builder(agent);
  }

  /** Collects the loop's configuration; {@link #run()} is the public, real-console entry point. */
  public static final class Builder {

    private static final List<String> DEFAULT_EXIT_WORDS = List.of("exit", "quit");

    private final Agent<String> agent;
    private String banner = "";
    private String prompt = "> ";
    private Set<String> exitWords = new LinkedHashSet<>(DEFAULT_EXIT_WORDS);
    private TurnObserver renderer;
    private PlanStore planStore;
    private String farewell;

    private Builder(Agent<String> agent) {
      this.agent = Objects.requireNonNull(agent, "agent must not be null");
    }

    /** The line printed once, before the first prompt. Empty (the default) prints nothing. */
    public Builder banner(String banner) {
      this.banner = Objects.requireNonNull(banner, "banner must not be null");
      return this;
    }

    /** The line printed before every read. Defaults to {@code "> "}. */
    public Builder prompt(String prompt) {
      this.prompt = Objects.requireNonNull(prompt, "prompt must not be null");
      return this;
    }

    /**
     * The words (after trimming) that end the loop. Defaults to {@code "exit"}, {@code "quit"}.
     * Duplicate words are silently deduplicated ({@link Set#copyOf}, not {@link Set#of}'s
     * throw-on-duplicate) — the caller is naming a set, not proving one is already distinct.
     *
     * @throws IllegalArgumentException if {@code words} is empty — a loop with no way out is a
     *     trap, not a valid configuration
     */
    public Builder exitOn(String... words) {
      Objects.requireNonNull(words, "words must not be null");
      if (words.length == 0) {
        throw new IllegalArgumentException("at least one exit word is required");
      }
      this.exitWords = Set.copyOf(Arrays.asList(words));
      return this;
    }

    /** Overrides the default {@link ConsoleRenderer} wholesale. */
    public Builder renderer(TurnObserver renderer) {
      this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
      return this;
    }

    /**
     * Opts into the plan checklist (design §9): the app that granted the model {@code update_plan}
     * hands the same {@code store} here, and the REPL prints the checklist at the end of every turn
     * whose plan changed. Mirrors the grant principle — the console never guesses a plan facility
     * exists.
     *
     * @throws IllegalStateException if called a second time
     */
    public Builder plan(PlanStore store) {
      Objects.requireNonNull(store, "store must not be null");
      if (this.planStore != null) {
        throw new IllegalStateException("plan(PlanStore) was already called");
      }
      this.planStore = store;
      return this;
    }

    /**
     * The line printed the instant the loop ends — an exit word or end of input — before {@link
     * #run()} returns: dim-styled when styling is enabled, plain text otherwise (see {@link
     * Ansi#dim}). Optional; unset means the loop ends silently, exactly the old behavior.
     *
     * @throws IllegalStateException if called a second time
     */
    public Builder farewell(String farewell) {
      Objects.requireNonNull(farewell, "farewell must not be null");
      if (this.farewell != null) {
        throw new IllegalStateException("farewell(String) was already called");
      }
      this.farewell = farewell;
      return this;
    }

    /**
     * The real-console entry point: a thin adapter over {@link System#in}/{@link System#out}. The
     * reader is {@link ConsoleIo#stdin()}, not a fresh wrap of {@link System#in} — shared with
     * {@link ConsoleApprover}'s own default constructor, so a mid-turn approval prompt reads from
     * the same buffer this loop does, rather than each stealing from the other's read of stdin.
     */
    public void run() {
      new ConsoleRepl(
              agent,
              banner,
              prompt,
              exitWords,
              renderer,
              new Io(ConsoleIo.stdin(), ConsoleIo.stdout()),
              planStore,
              farewell)
          .run();
    }
  }
}
