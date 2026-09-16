package org.jwcarman.nessy.inference.gemini;

/** What {@link GeminiInferenceProvider#create} takes: something that fills in a config. */
@FunctionalInterface
public interface GeminiProviderCustomizer {
  void customize(GeminiProviderConfig config);
}
