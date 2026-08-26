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
package org.jwcarman.nessy.agent;

import java.util.Objects;
import java.util.UUID;
import org.jwcarman.continuum.api.ComputationId;

/**
 * The one place Nessy's string-valued {@code org.jwcarman.nessy.api.tool.ComputationId} becomes
 * Continuum's UUID-valued {@link ComputationId} (continuum-adoption spec §3), so every caller
 * converts here rather than scattering {@code UUID.fromString} calls. Takes the unwrapped string
 * value, not Nessy's own {@code ComputationId} type, so this file never needs to import a type
 * whose simple name collides with Continuum's own.
 *
 * <p>Public for the reason the other wiring in this package is: {@code RegistryToolCallExecutor}
 * lives one package over and is where the two handoff doors now tidy up a failed deferral. Wiring,
 * never application vocabulary.
 */
public final class ContinuumIds {

  private ContinuumIds() {}

  /**
   * @param nessyComputationId a Nessy {@code ComputationId}'s own {@code value()} — itself a
   *     Continuum-minted UUID rendered as text
   * @return the same identity, as Continuum's own {@link ComputationId}
   */
  public static ComputationId continuumId(String nessyComputationId) {
    Objects.requireNonNull(nessyComputationId, "nessyComputationId must not be null");
    return new ComputationId(UUID.fromString(nessyComputationId));
  }
}
