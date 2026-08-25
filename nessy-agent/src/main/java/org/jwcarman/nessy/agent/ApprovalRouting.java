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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;

/**
 * The approval kind's continuation (approval-lifecycle spec §1.3): where the answer is delivered,
 * plus the frozen question it is an answer to — parked as evidence, so the desk can show a human
 * the same document the approver was handed.
 *
 * @param routing the scope coordinates and the originating call
 * @param request the frozen question
 */
public record ApprovalRouting(Routing routing, ApprovalRequest request) {

  public ApprovalRouting {
    Objects.requireNonNull(routing, "routing must not be null");
    Objects.requireNonNull(request, "request must not be null");
  }

  /**
   * A codec over the pinned mapper; decoding re-attaches it so the request's typed fact reads work.
   *
   * @param mapper the pinned mapper
   * @return the codec
   */
  public static Codec<ApprovalRouting> codec(ObjectMapper mapper) {
    Objects.requireNonNull(mapper, "mapper must not be null");
    return new Codec<>() {
      @Override
      public byte[] encode(ApprovalRouting value) {
        try {
          return mapper.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
          throw new IllegalArgumentException("unencodable approval routing", e);
        }
      }

      @Override
      public ApprovalRouting decode(byte[] bytes) {
        try {
          ApprovalRouting decoded =
              mapper.readValue(new String(bytes, StandardCharsets.UTF_8), ApprovalRouting.class);
          ApprovalRequest request = decoded.request();
          return new ApprovalRouting(
              decoded.routing(),
              new ApprovalRequest(
                  request.agentType(),
                  request.agentId(),
                  request.call(),
                  request.action(),
                  request.facts().attach(mapper)));
        } catch (JsonProcessingException e) {
          throw new IllegalArgumentException("undecodable approval routing", e);
        }
      }
    };
  }
}
