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

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CompletionPolicy;

/**
 * Something the model can ask the harness to do.
 *
 * <p>A tool is a name, a sentence explaining when to use it, a record describing its arguments, and
 * a method that runs. The JSON Schema is derived from {@link #inputType()} rather than written by
 * hand.
 *
 * <p>Durable re-drives execute at-least-once: a tool that cannot be safely re-run makes itself
 * idempotent, or parks and lets its remote side deduplicate by token.
 *
 * @param <T> the record this tool's arguments arrive in
 */
public interface Tool<T> {

  /**
   * Composes a first-party tool from per-door customization: {@code customizer} fills in a live
   * {@link ToolConfig}, then this factory turns it into the finished {@link Tool}. No public {@code
   * build()} survives here; the factory is the only place a {@link ToolConfig} ever turns into a
   * {@link Tool} (design of record 2026-08-16 §1, amended 2026-08-20 §5) — mirroring {@link
   * org.jwcarman.nessy.api.turn.TurnObserver#observe(org.jwcarman.nessy.api.turn.TurnObserverCustomizer)}.
   *
   * @param inputType the record this tool's arguments arrive in; also drives its default name
   * @param customizer fills in the tool's name, description, and exactly one handler door
   */
  static <T> Tool<T> of(Class<T> inputType, ToolCustomizer<T> customizer) {
    Objects.requireNonNull(inputType, "inputType must not be null");
    Objects.requireNonNull(customizer, "customizer must not be null");
    ToolConfig<T> config = new ToolConfig<>(inputType);
    customizer.customize(config);
    return config.finish();
  }

  /** What the model calls it. Must be unique within a registry. */
  String name();

  /** When to use it, written for the model rather than for you. */
  String description();

  /** The record its arguments deserialize into. */
  Class<T> inputType();

  /**
   * Runs the tool. Returns {@link Awaited.Deferred} only if the answer genuinely arrives through a
   * durable computation — a callback, an approval, a job. The deferred marker carries no identity:
   * the wiring derives the slot's deterministic id from the work's coordinates and registers the
   * continuation (durable spec, submit-once discipline).
   */
  Awaited<ToolResult> execute(T input, ToolContext context);

  /** The wire description derived from {@link #inputType()}. */
  default ToolSpec spec() {
    return new ToolSpec(name(), description(), Schemas.of(inputType()));
  }

  /**
   * The strongest completion semantics this tool needs (durable spec §14). A tool that answers
   * through a durable slot — an approval, a callback, a job — declares {@code DURABLE} so a wiring
   * that cannot suspend never shows it to the model at all (spec §4.3: filtering precedes failing).
   * The loud in-band failure remains the backstop for tools that under-declare.
   */
  default CompletionPolicy requiredCompletion() {
    return CompletionPolicy.IMMEDIATE;
  }

  /**
   * The reaper's authority to redispatch this tool's overdue durable computation (durable-
   * deliveries spec §6). Default {@link RetrySemantics#NON_RETRYABLE}: an overdue computation is
   * failed, never guessed safe to redispatch.
   */
  default RetrySemantics retrySemantics() {
    return RetrySemantics.NON_RETRYABLE;
  }

  /**
   * How long a durable computation this tool starts may stay pending before the reaper treats it as
   * overdue (durable-deliveries spec §6). Empty means no deadline — the computation waits
   * indefinitely, exactly like an approval.
   */
  default Optional<Duration> timeout() {
    return Optional.empty();
  }
}
