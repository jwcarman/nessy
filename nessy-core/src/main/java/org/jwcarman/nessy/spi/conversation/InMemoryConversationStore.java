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
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.conversation.LaneEntry;
import org.jwcarman.nessy.api.conversation.ParkedCall;

/**
 * The default {@link ConversationStore#inMemory()} implementation.
 *
 * <p>Every list ever handed to a caller or held in a map value is an immutable snapshot: an append
 * always builds and stores a fresh {@link List#copyOf}, never mutates a list already published —
 * the same {@code ListMemory} discipline, for the same reason. {@link ConcurrentHashMap}'s per-key
 * happens-before on the reference swap is all the safety a read of an immutable value ever needs,
 * with no risk of observing a torn or concurrently-modified list.
 *
 * <p>The state, the lane, and the park index are three separate maps, so no single-map operation (a
 * {@link ConcurrentHashMap#compute}, say) can make a save's compare-and-bump, its lane drain, and
 * its park-index sync move together — folding the lane/park updates into a {@code compute}
 * remapping function would run side effects there, which is unsafe if the map ever retries it.
 * Instead each conversation gets a dedicated monitor ({@code locks}, one object per id), and {@link
 * #save} holds it for the fenced CAS, the lane drain, and the park-index sync together — simple,
 * correct, and sufficient for a single JVM with no cross-process contender.
 *
 * <p>Readers join the same monitor. {@link #load} holds the conversation's lock for its whole read
 * of {@code sessions} and {@code lanes}, so it never observes a state from one save alongside a
 * lane that a later (or earlier) save drained — the two always come from the same generation.
 * {@link #findPark} and {@link #findParkConversation} cannot pick a lock before they know which
 * conversation a token belongs to, so each takes an unsynchronized first read of {@code parks} to
 * learn the id, then re-reads {@code parks} under that conversation's lock for the authoritative
 * answer; a token names exactly one conversation for its whole life, so the id from the first read
 * is always the right lock to acquire before the confirming read.
 *
 * <p>Every conversation it has ever seen — its state, its lane, its parks, its consumed tokens —
 * grows without eviction for the life of the process; there is no forgetting, no cap, no
 * compaction. That suits a process that owns its sessions, not a long-lived multi-tenant server.
 */
final class InMemoryConversationStore implements ConversationStore {

  private final Map<ConversationId, ConversationState> sessions = new ConcurrentHashMap<>();
  private final Map<ConversationId, List<LaneEntry>> lanes = new ConcurrentHashMap<>();
  private final Map<ParkToken, ParkedCallAt> parks = new ConcurrentHashMap<>();
  private final Map<ConversationId, Object> locks = new ConcurrentHashMap<>();
  private final Set<ParkToken> consumed = ConcurrentHashMap.newKeySet();

  private record ParkedCallAt(ConversationId id, ParkedCall call) {}

  @Override
  public Optional<Loaded> load(ConversationId id) {
    Object lock = locks.computeIfAbsent(id, key -> new Object());
    synchronized (lock) {
      ConversationState state = sessions.get(id);
      if (state == null) {
        return Optional.empty();
      }
      List<LaneEntry> lane = lanes.getOrDefault(id, List.of());
      return Optional.of(new Loaded(state, lane));
    }
  }

  @Override
  public ConversationState save(ConversationState state, Collection<String> drainedLaneIds) {
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

      Set<String> drained = Set.copyOf(drainedLaneIds);
      lanes.computeIfPresent(
          id,
          (key, entries) ->
              entries.stream().filter(entry -> !drained.contains(entry.id())).toList());

      parks.entrySet().removeIf(entry -> entry.getValue().id().equals(id));
      for (ParkedCall parked : bumped.parkedCalls()) {
        parks.put(parked.token(), new ParkedCallAt(id, parked));
      }

      return bumped;
    }
  }

  @Override
  public void appendLane(ConversationId id, LaneEntry entry) {
    lanes.compute(
        id,
        (key, existing) -> {
          List<LaneEntry> appended = new ArrayList<>(existing == null ? List.of() : existing);
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
   * taken under that conversation's own save-lock, confirms the entry is still there — the same
   * monitor {@link #save} holds while it removes and re-adds this conversation's park entries, so
   * this read can never land between a save's {@code removeIf} and its re-{@code put}.
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
