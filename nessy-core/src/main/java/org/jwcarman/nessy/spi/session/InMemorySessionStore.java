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
package org.jwcarman.nessy.spi.session;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.SessionId;
import org.jwcarman.nessy.api.SessionState;

/** The default {@link SessionStore#inMemory()} implementation. */
final class InMemorySessionStore implements SessionStore {

  private final Map<SessionId, SessionState> sessions = new ConcurrentHashMap<>();
  private final Set<ParkToken> consumed = ConcurrentHashMap.newKeySet();

  @Override
  public Optional<SessionState> load(SessionId id) {
    return Optional.ofNullable(sessions.get(id));
  }

  @Override
  public void save(SessionState state) {
    sessions.put(state.id(), state);
  }

  @Override
  public boolean consumeToken(ParkToken token) {
    return consumed.add(token);
  }
}
