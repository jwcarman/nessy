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

import java.util.Set;

/** Where tokens come from. */
public interface ModelProvider {

  /**
   * Starts one turn. The caller iterates the returned stream and must close it.
   *
   * <p>Blocking by design: on virtual threads that is cheaper and far more readable than a callback
   * protocol.
   */
  ModelStream stream(ModelRequest request);

  /** What this provider can actually do. See {@link Capability}. */
  Set<Capability> capabilities();

  /**
   * Who this provider is, for banners and logs — never used for model selection.
   *
   * <p>Direct-wired applications (one provider module, constructed explicitly) read this.
   * Env-driven applications built on {@code EnvModelProviders.select()} should prefer that method's
   * {@code Selection.providerName()} instead: it names the environment's own choice (e.g. {@code
   * "xai"} for an xAI key), where this default falls back to the concrete class name, which for xAI
   * is the shared {@code OpenAiModelProvider} class — not the vendor the key named.
   */
  default String name() {
    return getClass().getSimpleName();
  }
}
