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
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * Where a computation's outcome is delivered: the scope coordinates plus the originating call. This
 * is the continuation payload for both Continuum kinds, and it travels with every delivery, so
 * folding an outcome needs no lookup.
 *
 * @param agentType the agent type
 * @param agentId the scope id
 * @param responseId the model response that produced the call
 * @param call the tool call itself, arguments included
 */
public record Routing(String agentType, String agentId, String responseId, ToolCall call) {

  public Routing {
    requireText(agentType, "agentType");
    requireText(agentId, "agentId");
    requireText(responseId, "responseId");
    Objects.requireNonNull(call, "call must not be null");
  }

  /**
   * A codec over the pinned mapper.
   *
   * @param mapper the pinned mapper
   * @return the codec
   */
  public static Codec<Routing> codec(ObjectMapper mapper) {
    Objects.requireNonNull(mapper, "mapper must not be null");
    return new Codec<>() {
      @Override
      public byte[] encode(Routing value) {
        try {
          return mapper.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
          throw new IllegalArgumentException("undecodable routing", e);
        }
      }

      @Override
      public Routing decode(byte[] bytes) {
        try {
          return mapper.readValue(new String(bytes, StandardCharsets.UTF_8), Routing.class);
        } catch (JsonProcessingException e) {
          throw new IllegalArgumentException("undecodable routing", e);
        }
      }
    };
  }

  private static void requireText(String value, String name) {
    Objects.requireNonNull(value, name + " must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
