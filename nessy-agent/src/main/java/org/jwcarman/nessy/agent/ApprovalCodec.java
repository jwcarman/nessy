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
import java.util.Optional;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.api.tool.approval.Approval;

/**
 * A hand-rolled {@link Codec} for {@link Approval} — the approval kind's Continuum result type.
 * {@link Approval} carries no Jackson polymorphism annotations, so this is the discrimination, over
 * a plain {@code {type, reason?, reference?}} object, that {@code ContinuumClient}'s config
 * requires. Public: {@code HarnessConfig} builds the approval kind's client from a different
 * package.
 */
public final class ApprovalCodec {

  private static final String TYPE_FIELD = "type";
  private static final String REASON_FIELD = "reason";
  private static final String REFERENCE_FIELD = "reference";
  private static final String APPROVED = "APPROVED";
  private static final String DENIED = "DENIED";

  private ApprovalCodec() {}

  /**
   * @param mapper the pinned mapper
   * @return a codec over the pinned mapper
   */
  public static Codec<Approval> codec(ObjectMapper mapper) {
    Objects.requireNonNull(mapper, "mapper must not be null");
    return new Codec<>() {
      @Override
      public byte[] encode(Approval value) {
        ObjectNode node = mapper.createObjectNode();
        switch (value) {
          case Approval.Approved(var reference) -> {
            node.put(TYPE_FIELD, APPROVED);
            reference.ifPresent(r -> node.put(REFERENCE_FIELD, r));
          }
          case Approval.Denied(String reason, var reference) -> {
            node.put(TYPE_FIELD, DENIED).put(REASON_FIELD, reason);
            reference.ifPresent(r -> node.put(REFERENCE_FIELD, r));
          }
        }
        try {
          return mapper.writeValueAsBytes(node);
        } catch (JsonProcessingException e) {
          throw new IllegalArgumentException("unencodable approval", e);
        }
      }

      @Override
      public Approval decode(byte[] bytes) {
        JsonNode node;
        try {
          node = mapper.readTree(bytes);
        } catch (IOException e) {
          throw new IllegalArgumentException("undecodable approval", e);
        }
        String type = node.path(TYPE_FIELD).asText();
        Optional<String> reference =
            node.hasNonNull(REFERENCE_FIELD)
                ? Optional.of(node.path(REFERENCE_FIELD).asText())
                : Optional.empty();
        return switch (type) {
          case APPROVED -> new Approval.Approved(reference);
          case DENIED -> new Approval.Denied(node.path(REASON_FIELD).asText(), reference);
          default -> throw new IllegalArgumentException("unrecognized approval type: " + type);
        };
      }
    };
  }
}
