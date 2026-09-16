package org.jwcarman.nessy.embedding.bedrock;

/** What {@link BedrockEmbedder#create} takes: something that fills in a config. */
@FunctionalInterface
public interface BedrockEmbedderCustomizer {
  void customize(BedrockEmbedderConfig config);
}
