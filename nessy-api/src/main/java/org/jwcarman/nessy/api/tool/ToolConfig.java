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
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CompletionPolicy;

/**
 * What {@link Tool#of(Class, ToolCustomizer)} hands a customizer: a CONFIG, not a builder (design
 * of record 2026-08-16 §1) — fluent setters, no public {@code build()} — describing a {@link Tool}
 * in three lines:
 *
 * <pre>{@code
 * var tool = Tool.of(CreateAccount.class, t -> t
 *     .description("Create a new bank account.")
 *     .executes(cmd -> bankSvc.createAccount(cmd.name(), cmd.type())));
 * }</pre>
 *
 * <p>Exactly one handler door must be filled in: {@link #executes(Function)}, {@link
 * #executes(BiFunction)}, or {@link #defers(BiConsumer)}. {@link #description(String)} is mandatory
 * — it is written for the model, not for the developer.
 */
public final class ToolConfig<T> {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Class<T> inputType;
  private String name;
  private String description;
  private Function<T, ?> plainHandler;
  private BiFunction<T, ToolContext, ?> contextHandler;
  private BiConsumer<T, ToolContext> deferStarter;
  private CompletionPolicy explicitCompletion;
  private Duration timeout;

  ToolConfig(Class<T> inputType) {
    this.inputType = inputType;
    this.name = kebabCase(inputType.getSimpleName());
  }

  /**
   * Overrides the default kebab-case name derived from the input record's simple name. A blank name
   * is rejected at {@link #finish()} time, mirroring {@link #description(String)}'s own blank
   * guard.
   */
  public ToolConfig<T> name(String name) {
    this.name = Objects.requireNonNull(name, "name must not be null");
    return this;
  }

  /** The sentence explaining when to use this tool, written for the model. Mandatory. */
  public ToolConfig<T> description(String description) {
    this.description = Objects.requireNonNull(description, "description must not be null");
    return this;
  }

  /** The handler door for a tool whose answer needs only its input. */
  public ToolConfig<T> executes(Function<T, ?> handler) {
    this.plainHandler = Objects.requireNonNull(handler, "handler must not be null");
    return this;
  }

  /** The handler door for a tool whose answer also needs the invocation's {@link ToolContext}. */
  public ToolConfig<T> executes(BiFunction<T, ToolContext, ?> handler) {
    this.contextHandler = Objects.requireNonNull(handler, "handler must not be null");
    return this;
  }

  /**
   * The handler door for a tool whose answer arrives through a durable computation. {@code starter}
   * kicks off the work and returns; the built tool's {@code execute} always answers {@link
   * Awaited#deferred()}. Sets {@link #requires(CompletionPolicy)} to {@link
   * CompletionPolicy#DURABLE} unless an explicit call to {@link #requires(CompletionPolicy)}
   * overrides it, in either order.
   */
  public ToolConfig<T> defers(BiConsumer<T, ToolContext> starter) {
    this.deferStarter = Objects.requireNonNull(starter, "starter must not be null");
    return this;
  }

  /** Overrides the completion policy the built tool declares. Always wins over {@link #defers}. */
  public ToolConfig<T> requires(CompletionPolicy policy) {
    this.explicitCompletion = Objects.requireNonNull(policy, "policy must not be null");
    return this;
  }

  /**
   * How long a durable computation this tool starts may stay pending before the reaper treats it as
   * overdue (durable-deliveries spec §6). Unset means no deadline — the computation waits
   * indefinitely.
   */
  public ToolConfig<T> timeout(Duration timeout) {
    this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
    return this;
  }

  /**
   * Turns this config into the {@link Tool} it describes — the factory's own step, never a public
   * {@code build()} (design of record 2026-08-16 §1). Reached only from {@link Tool#of(Class,
   * ToolCustomizer)}, once {@code customize} has returned.
   */
  Tool<T> finish() {
    if (name.isBlank()) {
      throw new IllegalStateException("name must not be blank");
    }
    if (description == null || description.isBlank()) {
      throw new IllegalStateException("description must be provided — it is written for the model");
    }
    int handlerCount = countHandlers();
    if (handlerCount != 1) {
      throw new IllegalStateException(
          "tool '%s' must declare exactly one handler door (executes/executes/defers), found %d"
              .formatted(name, handlerCount));
    }
    return new Configured<>(
        name,
        description,
        inputType,
        buildExecutor(),
        completionPolicy(),
        Optional.ofNullable(timeout));
  }

  private CompletionPolicy completionPolicy() {
    if (explicitCompletion != null) {
      return explicitCompletion;
    }
    return deferStarter != null ? CompletionPolicy.DURABLE : CompletionPolicy.IMMEDIATE;
  }

  private int countHandlers() {
    int count = 0;
    if (plainHandler != null) {
      count++;
    }
    if (contextHandler != null) {
      count++;
    }
    if (deferStarter != null) {
      count++;
    }
    return count;
  }

  private BiFunction<T, ToolContext, Awaited<ToolResult>> buildExecutor() {
    if (deferStarter != null) {
      return (input, context) -> {
        deferStarter.accept(input, context);
        return Awaited.deferred();
      };
    }
    if (contextHandler != null) {
      return (input, context) -> Awaited.ready(render(contextHandler.apply(input, context)));
    }
    return (input, context) -> Awaited.ready(render(plainHandler.apply(input)));
  }

  /**
   * Return rendering (design of record 2026-08-20 §5): a {@link String} passes through as {@link
   * ToolResult#ok(String)}; a {@link ToolResult} passes as-is; {@code null} renders as {@code
   * ToolResult.ok("done")}; anything else JSON-serializes through the one shared {@link #MAPPER}.
   */
  private static ToolResult render(Object value) {
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

  /** {@code CreateAccount} → {@code create-account}; digits stay attached to their word segment. */
  private static String kebabCase(String simpleName) {
    return simpleName.replaceAll("(?<=.)(?=\\p{Upper})", "-").toLowerCase(Locale.ROOT);
  }

  /**
   * The {@link Tool} a {@link ToolConfig} finishes into (private: {@link ToolConfig#finish()} is
   * the only place one of these is ever built, per the dsl-coherence law).
   */
  private static final class Configured<T> implements Tool<T> {

    private final String name;
    private final String description;
    private final Class<T> inputType;
    private final BiFunction<T, ToolContext, Awaited<ToolResult>> executor;
    private final CompletionPolicy requiredCompletion;
    private final Optional<Duration> timeout;

    Configured(
        String name,
        String description,
        Class<T> inputType,
        BiFunction<T, ToolContext, Awaited<ToolResult>> executor,
        CompletionPolicy requiredCompletion,
        Optional<Duration> timeout) {
      this.name = name;
      this.description = description;
      this.inputType = inputType;
      this.executor = executor;
      this.requiredCompletion = requiredCompletion;
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
    public Optional<Duration> timeout() {
      return timeout;
    }
  }
}
