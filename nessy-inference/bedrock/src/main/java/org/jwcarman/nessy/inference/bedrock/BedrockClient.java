package org.jwcarman.nessy.inference.bedrock;

import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;

/**
 * The one call this module makes, as a seam, so a test can script a reply without a credential and
 * so that closing can be decided by who built the client.
 */
interface BedrockClient extends AutoCloseable {

  ConverseResponse converse(ConverseRequest request);

  @Override
  void close();

  /**
   * The real client. {@code owned} decides what {@link #close()} does to it: a client this module
   * built is closed here, one an application handed in is the application's to close.
   */
  static BedrockClient over(BedrockRuntimeClient client, boolean owned) {
    return new BedrockClient() {
      @Override
      public ConverseResponse converse(ConverseRequest request) {
        return client.converse(request);
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
