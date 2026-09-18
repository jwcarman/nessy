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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.HarnessConfig;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.SystemPromptSource;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolConfig;

/** What a terminal agent can be told about itself before the first prompt. */
public final class ReplConfig {

  private static final List<String> DEFAULT_EXIT_WORDS = List.of("exit", "quit", "/exit", "/quit");

  /**
   * The same agent every time this program runs, so the conversation is the same one. A random id
   * would start fresh on every launch, which is not what a person returning to a terminal expects.
   */
  private static final AgentId THE_TERMINAL =
      new AgentId(UUID.nameUUIDFromBytes("nessy-console".getBytes(StandardCharsets.UTF_8)));

  private final List<Consumer<HarnessConfig<String>>> tools = new ArrayList<>();
  private String banner = "";
  private String prompt = "> ";
  private Set<String> exitWords = new LinkedHashSet<>(DEFAULT_EXIT_WORDS);
  private String farewell = "";
  private SystemPromptSource systemPrompt =
      SystemPromptSource.constant(
          new SystemPrompt("You are a helpful assistant in someone's terminal."));
  private AgentType type = new AgentType("chat");
  private AgentId agentId = THE_TERMINAL;
  private int maxTokens = 4096;
  private DataSource dataSource;

  ReplConfig() {}

  public ReplConfig banner(String banner) {
    this.banner = Objects.requireNonNull(banner, "banner must not be null");
    return this;
  }

  public ReplConfig prompt(String prompt) {
    this.prompt = Objects.requireNonNull(prompt, "prompt must not be null");
    return this;
  }

  public ReplConfig farewell(String farewell) {
    this.farewell = Objects.requireNonNull(farewell, "farewell must not be null");
    return this;
  }

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

  public ReplConfig systemPrompt(String systemPrompt) {
    return systemPrompt(
        SystemPromptSource.constant(
            new SystemPrompt(
                Objects.requireNonNull(systemPrompt, "systemPrompt must not be null"))));
  }

  /** A prompt that is decided per call -- a template, say. */
  public ReplConfig systemPrompt(SystemPromptSource systemPrompt) {
    this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
    return this;
  }

  public ReplConfig agent(AgentType type) {
    this.type = Objects.requireNonNull(type, "type must not be null");
    return this;
  }

  public ReplConfig id(AgentId agentId) {
    this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
    return this;
  }

  public ReplConfig maxTokens(int maxTokens) {
    if (maxTokens < 1) {
      throw new IllegalArgumentException("maxTokens must be at least 1");
    }
    this.maxTokens = maxTokens;
    return this;
  }

  /**
   * Where the conversation is kept.
   *
   * <p><b>No default, and there used to be one.</b> It was an embedded H2, and the engine's schema
   * does not load on H2 at all -- its queries are PostgreSQL's. Left unset, the REPL uses whatever
   * {@code DataSource} the Boot context has (so {@code SPRING_DATASOURCE_URL} is the easy button),
   * and says so plainly if there is none.
   */
  public ReplConfig dataSource(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    return this;
  }

  public <I> ReplConfig tool(Tool<I> tool) {
    Objects.requireNonNull(tool, "tool must not be null");
    tools.add(harness -> harness.tool(tool));
    return this;
  }

  public <I> ReplConfig tool(Tool<I> tool, Consumer<ToolConfig<I>> customizer) {
    Objects.requireNonNull(tool, "tool must not be null");
    Objects.requireNonNull(customizer, "customizer must not be null");
    tools.add(harness -> harness.tool(tool, customizer));
    return this;
  }

  /**
   * Anything else the harness can be told -- ambient sources, summaries, the tail, retry policy.
   *
   * <p>The REPL names the few things a terminal always wants; this is the door to the rest, so a
   * notebook's index or a plan reaches the model without this class growing a method per feature.
   * Applied in order with the tools, after the REPL's own settings.
   */
  public ReplConfig harness(Consumer<HarnessConfig<String>> customizer) {
    tools.add(Objects.requireNonNull(customizer, "customizer must not be null"));
    return this;
  }

  String banner() {
    return banner;
  }

  String prompt() {
    return prompt;
  }

  boolean isExit(String line) {
    return exitWords.contains(normalize(line));
  }

  private static String normalize(String word) {
    return word.strip().toLowerCase(Locale.ROOT);
  }

  String farewell() {
    return farewell;
  }

  SystemPromptSource systemPrompt() {
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

  Optional<DataSource> dataSource() {
    return Optional.ofNullable(dataSource);
  }

  List<Consumer<HarnessConfig<String>>> tools() {
    return List.copyOf(tools);
  }
}
