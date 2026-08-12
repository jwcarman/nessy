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
package org.jwcarman.nessy.api.turn;

/**
 * Whoever is sitting there, watching this turn happen — a REPL painting deltas, a UI narrating
 * homework. Bound per entry: the observer handed to {@code tell} or {@code resume} sees the segment
 * that call starts, and nothing after a park. The consumer may not exist at all — an autonomous
 * agent runs every turn against {@link #noop()} and loses nothing.
 *
 * <p>Implement directly (a lambda) when one concern covers every event; extend {@link
 * TurnObserverAdapter} to override per-variant hooks and ignore the rest.
 */
public interface TurnObserver {

  void on(TurnEvent event);

  /** The absent audience: accepts everything, tells no one. */
  static TurnObserver noop() {
    return event -> {};
  }
}
