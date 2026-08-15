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

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;

/**
 * A real, in-process pairing of an MCP client transport and server transport provider, for tests
 * that need the whole wire protocol — {@code initialize}, {@code tools/list}, {@code tools/call},
 * newline-delimited JSON-RPC framing and all — without a subprocess, Docker, or a socket.
 *
 * <p>The SDK ships {@code StdioServerTransportProvider} already decoupled from any process: it
 * takes an {@link java.io.InputStream}/{@link java.io.OutputStream} pair directly. Its client-side
 * counterpart, {@code StdioClientTransport}, is not — it only ever launches a subprocess via {@code
 * ProcessBuilder}. {@link PipedClientTransport} is the missing half: the same newline-delimited
 * stdio framing, wired to a {@link PipedInputStream}/{@link PipedOutputStream} pair instead of a
 * process's own streams, so the whole handshake runs on plain threads inside this JVM.
 */
final class InMemoryMcpTransport {

  private InMemoryMcpTransport() {}

  /** A client transport and a server transport provider, plumbed to each other's streams. */
  record Pair(McpClientTransport client, McpServerTransportProvider server) {}

  static Pair open(McpJsonMapper mapper) {
    try {
      PipedOutputStream clientToServer = new PipedOutputStream();
      PipedInputStream serverIn = new PipedInputStream(clientToServer, 1 << 16);
      PipedOutputStream serverToClient = new PipedOutputStream();
      PipedInputStream clientIn = new PipedInputStream(serverToClient, 1 << 16);

      McpServerTransportProvider server =
          new StdioServerTransportProvider(mapper, serverIn, serverToClient);
      McpClientTransport client = new PipedClientTransport(clientIn, clientToServer, mapper);
      return new Pair(client, server);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
