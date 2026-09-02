/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.nessy.model.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Who closes the SDK client (ruled 2026-08-26). A gateway must close the client it BUILT and never
 * one handed in through {@link GeminiProviderConfig#client(Client)}, which the application owns.
 *
 * <p><b>Why this tests the close TARGET rather than a boolean.</b> {@code com.google.genai.Client}
 * is a {@code final} class with no observable closed state — the same fact that made {@link
 * GeminiClient} a seam in the first place (see its javadoc). So the ownership decision is expressed
 * as which {@link AutoCloseable} {@code wrap} is handed, and this hands it a recorder. No mocking
 * library is involved, and none could help: a final class cannot be proxied.
 */
class GeminiCloseOwnershipTest {

  @Test
  void a_client_the_config_built_itself_is_closed_through_the_seam() {
    AtomicInteger closes = new AtomicInteger();
    Client sdkClient = offlineClient();

    GeminiClient wrapped = GeminiProviderConfig.wrap(sdkClient, closes::incrementAndGet);
    wrapped.close();

    assertThat(closes).hasValue(1);
  }

  @Test
  void a_supplied_client_is_never_closed_because_the_close_target_releases_nothing() {
    AtomicInteger closes = new AtomicInteger();
    Client sdkClient = offlineClient();

    // What resolveClient() hands wrap() for the client(...) path.
    GeminiClient wrapped =
        GeminiProviderConfig.wrap(sdkClient, GeminiProviderConfig.NOTHING_TO_CLOSE);
    wrapped.close();

    assertThat(closes).hasValue(0);
  }

  /** The provider delegates its own close to the seam — the other half of the wiring. */
  @Test
  void the_provider_closes_the_seam_it_holds() {
    AtomicInteger closes = new AtomicInteger();
    GeminiClient recording =
        new GeminiClient() {

          @Override
          public GeminiStream generateContentStream(
              String model, List<Content> contents, GenerateContentConfig config) {
            throw new UnsupportedOperationException("this test never streams");
          }

          @Override
          public void close() {
            closes.incrementAndGet();
          }
        };

    new GeminiModelProvider(recording).close();

    assertThat(closes).hasValue(1);
  }

  /**
   * {@code AutoCloseable#close()} declares a checked {@code Exception}, but {@link
   * GeminiClient#close()} does not — {@code wrap}'s own close renames whatever {@code onClose}
   * throws into an unchecked {@link IllegalStateException} so the seam's signature can stay clean.
   */
  @Test
  void a_failing_close_target_is_renamed_into_an_unchecked_failure() {
    Client sdkClient = offlineClient();
    var originalFailure = new IOException("could not release the underlying resource");
    AutoCloseable failingClose =
        () -> {
          throw originalFailure;
        };

    GeminiClient wrapped = GeminiProviderConfig.wrap(sdkClient, failingClose);

    assertThatThrownBy(wrapped::close)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("closing the Gemini client failed")
        .hasCause(originalFailure);
  }

  /** A gateway holding a seam with nothing to release closes silently — the SPI default. */
  @Test
  void a_seam_with_nothing_to_release_closes_silently() {
    GeminiClient bare =
        (model, contents, config) -> {
          throw new UnsupportedOperationException("this test never streams");
        };

    assertThatCode(new GeminiModelProvider(bare)::close).doesNotThrowAnyException();
  }

  /**
   * Built with a syntactically valid key and never used: {@code Client.builder()} contacts nothing,
   * which is what lets this stay an offline test.
   */
  private static Client offlineClient() {
    return Client.builder().apiKey("test-key-never-used").build();
  }
}
