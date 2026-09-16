package org.jwcarman.nessy.embedding.gemini;

/** What {@link GeminiEmbedder#create} takes: something that fills in a config. */
@FunctionalInterface
public interface GeminiEmbedderCustomizer {
  void customize(GeminiEmbedderConfig config);
}
