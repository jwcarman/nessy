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
package org.jwcarman.nessy.embedding;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * @param properties vendor-prefixed settings ({@code voyage.truncation}); no embedding adapter
 *     supports any yet, so each is ignored and warned about once when the embedder is built
 */
public record EmbeddingOptions(
    String modelName, OptionalInt dimension, Map<String, String> properties) {

  public EmbeddingOptions {
    Objects.requireNonNull(modelName, "modelName must not be null");
    if (modelName.isBlank()) {
      throw new IllegalArgumentException("modelName must not be blank");
    }
    Objects.requireNonNull(dimension, "dimension must not be null");
    properties = copyOf(properties);
  }

  /** No properties. */
  public EmbeddingOptions(String modelName, OptionalInt dimension) {
    this(modelName, dimension, Map.of());
  }

  /** The model's own width. */
  public static EmbeddingOptions of(String modelName) {
    return new EmbeddingOptions(modelName, OptionalInt.empty());
  }

  /** Names only: a property's value may be sensitive, and this is what a log line prints. */
  @Override
  public String toString() {
    return "EmbeddingOptions[modelName="
        + modelName
        + ", dimension="
        + dimension
        + ", properties="
        + properties.keySet()
        + "]";
  }

  /** In the order given, which {@code Map.copyOf} would not keep. */
  private static Map<String, String> copyOf(Map<String, String> properties) {
    Objects.requireNonNull(properties, "properties must not be null");
    Map<String, String> copy = new LinkedHashMap<>();
    properties.forEach(
        (name, value) ->
            copy.put(
                Objects.requireNonNull(name, "a property name must not be null"),
                Objects.requireNonNull(value, () -> "property '" + name + "' has no value")));
    return Collections.unmodifiableMap(copy);
  }
}
