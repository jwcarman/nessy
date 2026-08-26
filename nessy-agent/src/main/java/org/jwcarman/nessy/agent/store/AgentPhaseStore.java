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
import org.jwcarman.nessy.agent.AgentPhase;
import org.jwcarman.nessy.spi.substrate.Versioned;

/**
 * Owns a scope's control state. Pre-scoped: no id parameter anywhere (spec §3.5). Every
 * implementation enforces the version CAS — it is the system's only lock (spec §3.2), the in-memory
 * store included.
 */
public interface AgentPhaseStore {

  /** Never null; a scope that has never saved loads {@code Idle} at version {@code 0}. */
  Versioned<AgentPhase> load();

  /**
   * Persists {@code phase.value()} at {@code phase.version() + 1} if and only if the stored version
   * still equals {@code phase.version()}; otherwise throws {@link StaleStateException}. The caller
   * passes the version it loaded — it never computes the next version (spec §3.4).
   */
  void save(Versioned<AgentPhase> phase);

  /**
   * The instant of the most recent successful {@link #save}; a scope that has never saved reports
   * the instant its state first came into existence in the store. Staleness — a dead effect versus
   * a slow one — is read from here (§6.1).
   */
  Instant lastSaved();
}
