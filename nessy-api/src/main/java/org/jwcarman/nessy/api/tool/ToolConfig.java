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

import java.util.Locale;
import java.util.Objects;
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

  private final Class<T> inputType;
  private String name;
  private String description;
  private Function<T, ?> plainHandler;
  private BiFunction<T, ToolContext, ?> contextHandler;
  private BiConsumer<T, ToolContext> deferStarter;
  private CompletionPolicy explicitCompletion;

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
    return new ConfiguredTool<>(name, description, inputType, buildExecutor(), completionPolicy());
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
      return (input, context) ->
          Awaited.ready(ConfiguredTool.render(contextHandler.apply(input, context)));
    }
    return (input, context) -> Awaited.ready(ConfiguredTool.render(plainHandler.apply(input)));
  }

  /** {@code CreateAccount} → {@code create-account}; digits stay attached to their word segment. */
  private static String kebabCase(String simpleName) {
    return simpleName.replaceAll("(?<=.)(?=\\p{Upper})", "-").toLowerCase(Locale.ROOT);
  }
}
