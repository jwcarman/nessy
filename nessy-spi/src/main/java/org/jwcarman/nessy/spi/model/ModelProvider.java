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
 */
public interface ModelProvider {

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
   * Env-driven applications built on {@code EnvModelProviders.select()} should prefer that method's
   * {@code Selection.providerName()} instead: it names the environment's own choice (e.g. {@code
   * "xai"} for an xAI key), where this default falls back to the concrete class name, which for xAI
   * is the shared {@code OpenAiModelProvider} class — not the vendor the key named.
   */
  default String name() {
    return getClass().getSimpleName();
  }
}
