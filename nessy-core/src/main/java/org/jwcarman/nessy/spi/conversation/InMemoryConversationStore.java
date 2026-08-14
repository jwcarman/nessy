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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.conversation.InboxEntry;
import org.jwcarman.nessy.api.conversation.ParkedCall;

/**
 * The default {@link ConversationStore#inMemory()} implementation.
 *
 * <p>Every list ever handed to a caller or held in a map value is an immutable snapshot: an append
 * always builds and stores a fresh {@link List#copyOf}, never mutates a list already published —
 * the same {@code TranscriptMemory} discipline, for the same reason. {@link ConcurrentHashMap}'s
 * per-key happens-before on the reference swap is all the safety a read of an immutable value ever
 * needs, with no risk of observing a torn or concurrently-modified list.
 *
 * <p>The state, the inbox, and the park index are three separate maps, so no single-map operation
 * (a {@link ConcurrentHashMap#compute}, say) can make a save's compare-and-bump, its inbox drain,
 * and its park-index sync move together — folding the inbox/park updates into a {@code compute}
 * remapping function would run side effects there, which is unsafe if the map ever retries it.
 * Instead each conversation gets a dedicated monitor ({@code locks}, one object per id), and {@link
 * #save} holds it for the fenced CAS, the inbox drain, and the park-index sync together — simple,
 * correct, and sufficient for a single JVM with no cross-process contender.
 *
 * <p>Readers join the same monitor. {@link #load} holds the conversation's lock for its whole read
 * of {@code sessions} and {@code inboxes}, so it never observes a state from one save alongside an
 * inbox that a later (or earlier) save drained — the two always come from the same generation.
 * {@link #findPark} and {@link #findParkConversation} cannot pick a lock before they know which
 * conversation a token belongs to, so each takes an unsynchronized first read of {@code parks} to
 * learn the id, then re-reads {@code parks} under that conversation's lock for the authoritative
 * answer; a token names exactly one conversation for its whole life, so the id from the first read
 * is always the right lock to acquire before the confirming read. That first read is safe precisely
 * because {@link #save} moves the park index by delta ({@link #syncParks}), not by clearing and
 * rebuilding a conversation's entries on every save: a token that is still parked, unchanged, is
 * never removed from {@code parks} and so never transiently absent for a racing reader to miss.
 * Only a token this save genuinely stops parking is ever removed — so an unsynchronized read that
 * finds it absent is not a false negative racing the rebuild, it is either a real removal or a
 * token this store never held, and {@code Optional.empty()} is the correct answer to both.
 *
 * <p>Every conversation it has ever seen — its state, its inbox, its parks, its consumed tokens —
 * grows without eviction for the life of the process; there is no forgetting, no cap, no
 * compaction. That suits a process that owns its sessions, not a long-lived multi-tenant server.
 */
final class InMemoryConversationStore implements ConversationStore {

  private final Map<ConversationId, ConversationState> sessions = new ConcurrentHashMap<>();
  private final Map<ConversationId, List<InboxEntry>> inboxes = new ConcurrentHashMap<>();
  private final Map<ParkToken, ParkedCallAt> parks = new ConcurrentHashMap<>();
  private final Map<ConversationId, Object> locks = new ConcurrentHashMap<>();
  private final Set<ParkToken> consumed = ConcurrentHashMap.newKeySet();

  private record ParkedCallAt(ConversationId id, ParkedCall call) {}

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

      syncParks(id, bumped);

      return bumped;
    }
  }

  /**
   * Moves the park index to {@code bumped.parkedCalls()} by delta, not by churn: a token this
   * conversation already owns and still owns, unchanged, is never removed and never re-put. Only a
   * token this conversation owned but no longer does is removed; only a token that is new or whose
   * {@link ParkedCall} actually changed is put. Called with this conversation's monitor already
   * held.
   */
  private void syncParks(ConversationId id, ConversationState bumped) {
    Map<ParkToken, ParkedCall> desired = new LinkedHashMap<>();
    for (ParkedCall parked : bumped.parkedCalls()) {
      desired.put(parked.token(), parked);
    }
    parks
        .entrySet()
        .removeIf(
            entry -> entry.getValue().id().equals(id) && !desired.containsKey(entry.getKey()));
    desired.forEach(
        (token, parked) -> {
          ParkedCallAt current = parks.get(token);
          if (current == null || !current.call().equals(parked)) {
            parks.put(token, new ParkedCallAt(id, parked));
          }
        });
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

  @Override
  public Optional<ParkedCall> findPark(ParkToken token) {
    return resolveParkedCallAt(token).map(ParkedCallAt::call);
  }

  @Override
  public Optional<ConversationId> findParkConversation(ParkToken token) {
    return resolveParkedCallAt(token).map(ParkedCallAt::id);
  }

  /**
   * The authoritative park lookup: an unsynchronized first read learns which conversation owns
   * {@code token} (a token names exactly one conversation for its whole life), then a second read,
   * taken under that conversation's own save-lock, confirms the entry is still there. The first
   * read cannot itself be torn by a save's {@link #syncParks}, which only ever removes a token that
   * is genuinely no longer parked — so a first read that finds nothing is never a save's rebuild
   * window, only a real absence, and the {@code null} branch below can return directly without
   * taking a lock it has no id to pick.
   */
  private Optional<ParkedCallAt> resolveParkedCallAt(ParkToken token) {
    ParkedCallAt firstRead = parks.get(token);
    if (firstRead == null) {
      return Optional.empty();
    }
    Object lock = locks.computeIfAbsent(firstRead.id(), key -> new Object());
    synchronized (lock) {
      return Optional.ofNullable(parks.get(token));
    }
  }

  @Override
  public boolean consumeToken(ParkToken token) {
    return consumed.add(token);
  }
}
