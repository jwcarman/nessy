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
package org.jwcarman.nessy.embedding.gemini;

import com.google.genai.Client;
import com.google.genai.types.EmbedContentConfig;
import com.google.genai.types.EmbedContentResponse;
import java.util.List;

/**
 * The one call this module makes, as a seam. {@link Client} and its {@code models} are final
 * classes, so neither can stand in for itself in a test; this is what the embedder talks to.
 */
interface GeminiEmbeddingClient extends AutoCloseable {

  EmbedContentResponse embed(String model, List<String> texts, EmbedContentConfig config);

  @Override
  void close();

  /** The real client; {@code owned} decides whether {@link #close()} closes it. */
  static GeminiEmbeddingClient over(Client client, boolean owned) {
    return new GeminiEmbeddingClient() {
      @Override
      public EmbedContentResponse embed(
          String model, List<String> texts, EmbedContentConfig config) {
        return client.models.embedContent(model, texts, config);
      }

      @Override
      public void close() {
        if (owned) {
          client.close();
        }
      }
    };
  }
}
