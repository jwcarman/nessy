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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.conversation.ConversationId;

/**
 * The in-process {@link Parks} registry: every wait ever registered, kept in a map for the life of
 * the process.
 *
 * <p>{@link #park} is idempotent on token by construction — {@link Map#putIfAbsent} leaves an
 * already-registered entry untouched, the shape an at-least-once loop retry's re-registration
 * needs. Entries are never removed by this registry once written: replay protection and "is this
 * call still outstanding" are the fold's own questions, not this registry's (design §5).
 *
 * <p>Every conversation it has ever parked for grows without eviction for the life of the process —
 * there is no forgetting, no cap, no compaction. That suits a process that owns its sessions, not a
 * long-lived multi-tenant server.
 */
final class InMemoryParks implements Parks {

  private final Map<ParkToken, Park> parks = new ConcurrentHashMap<>();

  @Override
  public void park(Park park) {
    parks.putIfAbsent(park.token(), park);
  }

  @Override
  public Optional<Park> find(ParkToken token) {
    return Optional.ofNullable(parks.get(token));
  }

  @Override
  public List<Park> forConversation(ConversationId id) {
    List<Park> matches = new ArrayList<>();
    for (Park park : parks.values()) {
      if (park.conversationId().equals(id)) {
        matches.add(park);
      }
    }
    return List.copyOf(matches);
  }
}
