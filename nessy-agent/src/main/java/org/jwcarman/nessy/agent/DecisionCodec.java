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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Objects;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.api.Decision;

/**
 * A hand-rolled {@link Codec} for {@link Decision} — the approval kind's Continuum result type.
 * {@link Decision} carries no Jackson polymorphism annotations (it lives in {@code nessy-api},
 * which does not depend on Jackson), the same reason the old (retired) Substrate-backed wiring's
 * outcome codec used to hand-roll its own {@code AllowWire}/{@code DenyWire} discriminated shape;
 * this is that same discrimination, over a plain {@code {type, reason?}} object, for the Continuum
 * result codec {@code ContinuumClient}'s config requires. Public: {@code HarnessConfig} builds the
 * approval kind's client from a different package.
 */
public final class DecisionCodec {

  private static final String TYPE_FIELD = "type";
  private static final String REASON_FIELD = "reason";
  private static final String ALLOW = "ALLOW";
  private static final String DENY = "DENY";

  private DecisionCodec() {}

  /**
   * @param mapper the pinned mapper
   * @return a codec over the pinned mapper
   */
  public static Codec<Decision> codec(ObjectMapper mapper) {
    Objects.requireNonNull(mapper, "mapper must not be null");
    return new Codec<>() {
      @Override
      public byte[] encode(Decision value) {
        ObjectNode node = mapper.createObjectNode();
        switch (value) {
          case Decision.Allow _ -> node.put(TYPE_FIELD, ALLOW);
          case Decision.Deny(String reason) -> node.put(TYPE_FIELD, DENY).put(REASON_FIELD, reason);
        }
        try {
          return mapper.writeValueAsBytes(node);
        } catch (JsonProcessingException e) {
          throw new IllegalArgumentException("undecodable decision", e);
        }
      }

      @Override
      public Decision decode(byte[] bytes) {
        JsonNode node;
        try {
          node = mapper.readTree(bytes);
        } catch (IOException e) {
          throw new IllegalArgumentException("undecodable decision", e);
        }
        String type = node.path(TYPE_FIELD).asText();
        return switch (type) {
          case ALLOW -> Decision.allow();
          case DENY -> new Decision.Deny(node.path(REASON_FIELD).asText());
          default -> throw new IllegalArgumentException("unrecognized decision type: " + type);
        };
      }
    };
  }
}
