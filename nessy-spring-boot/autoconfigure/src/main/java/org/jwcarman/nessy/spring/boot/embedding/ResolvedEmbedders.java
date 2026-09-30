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
package org.jwcarman.nessy.spring.boot.embedding;

import java.util.List;

/**
 * Every preset or custom embedder the registrar actually registered (never a skipped one), for the
 * factory bean to register under its id and for the report to tell a resolved embedder apart from
 * an application's own {@code EmbeddingProvider} bean.
 *
 * <p>Registered as the singleton bean {@code nessyResolvedEmbedders}.
 */
record ResolvedEmbedders(List<ResolvedEmbedder> embedders) {

  ResolvedEmbedders {
    embedders = List.copyOf(embedders);
  }
}
