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
package org.jwcarman.nessy.spi.embedding;

import java.util.List;
import org.jwcarman.nessy.api.embedding.Embedding;

/**
 * One vendor's embeddings, over one connection.
 *
 * <p>What an integrator implements, and the only thing that codes to a vendor's SDK. It holds
 * credentials and an endpoint and nothing about a model: which model, and how wide, arrive per
 * call, the way they do for inference.
 *
 * <p><b>Two methods, because retrieval is asymmetric.</b> A document is a statement waiting to be
 * found and a query is the asking, and a vendor trained for retrieval places the two differently on
 * purpose -- Gemini calls it a task type, Voyage an input type. A vendor with nothing to say
 * answers both the same way, which is the truth for it rather than a shortcut.
 *
 * <p>Whoever built the connection closes it. An {@link org.jwcarman.nessy.api.embedding.Embedder}
 * over this owns nothing and closes nothing.
 */
public interface EmbeddingProvider {

  /** The documents, in the order given. */
  List<Embedding> embedDocuments(List<String> texts, EmbeddingOptions options);

  /** The question being asked. Singular, because a search has one. */
  Embedding embedQuery(String query, EmbeddingOptions options);

  /**
   * The vendor, as OpenTelemetry's GenAI semantic conventions name it: {@code openai}, {@code
   * gcp.gemini}, {@code aws.bedrock}. Every adapter says so.
   */
  String providerName();
}
