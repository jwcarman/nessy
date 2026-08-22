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
package org.jwcarman.nessy.api.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import java.util.function.BiFunction;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CompletionPolicy;

/**
 * The {@link Tool} a {@link ToolConfig} finishes into (package-private: {@link ToolConfig#finish()}
 * is the only place one of these is ever built, per the dsl-coherence law).
 */
final class ConfiguredTool<T> implements Tool<T> {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String name;
  private final String description;
  private final Class<T> inputType;
  private final BiFunction<T, ToolContext, Awaited<ToolResult>> executor;
  private final CompletionPolicy requiredCompletion;
  private final RetrySemantics retrySemantics;
  private final Optional<Duration> timeout;

  ConfiguredTool(
      String name,
      String description,
      Class<T> inputType,
      BiFunction<T, ToolContext, Awaited<ToolResult>> executor,
      CompletionPolicy requiredCompletion,
      RetrySemantics retrySemantics,
      Optional<Duration> timeout) {
    this.name = name;
    this.description = description;
    this.inputType = inputType;
    this.executor = executor;
    this.requiredCompletion = requiredCompletion;
    this.retrySemantics = retrySemantics;
    this.timeout = timeout;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String description() {
    return description;
  }

  @Override
  public Class<T> inputType() {
    return inputType;
  }

  @Override
  public Awaited<ToolResult> execute(T input, ToolContext context) {
    return executor.apply(input, context);
  }

  @Override
  public CompletionPolicy requiredCompletion() {
    return requiredCompletion;
  }

  @Override
  public RetrySemantics retrySemantics() {
    return retrySemantics;
  }

  @Override
  public Optional<Duration> timeout() {
    return timeout;
  }

  /**
   * Return rendering (design of record 2026-08-20 §5): a {@link String} passes through as {@link
   * ToolResult#ok(String)}; a {@link ToolResult} passes as-is; {@code null} renders as {@code
   * ToolResult.ok("done")}; anything else JSON-serializes through the one shared {@link #MAPPER}.
   */
  static ToolResult render(Object value) {
    if (value instanceof ToolResult result) {
      return result;
    }
    if (value instanceof String text) {
      return ToolResult.ok(text);
    }
    if (value == null) {
      return ToolResult.ok("done");
    }
    try {
      return ToolResult.ok(MAPPER.writeValueAsString(value));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("failed to render tool result as JSON", e);
    }
  }
}
