package org.jwcarman.nessy.embedding.voyage;

/** What {@link VoyageEmbedder#create} takes: something that fills in a config. */
@FunctionalInterface
public interface VoyageEmbedderCustomizer {
  void customize(VoyageEmbedderConfig config);
}
