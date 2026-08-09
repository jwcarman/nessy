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
package org.jwcarman.nessy.spi;

import java.util.List;
import org.jwcarman.nessy.api.Message;
import org.jwcarman.nessy.api.SessionState;

/**
 * Projects session state into the messages one model call sees.
 *
 * <p>State stays the full source of truth; the projection decides what THIS request carries —
 * windows, redaction, elision, budgeting. Pure and total: no I/O, no mutation, same output for the
 * same state. Consulted by engines at request assembly for conversational calls only; a compaction
 * call carries its own messages and is never projected.
 */
public interface ContextBuilder {

  List<Message> project(SessionState state);

  /** The default: the model sees everything. */
  static ContextBuilder identity() {
    return SessionState::messages;
  }

  /** Staged for Task 5: elides older tool results, keeping the most recent messages verbatim. */
  static ContextBuilder elidingToolResults(int keepRecentMessages) {
    throw new UnsupportedOperationException("Task 5");
  }
}
