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
package org.jwcarman.nessy.agent.store;

import java.time.Instant;
import org.jwcarman.nessy.agent.State;

/**
 * Owns a scope's control state. Pre-scoped: no id parameter anywhere (spec §3.5). Every
 * implementation enforces the version CAS — it is the system's only lock (spec §3.2), the in-memory
 * store included.
 */
public interface AgentStateStore {

  /** Never null; a scope that has never saved loads {@link State#initial()}. */
  State load();

  /**
   * Persists {@code state.phase()} at {@code state.version() + 1} if and only if the stored version
   * still equals {@code state.version()}; otherwise throws {@link StaleStateException}. The caller
   * passes the state it loaded — it never computes the next version (spec §3.4).
   */
  void save(State state);

  /**
   * The instant of the most recent successful {@link #save}; a fresh scope reports its construction
   * instant. Staleness — a dead effect versus a slow one — is read from here (§6.1).
   */
  Instant lastSaved();
}
