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

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.conversation.AgendaItem;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.conversation.ParkedCall;

/**
 * Where a session lives between steps.
 *
 * <p>Because {@code ConversationState} is a plain serializable record, durable resume is an
 * implementation of this interface rather than a change to the loop. The control block and the
 * agenda are two different durability shapes sharing one store: {@code state} is fenced —
 * compare-and-swap, one writer wins — while the agenda is an append-only log any number of tells
 * and resolutions can write to concurrently, drained only by the winning save.
 */
public interface ConversationStore {

  /** The zero-configuration default: sessions live in this JVM and die with it. */
  static ConversationStore inMemory() {
    return new InMemoryConversationStore();
  }

  /** A conversation's control block together with whatever the agenda still holds for it. */
  record Loaded(ConversationState state, List<AgendaItem> agenda) {

    public Loaded {
      Objects.requireNonNull(state, "state must not be null");
      Objects.requireNonNull(agenda, "agenda must not be null");
      agenda = List.copyOf(agenda);
    }
  }

  Optional<Loaded> load(ConversationId id);

  /**
   * The fenced save: persists {@code state} iff the stored version equals {@code state.version()},
   * atomically bumping to {@code version()+1}, deleting the drained agenda entries, and syncing the
   * park index from {@code state.parkedCalls()} — one atomic act. Returns the state with the bumped
   * version (the caller's new read-base). Readers observe this act atomically too: {@link #load},
   * {@link #findPark}, and {@link #findParkConversation} never see the state, the agenda, or a park
   * from one generation mixed with either of the others from a different one — a concurrent reader
   * sees either every effect of this save or none of them.
   *
   * @throws StaleStateException when the stored version differs — the caller read a base that has
   *     since moved; reload and re-drive.
   */
  ConversationState save(ConversationState state, Collection<String> drainedAgendaIds);

  /** Unconditional, atomic, never contended with saves. */
  void appendAgenda(ConversationId id, AgendaItem entry);

  /** The park index: token → the conversation and call it belongs to. */
  Optional<ParkedCall> findPark(ParkToken token);

  /** The conversation a token parks under, for driving after a resolution. */
  Optional<ConversationId> findParkConversation(ParkToken token);

  /**
   * Claims a park token, returning {@code false} if it was already claimed.
   *
   * <p>Resume delivery is at-least-once in every real transport, so without this a retried webhook
   * or a double-clicked Slack button replays a tool call.
   */
  boolean consumeToken(ParkToken token);
}
