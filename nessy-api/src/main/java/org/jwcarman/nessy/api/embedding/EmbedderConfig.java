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
package org.jwcarman.nessy.api.embedding;

import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.VendorProperty;

/**
 * What varies between one embedder and the next.
 *
 * <p>Which registered provider makes the vectors, which model, how many coordinates, and any vendor
 * properties. The connections are the factory's, registered once under the names a store asks for
 * them by.
 */
public interface EmbedderConfig {

  /**
   * Which of the factory's embedding providers makes the vectors. Defaults to the factory's; an
   * embedder that names none, made by a factory with no default, fails when it is made, listing
   * what is registered.
   */
  EmbedderConfig provider(ProviderId id);

  /** {@link #provider(ProviderId)}, by name. */
  default EmbedderConfig provider(String id) {
    return provider(ProviderId.of(id));
  }

  /**
   * Which model turns text into vectors.
   *
   * <p>The choice a store is then stuck with: every vector in a table has to come from one model,
   * or the distances between them mean nothing. Changing it is a migration, not a setting.
   */
  EmbedderConfig model(String model);

  /**
   * How many coordinates to ask for, where the vendor allows fewer than the model's own.
   *
   * <p>Left unset, the model's. Shorter vectors are cheaper to store and to compare, and worse at
   * telling near things apart.
   */
  EmbedderConfig dimension(Dimension dimension);

  /**
   * {@link #dimension(Dimension)}, by number.
   *
   * @throws IllegalArgumentException if the number is less than 1
   */
  default EmbedderConfig dimension(int value) {
    return dimension(new Dimension(value));
  }

  /**
   * A setting the vendor understands and this interface does not name, prefixed by the adapter that
   * reads it ({@code voyage.truncation}). Only the names an adapter supports are sent: an
   * unsupported name under its prefix is ignored, with a warning, and the embedding adapters
   * support none yet. Repeatable; the last value given for a name wins.
   *
   * @throws IllegalArgumentException if either argument is blank
   */
  EmbedderConfig property(String name, String value);

  /**
   * {@link #property(String, String)}, with the property declared by the adapter that reads it and
   * the value typed: it is stored as the text {@code property} writes it as.
   */
  default <T> EmbedderConfig property(VendorProperty<T> property, T value) {
    return property(property.name(), property.format(value));
  }
}
