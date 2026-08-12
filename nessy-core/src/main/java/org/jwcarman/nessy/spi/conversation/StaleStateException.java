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
package org.jwcarman.nessy.spi.conversation;

import org.jwcarman.nessy.api.conversation.ConversationId;

/**
 * Thrown by {@link ConversationStore#save} when the caller's {@code state.version()} no longer
 * matches what the store holds — the caller read a base that has since moved, and must reload and
 * re-drive rather than clobber whoever moved it.
 */
public final class StaleStateException extends RuntimeException {

  private final ConversationId id;
  private final long expected;
  private final long found;

  public StaleStateException(ConversationId id, long expected, long found) {
    super(
        "stale save for "
            + id
            + ": expected version "
            + expected
            + " but the store holds "
            + found);
    this.id = id;
    this.expected = expected;
    this.found = found;
  }

  public ConversationId id() {
    return id;
  }

  public long expected() {
    return expected;
  }

  public long found() {
    return found;
  }
}
