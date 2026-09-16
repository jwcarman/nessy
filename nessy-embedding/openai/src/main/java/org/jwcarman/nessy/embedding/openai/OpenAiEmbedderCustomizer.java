package org.jwcarman.nessy.embedding.openai;

/** What {@link OpenAiEmbedder#create} takes: something that fills in a config. */
@FunctionalInterface
public interface OpenAiEmbedderCustomizer {
  void customize(OpenAiEmbedderConfig config);
}
