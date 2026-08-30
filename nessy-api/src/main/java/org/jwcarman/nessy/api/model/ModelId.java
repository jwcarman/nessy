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
package org.jwcarman.nessy.api.model;

import java.util.Objects;

/**
 * WHICH model, by the name its provider knows it by — {@code "claude-opus-5"}.
 *
 * <p>A name rather than a bound model handle, deliberately: model choice is deployment
 * configuration, not code. An application should be able to move an agent from a cheap model to an
 * expensive one in a properties file, which handing over a live model object would force into Java.
 * Resolving the name is the provider's job.
 */
public record ModelId(String value) {

  public ModelId {
    Objects.requireNonNull(value, "value must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("model id must not be blank");
    }
  }

  public static ModelId of(String value) {
    return new ModelId(value);
  }
}
