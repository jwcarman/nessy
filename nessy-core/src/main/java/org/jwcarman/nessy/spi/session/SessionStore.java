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

import java.util.Optional;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.SessionId;
import org.jwcarman.nessy.api.SessionState;

/**
 * Where a session lives between steps.
 *
 * <p>Because {@code SessionState} is a plain serializable record, durable resume is an
 * implementation of this interface rather than a change to the engine.
 */
public interface SessionStore {

  /**
   * The zero-configuration default: sessions live in this JVM and die with it.
   *
   * <p>Correct for a CLI, a test, or any front-end that owns the whole session. Anything that needs
   * a session to survive a restart wants a durable store.
   *
   * <p>Last write wins: there is no compare-and-set, so two threads running the same session will
   * clobber each other, and the consumed-token set grows without eviction for the life of the
   * process. That suits a process that owns its sessions, not a long-lived multi-tenant server.
   */
  static SessionStore inMemory() {
    return new InMemorySessionStore();
  }

  Optional<SessionState> load(SessionId id);

  void save(SessionState state);

  /**
   * Claims a park token, returning {@code false} if it was already claimed.
   *
   * <p>Resume delivery is at-least-once in every real transport, so without this a retried webhook
   * or a double-clicked Slack button replays a tool call.
   */
  boolean consumeToken(ParkToken token);
}
