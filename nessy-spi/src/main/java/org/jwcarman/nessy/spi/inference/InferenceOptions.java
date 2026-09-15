package org.jwcarman.nessy.spi.inference;

import java.util.Objects;

/**
 * The terms an inference is asked on: which model, and the longest answer to allow.
 *
 * <p>Nothing here is a provider's feature. Whether a provider thinks, caches a prompt or streams is
 * configured where the provider is, by the application that built it -- and an agent type that
 * needs a differently configured provider gets a factory of its own.
 *
 * @param modelName the model, as the provider names it
 * @param maxTokens the longest answer to allow, or zero for the provider's default
 */
public record InferenceOptions(String modelName, int maxTokens) {

  public InferenceOptions {
    Objects.requireNonNull(modelName, "modelName must not be null");
    if (modelName.isBlank()) {
      throw new IllegalArgumentException("modelName must not be blank");
    }
  }

  public static InferenceOptions of(String modelName) {
    return new InferenceOptions(modelName, 0);
  }

  public boolean hasMaxTokens() {
    return maxTokens > 0;
  }
}
