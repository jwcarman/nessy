package org.jwcarman.nessy.inference.gemini;

import com.google.genai.Client;
import com.google.genai.ResponseStream;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * The one call this module makes, as a seam.
 *
 * <p>{@link Client} is a final class whose {@code models} field is a final class too, so neither
 * can stand in for itself in a test. This interface is what the provider talks to, {@link #over}
 * wraps the real client behind it, and a test hands in a lambda.
 */
interface GeminiClient extends AutoCloseable {

  /**
   * The reply as the server streams it: partial responses, each carrying the parts that arrived
   * since the last. Closing the stream closes the connection behind it.
   */
  Stream<GenerateContentResponse> generateContentStream(
      String model, List<Content> contents, GenerateContentConfig config);

  @Override
  void close();

  /**
   * The real client. {@code owned} decides what {@link #close()} does to it: a client this module
   * built is closed here, one an application handed in is the application's to close.
   */
  static GeminiClient over(Client client, boolean owned) {
    return new GeminiClient() {
      @Override
      public Stream<GenerateContentResponse> generateContentStream(
          String model, List<Content> contents, GenerateContentConfig config) {
        ResponseStream<GenerateContentResponse> responses =
            client.models.generateContentStream(model, contents, config);
        return StreamSupport.stream(responses.spliterator(), false).onClose(responses::close);
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
