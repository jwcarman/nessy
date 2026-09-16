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
