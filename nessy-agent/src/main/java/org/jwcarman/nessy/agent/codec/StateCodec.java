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
package org.jwcarman.nessy.agent.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.jwcarman.nessy.agent.Phase;

/**
 * Internal storage machinery: renders {@link Phase} to and from the JSON the byte-payload substrate
 * persists as the {@code state} document's payload (spec §7). Not API vocabulary — the scope
 * version lives in the substrate's own document version (see the {@code state} recipe), not in this
 * payload.
 *
 * <p>{@link Phase} carries its own Jackson annotations (spec §7); this codec is the mapper-binding
 * boundary. {@code AwaitingTools} round-trips through its canonical constructor, so its
 * pending-non-empty and pending-subset-of-the-turn invariants are re-checked on every read — a
 * violation surfaces as a Jackson failure this codec translates into an {@link
 * IllegalArgumentException} naming the offense, same as a malformed payload or an unrecognized
 * discriminator.
 *
 * <p>Wraps one caller-supplied, already-pinned {@link ObjectMapper} (spec §7) — no static mapper
 * survives here.
 */
public final class StateCodec {

  private final Codecs codecs;

  public StateCodec(ObjectMapper mapper) {
    this.codecs = new Codecs(mapper);
  }

  public String toJson(Phase phase) {
    Objects.requireNonNull(phase, "phase must not be null");
    return codecs.write(phase);
  }

  public Phase phase(String json) {
    Objects.requireNonNull(json, "json must not be null");
    JsonNode root = codecs.readTree(json, "phase");
    Codecs.requireArrayIfPresent(root, "pending", "awaiting-tools phase");
    Codecs.requireArrayIfPresent(root, "gathered", "awaiting-tools phase");
    return codecs.bind(root, Phase.class, "phase");
  }
}
