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
package org.jwcarman.nessy.tool.mcp;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/**
 * A transport whose handshake never gets off the ground — {@link #connect} fails immediately, with
 * no wait for the SDK's initialization timeout — so a test can observe what {@link
 * McpToolbox#connect} does when the handshake itself is the thing that fails, without paying the
 * SDK's default 20-second timeout to find out.
 */
final class FailingClientTransport implements McpClientTransport {

  private final AtomicBoolean closed = new AtomicBoolean(false);

  boolean wasClosed() {
    return closed.get();
  }

  @Override
  public Mono<Void> connect(
      Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
    return Mono.error(new IllegalStateException("simulated handshake failure"));
  }

  @Override
  public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
    return Mono.error(new IllegalStateException("transport never connected"));
  }

  @Override
  public Mono<Void> closeGracefully() {
    return Mono.fromRunnable(() -> closed.set(true));
  }

  @Override
  public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
    throw new UnsupportedOperationException(
        "never reached: the handshake fails before any message arrives");
  }
}
