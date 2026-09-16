package org.jwcarman.nessy.inference.bedrock;

import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler;

/**
 * The one call this module makes, as a seam, so a test can script a reply without a credential and
 * so that closing can be decided by who built the client.
 *
 * <p>{@code ConverseStream} exists only on the SDK's async client, which pushes events to a handler
 * on its own thread and completes a future when the stream ends. This seam turns that into one
 * blocking call: every event is handed to {@code onEvent} in order, and the call returns once the
 * stream has ended or throws what the SDK threw.
 */
interface BedrockClient extends AutoCloseable {

  /** Runs one streaming call to its end, handing every event to {@code onEvent} as it arrives. */
  void converseStream(ConverseStreamRequest request, Consumer<ConverseStreamOutput> onEvent);

  @Override
  void close();

  /**
   * The real client. {@code owned} decides what {@link #close()} does to it: a client this module
   * built is closed here, one an application handed in is the application's to close.
   */
  static BedrockClient over(BedrockRuntimeAsyncClient client, boolean owned) {
    Objects.requireNonNull(client, "client must not be null");
    return new BedrockClient() {
      @Override
      public void converseStream(
          ConverseStreamRequest request, Consumer<ConverseStreamOutput> onEvent) {
        ConverseStreamResponseHandler handler =
            ConverseStreamResponseHandler.builder()
                .subscriber(
                    ConverseStreamResponseHandler.Visitor.builder().onDefault(onEvent).build())
                .build();
        try {
          client.converseStream(request, handler).join();
        } catch (CompletionException e) {
          throw unwrap(e);
        }
      }

      @Override
      public void close() {
        if (owned) {
          client.close();
        }
      }
    };
  }

  /**
   * The SDK's own exception rather than the future's wrapper, so the failure is classified by what
   * the service said. A checked cause, which the SDK does not throw but a future can carry, is
   * wrapped so it still surfaces.
   */
  static RuntimeException unwrap(CompletionException e) {
    Throwable cause = e.getCause() == null ? e : e.getCause();
    if (cause instanceof RuntimeException runtime) {
      return runtime;
    }
    return new IllegalStateException(
        cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause);
  }
}
