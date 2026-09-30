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
package org.jwcarman.nessy.inference;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The terms an inference is asked on: which model, the longest answer to allow, and the vendor
 * properties the agent type carries.
 *
 * <p>Fixed when a harness is built and identical on every call, which is why properties live here
 * rather than on {@link InferenceRequest}: they are the agent type's configuration, like its model.
 * An adapter reads the entries under its own prefix and ignores the rest; see {@code
 * InferenceProvider#validate}. Never written to the event log.
 *
 * @param modelName the model, as the provider names it
 * @param maxTokens the longest answer to allow, or zero for the provider's default
 * @param properties vendor-prefixed settings the neutral API does not name ({@code
 *     openai.reasoning.effort}), in the order given; empty for none
 */
public record InferenceOptions(String modelName, int maxTokens, Map<String, String> properties) {

  public InferenceOptions {
    Objects.requireNonNull(modelName, "modelName must not be null");
    if (modelName.isBlank()) {
      throw new IllegalArgumentException("modelName must not be blank");
    }
    properties = copyOf(properties);
  }

  /** No properties. */
  public InferenceOptions(String modelName, int maxTokens) {
    this(modelName, maxTokens, Map.of());
  }

  public static InferenceOptions of(String modelName) {
    return new InferenceOptions(modelName, 0);
  }

  public boolean hasMaxTokens() {
    return maxTokens > 0;
  }

  /** Names only: a property's value may be sensitive, and this is what a log line prints. */
  @Override
  public String toString() {
    return "InferenceOptions[modelName="
        + modelName
        + ", maxTokens="
        + maxTokens
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
