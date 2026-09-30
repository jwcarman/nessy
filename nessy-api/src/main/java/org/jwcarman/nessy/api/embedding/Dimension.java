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
 * How many coordinates a vector has: the width an embedder is asked for, and the width it reports.
 *
 * <p>At least one; a width of zero or less is refused where it is made, so it cannot travel to a
 * vendor that would answer with a confusing error, or be mistaken for "not known yet".
 *
 * @param value the number of coordinates
 */
public record Dimension(int value) {

  public Dimension {
    if (value < 1) {
      throw new IllegalArgumentException("an embedding dimension must be at least 1, was " + value);
    }
  }

  public static Dimension of(int value) {
    return new Dimension(value);
  }

  @Override
  public String toString() {
    return Integer.toString(value);
  }
}
