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
