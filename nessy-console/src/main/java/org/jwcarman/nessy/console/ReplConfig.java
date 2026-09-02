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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.HarnessConfig;
import org.jwcarman.nessy.api.memory.Memory;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolBindingConfig;
import org.jwcarman.nessy.spi.store.Schemas;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/**
 * What {@link Repl#run(ReplCustomizer)} hands a customizer: a CONFIG, not a builder (design of
 * record 2026-08-16 §1) — fluent setters, no public {@code build()}.
 *
 * <p>Every setting has a default that works, because the point of this module is that a console
 * application starts from nothing. A customizer that sets only a system prompt is a complete
 * program.
 */
public final class ReplConfig {

  /**
   * Both the bare words and their slash forms, because a person who has used any other REPL will
   * type {@code /exit} — and a leave word that is merely ALMOST right is worse than none: it goes
   * to the model, which says a warm goodbye and leaves you exactly where you were.
   */
  private static final List<String> DEFAULT_EXIT_WORDS = List.of("exit", "quit", "/exit", "/quit");

  /** Applied to the harness in the order the caller granted them. */
  private final List<Consumer<HarnessConfig<String>>> tools = new ArrayList<>();

  private String banner = "";
  private String prompt = "> ";
  private Set<String> exitWords = new LinkedHashSet<>(DEFAULT_EXIT_WORDS);
  private String farewell = "";
  private String systemPrompt = "You are a helpful assistant in someone's terminal.";
  private AgentType type = AgentType.of("chat");
  private AgentId agentId = AgentId.of("cli");
  private int maxTokens = 4096;

  /**
   * In memory by default, because nothing a REPL writes has any reason to outlive the terminal.
   *
   * <p>Constructed HERE rather than inside {@link Repl} so a caller who needs to reach it — to open
   * a notebook or a plan over the same store — can build one and hand it in, instead of asking this
   * object what it happens to be holding.
   */
  private DataSource dataSource = ownDatabase();

  /** Null until set, which is how {@link Repl} knows to leave the harness on its own default. */
  private Memory memory;

  ReplConfig() {}

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

  /** The line printed once on the way out. Empty (the default) prints nothing. */
  public ReplConfig farewell(String farewell) {
    this.farewell = Objects.requireNonNull(farewell, "farewell must not be null");
    return this;
  }

  /**
   * The words (after trimming) that end the loop. Defaults to {@code "exit"} and {@code "quit"};
   * end-of-input always ends it too, whatever this says.
   *
   * @throws IllegalArgumentException if {@code words} is empty — a loop with no way out is a trap,
   *     not a configuration
   */
  public ReplConfig exitOn(String... words) {
    Objects.requireNonNull(words, "words must not be null");
    if (words.length == 0) {
      throw new IllegalArgumentException(
          "exitOn needs at least one word; a loop with no way out is a trap");
    }
    this.exitWords =
        new LinkedHashSet<>(List.of(words).stream().map(ReplConfig::normalize).toList());
    return this;
  }

  /** The standing instruction this agent works under. */
  public ReplConfig systemPrompt(String systemPrompt) {
    this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
    return this;
  }

  /** What kind of agent this is. Namespaces everything it persists. Defaults to {@code chat}. */
  public ReplConfig agent(AgentType type) {
    this.type = Objects.requireNonNull(type, "type must not be null");
    return this;
  }

  /**
   * Which agent the person is talking to. Defaults to {@code cli}.
   *
   * <p>Worth setting only if one process runs more than one conversation: state is in memory and
   * dies with the process either way, so two runs of the same program never meet.
   */
  public ReplConfig id(AgentId agentId) {
    this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
    return this;
  }

  /** The longest answer to allow. Defaults to 4096 tokens. */
  public ReplConfig maxTokens(int maxTokens) {
    if (maxTokens < 1) {
      throw new IllegalArgumentException("maxTokens must be at least 1");
    }
    this.maxTokens = maxTokens;
    return this;
  }

  /**
   * Where this REPL keeps what it writes. Defaults to a fresh in-memory H2 database.
   *
   * <p>Set this when something ELSE needs the same store — a {@code Notebook}, a plan — so that
   * what the agent remembers and what its tools read live in one place:
   *
   * <pre>{@code
   * DataSource database = ...;
   * Notebook notebook = new JdbcNotebook(database, type);
   * Repl.run(config -> config.dataSource(database).tool(NotebookTools.remember(notebook)));
   * }</pre>
   *
   * <p>A durable database will work, and is the wrong shape for a console application: see {@link
   * Repl} on why nothing here is meant to survive the process.
   */
  public ReplConfig dataSource(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    return this;
  }

  /**
   * What the agent remembers, and what it is shown.
   *
   * <p>Left unset, the harness picks its own default — a recent-history transcript, which is enough
   * for a conversation someone is typing. Set this to put stages in front of the model, such as a
   * notebook index or a plan.
   */
  public ReplConfig memory(Memory memory) {
    this.memory = Objects.requireNonNull(memory, "memory must not be null");
    return this;
  }

  /** Grants one tool, ungated and described by its input's {@code toString()}. */
  public <I> ReplConfig tool(Tool<I> tool) {
    Objects.requireNonNull(tool, "tool must not be null");
    tools.add(harness -> harness.tool(tool));
    return this;
  }

  /**
   * Grants one tool, saying how it is bound — an approver, a renderer.
   *
   * <p>The customizer is held rather than applied, so the tool's input type stays tied to its own
   * binding: the type parameter lives on the METHOD, exactly as it does on {@code HarnessConfig},
   * and a list of {@code ToolBinding<?>} could not keep that promise.
   */
  public <I> ReplConfig tool(Tool<I> tool, Consumer<ToolBindingConfig<I>> customizer) {
    Objects.requireNonNull(tool, "tool must not be null");
    Objects.requireNonNull(customizer, "customizer must not be null");
    tools.add(harness -> harness.tool(tool, customizer));
    return this;
  }

  String banner() {
    return banner;
  }

  String prompt() {
    return prompt;
  }

  /** Whether this line means "I am done", ignoring case and surrounding space. */
  boolean isExit(String line) {
    return exitWords.contains(normalize(line));
  }

  private static String normalize(String word) {
    return word.strip().toLowerCase(java.util.Locale.ROOT);
  }

  String farewell() {
    return farewell;
  }

  String systemPrompt() {
    return systemPrompt;
  }

  AgentType type() {
    return type;
  }

  AgentId agentId() {
    return agentId;
  }

  int maxTokens() {
    return maxTokens;
  }

  DataSource dataSource() {
    return dataSource;
  }

  /**
   * This REPL's own database: H2, in memory, with every module's schema already in it.
   *
   * <p>Built HERE rather than inside {@link Repl} so a caller who needs to reach it — to open a
   * notebook or a plan over the same store — can build one and hand it in, instead of asking this
   * object what it happens to be holding.
   */
  private static DataSource ownDatabase() {
    DataSource database =
        new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .build();
    Schemas.initialize(database);
    return database;
  }

  /** Empty when the caller said nothing, so the harness keeps its own default. */
  Optional<Memory> memory() {
    return Optional.ofNullable(memory);
  }

  List<Consumer<HarnessConfig<String>>> tools() {
    return List.copyOf(tools);
  }
}
