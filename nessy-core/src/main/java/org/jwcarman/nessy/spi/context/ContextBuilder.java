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
package org.jwcarman.nessy.spi.context;

import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.session.SessionState;

/**
 * Projects session state into the messages one model call sees.
 *
 * <p>State stays the full source of truth; the projection decides what THIS request carries —
 * windows, redaction, elision, budgeting. Pure and total: no I/O, no mutation, same output for the
 * same state. Consulted by engines at request assembly for conversational calls only; a compaction
 * call carries its own messages and is never projected.
 */
public interface ContextBuilder {

  Context project(SessionState state);

  /** The default: the model sees everything. */
  static ContextBuilder identity() {
    return state -> Context.of(state.messages());
  }

  /**
   * Elides the content of tool results older than the last {@code keepRecentMessages} messages,
   * keeping the recent window verbatim.
   *
   * @param keepRecentMessages how many of the most recent messages survive projection untouched;
   *     must be at least 0
   */
  static ContextBuilder elidingToolResults(int keepRecentMessages) {
    return new ElidingToolResults(keepRecentMessages);
  }
}
