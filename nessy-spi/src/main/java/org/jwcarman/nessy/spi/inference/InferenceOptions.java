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
