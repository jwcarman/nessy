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
package org.jwcarman.nessy.internal;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;

/**
 * The single place identifier generation lives. Presently generates time-ordered UUIDv7 via
 * java-uuid-generator (JUG), sortable by creation time and index-friendly for durable stores.
 *
 * <p><strong>Design note:</strong> v7 identifiers deliberately embed their mint timestamp, trading
 * some entropy for temporal locality. This is the single place any future identifier kind should
 * draw from: add a new method here, never a new UUID source elsewhere.
 */
public final class Identifiers {

  private static final TimeBasedEpochGenerator GENERATOR = Generators.timeBasedEpochGenerator();

  private Identifiers() {}

  /**
   * Generate the next identifier.
   *
   * @return a time-ordered UUIDv7 as a string, never null
   */
  public static String next() {
    return GENERATOR.generate().toString();
  }
}
