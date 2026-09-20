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

/**
 * What varies between one embedder and the next.
 *
 * <p>Which model, and how many coordinates it should produce. The connection is the factory's, and
 * is not a decision anybody makes twice.
 */
public interface EmbedderConfig {

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
  EmbedderConfig dimension(int dimension);
}
