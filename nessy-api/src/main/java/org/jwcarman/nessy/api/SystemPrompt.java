package org.jwcarman.nessy.api;

import java.util.Objects;

/**
 * What an agent type is told about itself, before anything it is told about the world.
 *
 * <p>Blank is rejected rather than treated as absent. An application that wants no system prompt
 * says so by not setting one; a blank string is somebody's template that came out empty, and
 * sending it is worse than sending nothing -- some providers reject an empty system message, and
 * the ones that do not are being told the agent has no instructions at all.
 */
public record SystemPrompt(String value) {

  public SystemPrompt {
    Objects.requireNonNull(value, "value must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("value must not be blank");
    }
  }
}
