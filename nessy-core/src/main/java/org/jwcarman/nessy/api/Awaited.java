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
package org.jwcarman.nessy.api;

/**
 * The outcome of something that might have to wait.
 *
 * <p>Two arms, no third (durable spec, two-armed ruling): {@link Ready} is the answer in hand;
 * {@link Deferred} says the answer arrives through a durable computation. Deferred carries no
 * identity — the wiring derives the slot's deterministic id from the work's coordinates
 * (submit-once discipline), because a tool can neither reach the backend nor know the scope. A
 * future {@code ToolContext} may grow slot creation for tools that own their references.
 *
 * @param <T> what the wait produces
 */
public sealed interface Awaited<T> {

  /** The wait finished in-process: {@code value} is the answer, in hand right now. */
  record Ready<T>(T value) implements Awaited<T> {}

  /** The wait outlives this process: the answer arrives through the durable computation. */
  record Deferred<T>() implements Awaited<T> {}

  /** {@link Ready#Ready(Object)} wrapping {@code value}. */
  static <T> Awaited<T> ready(T value) {
    return new Ready<>(value);
  }

  /** A {@link Deferred} marker. */
  static <T> Awaited<T> deferred() {
    return new Deferred<>();
  }
}
