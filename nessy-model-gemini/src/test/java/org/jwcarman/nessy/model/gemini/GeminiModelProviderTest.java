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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelRequest;

class GeminiModelProviderTest {

  private static GeminiClient fakeClient(Object[] capturedArgs, GeminiStream response) {
    return (model, contents, config) -> {
      capturedArgs[0] = model;
      capturedArgs[1] = contents;
      capturedArgs[2] = config;
      return response;
    };
  }

  @Nested
  class Streaming {

    @Test
    void delegates_to_the_client_and_returns_its_stream_unchanged() {
      var capturedArgs = new Object[3];
      var response = new GeminiStream(List.of(), () -> {});
      var provider = new GeminiModelProvider(fakeClient(capturedArgs, response));
      var request =
          new ModelRequest(
              Context.of(List.of()), "sys", "gemini-2.5-flash", 1024, List.of(), Set.of(), null);

      var stream = provider.stream(request);

      assertThat(stream).isSameAs(response);
      assertThat(capturedArgs[0]).isEqualTo("gemini-2.5-flash");
      assertThat(capturedArgs[1]).isInstanceOf(List.class);
      assertThat(capturedArgs[2]).isInstanceOf(GenerateContentConfig.class);
      var config = (GenerateContentConfig) capturedArgs[2];
      assertThat(config.maxOutputTokens()).contains(1024);
    }
  }

  @Nested
  class Builder {

    @Test
    void rejects_build_with_neither_a_key_nor_a_client() {
      var builder = GeminiModelProvider.builder();

      assertThatThrownBy(builder::build)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("apiKey")
          .hasMessageContaining("fromEnv")
          .hasMessageContaining("client");
    }

    @Test
    void a_blank_api_key_is_rejected_the_same_as_a_missing_one() {
      var builder = GeminiModelProvider.builder().apiKey("   ");

      assertThatThrownBy(builder::build)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("apiKey")
          .hasMessageContaining("fromEnv")
          .hasMessageContaining("client");
    }

    @Test
    void an_api_key_alone_is_enough_to_build() {
      var provider = GeminiModelProvider.builder().apiKey("test-key").build();

      assertThat(provider).isNotNull();
    }

    @Test
    void a_base_url_is_accepted_without_error() {
      var provider =
          GeminiModelProvider.builder()
              .apiKey("test-key")
              .baseUrl("https://example.invalid")
              .build();

      assertThat(provider).isNotNull();
    }

    @Test
    void a_preconfigured_client_bypasses_the_key_requirement() {
      Client client = Client.builder().apiKey("test-key").build();

      var provider = GeminiModelProvider.builder().client(client).build();

      assertThat(provider).isNotNull();
    }

    @Test
    void from_env_fails_clearly_when_neither_variable_is_set_naming_both() {
      assumeTrue(System.getenv("GEMINI_API_KEY") == null, "GEMINI_API_KEY is set in this shell");
      assumeTrue(System.getenv("GOOGLE_API_KEY") == null, "GOOGLE_API_KEY is set in this shell");

      var builder = GeminiModelProvider.builder().fromEnv();

      assertThatThrownBy(builder::build)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("GEMINI_API_KEY")
          .hasMessageContaining("GOOGLE_API_KEY");
    }

    @Test
    void an_explicit_api_key_set_after_from_env_still_builds_without_needing_the_environment() {
      var provider = GeminiModelProvider.builder().fromEnv().apiKey("explicit-key").build();

      assertThat(provider).isNotNull();
    }
  }

  @Nested
  class Capabilities {

    @Test
    void v1_advertises_parallel_tool_calls_but_not_thinking_caching_or_image_input() {
      var provider = GeminiModelProvider.builder().apiKey("test-key").build();

      assertThat(provider.capabilities()).containsExactly(Capability.PARALLEL_TOOL_CALLS);
    }
  }

  @Nested
  class Name {

    @Test
    void reports_gemini() {
      var provider = GeminiModelProvider.builder().apiKey("test-key").build();

      assertThat(provider.name()).isEqualTo("Gemini");
    }
  }
}
