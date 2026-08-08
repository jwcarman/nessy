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
package org.jwcarman.nessy.session;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.nessy.core.ParkToken;
import org.jwcarman.nessy.core.SessionId;
import org.jwcarman.nessy.core.SessionState;

/**
 * The zero-configuration default: sessions live in this JVM and die with it.
 *
 * <p>Correct for a CLI, a test, or any front-end that owns the whole session. Anything that needs a
 * session to survive a restart wants a durable store.
 */
public final class InMemorySessionStore implements SessionStore {

  private final Map<SessionId, SessionState> sessions = new ConcurrentHashMap<>();
  private final Set<ParkToken> consumed = ConcurrentHashMap.newKeySet();

  @Override
  public Optional<SessionState> load(SessionId id) {
    return Optional.ofNullable(sessions.get(id));
  }

  /**
   * Last write wins: there is no compare-and-set, so two threads running the same session will
   * clobber each other, and the consumed-token set grows without eviction for the life of the
   * process. That suits a process that owns its sessions, not a long-lived multi-tenant server.
   */
  @Override
  public void save(SessionState state) {
    sessions.put(state.id(), state);
  }

  @Override
  public boolean consumeToken(ParkToken token) {
    return consumed.add(token);
  }
}
