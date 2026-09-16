package org.jwcarman.nessy.inference.bedrock;

/** What {@link BedrockInferenceProvider#create} takes: something that fills in a config. */
@FunctionalInterface
public interface BedrockProviderCustomizer {
  void customize(BedrockProviderConfig config);
}
