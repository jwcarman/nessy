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
package org.jwcarman.nessy.engine;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * The mapper persisted state and transcripts are written with. Pekko has no say in how it is built.
 *
 * <p>{@code FAIL_ON_UNKNOWN_PROPERTIES} is off, and that is a migration decision rather than
 * laziness. A durable-state document is REWRITTEN in place, so there is no event log to replay and
 * no automatic migration: the day a field leaves the state, every row still on disk carries it, and
 * a strict reader turns "we shipped a smaller state" into "the agent cannot load". Tolerating
 * unknown fields makes a SHRINKING change safe; anything larger needs a manifest bump and a real
 * migration.
 *
 * <p>It adds no bindings of its own. Every sealed type the engine persists carries its own
 * discriminator in the API, where the wire names belong — the engine does not get to decide what a
 * stored transcript calls things.
 */
final class EngineMapper {

  private EngineMapper() {}

  /** One configured mapper, shared. {@link ObjectMapper} is thread-safe once configured. */
  static final ObjectMapper INSTANCE = create();

  static ObjectMapper create() {
    return new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  }
}
