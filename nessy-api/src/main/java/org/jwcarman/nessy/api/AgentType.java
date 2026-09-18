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
package org.jwcarman.nessy.api;

import java.util.Objects;

/**
 * What kind of agent this is.
 *
 * <p>Names an agent's rows and scopes what its dispatcher polls for. A value type rather than a
 * string so it cannot be swapped with an id, a name, or any of the other strings this system passes
 * around.
 *
 * <p>Blank is rejected because it is not a name and would still key rows: an agent type of {@code
 * ""} would quietly share a namespace with every other one somebody forgot to name.
 */
public record AgentType(String value) {

  public AgentType {
    Objects.requireNonNull(value, "value must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("value must not be blank");
    }
  }
}
