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
 * <p>Virtual threads unmount a task from a carrier thread; this unmounts a session from a process.
 * An in-process implementation blocks and returns {@link Ready}; a durable one returns {@link
 * Parked} so the loop can persist the session and let another machine finish it.
 *
 * @param <T> what the wait produces
 */
public sealed interface Awaited<T> {

  record Ready<T>(T value) implements Awaited<T> {}

  record Parked<T>(ParkToken token) implements Awaited<T> {}

  static <T> Awaited<T> ready(T value) {
    return new Ready<>(value);
  }

  static <T> Awaited<T> parked(ParkToken token) {
    return new Parked<>(token);
  }
}
