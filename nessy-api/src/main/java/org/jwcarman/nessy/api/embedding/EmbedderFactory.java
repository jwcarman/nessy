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
package org.jwcarman.nessy.api.embedding;

import java.util.function.Consumer;

/**
 * Makes embedders that share a connection.
 *
 * <p>One vendor, one set of credentials, and as many embedders over them as there are stores. A
 * notebook and an episode log can be keyed on different models without either of them owning a
 * client, which is what makes changing one a change to that store rather than to the application.
 */
@FunctionalInterface
public interface EmbedderFactory {

  /** One embedder, for a store. */
  Embedder create(Consumer<EmbedderConfig> customizer);
}
