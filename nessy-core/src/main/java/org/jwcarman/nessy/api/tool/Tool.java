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
import org.jwcarman.nessy.internal.Schemas;

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
   * What this call looks like to a human, in the approval prompt.
   *
   * <p>The default is the record's {@code toString}, which is usable but reads like {@code
   * Greet[name=Ada]}. Override it: a prompt you skim is a prompt you approve without reading.
   */
  default String describe(T input) {
    return String.valueOf(input);
  }

  /**
   * Runs the tool. Returns {@link Awaited.Parked} only if it genuinely must wait.
   *
   * <p>The parking recipe, in three steps: mint a token via {@link
   * org.jwcarman.nessy.api.ParkToken#generate()}; return {@link
   * Awaited#parked(org.jwcarman.nessy.api.ParkToken)} with it; then get that token to the outside
   * world — the tool's own job, not the harness's, done via {@link ToolContext#progress}, the
   * tool's own transport (a webhook payload, a queued message), or a caller reading it back off
   * {@link org.jwcarman.nessy.Agent#snapshot}.
   *
   * @see org.jwcarman.nessy.Agent#resume
   */
  Awaited<ToolResult> execute(T input, ToolContext context);

  /** The wire description derived from {@link #inputType()}. */
  default ToolSpec spec() {
    return new ToolSpec(name(), description(), Schemas.of(inputType()));
  }
}
