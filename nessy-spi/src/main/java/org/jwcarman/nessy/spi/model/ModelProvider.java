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
package org.jwcarman.nessy.spi.model;

/**
 * The vendor gateway — an application singleton holding the SDK client, credentials, and transport
 * for one vendor.
 *
 * <p>A gateway does not run requests itself; it hands out {@link Model} handles that do. {@link
 * #model(String)} returns a cheap, immutable handle bound to one model id, sharing this gateway's
 * client. Two calls with different ids yield two independent handles from the same gateway — one
 * gateway per app, many models drawn from it.
 *
 * <p><b>A gateway is {@link AutoCloseable}</b> (ruled 2026-08-26), because the SDK client it holds
 * owns a connection pool and the threads that service it, and every vendor SDK this repository
 * wraps has a {@code close()}: an application that builds a gateway and walks away leaves those
 * threads running until the JVM exits — which is invisible in a CLI and expensive in a long-running
 * process that builds more than one. {@link #close()} defaults to a no-op, so a gateway with
 * nothing to release says nothing and no out-of-tree implementation breaks; it narrows {@link
 * AutoCloseable#close()} to throw no checked exception, so a try-with-resources over a gateway
 * needs no catch. Closing a gateway invalidates every {@link Model} handle drawn from it.
 */
public interface ModelProvider extends AutoCloseable {

  /**
   * Binds a model id to this gateway's shared client.
   *
   * @param id the vendor's model identifier, e.g. {@code "claude-opus-5"}; must not be null or
   *     blank
   * @throws IllegalArgumentException if {@code id} is null or blank
   */
  Model model(String id);

  /**
   * Who this provider is, for banners and logs — never used for model selection.
   *
   * <p>Direct-wired applications (one provider module, constructed explicitly) read this.
   * Applications built on {@code ModelDiscovery.select()} should prefer that method's {@code
   * Selection.providerName()} instead: it is the registered {@link ModelProviderBootstrap#name()}
   * of whichever provider bootstrapped (e.g. {@code "xai"} for an xAI key), where this default
   * falls back to the concrete class name, which for xAI is the shared {@code OpenAiModelProvider}
   * class — not the vendor the key named.
   */
  default String name() {
    return getClass().getSimpleName();
  }

  /**
   * Releases whatever this gateway holds — the SDK client, its connection pool, its threads.
   * Idempotent by contract: closing twice is a no-op, not an error.
   *
   * <p>The default releases nothing, which is right for a gateway over a stateless client and for
   * every test double. A gateway that owns a client SHOULD override this and close it — unless the
   * client was handed in by the application, which then owns it (see {@code BedrockModelProvider}
   * for the asymmetry that rule produces).
   */
  @Override
  default void close() {
    // Nothing held; nothing to release.
  }
}
