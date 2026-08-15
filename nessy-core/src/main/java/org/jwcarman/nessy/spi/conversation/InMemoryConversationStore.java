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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.conversation.InboxEntry;

/**
 * The default {@link ConversationStore#inMemory()} implementation.
 *
 * <p>Every list ever handed to a caller or held in a map value is an immutable snapshot: an append
 * always builds and stores a fresh {@link List#copyOf}, never mutates a list already published —
 * the same in-memory transcript discipline, for the same reason. {@link ConcurrentHashMap}'s
 * per-key happens-before on the reference swap is all the safety a read of an immutable value ever
 * needs, with no risk of observing a torn or concurrently-modified list.
 *
 * <p>The state and the inbox are two separate maps, so no single-map operation (a {@link
 * ConcurrentHashMap#compute}, say) can make a save's compare-and-bump and its inbox drain move
 * together — folding the inbox update into a {@code compute} remapping function would run side
 * effects there, which is unsafe if the map ever retries it. Instead each conversation gets a
 * dedicated monitor ({@code locks}, one object per id), and {@link #save} holds it for the fenced
 * CAS and the inbox drain together — simple, correct, and sufficient for a single JVM with no
 * cross-process contender.
 *
 * <p>Readers join the same monitor. {@link #load} holds the conversation's lock for its whole read
 * of {@code sessions} and {@code inboxes}, so it never observes a state from one save alongside an
 * inbox that a later (or earlier) save drained — the two always come from the same generation.
 *
 * <p>Every conversation it has ever seen — its state, its inbox — grows without eviction for the
 * life of the process; there is no forgetting, no cap, no compaction. That suits a process that
 * owns its sessions, not a long-lived multi-tenant server.
 */
final class InMemoryConversationStore implements ConversationStore {

  private final Map<ConversationId, ConversationState> sessions = new ConcurrentHashMap<>();
  private final Map<ConversationId, List<InboxEntry>> inboxes = new ConcurrentHashMap<>();
  private final Map<ConversationId, Object> locks = new ConcurrentHashMap<>();

  @Override
  public Optional<Loaded> load(ConversationId id) {
    Object lock = locks.computeIfAbsent(id, key -> new Object());
    synchronized (lock) {
      ConversationState state = sessions.get(id);
      List<InboxEntry> inbox = inboxes.getOrDefault(id, List.of());
      if (state == null && inbox.isEmpty()) {
        return Optional.empty();
      }
      // The unified drive appends before it ever saves: a brand-new conversation's first entry
      // lands on the inbox with no state row behind it yet. A conversation this store has never
      // saved but has already taken mail for is not "unknown" — it is a fresh conversation
      // (version 0) whose inbox load must not discard what append already durably holds.
      ConversationState effective = state == null ? ConversationState.newConversation(id) : state;
      return Optional.of(new Loaded(effective, inbox));
    }
  }

  @Override
  public ConversationState save(ConversationState state, Collection<String> drainedInboxIds) {
    ConversationId id = state.id();
    Object lock = locks.computeIfAbsent(id, key -> new Object());
    synchronized (lock) {
      ConversationState existing = sessions.get(id);
      long storedVersion = existing == null ? 0L : existing.version();
      if (storedVersion != state.version()) {
        throw new StaleStateException(id, state.version(), storedVersion);
      }
      ConversationState bumped = state.withVersion(state.version() + 1);
      sessions.put(id, bumped);

      Set<String> drained = Set.copyOf(drainedInboxIds);
      inboxes.computeIfPresent(
          id,
          (key, entries) ->
              entries.stream().filter(entry -> !drained.contains(entry.id())).toList());

      return bumped;
    }
  }

  @Override
  public void append(ConversationId id, InboxEntry entry) {
    inboxes.compute(
        id,
        (key, existing) -> {
          List<InboxEntry> appended = new ArrayList<>(existing == null ? List.of() : existing);
          appended.add(entry);
          return List.copyOf(appended);
        });
  }
}
