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
package org.jwcarman.nessy.examples.scout;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * The client half of {@link InMemoryMcpTransport}: the SDK's own stdio wire framing —
 * newline-delimited JSON-RPC — read from and written to whatever streams it is handed, with no
 * process of its own. See {@link InMemoryMcpTransport} for why this exists instead of the SDK's
 * process-bound {@code StdioClientTransport}.
 *
 * <p>Reproduced, with attribution, from {@code nessy-tool-mcp}'s own test tree ({@code
 * nessy-tool-mcp/src/test/java/org/jwcarman/nessy/tool/mcp/PipedClientTransport.java}): that
 * pattern lives in another module's {@code src/test}, which this module cannot depend on (test-jars
 * aren't published across the reactor here), so scout reproduces the minimal client/server pairing
 * locally rather than reaching across the module boundary.
 */
final class PipedClientTransport implements McpClientTransport {

  private final InputStream in;
  private final OutputStream out;
  private final McpJsonMapper jsonMapper;
  private final Sinks.Many<McpSchema.JSONRPCMessage> outboundSink =
      Sinks.many().unicast().onBackpressureBuffer();
  private final Scheduler inboundScheduler = Schedulers.newSingle("mcp-test-inbound");
  private final Scheduler outboundScheduler = Schedulers.newSingle("mcp-test-outbound");
  private volatile boolean closing;

  PipedClientTransport(InputStream in, OutputStream out, McpJsonMapper jsonMapper) {
    this.in = in;
    this.out = out;
    this.jsonMapper = jsonMapper;
  }

  @Override
  public Mono<Void> connect(
      Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
    return Mono.<Void>fromRunnable(
            () -> {
              startInbound(handler);
              startOutbound();
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  private void startInbound(
      Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
    inboundScheduler.schedule(
        () -> {
          try (BufferedReader reader =
              new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while (!closing && (line = reader.readLine()) != null) {
              McpSchema.JSONRPCMessage message =
                  McpSchema.deserializeJsonRpcMessage(jsonMapper, line);
              Mono.just(message).transform(handler).subscribe();
            }
          } catch (IOException e) {
            if (!closing) {
              throw new IllegalStateException("in-memory MCP transport read failed", e);
            }
          }
        });
  }

  private void startOutbound() {
    outboundSink
        .asFlux()
        .publishOn(outboundScheduler)
        .subscribe(
            message -> {
              try {
                String json = jsonMapper.writeValueAsString(message);
                json = json.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
                synchronized (out) {
                  out.write(json.getBytes(StandardCharsets.UTF_8));
                  out.write('\n');
                  out.flush();
                }
              } catch (IOException e) {
                if (!closing) {
                  throw new IllegalStateException("in-memory MCP transport write failed", e);
                }
              }
            });
  }

  @Override
  public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
    if (closing) {
      return Mono.error(new IllegalStateException("transport is closed"));
    }
    if (outboundSink.tryEmitNext(message).isSuccess()) {
      return Mono.empty();
    }
    return Mono.error(new IllegalStateException("failed to enqueue message"));
  }

  @Override
  public Mono<Void> closeGracefully() {
    return Mono.fromRunnable(
        () -> {
          closing = true;
          outboundSink.tryEmitComplete();
          closeQuietly(in);
          closeQuietly(out);
          inboundScheduler.dispose();
          outboundScheduler.dispose();
        });
  }

  private static void closeQuietly(Closeable closeable) {
    try {
      closeable.close();
    } catch (IOException ignored) {
      // best-effort teardown; the streams are pipes that only this pair uses
    }
  }

  @Override
  public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
    return jsonMapper.convertValue(data, typeRef);
  }
}
