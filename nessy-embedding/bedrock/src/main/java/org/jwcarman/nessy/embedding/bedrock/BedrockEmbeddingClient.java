package org.jwcarman.nessy.embedding.bedrock;

import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

/** The one call this module makes, as a seam a test can script without a credential. */
interface BedrockEmbeddingClient extends AutoCloseable {

  InvokeModelResponse invoke(InvokeModelRequest request);

  @Override
  void close();

  /** The real client; {@code owned} decides whether {@link #close()} closes it. */
  static BedrockEmbeddingClient over(BedrockRuntimeClient client, boolean owned) {
    return new BedrockEmbeddingClient() {
      @Override
      public InvokeModelResponse invoke(InvokeModelRequest request) {
        return client.invokeModel(request);
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
