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
package org.jwcarman.nessy.spi.embedding;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Which model, and how wide.
 *
 * <p>What {@code InferenceOptions} is to a model call: the dials a caller turns, kept apart from
 * the connection that carries them. A provider holds credentials and an endpoint and is told these
 * per call, so two stores on two models share one connection instead of opening two.
 *
 * @param modelName the embedding model, which a store is then keyed on
 * @param dimension how many coordinates to ask for, where the vendor allows fewer than the model's
 *     own; empty for the model's
 */
public record EmbeddingOptions(String modelName, OptionalInt dimension) {

  public EmbeddingOptions {
    Objects.requireNonNull(modelName, "modelName must not be null");
    if (modelName.isBlank()) {
      throw new IllegalArgumentException("modelName must not be blank");
    }
    Objects.requireNonNull(dimension, "dimension must not be null");
  }

  /** The model's own width. */
  public static EmbeddingOptions of(String modelName) {
    return new EmbeddingOptions(modelName, OptionalInt.empty());
  }
}
