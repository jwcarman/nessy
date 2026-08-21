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
}
