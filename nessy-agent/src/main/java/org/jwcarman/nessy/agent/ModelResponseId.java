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
package org.jwcarman.nessy.agent;

import org.jwcarman.nessy.api.Identifiers;

/**
 * A Nessy-generated identity for one committed model response (durable-parcels spec §2). Minted in
 * the model-call executor when the response arrives — never in the reducer, which stays a pure fold
 * (purity law: re-handling the same event must yield identical state). Closes the
 * provider-uniqueness hole a raw {@code ToolCall.id()} leaves open, since provider call ids are not
 * contractually unique over an agent's lifetime.
 */
public record ModelResponseId(String value) {

  public ModelResponseId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("model response id must not be blank");
    }
  }

  /** Mints a fresh, time-ordered (UUIDv7) response id. Executor-only — never called from a fold. */
  public static ModelResponseId generate() {
    return new ModelResponseId(Identifiers.next());
  }

  public static ModelResponseId of(String value) {
    return new ModelResponseId(value);
  }
}
