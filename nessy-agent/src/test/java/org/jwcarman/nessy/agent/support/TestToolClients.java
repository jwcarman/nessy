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
package org.jwcarman.nessy.agent.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.continuum.Continuum;
import org.jwcarman.continuum.ContinuumClient;
import org.jwcarman.continuum.DefaultContinuum;
import org.jwcarman.continuum.memory.InMemoryContinuumRepository;
import org.jwcarman.nessy.agent.Routing;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * Test-only Continuum wiring for the tool kind (continuum-adoption spec §3): every test that builds
 * a {@code ComputationDeferredToolCallPolicy}, {@code CompletionDesk}, or {@code DeliveryWorker}
 * directly — bypassing {@code HarnessConfig#finish()} — needs the same client shape production
 * wiring builds. One fresh, in-memory {@link Continuum} per call, so tests stay isolated from one
 * another. Mirrors {@link TestApprovalClients}.
 */
public final class TestToolClients {

  private static final Duration DEADLINE = Duration.ofHours(1);

  private TestToolClients() {}

  /**
   * @param kind the tool kind's own Continuum kind string (see {@code Kinds#tool})
   * @param mapper the pinned mapper
   * @return a fresh tool-kind client over a fresh in-memory repository
   */
  public static ContinuumClient<ToolResult, Routing> client(String kind, ObjectMapper mapper) {
    Continuum continuum = new DefaultContinuum(new InMemoryContinuumRepository());
    return continuum.client(
        kind,
        ToolResult.class,
        Routing.class,
        cfg ->
            cfg.resultCodec(toolResultCodec(mapper))
                .continuationCodec(Routing.codec(mapper))
                .deadline(DEADLINE));
  }

  /**
   * {@link ToolResult} carries no Jackson polymorphism of its own (a plain record), so the pinned
   * mapper binds it directly — no hand-rolled discriminated shape needed the way {@code
   * ApprovalCodec} exists for the approval kind's {@code Approval}. Public so a test that builds
   * its own {@code ContinuumClient<ToolResult, Routing>} directly (e.g. one that needs a
   * controllable clock {@link #client} doesn't expose) can share this codec rather than
   * re-declaring it.
   *
   * @param mapper the pinned mapper
   * @return the tool result codec
   */
  public static Codec<ToolResult> toolResultCodec(ObjectMapper mapper) {
    return new Codec<>() {
      @Override
      public byte[] encode(ToolResult value) {
        try {
          return mapper.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
          throw new IllegalArgumentException("undecodable tool result", e);
        }
      }

      @Override
      public ToolResult decode(byte[] bytes) {
        try {
          return mapper.readValue(new String(bytes, StandardCharsets.UTF_8), ToolResult.class);
        } catch (JsonProcessingException e) {
          throw new IllegalArgumentException("undecodable tool result", e);
        }
      }
    };
  }
}
