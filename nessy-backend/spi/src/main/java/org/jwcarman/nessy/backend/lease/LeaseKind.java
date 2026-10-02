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
package org.jwcarman.nessy.backend.lease;

import org.jwcarman.nessy.api.Identifiers;

/**
 * The namespace a lease lives in: which activity is being excluded, so the chapter keeper's {@code
 * nessy.chapters} and any other activity on the same agent are two leases and not one.
 *
 * <p>Bounded at {@value #MAX_LENGTH} because it is a column, not free text -- whatever stores
 * leases keys a row on it alongside the agent.
 */
public record LeaseKind(String value) {

  private static final int MAX_LENGTH = 64;

  public LeaseKind {
    value = Identifiers.require(value, "lease kind", MAX_LENGTH);
  }
}
