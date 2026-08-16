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

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.spi.plan.PlanStore;

/**
 * What {@link ConsoleRepl#run(Agent, ReplCustomizer)} hands a customizer: a CONFIG, not a builder
 * (design of record 2026-08-16 §1) — fluent setters, no public {@code build()}. Collects the loop's
 * configuration; {@link #run()} (package-private, reached only from the factory) is where a real
 * console enters.
 */
public final class ReplConfig {

  private static final List<String> DEFAULT_EXIT_WORDS = List.of("exit", "quit");

  private final Agent<String> agent;
  private String banner = "";
  private String prompt = "> ";
  private Set<String> exitWords = new LinkedHashSet<>(DEFAULT_EXIT_WORDS);
  private TurnObserver renderer;
  private PlanStore planStore;
  private String farewell;

  ReplConfig(Agent<String> agent) {
    this.agent = Objects.requireNonNull(agent, "agent must not be null");
  }

  /** The line printed once, before the first prompt. Empty (the default) prints nothing. */
  public ReplConfig banner(String banner) {
    this.banner = Objects.requireNonNull(banner, "banner must not be null");
    return this;
  }

  /** The line printed before every read. Defaults to {@code "> "}. */
  public ReplConfig prompt(String prompt) {
    this.prompt = Objects.requireNonNull(prompt, "prompt must not be null");
    return this;
  }

  /**
   * The words (after trimming) that end the loop. Defaults to {@code "exit"}, {@code "quit"}.
   * Duplicate words are silently deduplicated ({@link Set#copyOf}, not {@link Set#of}'s
   * throw-on-duplicate) — the caller is naming a set, not proving one is already distinct.
   *
   * @throws IllegalArgumentException if {@code words} is empty — a loop with no way out is a trap,
   *     not a valid configuration
   */
  public ReplConfig exitOn(String... words) {
    Objects.requireNonNull(words, "words must not be null");
    if (words.length == 0) {
      throw new IllegalArgumentException("at least one exit word is required");
    }
    this.exitWords = Set.copyOf(Arrays.asList(words));
    return this;
  }

  /** Overrides the default {@link ConsoleRenderer} wholesale. */
  public ReplConfig renderer(TurnObserver renderer) {
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
  public ReplConfig plan(PlanStore store) {
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
  public ReplConfig farewell(String farewell) {
    Objects.requireNonNull(farewell, "farewell must not be null");
    if (this.farewell != null) {
      throw new IllegalStateException("farewell(String) was already called");
    }
    this.farewell = farewell;
    return this;
  }

  /**
   * Turns this config into a running loop — the factory's own step, never a public {@code build()}
   * (design of record 2026-08-16 §1). Reached only from {@link ConsoleRepl#run(Agent,
   * ReplCustomizer)}, once {@code customize} has returned: a thin adapter over {@link
   * System#in}/{@link System#out}. The reader is {@link ConsoleIo#stdin()}, not a fresh wrap of
   * {@link System#in} — shared with {@link ConsoleApprover}'s own default constructor, so a
   * mid-turn approval prompt reads from the same buffer this loop does, rather than each stealing
   * from the other's read of stdin.
   */
  void run() {
    new ConsoleRepl(
            agent,
            new ConsoleRepl.Chrome(banner, prompt, exitWords, farewell),
            renderer,
            new ConsoleRepl.Io(ConsoleIo.stdin(), ConsoleIo.stdout()),
            planStore)
        .run();
  }
}
