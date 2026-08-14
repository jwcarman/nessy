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
package org.jwcarman.nessy.spi.memory;

import java.util.List;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.message.Message;

/**
 * An append-only, versioned, per-conversation message log — the storage primitive some memories are
 * based on, and the read surface audit and chat history need.
 *
 * <p>The transcript stores raw tellings, open tails included: {@code Context} assembly, wire
 * legality, and the open-tail trim are {@code Memory}'s border law, not the transcript's. An
 * auditor sees what was actually told.
 */
public interface Transcript {

  /** One entry: the message and the monotonic per-conversation version it landed at. */
  record Entry(long version, Message message) {}

  /**
   * Appends unless {@code message} equals the current last entry — the at-least-once re-telling
   * rule (a transcript does not stutter). Returns the entry either way: the new one, or the
   * existing last it deduplicated against.
   */
  Entry append(ConversationId id, Message message);

  /** The whole log, in version order. */
  List<Entry> all(ConversationId id);

  /** The tail: every entry with version strictly greater than {@code afterVersion}. */
  List<Entry> tail(ConversationId id, long afterVersion);

  /**
   * The scroll-up page: up to {@code limit} entries with version strictly less than {@code
   * beforeVersion}, in version order (the caller prepends them above what it already shows).
   */
  List<Entry> page(ConversationId id, long beforeVersion, int limit);

  /** An unbounded, process-local transcript backed by a concurrent map. */
  static Transcript inMemory() {
    return new InMemoryTranscript();
  }
}
